package io.mo.glassmic.data.diag

import android.content.Context
import android.os.Build
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.BuildConfig
import io.mo.glassmic.core.Constants
import io.mo.glassmic.data.config.ConfigStore
import io.mo.glassmic.data.runtime.BootGateRepository
import io.mo.glassmic.data.runtime.HookStatusRepository
import io.mo.glassmic.data.runtime.SafeModeRepository
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 诊断包导出。
 *
 * 默认脱敏：不写入用户路径、不写入 App 列表、不写入音频文件。
 * 内容仅限：环境信息、配置摘要、最近日志、安全模式记录、Xposed ping 状态。
 *
 * 产物：filesDir/diagnostics/glassmic-diag-YYYYMMDD-HHmmss.zip
 * 返回值是可分享的 content:// Uri（用 FileProvider，需要 res/xml/file_paths.xml）。
 */
@Singleton
class DiagnosticBundler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: ConfigStore,
    private val safeModeRepo: SafeModeRepository,
    private val bootGate: BootGateRepository,
    private val hookStatusRepo: HookStatusRepository
) {

    suspend fun export(): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "diagnostics").apply { if (!exists()) mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val target = File(dir, "glassmic-diag-$ts.zip")

        ZipOutputStream(FileOutputStream(target)).use { zip ->
            writeEntry(zip, "summary.json", buildSummary())
            writeEntry(zip, "log.txt", GlassLog.dump())
            writeEntry(zip, "safe_mode.json", buildSafeMode())
            writeEntry(zip, "hook_status.json", buildHook())
        }
        GlassLog.b("Diag") { "诊断包已生成: ${target.name} size=${target.length()}" }
        target
    }

    fun shareUri(file: File) =
        FileProvider.getUriForFile(context, "${Constants.APP_PACKAGE}.fileprovider", file)

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private suspend fun buildSummary(): String {
        val cfg = configStore.current()
        return JSONObject().apply {
            put("generated_at", System.currentTimeMillis())
            put("app", JSONObject().apply {
                put("versionName", BuildConfig.VERSION_NAME)
                put("versionCode", BuildConfig.VERSION_CODE)
                put("debug", BuildConfig.DEBUG)
            })
            put("device", JSONObject().apply {
                put("sdk", Build.VERSION.SDK_INT)
                put("release", Build.VERSION.RELEASE)
                put("brand", Build.BRAND)
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
            })
            put("config", JSONObject().apply {
                put("global_switch", cfg.globalSwitch)
                put("scope_mode", cfg.scopeMode.name)
                put("playback_policy", cfg.playbackPolicy.name)
                put("onboarding_completed", cfg.onboardingCompleted)
                put("whitelist_count", cfg.whitelistCount)
                put("blacklist_count", cfg.blacklistCount)
                put("has_current_audio", cfg.currentAudioId.isNotBlank())
                put("appearance_theme", cfg.appearance.theme.name)
                put("appearance_glass", cfg.appearance.glassEffect)
                put("logging_level", cfg.logging.level.name)
                put("experimental_unlocked", cfg.experimental.unlocked)
            })
            put("boot_gate", JSONObject().apply {
                put("user_enabled_after_boot", bootGate.userEnabledAfterBoot())
            })
        }.toString(2)
    }

    private fun buildSafeMode(): String {
        val info = safeModeRepo.snapshot()
        return JSONObject().apply {
            put("active", info != null)
            if (info != null) {
                put("reason", info.reason.name)
                put("occurred_at", info.occurredAt)
            }
        }.toString(2)
    }

    private fun buildHook(): String {
        val s = hookStatusRepo.snapshot()
        return JSONObject().apply {
            put("activity", s.activity.name)
            put("last_ping_ms", s.lastPingMs)
            put("last_package", s.lastPackage ?: "")
            put("api", s.api)
        }.toString(2)
    }
}
