package io.mo.glassmic.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.mo.glassmic.MainActivity
import io.mo.glassmic.R
import io.mo.glassmic.core.Constants
import io.mo.glassmic.data.audio.PlaybackController
import io.mo.glassmic.data.runtime.RuntimeStateHolder
import io.mo.glassmic.log.GlassLog
import io.mo.glassmic.provider.ProviderGate
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 常驻前台服务。
 *
 * 设计强制要求（与需求 §13 一致）：
 * - 不提供隐藏通知选项
 * - 不在用户没主动启用时启动
 * - onDestroy 时清理 running.lock 哨兵
 * - 通知不放控制按钮，保持极简
 */
@AndroidEntryPoint
class GlassForegroundService : LifecycleService() {

    @Inject lateinit var runtime: RuntimeStateHolder
    @Inject lateinit var playback: PlaybackController

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // 放开跨进程 Provider——只有服务真正运行时注入进程才允许查询决策 / 读 PCM。
        // 服务未运行时 Provider 被禁用，AMS 无法拉起本进程（防「杀掉又复活」）。
        ProviderGate.enable(this)
        // 创建运行哨兵——onDestroy 时删除
        runCatching { File(filesDir, Constants.RUNNING_SENTINEL).createNewFile() }
        startForegroundCompat()
        runtime.setEnabled(true)
        lifecycleScope.launch {
            if (playback.restorePersistedClip()) {
                GlassLog.b("FgService") { "restored persisted audio source" }
            }
        }
        GlassLog.b("FgService") { "前台服务启动，常驻通知已显示" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // 被系统杀掉后不自动拉起——与「不在用户没主动启用时启动」的要求一致，
        // 异常退出由 RUNNING_SENTINEL + SafeModeWatchdog 检测处理，不靠 sticky 复活掩盖。
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        runtime.setEnabled(false)
        // 禁用跨进程 Provider——切断 AMS 强制拉起链路，服务停止后不再被无故复活。
        ProviderGate.disable(this)
        // 清理运行哨兵——表示本次正常退出
        runCatching { File(filesDir, Constants.RUNNING_SENTINEL).delete() }
        GlassLog.b("FgService") { "前台服务已停止" }
        super.onDestroy()
    }

    /**
     * 启动前台服务，并按运行时权限选择 FGS 类型。
     *
     * Android 14（API 34）对 microphone 类型有强校验：启动时必须已授予 RECORD_AUDIO，
     * 否则 validateForegroundServiceType 抛 SecurityException 导致服务创建失败、App 崩溃。
     * 本服务自身不直接录音（麦克风替换发生在被注入进程），因此未授权时降级到 specialUse
     * 类型（无运行时权限门槛、无时长限制）。再以多级 try-catch 兜底，确保任何情况下不崩。
     */
    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT < 34) {
            runCatching { startForeground(Constants.NOTIF_ID, buildNotification()) }
                .onFailure { GlassLog.b("FgService") { "startForeground 失败: ${it.message}" } }
            return
        }

        val preferred = if (hasRecordAudioPermission()) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }

        // 依次尝试：首选类型 → specialUse → 无类型。任一成功即返回。
        val attempts = linkedSetOf(
            preferred,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        for (type in attempts) {
            try {
                startForeground(Constants.NOTIF_ID, buildNotification(), type)
                return
            } catch (t: Throwable) {
                GlassLog.b("FgService") { "startForeground type=$type 失败: ${t.message}" }
            }
        }
        runCatching { startForeground(Constants.NOTIF_ID, buildNotification()) }
            .onFailure { GlassLog.b("FgService") { "startForeground 兜底失败: ${it.message}" } }
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(Constants.NOTIF_CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                Constants.NOTIF_CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Constants.NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pi)
            // 故意不加 action 按钮，需求 §13.2
            .build()
    }

    companion object {
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, GlassForegroundService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GlassForegroundService::class.java))
        }
    }
}
