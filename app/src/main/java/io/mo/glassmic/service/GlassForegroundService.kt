package io.mo.glassmic.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
        // 创建运行哨兵——onDestroy 时删除
        runCatching { File(filesDir, Constants.RUNNING_SENTINEL).createNewFile() }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                Constants.NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(Constants.NOTIF_ID, buildNotification())
        }
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
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        runtime.setEnabled(false)
        // 清理运行哨兵——表示本次正常退出
        runCatching { File(filesDir, Constants.RUNNING_SENTINEL).delete() }
        GlassLog.b("FgService") { "前台服务已停止" }
        super.onDestroy()
    }

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
