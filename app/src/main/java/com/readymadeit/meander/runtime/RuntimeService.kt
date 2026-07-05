package com.readymadeit.meander.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Keeps the embedded hkp-rt runtime alive as a foreground service so it (and LAN
 * discovery / remote-runtime access) survives the app being backgrounded. The
 * native server is process-wide; this service owns its lifecycle: started when
 * the app launches, stopped when the task is removed.
 */
class RuntimeService : Service() {
    private lateinit var runtimeHost: RuntimeHost

    override fun onCreate() {
        super.onCreate()
        runtimeHost = RuntimeHost(applicationContext)
        startForeground(NOTIFICATION_ID, buildNotification())
        // Native start() blocks up to ~500ms detecting bind failure; keep it off
        // the main thread.
        Thread({ runtimeHost.start() }, "hkp-runtime-start").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restart if the process is killed and recreated while backgrounded.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped the app away: shut the runtime down and stop the service.
        runtimeHost.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runtimeHost.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Runtime",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the local runtime available for boards and nearby devices."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        // A valid small icon is mandatory for startForeground; the app ships no
        // launcher icon, so use a guaranteed-present system status icon.
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Readymade runtime")
            .setContentText("Running local runtime")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
        return builder.build()
    }

    companion object {
        private const val TAG = "RuntimeService"
        private const val CHANNEL_ID = "hkp-runtime"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, RuntimeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
