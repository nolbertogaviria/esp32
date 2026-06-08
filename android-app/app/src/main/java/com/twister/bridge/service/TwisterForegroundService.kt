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
import com.twister.bridge.nav.OsmAndNavigationBridge
import com.twister.bridge.notif.TwisterNotificationListener
import java.util.Timer
import kotlin.concurrent.fixedRateTimer

class TwisterForegroundService : Service() {
    private lateinit var bleLinkManager: BleLinkManager
    private var osmAndBridge: OsmAndNavigationBridge? = null
    private var heartbeatTimer: Timer? = null
    private var gpsScenarioIndex = 0
    private var timeSyncTick = 0

    // Escenarios de simulacion GPS: (direction, distanceM, roadName)
    //   aviso lejano → acercamiento → giro → continuar → proximo giro → autopista → salida
    // Direcciones: 0=IZQ, 1=DER, 2=GIRO_U, 3=RECTO, 4=SAL_IZQ, 5=SAL_DER
    // Valores de dirección: STRAIGHT=0, LEFT=1, RIGHT=2, UTURN=3, EXIT_LEFT=4, EXIT_RIGHT=5
    private val gpsScenarios = listOf(
        // Previo al giro — aviso lejano (500m)
        Triple(2, 500,  "Av Corrientes"),   // RIGHT
        // Acercamiento (200m)
        Triple(2, 200,  "Av Corrientes"),   // RIGHT
        // Inminente (80m)
        Triple(2, 80,   "Av Corrientes"),   // RIGHT
        // Giro realizado — continuar recto por la nueva calle
        Triple(0, 1400, "Calle Florida"),   // STRAIGHT
        // Proximo giro a la izquierda (300m)
        Triple(1, 300,  "Calle Florida"),   // LEFT
        // Giro izquierda inminente (60m)
        Triple(1, 60,   "Calle Florida"),   // LEFT
        // En autopista — continuar recto largo tramo
        Triple(0, 8200, "Autopista Panamericana"), // STRAIGHT
        // Preparar salida derecha (1km)
        Triple(5, 1000, "Autopista Panamericana"),
        // Salida inminente (300m)
        Triple(5, 300,  "Autopista Panamericana"),
        // Giro en U (recalculo de ruta)
        Triple(3, 40,   "Av San Martin"),   // UTURN
        // Continuar recto, destino proximo
        Triple(0, 150,  "Llegando al destino") // STRAIGHT
    )

    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_FORWARD_TURN -> {
                    val dir  = intent.getIntExtra(EXTRA_TURN_DIR, 3)
                    val dist = intent.getIntExtra(EXTRA_TURN_DIST, 0)
                    val road = intent.getStringExtra(EXTRA_TURN_ROAD).orEmpty()
                    val exit = intent.getIntExtra(EXTRA_TURN_EXIT, 0)
                    bleLinkManager.sendTurn(dir, dist, road, exit)
                }
                ACTION_FORWARD_NOTIF -> {
                    val source = intent.getIntExtra(EXTRA_NOTIF_SOURCE, 0)
                    val title = intent.getStringExtra(EXTRA_NOTIF_TITLE).orEmpty()
                    val body = intent.getStringExtra(EXTRA_NOTIF_BODY).orEmpty()
                    bleLinkManager.sendNotification(source, title, body)
                }
                ACTION_SEND_ACTIVE_NOTIFS -> {
                    com.twister.bridge.notif.TwisterNotificationListener.instance
                        ?.forwardActiveNotifications()
                }
                ACTION_SYNC_NOTIFS -> {
                    bleLinkManager.sendCmd(TWISTER_CMD_CLEAR_NOTIF)
                    val sources = intent.getIntArrayExtra(EXTRA_NOTIF_SOURCES) ?: return
                    val titles  = intent.getStringArrayExtra(EXTRA_NOTIF_TITLES) ?: return
                    val bodies  = intent.getStringArrayExtra(EXTRA_NOTIF_BODIES) ?: return
                    for (i in sources.indices) {
                        bleLinkManager.sendNotification(
                            sources[i],
                            titles.getOrElse(i) { "" },
                            bodies.getOrElse(i) { "" }
                        )
                    }
                }
                ACTION_TEST_NOTIF -> {
                    val now = java.time.LocalDateTime.now()
                    val fecha = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").format(now)
                    val hora  = java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(now)
                    bleLinkManager.sendNotification(3, fecha, hora)
                }
                ACTION_SIMULATE_GPS -> {
                    val s = gpsScenarios[gpsScenarioIndex % gpsScenarios.size]
                    bleLinkManager.sendTurn(s.first, s.second, s.third)
                    gpsScenarioIndex++
                }
                ACTION_SPLASH -> {
                    bleLinkManager.sendCmd(TWISTER_CMD_SPLASH)
                }
                ACTION_REQUEST_STATE -> {
                    bleLinkManager.broadcastCurrentState()
                }
                ACTION_SET_DEVICE -> {
                    val mac  = intent!!.getStringExtra(EXTRA_DEVICE_MAC) ?: return
                    val name = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: mac
                    bleLinkManager.setTargetDevice(mac, name)
                }
                ACTION_FORWARD_GPS_RAW -> {
                    val text = intent.getStringExtra(EXTRA_GPS_RAW_TEXT).orEmpty()
                    bleLinkManager.sendGpsRaw(text)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // startForeground() debe llamarse lo antes posible para evitar crash en Android 12+
        createChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Twister activo")
            .setContentText("Enlace BLE en segundo plano")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        startForeground(1001, notification)

        bleLinkManager = BleLinkManager(this)
        osmAndBridge = OsmAndNavigationBridge(this).also { it.connect() }
        ContextCompat.registerReceiver(
            this,
            notifReceiver,
            IntentFilter(ACTION_FORWARD_NOTIF).also {
                it.addAction(ACTION_FORWARD_TURN)
                it.addAction(ACTION_FORWARD_GPS_RAW)
                it.addAction(ACTION_TEST_NOTIF)
                it.addAction(ACTION_REQUEST_STATE)
                it.addAction(ACTION_SET_DEVICE)
                it.addAction(ACTION_SEND_ACTIVE_NOTIFS)
                it.addAction(ACTION_SIMULATE_GPS)
                it.addAction(ACTION_SPLASH)
                it.addAction(ACTION_SYNC_NOTIFS)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        startHeartbeat()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        heartbeatTimer?.cancel()
        osmAndBridge?.disconnect()
        bleLinkManager.release()
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
            if (bleLinkManager.isConnected()) {
                val uptimeS = (SystemClock.elapsedRealtime() / 1000L).toUInt()
                bleLinkManager.sendHeartbeat(uptimeS)
                
                // Enviar la fecha y la hora cada 10 segundos (cada 2 ticks de 5s)
                timeSyncTick++
                if (timeSyncTick % 2 == 0) {
                    val now = java.time.LocalDateTime.now()
                    val fecha = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").format(now)
                    val hora  = java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(now)
                    bleLinkManager.sendNotification(3, fecha, hora)
                }
            }
            // Poll activo: si Maps está navegando, reenviar su instrucción aunque
            // onNotificationPosted no haya disparado (algunos Android/OEM no son confiables)
            // Poll de notificaciones solo cuando AIDL no está activo (evita sobreescribir
            // la visualización estructurada con texto crudo)
            if (bleLinkManager.isConnected() && osmAndBridge?.isConnected != true) {
                TwisterNotificationListener.instance?.getActiveNavText()?.let { navText ->
                    bleLinkManager.sendGpsRaw(navText)
                }
            }
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
        const val ACTION_FORWARD_NOTIF  = "com.twister.bridge.ACTION_FORWARD_NOTIF"
        const val ACTION_FORWARD_TURN   = "com.twister.bridge.ACTION_FORWARD_TURN"
        const val ACTION_FORWARD_GPS_RAW = "com.twister.bridge.ACTION_FORWARD_GPS_RAW"
        const val EXTRA_GPS_RAW_TEXT     = "extra_gps_raw_text"
        const val ACTION_TEST_NOTIF     = "com.twister.bridge.ACTION_TEST_NOTIF"
        const val ACTION_REQUEST_STATE  = "com.twister.bridge.REQUEST_STATE"
        const val ACTION_SET_DEVICE     = "com.twister.bridge.SET_DEVICE"
        const val ACTION_SEND_ACTIVE_NOTIFS = "com.twister.bridge.SEND_ACTIVE_NOTIFS"
        const val ACTION_SIMULATE_GPS   = "com.twister.bridge.SIMULATE_GPS"
        const val ACTION_SPLASH          = "com.twister.bridge.ACTION_SPLASH"
        const val TWISTER_CMD_SPLASH: Byte = 0x01
        const val EXTRA_TURN_DIR  = "extra_turn_dir"
        const val EXTRA_TURN_DIST = "extra_turn_dist"
        const val EXTRA_TURN_ROAD = "extra_turn_road"
        const val EXTRA_TURN_EXIT = "extra_turn_exit"
        const val EXTRA_DEVICE_MAC      = "extra_device_mac"
        const val EXTRA_DEVICE_NAME     = "extra_device_name"
        const val EXTRA_NOTIF_SOURCE  = "extra_notif_source"
        const val EXTRA_NOTIF_TITLE   = "extra_notif_title"
        const val EXTRA_NOTIF_BODY    = "extra_notif_body"
        const val ACTION_SYNC_NOTIFS  = "com.twister.bridge.ACTION_SYNC_NOTIFS"
        const val TWISTER_CMD_CLEAR_NOTIF: Byte = 0x02
        const val EXTRA_NOTIF_SOURCES = "extra_notif_sources"
        const val EXTRA_NOTIF_TITLES  = "extra_notif_titles"
        const val EXTRA_NOTIF_BODIES  = "extra_notif_bodies"
    }
}
