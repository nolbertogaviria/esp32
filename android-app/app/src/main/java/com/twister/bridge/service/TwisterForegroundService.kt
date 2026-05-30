package com.twister.bridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.twister.bridge.R
import com.twister.bridge.ble.BleLinkManager
import java.util.Timer
import kotlin.concurrent.fixedRateTimer

class TwisterForegroundService : Service() {
    private lateinit var bleLinkManager: BleLinkManager
    private var heartbeatTimer: Timer? = null

    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_FORWARD_NOTIF) {
                return
            }

            val source = intent.getIntExtra(EXTRA_NOTIF_SOURCE, 0)
            val title = intent.getStringExtra(EXTRA_NOTIF_TITLE).orEmpty()
            val body = intent.getStringExtra(EXTRA_NOTIF_BODY).orEmpty()
            bleLinkManager.sendNotification(source, title, body)
        }
    }

    override fun onCreate() {
        super.onCreate()
        bleLinkManager = BleLinkManager(this)
        createChannel()
        ContextCompat.registerReceiver(
            this,
            notifReceiver,
            IntentFilter(ACTION_FORWARD_NOTIF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Twister activo")
            .setContentText("Enlace BLE en segundo plano")
            .setSmallIcon(R.drawable.ic_stat_name)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
        startHeartbeat()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        heartbeatTimer?.cancel()
        unregisterReceiver(notifReceiver)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = fixedRateTimer(
            name = "twister-heartbeat",
            daemon = true,
            initialDelay = 5000L,
            period = 5000L
        ) {
            val uptimeS = (SystemClock.elapsedRealtime() / 1000L).toUInt()
            bleLinkManager.sendHeartbeat(uptimeS)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Twister Service",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "twister_foreground"
        const val ACTION_FORWARD_NOTIF = "com.twister.bridge.ACTION_FORWARD_NOTIF"
        const val EXTRA_NOTIF_SOURCE = "extra_notif_source"
        const val EXTRA_NOTIF_TITLE = "extra_notif_title"
        const val EXTRA_NOTIF_BODY = "extra_notif_body"
    }
}
