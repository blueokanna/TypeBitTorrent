package com.typebit.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.typebit.MainActivity
import com.typebit.app.appStore
import com.typebit.model.TorrentStatus
import com.typebit.ui.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Foreground `dataSync` service: pins the process (and the in-process Rust engine) while the app is
 *  backgrounded; it only renders status into the mandatory notification. */
class DownloadService : Service() {
    companion object {
        private const val CHANNEL_ID = "typebit_downloads"
        private const val CHANNEL_NAME = "下载/做种"
        private const val NOTIFICATION_ID = 0x54B2 // "T B"
        private const val ACTION_START = "com.typebit.download.START"
        private const val ACTION_STOP = "com.typebit.download.STOP"
        private const val WAKELOCK_TAG = "typebit:download"
        private const val TAG = "TypeBitDownloadService"

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java).setAction(ACTION_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                // ForegroundServiceStartNotAllowedException (app not in foreground) or duplicate start:
                // harmless for one tick, the store retries.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DownloadService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var tickJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // `startForeground` throws on hostile OEM states (permission revoked mid-flight, channel
        // deleted by the user); an uncaught throw would kill the whole process.
        try {
            startForeground(NOTIFICATION_ID, buildNotification("启动中", "准备下载/做种…"))
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "startForeground failed", t)
        }
        // Partial wake lock only: keeps the CPU alive (screen may be off) so the engine's timers and
        // sockets keep firing; a full lock would drain the battery.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock =
                pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                    setReferenceCounted(false)
                    try {
                        acquire()
                    } catch (_: Exception) {
                        // Some OEM builds refuse the lock; downloads continue regardless.
                    }
                }
        tickJob =
                scope.launch {
                    while (isActive) {
                        updateNotification()
                        delay(1_000)
                    }
                }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY: after an OS reclaim the service restarts with a null intent, and the store
        // re-kicks it on each poll.
        return START_STICKY
    }

    override fun onDestroy() {
        tickJob?.cancel()
        scope.cancel()
        wakeLock?.let {
            if (it.isHeld) {
                try {
                    it.release()
                } catch (_: Exception) {
                }
            }
        }
        wakeLock = null
        super.onDestroy()
    }

    private fun updateNotification() {
        val state = appStore.state.value
        val active =
                state.torrents.filter {
                    it.status == TorrentStatus.DOWNLOADING ||
                            it.status == TorrentStatus.SEEDING ||
                            it.status == TorrentStatus.FETCHING_METADATA
                }
        // The store owns start/stop (`ensureBackgroundMode`); this service never self-stops, so a brief
        // empty snapshot cannot drop the foreground pin mid-download.
        val (title, text) =
                if (active.isEmpty()) {
                    "TypeBitTorrent" to "后台服务运行中 · 暂无活动任务"
                } else {
                    val top = active.maxByOrNull { it.downSpeed }
                    val t =
                            if (active.size == 1 && top != null) top.name
                            else "正在传输 ${active.size} 个任务"
                    val p =
                            if (top != null && top.sizeBytes > 0) {
                                "${Format.percent(top.progress)} · ${Format.bytes(top.downloadedBytes)}/${Format.bytes(top.sizeBytes)}"
                            } else {
                                "获取元数据…"
                            }
                    val speeds = "↓ ${Format.speed(state.globalDownRate)} · ↑ ${Format.speed(state.globalUpRate)}"
                    t to "$p · $speeds"
                }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(NOTIFICATION_ID, buildNotification(title, text))
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi =
                PendingIntent.getActivity(
                        this,
                        0,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
        val builder =
                Notification.Builder(this, CHANNEL_ID)
                        .setSmallIcon(com.typebit.app.R.drawable.ic_launcher_monochrome)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setContentIntent(pi)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setCategory(Notification.CATEGORY_PROGRESS)
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                    NotificationChannel(
                            CHANNEL_ID,
                            CHANNEL_NAME,
                            NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "后台下载与做种状态"
                        setShowBadge(false)
                    }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
