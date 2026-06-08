package com.twister.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.twister.bridge.ble.BleLinkManager
import com.twister.bridge.nav.OsmAndInstructionLog
import com.twister.bridge.service.TwisterForegroundService
import java.io.File

class MainActivity : AppCompatActivity() {

    private val blePermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    private lateinit var bleStateView: TextView
    private lateinit var bleDot: View
    private lateinit var deviceView: TextView
    private lateinit var notifStatusView: TextView
    private lateinit var blePermView: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            startTwisterService()
        } else {
            blePermView.text = "DENEGADO"
            blePermView.setTextColor(0xFFFF3D00.toInt())
        }
    }

    private val bleStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connected = intent.getBooleanExtra(BleLinkManager.EXTRA_CONNECTED, false)
            if (connected) {
                bleStateView.text = "Conectado"
                bleStateView.setTextColor(0xFF00C853.toInt())
                bleDot.backgroundTintList = ColorStateList.valueOf(0xFF00C853.toInt())
            } else {
                bleStateView.text = "Buscando..."
                bleStateView.setTextColor(0xFFFFB300.toInt())
                bleDot.backgroundTintList = ColorStateList.valueOf(0xFFFFB300.toInt())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        bleStateView    = findViewById(R.id.bleStateView)
        bleDot          = findViewById(R.id.bleDot)
        deviceView      = findViewById(R.id.deviceView)
        notifStatusView = findViewById(R.id.notifStatusView)
        blePermView     = findViewById(R.id.blePermView)
        swipeRefresh    = findViewById(R.id.swipeRefresh)

        swipeRefresh.setColorSchemeColors(0xFFFF6D00.toInt())
        swipeRefresh.setProgressBackgroundColorSchemeColor(0xFF1A1A1A.toInt())

        findViewById<MaterialButton>(R.id.selectDeviceBtn).setOnClickListener { showDevicePicker() }
        findViewById<MaterialButton>(R.id.testBtn).setOnClickListener { sendTestNotif() }
        findViewById<MaterialButton>(R.id.sendActiveNotifsBtn).setOnClickListener { sendActiveNotifs() }
        findViewById<MaterialButton>(R.id.simulateGpsBtn).setOnClickListener { simulateGps() }
        findViewById<MaterialButton>(R.id.splashBtn).setOnClickListener { sendSplash() }
        findViewById<MaterialButton>(R.id.osmandLogBtn).setOnClickListener { openOsmandLog() }

        swipeRefresh.setOnRefreshListener {
            updateStatus()
            updateDeviceView()
            sendBroadcast(Intent(TwisterForegroundService.ACTION_REQUEST_STATE).setPackage(packageName))
            swipeRefresh.isRefreshing = false
        }

        ensureNotificationListenerEnabled()

        if (blePermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startTwisterService()
        } else {
            permLauncher.launch(blePermissions)
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            bleStateReceiver,
            IntentFilter(BleLinkManager.ACTION_BLE_STATE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        sendBroadcast(Intent(TwisterForegroundService.ACTION_REQUEST_STATE).setPackage(packageName))
        updateStatus()
        updateDeviceView()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(bleStateReceiver)
    }

    @SuppressLint("MissingPermission")
    private fun showDevicePicker() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

        // Solo dispositivos BLE (LE puro, DUAL o desconocido) — excluye auriculares/teclados clásicos
        val bonded: List<BluetoothDevice> = (adapter.bondedDevices ?: emptySet())
            .filter { it.type == BluetoothDevice.DEVICE_TYPE_LE || it.type == BluetoothDevice.DEVICE_TYPE_UNKNOWN }
            .sortedBy { it.name ?: "" }

        if (bonded.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Sin dispositivos BLE emparejados")
                .setMessage("Ve a Ajustes → Bluetooth y empareja el ESP32 \"Twister\" primero.\n\n(Los dispositivos clásicos como auriculares no aparecen aquí.)")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val typeLabel = mapOf(
            BluetoothDevice.DEVICE_TYPE_LE      to "BLE",
            BluetoothDevice.DEVICE_TYPE_UNKNOWN to "?"
        )
        val names = bonded.map {
            val tipo = typeLabel[it.type] ?: "?"
            "${it.name ?: "Desconocido"}  [$tipo]  (${it.address})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Seleccionar dispositivo BLE")
            .setItems(names) { _, idx ->
                val device = bonded[idx]
                val mac  = device.address
                val name = device.name ?: "Desconocido"
                sendBroadcast(
                    Intent(TwisterForegroundService.ACTION_SET_DEVICE)
                        .setPackage(packageName)
                        .putExtra(TwisterForegroundService.EXTRA_DEVICE_MAC, mac)
                        .putExtra(TwisterForegroundService.EXTRA_DEVICE_NAME, name)
                )
                updateDeviceView()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateDeviceView() {
        val prefs = getSharedPreferences(BleLinkManager.PREFS_NAME, Context.MODE_PRIVATE)
        val mac  = prefs.getString(BleLinkManager.PREF_MAC, null)
        val name = prefs.getString(BleLinkManager.PREF_DEVICE_NAME, null)
        deviceView.text = if (mac != null) "$name\n$mac"
                          else "Ninguno configurado"
    }

    private fun ensureNotificationListenerEnabled() {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        if (!enabled) startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun startTwisterService() {
        startForegroundService(Intent(this, TwisterForegroundService::class.java))
        updateStatus()
    }

    private fun sendTestNotif() {
        sendBroadcast(Intent(TwisterForegroundService.ACTION_TEST_NOTIF).setPackage(packageName))
    }

    private fun sendActiveNotifs() {
        sendBroadcast(Intent(TwisterForegroundService.ACTION_SEND_ACTIVE_NOTIFS).setPackage(packageName))
    }

    private fun simulateGps() {
        sendBroadcast(Intent(TwisterForegroundService.ACTION_SIMULATE_GPS).setPackage(packageName))
    }

    private fun sendSplash() {
        sendBroadcast(Intent(TwisterForegroundService.ACTION_SPLASH).setPackage(packageName))
    }

    private fun openOsmandLog() {
        val f = File(OsmAndInstructionLog.path(this))
        if (!f.exists() || f.length() == 0L) {
            AlertDialog.Builder(this)
                .setTitle("Log OsmAnd")
                .setMessage("El archivo est\u00e1 vac\u00edo o no existe a\u00fan.\nInicia OsmAnd con una ruta activa para generar datos.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val lines  = f.readLines()
        val shown  = lines.takeLast(300)
        val header = if (lines.size > 300) "(mostrando \u00falimas 300 de ${lines.size} l\u00edneas)\n\n" else ""
        val preview = header + shown.joinToString("\n")

        val textView = TextView(this).apply {
            text      = preview
            textSize  = 9.5f
            typeface  = Typeface.MONOSPACE
            setTextColor(0xFFCCCCCC.toInt())
            setBackgroundColor(0xFF111111.toInt())
            setPadding(28, 28, 28, 28)
        }
        val scroll = ScrollView(this).apply {
            addView(textView)
            setBackgroundColor(0xFF111111.toInt())
        }
        // Desplazar al final tras renderizar
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

        AlertDialog.Builder(this)
            .setTitle("Log OsmAnd \u2014 ${f.name}")
            .setView(scroll)
            .setPositiveButton("Compartir") { _, _ ->
                val fullText = f.readText()
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, OsmAndInstructionLog.FILE_NAME)
                    putExtra(Intent.EXTRA_TEXT, fullText)
                }
                startActivity(Intent.createChooser(share, "Compartir log"))
            }
            .setNeutralButton("Copiar") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("osmand_log", f.readText()))
                Toast.makeText(this, "Copiado al portapapeles", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun updateStatus() {
        val notifEnabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true
        val bleGranted = blePermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        notifStatusView.text = if (notifEnabled) "ACTIVO" else "DESHABILITADO"
        notifStatusView.setTextColor(if (notifEnabled) 0xFF00C853.toInt() else 0xFFFF3D00.toInt())

        blePermView.text = if (bleGranted) "CONCEDIDO" else "FALTA PERMISO"
        blePermView.setTextColor(if (bleGranted) 0xFF00C853.toInt() else 0xFFFF3D00.toInt())
    }
}

