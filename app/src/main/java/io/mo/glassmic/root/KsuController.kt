package io.mo.glassmic.root

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.runtime.BootGateRepository
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.proto.InjectionBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class KsuSnapshot(
    val version: String,
    val mode: Int,
    val paused: Boolean,
    val selected: String,
    val positionMs: Long,
    val durationMs: Long,
    val gainDb: Float,
    val speed: Float,
    val loop: Boolean,
    val files: List<String>,
    val targets: Set<String>,
    val mixSupported: Boolean = false,
    val mix: Boolean = false,
    val micLevel: Int = 100,
    val mixNeedsRestart: Boolean = false
) {
    val playing: Boolean get() = mode == 1 && !paused

    companion object {
        fun parse(text: String): KsuSnapshot {
            val json = JSONObject(text)
            check(json.getBoolean("ok")) { json.optString("error", "Module command failed") }
            val version = json.getString("version")
            // The existing CLI has no capability handshake. Only accept the known protocol family.
            val revision = version.removePrefix("0.1.0.").toIntOrNull()
            check(version.startsWith("0.1.0.") && revision != null && revision >= 9) {
                "Unsupported GlassMic module version: $version (requires 0.1.0.9 or a compatible revision)"
            }
            val mode = json.getInt("mode")
            val duration = json.getLong("durationMs")
            val position = json.getLong("positionMs")
            val gain = json.getDouble("gainDb").toFloat()
            val speed = json.getDouble("speed").toFloat()
            check(mode in 0..2 && duration >= 0 && position >= 0 && gain.isFinite() && speed.isFinite()) {
                "Invalid module playback state"
            }
            val mixSupported = json.optBoolean("mixSupported", false)
            val micLevel = if (mixSupported) json.getDouble("micLevel") else 100.0
            check(micLevel.isFinite() && micLevel in 0.0..200.0 && micLevel == micLevel.toInt().toDouble()) {
                "Invalid module microphone level"
            }
            val clients = json.optJSONArray("clients")
            val needsRestart = mixSupported && clients != null && (0 until clients.length()).any {
                !clients.getJSONObject(it).optBoolean("mixSupported", false)
            }
            val files = json.getJSONArray("files")
            val targets = json.getJSONArray("targets")
            return KsuSnapshot(version, mode, json.getBoolean("paused"), json.getString("selected"),
                position.coerceAtMost(duration), duration, gain.coerceIn(-24f, 12f), speed.coerceIn(.5f, 2f),
                json.getBoolean("loop"), (0 until files.length()).map { files.getJSONObject(it).getString("name") }
                    .filter(KsuCommand::mediaName).sorted(),
                (0 until targets.length()).map { targets.getString(it) }.toSet(),
                mixSupported, mixSupported && json.getBoolean("mix"), micLevel.toInt(), needsRestart)
        }
    }
}

data class KsuState(
    val snapshot: KsuSnapshot? = null,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val stopping: Boolean = false,
    val error: String? = null
)

/** Control only: the module owns its media, playback clock and Zygisk consumers. */
@Singleton
class KsuController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: ConfigStore,
    private val runtime: RuntimeStateHolder,
    private val bootGate: BootGateRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(KsuState())
    val state: StateFlow<KsuState> = mutableState.asStateFlow()
    private val power = context.getSystemService(PowerManager::class.java)
    @Volatile private var active = false
    private var poller: Job? = null
    private val lifecycleLock = Any()
    private var releaseJob: Deferred<Boolean>? = null
    // A failed release must remain retryable; never report the module as stopped on an error.
    @Volatile private var touched = false

    fun start() = synchronized(lifecycleLock) {
        poller?.cancel()
        val pendingRelease = releaseJob
        poller = scope.launch {
            pendingRelease?.await()
            ensureActive()
            active = true
            refresh()
            while (isActive && active) {
                mutableState.subscriptionCount.first { it > 0 }
                delay(1500)
                if (power.isInteractive && state.value.connected && !state.value.busy) refresh(silent = true)
            }
        }
    }

    private suspend fun permitted(): Boolean {
        val cfg = config.current()
        return active && runtime.value.enabled && !runtime.value.safeMode && bootGate.userEnabledAfterBoot() &&
            cfg.globalSwitch && cfg.injectionBackend == InjectionBackend.KERNEL_SU
    }

    fun refreshNow() {
        if (!runtime.value.enabled || state.value.busy || state.value.stopping) return
        if (!active) start() else scope.launch { refresh() }
    }

    private suspend fun refresh(silent: Boolean = false) = mutex.withLock {
        if (!permitted()) return@withLock
        if (!silent) mutableState.update { it.copy(busy = true, error = null) }
        try {
            receive("status")
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            mutableState.update { it.copy(connected = false, error = error.message) }
        } finally { mutableState.update { it.copy(busy = false) } }
    }

    /** Called by the UI, with values validated again before any root process is started. */
    fun command(action: String, args: List<String> = emptyList()) {
        if (state.value.busy || state.value.stopping) return
        mutableState.update { it.copy(busy = true, error = null) }
        scope.launch {
            mutex.withLock {
                try {
                    if (!permitted()) return@withLock
                    receive(action, args)
                    if (action == "load" && permitted()) receive("play")
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    // A command may have executed even when its reply was lost. Require a refresh.
                    mutableState.update { it.copy(connected = false, error = error.message) }
                } finally { mutableState.update { it.copy(busy = false) } }
            }
        }
    }

    private suspend fun receive(action: String, args: List<String> = emptyList()) {
        if (action == "mix" || action == "mic-level") check(state.value.snapshot?.mixSupported == true) {
            "Mixing requires GlassMic module 0.1.0.10 or newer"
        }
        val command = KsuCommand.build(action, args)
        if (action != "status") markPending()
        val snapshot = KsuSnapshot.parse(KsuProcess.execute(command))
        markPending()
        mutableState.update { it.copy(snapshot = snapshot, connected = true, error = null) }
    }

    private suspend fun markPending() {
        touched = true
        if (!config.current().ksuControlPending) config.update { it.setKsuControlPending(true) }
    }

    fun importAudio(uri: Uri, displayName: String) {
        if (state.value.busy || state.value.stopping) return
        mutableState.update { it.copy(busy = true, error = null) }
        scope.launch {
            mutex.withLock {
                var staged: File? = null
                try {
                    if (!permitted()) return@withLock
                    val ext = displayName.substringAfterLast('.', "").lowercase()
                    require(ext in setOf("wav", "mp3", "flac")) { "Please select WAV, MP3 or FLAC" }
                    val name = displayName.takeIf(KsuCommand::mediaName) ?: "Audio.$ext"
                    val root = checkNotNull(context.getExternalFilesDir("ksu-import")) { "Shared storage unavailable" }
                    val directory = File(root, UUID.randomUUID().toString())
                    check(directory.mkdirs()) { "Cannot create import directory" }
                    val file = File(directory, name)
                    staged = file
                    checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= 512L * 1024 * 1024) { "Audio exceeds 512 MiB" }
                                if (!permitted()) error("Import cancelled")
                                output.write(buffer, 0, count)
                            }
                            require(total > 0) { "Empty audio file" }
                        }
                    }
                    if (!permitted()) return@withLock
                    val response = JSONObject(KsuProcess.execute(KsuCommand.build("import", listOf(file.absolutePath))))
                    check(response.getBoolean("ok")) { response.optString("error", "Import failed") }
                    receive("status")
                } catch (error: Exception) {
                    mutableState.update { it.copy(error = error.message) }
                } finally {
                    staged?.let { it.delete(); it.parentFile?.delete() }
                    mutableState.update { it.copy(busy = false) }
                }
            }
        }
    }

    fun stop() { beginRelease() }

    private fun beginRelease(): Deferred<Boolean> = synchronized(lifecycleLock) {
        active = false
        poller?.cancel()
        releaseJob?.takeIf { !it.isCompleted }?.let { return@synchronized it }
        mutableState.update { it.copy(stopping = true) }
        scope.async {
            mutex.withLock {
                try {
                    if (touched || config.current().ksuControlPending) {
                        receive("real")
                        check(state.value.snapshot?.mode == 0) { "Module did not restore the real microphone" }
                        config.update { it.setKsuControlPending(false) }
                        touched = false
                    }
                    mutableState.update { it.copy(connected = false) }
                    true
                } catch (error: Exception) {
                    mutableState.update { it.copy(connected = false, error = error.message) }
                    false
                } finally { mutableState.update { it.copy(stopping = false, busy = false) } }
            }
        }.also { releaseJob = it }
    }

    /** Release outlives a disappearing screen. A failed release leaves the backend selected. */
    suspend fun release(): Boolean = beginRelease().await()
}
