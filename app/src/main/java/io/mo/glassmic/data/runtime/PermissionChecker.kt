package io.mo.glassmic.data.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.core.Constants
import java.io.DataOutputStream
import javax.inject.Inject
import javax.inject.Singleton

enum class PermissionStatus { GRANTED, DENIED, UNKNOWN }

data class PermissionState(
    val root: PermissionStatus = PermissionStatus.UNKNOWN,
    val notification: PermissionStatus = PermissionStatus.UNKNOWN,
    val overlay: PermissionStatus = PermissionStatus.UNKNOWN,
    val fileAccess: PermissionStatus = PermissionStatus.UNKNOWN,
    val foregroundService: PermissionStatus = PermissionStatus.UNKNOWN,
    val androidVersionSupported: Boolean = true,
    val safeModeOk: Boolean = true
) {
    val allGranted: Boolean get() = listOf(
        root, notification, overlay, fileAccess, foregroundService
    ).all { it == PermissionStatus.GRANTED } && androidVersionSupported && safeModeOk
}

@Singleton
class PermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val safeModeRepo: SafeModeRepository
) {

    fun checkAll(): PermissionState = PermissionState(
        root = checkRoot(),
        notification = checkNotification(),
        overlay = checkOverlay(),
        fileAccess = checkFileAccess(),
        foregroundService = checkForegroundService(),
        androidVersionSupported = Build.VERSION.SDK_INT >= 29,
        safeModeOk = !safeModeRepo.isActive()
    )

    // ============ Root ============
    fun checkRoot(): PermissionStatus {
        // 静默检测 su 二进制；若用户拒绝授予则会失败
        return runCatching {
            val process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use {
                it.writeBytes("id\nexit\n")
                it.flush()
            }
            val exit = process.waitFor()
            if (exit == 0) PermissionStatus.GRANTED else PermissionStatus.DENIED
        }.getOrDefault(PermissionStatus.DENIED)
    }

    // ============ 通知 ============
    fun checkNotification(): PermissionStatus {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            return if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
        }
        // 旧版默认有，但 channel 可能被关
        return PermissionStatus.GRANTED
    }

    // ============ 悬浮窗 ============
    fun checkOverlay(): PermissionStatus =
        if (Settings.canDrawOverlays(context)) PermissionStatus.GRANTED
        else PermissionStatus.DENIED

    // ============ 文件访问 ============
    fun checkFileAccess(): PermissionStatus {
        // Android 13+ READ_MEDIA_AUDIO
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            return if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
        }
        // API 29-32: READ_EXTERNAL_STORAGE
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        return if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
    }

    // ============ 前台服务能力 ============
    fun checkForegroundService(): PermissionStatus {
        if (Build.VERSION.SDK_INT >= 34) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
            ) == PackageManager.PERMISSION_GRANTED
            return if (granted) PermissionStatus.GRANTED else PermissionStatus.DENIED
        }
        return PermissionStatus.GRANTED
    }

    fun overlaySettingsIntent() =
        android.content.Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${Constants.APP_PACKAGE}")
        )

    fun appNotificationSettingsIntent() =
        android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, Constants.APP_PACKAGE)
        }
}
