package io.mo.glassmic.root

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.R
import io.mo.glassmic.audio.SharedPcmPublisher
import io.mo.glassmic.core.model.SourceType
import androidx.core.content.ContextCompat
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.config.audioPolicyTargets
import io.mo.glassmic.data.runtime.EffectiveSourceResolver
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.proto.AppConfig
import io.mo.glassmic.proto.InjectionBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class PolicyPhase { IDLE, STARTING, ACTIVE, STOPPING, ERROR }
data class PolicyStatus(val phase: PolicyPhase = PolicyPhase.IDLE, val detail: String = "") {
    fun label(context: Context): String = context.getString(when (phase) {
        PolicyPhase.IDLE -> R.string.backend_idle
        PolicyPhase.STARTING -> R.string.backend_starting
        PolicyPhase.ACTIVE -> R.string.backend_active
        PolicyPhase.STOPPING -> R.string.backend_stopping
        PolicyPhase.ERROR -> R.string.backend_failed
    }) + if (detail.isBlank()) "" else ": $detail"
}

/** Foreground-service-owned, opt-in root backend. Construction never starts su or the audio pipeline. */
@Singleton
class AudioPolicyController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: ConfigStore,
    private val runtime: RuntimeStateHolder,
    private val resolver: EffectiveSourceResolver,
    private val publisher: Lazy<SharedPcmPublisher>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val retryVersion = AtomicLong()
    private val mutableStatus = MutableStateFlow(PolicyStatus())
    val status = mutableStatus.asStateFlow()
    private var watcher: Job? = null // Accessed on the service's main thread.
    private var session: Session? = null // Accessed only while holding lifecycleMutex.

    private class Session(val process: Process, val fifo: File) {
        val ready = CompletableDeferred<Unit>()
        var reader: Job? = null
        var heartbeat: Job? = null
        var consumer: String? = null
        @Volatile var error: String? = null
    }

    /** Resolved target set. [skipped] counts selected packages that cannot be routed by UID. */
    private data class Targets(val uids: List<Int>, val skipped: Int)

    // Bumped on install/uninstall/update so UID lists (especially all-apps mode) are recomputed.
    private val packagesVersion = AtomicLong()
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { packagesVersion.incrementAndGet() }
    }

    fun start() {
        if (watcher?.isActive == true) return
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(context, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        watcher = scope.launch {
            lifecycleMutex.withLock {
                try { observe() }
                finally {
                    withContext(NonCancellable) { closeSession() }
                    // Preserve errors until the user retries or switches backends.
                    if (mutableStatus.value.phase != PolicyPhase.ERROR) publish(PolicyPhase.IDLE)
                }
            }
        }
    }

    fun stop() {
        watcher?.cancel()
        watcher = null
        runCatching { context.unregisterReceiver(packageReceiver) }
    }
    fun retry() { retryVersion.incrementAndGet() }

    fun diagnostics(): JSONObject = JSONObject().apply {
        put("phase", status.value.phase.name)
        put("detail", status.value.detail)
        put("sample_rate", 48000)
        put("channels", 1)
        put("format", "PCM16")
    }

    /** Identifies the user's selection; independent of installed-package changes. */
    private fun targetConfigKey(cfg: AppConfig): String =
        if (cfg.audioPolicyAllApps) ALL_APPS else cfg.audioPolicyTargets().sorted().joinToString(",")

    /** Every target shares one PCM stream and the same gates, so one package stands in for the selection. */
    private fun injecting(cfg: AppConfig): Boolean {
        val probe = if (cfg.audioPolicyAllApps) ALL_APPS else cfg.audioPolicyTargets().minOrNull() ?: return false
        return resolver.resolve(probe, forAudioPolicy = true) != SourceType.REAL_MIC
    }

    private fun stillRequested(cfg: AppConfig, configKey: String): Boolean =
        runtime.value.enabled && cfg.globalSwitch && cfg.injectionBackend == InjectionBackend.AUDIO_POLICY &&
            targetConfigKey(cfg) == configKey && injecting(cfg)

    private suspend fun observe() {
        var activeTarget: String? = null
        var activeUids: List<Int>? = null
        var failedRequest: String? = null
        while (currentCoroutineContext().isActive) {
            val cfg = config.current()
            val enabled = cfg.injectionBackend == InjectionBackend.AUDIO_POLICY &&
                cfg.globalSwitch && runtime.value.enabled
            val configKey = targetConfigKey(cfg)
            val target = "$configKey#${packagesVersion.get()}"
            val request = "${cfg.injectionBackend}:$target:${retryVersion.get()}"
            if (!enabled) {
                closeSession()
                activeTarget = null
                activeUids = null
                failedRequest = null
                publish(PolicyPhase.IDLE)
            } else if (configKey.isEmpty()) {
                closeSession()
                activeTarget = null
                activeUids = null
                publish(PolicyPhase.ERROR, context.getString(R.string.backend_scope_error))
            } else if (!injecting(cfg)) {
                closeSession()
                activeTarget = null
                activeUids = null
                if (failedRequest != request) publish(PolicyPhase.IDLE)
            } else if (failedRequest != request) {
                try {
                    if (activeTarget != target || session == null) {
                        val targets = resolveTargets(cfg)
                        // A package change that leaves the UID set intact must not interrupt audio.
                        if (session == null || targets.uids != activeUids) {
                            closeSession()
                            activeUids = null
                            publish(PolicyPhase.STARTING)
                            // XBridge caches for 200 ms; native polls every 250 ms. Let old FILE decisions expire.
                            delay(750)
                            openSession(targets.uids)
                            // Startup/root authorization may take seconds. Recheck before feeding PCM.
                            if (!stillRequested(config.current(), configKey)) {
                                closeSession()
                                activeTarget = null
                                continue
                            }
                            attachPcm(CONSUMER_NAME)
                            activeUids = targets.uids
                        }
                        activeTarget = target
                        publish(PolicyPhase.ACTIVE, activeDetail(targets))
                    }
                    session?.let {
                        it.error?.let { message -> throw IOException(message) }
                        if (!it.process.isAlive) throw IOException("AudioPolicy helper exited")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    closeSession()
                    activeTarget = null
                    activeUids = null
                    if (stillRequested(config.current(), configKey)) {
                        failedRequest = request
                        publish(PolicyPhase.ERROR, error.message ?: error.javaClass.simpleName)
                    } else {
                        // Cancelling startup by restoring the mic/changing targets is not a failure.
                        failedRequest = null
                        publish(PolicyPhase.IDLE)
                    }
                }
            }
            delay(100)
        }
    }

    private fun activeDetail(targets: Targets): String {
        val count = context.getString(R.string.backend_targets_active, targets.uids.size)
        return if (targets.skipped == 0) count
            else count + context.getString(R.string.backend_targets_skipped, targets.skipped)
    }

    /**
     * Maps the selection to app UIDs. System apps, the launcher, input methods and GlassMic are never
     * targeted. UID routing cannot distinguish packages sharing one UID, so a shared UID is only used
     * when every package on it is itself a target.
     */
    @Suppress("DEPRECATION")
    private fun resolveTargets(cfg: AppConfig): Targets {
        val pm = context.packageManager
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName
        val inputMethods = pm.queryIntentServices(Intent("android.view.InputMethod"), 0)
            .mapTo(HashSet()) { it.serviceInfo.packageName }
        fun eligible(app: ApplicationInfo) = app.uid % 100000 >= 10000 &&
            app.uid != android.os.Process.myUid() &&
            app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0 &&
            app.packageName != home && app.packageName !in inputMethods

        if (cfg.audioPolicyAllApps) {
            // Apps that never request RECORD_AUDIO cannot record; leaving them out keeps the rule list short.
            val candidates = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter { info ->
                val app = info.applicationInfo ?: return@filter false
                info.requestedPermissions?.contains(Manifest.permission.RECORD_AUDIO) == true && eligible(app)
            }
            val uids = candidates.mapNotNull { it.applicationInfo?.uid }.distinct().filter { uid ->
                pm.getPackagesForUid(uid).orEmpty().all { pkg ->
                    runCatching { eligible(pm.getApplicationInfo(pkg, 0)) }.getOrDefault(false)
                }
            }.sorted()
            require(uids.isNotEmpty()) { context.getString(R.string.backend_no_eligible_apps) }
            return Targets(uids, skipped = 0)
        }

        val selected = cfg.audioPolicyTargets()
        val uids = sortedSetOf<Int>()
        var skipped = 0
        var lastError: String? = null
        for (pkg in selected.sorted()) {
            val app = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            lastError = when {
                app == null || !eligible(app) -> context.getString(R.string.backend_target_error)
                !pm.getPackagesForUid(app.uid).orEmpty().all { it in selected } ->
                    context.getString(R.string.backend_shared_uid_error)
                else -> { uids += app.uid; continue }
            }
            skipped++
        }
        require(uids.isNotEmpty()) { lastError ?: context.getString(R.string.backend_scope_error) }
        return Targets(uids.toList(), skipped)
    }

    private suspend fun openSession(uids: List<Int>) {
        val dir = File(context.filesDir, "audio-policy").apply { mkdirs() }
        // A private, unpredictable FIFO for each run; no world-writable pipe or shared /data/local/tmp APK.
        val fifo = File(dir, "pcm-${UUID.randomUUID()}")
        Os.mkfifo(fifo.absolutePath, 0x180 /* 0600 */)
        val command = "CLASSPATH=${quote(context.applicationInfo.sourceDir)} exec /system/bin/app_process " +
            "/system/bin io.mo.glassmic.root.AudioPolicyMain ${uids.joinToString(",")} ${quote(fifo.absolutePath)}"
        val process = try {
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        } catch (error: Exception) {
            fifo.delete()
            throw error
        }
        val current = Session(process, fifo)
        session = current
        current.heartbeat = scope.launch {
            try {
                while (isActive && process.isAlive) {
                    // Target changes are handled by observe(); here only stop for gates that end injection.
                    val latest = config.current()
                    if (!runtime.value.enabled || !latest.globalSwitch ||
                        latest.injectionBackend != InjectionBackend.AUDIO_POLICY || !injecting(latest)) {
                        process.outputStream.close()
                        current.ready.completeExceptionally(IOException("Injection request changed during startup"))
                        break
                    }
                    process.outputStream.write("PING\n".toByteArray())
                    process.outputStream.flush()
                    delay(1000)
                }
            } catch (error: IOException) { current.error = error.message }
        }
        current.reader = scope.launch {
            val line = ByteArrayOutputStream()
            val bytes = ByteArray(1024)
            try {
                val input = process.inputStream
                while (isActive && (process.isAlive || input.available() > 0)) {
                    val count = input.available().coerceAtMost(bytes.size)
                    if (count == 0) { delay(25); continue }
                    val n = input.read(bytes, 0, count)
                    if (n < 0) break
                    for (i in 0 until n) {
                        if (bytes[i] == 10.toByte()) {
                            val text = line.toString("UTF-8").trim()
                            line.reset()
                            when {
                                text == "READY" -> current.ready.complete(Unit)
                                text.startsWith("ERROR ") -> {
                                    current.error = text.removePrefix("ERROR ").take(500)
                                    current.ready.completeExceptionally(IOException(current.error))
                                }
                            }
                        } else if (line.size() < 2048) line.write(bytes[i].toInt())
                    }
                }
                if (!current.ready.isCompleted) current.ready.completeExceptionally(
                    IOException(context.getString(R.string.backend_root_error)))
            } catch (error: IOException) {
                current.error = error.message
                current.ready.completeExceptionally(error)
            }
        }
        try {
            withTimeout(30000) { current.ready.await() }
        } catch (error: TimeoutCancellationException) {
            throw IOException(context.getString(R.string.backend_start_timeout), error)
        }
    }

    private suspend fun attachPcm(pkg: String) {
        val current = checkNotNull(session)
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (true) {
            currentCoroutineContext().ensureActive()
            current.error?.let { throw IOException(it) }
            check(current.process.isAlive) { "AudioPolicy helper exited before opening PCM" }
            try {
                val fd = Os.open(current.fifo.absolutePath, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
                val parcel = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
                        Os.fcntlInt(fd, OsConstants.F_SETFL, flags and OsConstants.O_NONBLOCK.inv())
                        ParcelFileDescriptor.dup(fd)
                    } else {
                        // Os.fcntlInt 是 API 30 才公开的，Android 10 上 NoSuchMethodError。
                        // 非阻塞 open 成功说明读端已就位，此时再以阻塞方式 open 会立即返回。
                        ParcelFileDescriptor.open(current.fifo, ParcelFileDescriptor.MODE_WRITE_ONLY)
                    }
                } finally { Os.close(fd) }
                try {
                    current.consumer = publisher.get().attachConsumer(pkg, 48000, 1, parcel,
                        bufferBytes = 4096, queueCapacity = 2, framed = true)
                } catch (error: Exception) {
                    parcel.close()
                    throw error
                }
                return
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.ENXIO || SystemClock.elapsedRealtime() >= deadline) throw error
                delay(25)
            }
        }
    }

    private suspend fun closeSession() = withContext(NonCancellable) {
        val current = session ?: return@withContext
        session = null
        publish(PolicyPhase.STOPPING)
        current.heartbeat?.cancelAndJoin()
        // Closing the owner channel makes the root child exit even if su is a separate parent process.
        runCatching { current.process.outputStream.close() }
        current.consumer?.let { publisher.get().detach(it) }
        withContext(Dispatchers.IO) {
            if (!current.process.waitFor(2, TimeUnit.SECONDS)) current.process.destroyForcibly()
        }
        current.reader?.cancelAndJoin()
        runCatching { current.process.inputStream.close() }
        current.fifo.delete()
    }

    private fun publish(phase: PolicyPhase, detail: String = "") {
        val next = PolicyStatus(phase, detail)
        if (mutableStatus.value == next) return
        mutableStatus.value = next
        GlassLog.b("AudioPolicy") { "$phase $detail" }
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        /** Selection key and resolver probe for all-apps mode; never a valid package name. */
        const val ALL_APPS = "*"
        const val CONSUMER_NAME = "audiopolicy"
    }
}
