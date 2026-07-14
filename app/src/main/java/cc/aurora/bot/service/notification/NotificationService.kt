package cc.aurora.bot.service.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import cc.aurora.bot.service.http.HttpServer

/**
 * Aurora 前台服务通知，用于保持 HTTP 服务器在后台运行。
 *
 * 功能：
 * - 为 Android O+ 创建通知渠道
 * - 显示 HTTP 服务器运行状态和端口号
 * - 提供 start/stop 方法控制服务生命周期
 */
class NotificationService : Service() {

    companion object {
        const val TAG = "AuroraNotification"
        const val CHANNEL_ID = "aurora_http_server"
        const val CHANNEL_NAME = "Aurora HTTP 服务器"
        const val CHANNEL_DESC = "显示 Aurora HTTP 服务器的运行状态"
        const val NOTIFICATION_ID = 1001

        /**
         * 创建通知渠道（Android O+ 必需）。
         * 应在 Application.onCreate() 或 Service.onCreate() 中调用。
         */
        @JvmStatic
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = CHANNEL_DESC
                    setShowBadge(false)
                }
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel created: $CHANNEL_ID")
            }
        }

        /**
         * 启动前台服务，显示 HTTP 服务器运行通知。
         *
         * @param context 上下文
         * @param port HTTP 服务器实际监听的端口号
         */
        @JvmStatic
        fun start(context: Context, port: Int) {
            val intent = Intent(context, NotificationService::class.java)
            intent.putExtra("port", port)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "NotificationService started, port=$port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start NotificationService: ${e.message}")
            }
        }

        /**
         * 停止前台服务，移除通知。
         *
         * @param context 上下文
         */
        @JvmStatic
        fun stop(context: Context) {
            val intent = Intent(context, NotificationService::class.java)
            try {
                context.stopService(intent)
                Log.d(TAG, "NotificationService stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop NotificationService: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra("port", 0) ?: 0
        val notification = buildNotification(port)
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "NotificationService destroyed")
    }

    /**
     * 构建前台通知，显示服务器状态和端口信息。
     */
    private fun buildNotification(port: Int): Notification {
        val serverStatus = if (HttpServer.status == HttpServer.ServerStatus.RUNNING) "运行中" else "未运行"
        val title = "Aurora HTTP 服务器"
        val content = "状态: $serverStatus | 端口: $port"

        // 点击通知打开主界面
        val pendingIntent = try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                PendingIntent.getActivity(
                    this,
                    0,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE
                        else 0
                )
            } else null
        } catch (e: Exception) {
            null
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .apply {
                if (pendingIntent != null) {
                    setContentIntent(pendingIntent)
                }
            }
            .build()
    }
}