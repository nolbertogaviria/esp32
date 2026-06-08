package com.twister.bridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.twister.bridge.protocol.TwisterMessageType
import com.twister.bridge.protocol.TwisterNotifSource
import com.twister.bridge.protocol.TwisterProtocol
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

@SuppressLint("MissingPermission")
class BleLinkManager(private val context: Context) {

    companion object {
        private const val TAG = "TwisterBle"
        private const val DEVICE_NAME = "Twister"
        const val PREFS_NAME = "twister_ble"
        const val PREF_MAC = "saved_mac"
        const val PREF_DEVICE_NAME = "saved_device_name"
        const val ACTION_BLE_STATE = "com.twister.bridge.BLE_STATE"
        const val EXTRA_CONNECTED = "ble_connected"
        private val SVC_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        private val CHAR_RX_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    }

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var seq: UByte = 0u
    private var scanning = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val writeQueue = LinkedBlockingQueue<ByteArray>(32)
    @Volatile private var writeInProgress = false
    private var writeTimeoutRunnable: Runnable? = null
    private var discoverRunnable: Runnable? = null  // para cancelarlo si hay disconnect antes del delay

    init {
        connectOrScan()
    }

    // ---- Public API ----

    fun isConnected(): Boolean = rxChar != null

    fun sendTurn(direction: Int, distanceM: Int, roadName: String, exitNumber: Int = 0) {
        val payload = TwisterProtocol.buildTurnPayload(direction, distanceM, roadName, exitNumber)
        enqueue(TwisterProtocol.buildFrame(TwisterMessageType.TURN, nextSeq(), payload))
    }

    fun sendNotification(source: Int, title: String, body: String) {
        val safeSource = TwisterNotifSource.fromRaw(source)
        val payload = TwisterProtocol.buildNotifPayload(safeSource, title, body)
        enqueue(TwisterProtocol.buildFrame(TwisterMessageType.NOTIF, nextSeq(), payload))
    }

    fun sendHeartbeat(uptimeSeconds: UInt, flags: UByte = 0u) {
        val payload = TwisterProtocol.buildHeartbeatPayload(uptimeSeconds, flags)
        enqueue(TwisterProtocol.buildFrame(TwisterMessageType.HEARTBEAT, nextSeq(), payload))
    }

    fun sendCmd(cmdId: Byte) {
        val payload = TwisterProtocol.buildCmdPayload(cmdId)
        enqueue(TwisterProtocol.buildFrame(TwisterMessageType.CMD, nextSeq(), payload))
    }

    fun sendGpsRaw(text: String) {
        val payload = TwisterProtocol.buildGpsRawPayload(text)
        enqueue(TwisterProtocol.buildFrame(TwisterMessageType.GPS_RAW, nextSeq(), payload))
    }

    fun release() {
        discoverRunnable?.let { mainHandler.removeCallbacks(it) }
        discoverRunnable = null
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        rxChar = null
    }

    /** Cambia el dispositivo objetivo, guarda en prefs y reconecta. */
    fun setTargetDevice(mac: String, name: String) {
        Log.i(TAG, "Nuevo dispositivo objetivo: $name ($mac)")
        prefs.edit().putString(PREF_MAC, mac).putString(PREF_DEVICE_NAME, name).apply()
        discoverRunnable?.let { mainHandler.removeCallbacks(it) }
        discoverRunnable = null
        rxChar = null
        writeInProgress = false
        writeQueue.clear()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        broadcastBleState(false)
        mainHandler.postDelayed({ connectOrScan() }, 500)
    }

    // ---- Connect or scan ----

    private fun connectOrScan() {
        // 1) MAC guardada en prefs
        val savedMac = prefs.getString(PREF_MAC, null)
        if (savedMac != null) {
            Log.i(TAG, "MAC guardada: $savedMac, conectando directamente")
            connectToMac(savedMac)
            return
        }
        // 2) Buscar en dispositivos emparejados (bonded) — evita el scan y el diálogo de Samsung
        val bonded = adapter.bondedDevices?.find { it.name == DEVICE_NAME }
        if (bonded != null) {
            Log.i(TAG, "Dispositivo emparejado encontrado: ${bonded.address}, conectando sin scan")
            prefs.edit().putString(PREF_MAC, bonded.address).putString(PREF_DEVICE_NAME, bonded.name ?: DEVICE_NAME).apply()
            connectToMac(bonded.address)
            return
        }
        // 3) Scan como último recurso
        Log.i(TAG, "Sin MAC guardada ni dispositivo emparejado, iniciando scan")
        startScan()
    }

    private fun connectToMac(mac: String) {
        try {
            val device = adapter.getRemoteDevice(mac)
            // autoConnect=true: Android espera slot BLE libre en lugar de fallar con error 133
            // (necesario cuando hay smartwatch + auriculares + otros dispositivos BLE activos)
            device.connectGatt(context, true, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.e(TAG, "Error conectando a MAC guardada: ${e.message}, iniciando scan")
            prefs.edit().remove(PREF_MAC).apply()
            startScan()
        }
    }

    // ---- Scan ----

    private fun startScan() {
        if (scanning || adapter?.isEnabled != true) return
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            val filter = ScanFilter.Builder().setDeviceName(DEVICE_NAME).build()
            adapter.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            Log.i(TAG, "Scan iniciado buscando '$DEVICE_NAME'")
        } catch (e: SecurityException) {
            Log.e(TAG, "Sin permiso BLE para scan: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar scan: ${e.message}")
        }
    }

    private fun stopScan() {
        if (!scanning) return
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) { /* ignorar */ }
        scanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address
            val name = result.device.name ?: DEVICE_NAME
            Log.i(TAG, "Dispositivo encontrado: $name ($mac)")
            stopScan()
            prefs.edit().putString(PREF_MAC, mac).putString(PREF_DEVICE_NAME, name).apply()
            result.device.connectGatt(context, true, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan fallido codigo=$errorCode")
            scanning = false
        }
    }

    // ---- GATT ----

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            gatt = g
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT conectado (status=$status), pidiendo alta prioridad y esperando 600ms")
                // CONNECTION_PRIORITY_HIGH reduce latencia y evita que Samsung corte la conexión (error 133)
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                discoverRunnable = Runnable {
                    discoverRunnable = null
                    if (gatt == null) {
                        Log.w(TAG, "discoverServices cancelado: gatt ya cerrado")
                        return@Runnable
                    }
                    val ok = g.discoverServices()
                    Log.i(TAG, "discoverServices() = $ok")
                    if (!ok) {
                        // Samsung a veces devuelve false: reintento tras 1s
                        Log.w(TAG, "discoverServices() false, reintentando en 1s")
                        mainHandler.postDelayed({ g.discoverServices() }, 1000)
                    }
                }
                mainHandler.postDelayed(discoverRunnable!!, 600)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT desconectado (status=$status), reintentando en 3s")
                // Cancelar el discover pendiente para no llamarlo sobre gatt cerrado
                discoverRunnable?.let { mainHandler.removeCallbacks(it) }
                discoverRunnable = null
                rxChar = null
                writeInProgress = false
                writeQueue.clear()
                broadcastBleState(false)
                g.close()
                gatt = null
                mainHandler.postDelayed({ connectOrScan() }, 3000)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onServicesDiscovered error=$status")
                return
            }
            val services = g.services.map { it.uuid.toString() }
            Log.i(TAG, "Servicios descubiertos (${services.size}): $services")
            rxChar = g.getService(SVC_UUID)?.getCharacteristic(CHAR_RX_UUID)
            if (rxChar != null) {
                Log.i(TAG, "Listo para enviar frames al ESP32")
                broadcastBleState(true)
                drainQueue()
            } else {
                Log.e(TAG, "Caracteristica RX no encontrada. SVC_UUID=$SVC_UUID buscado en: $services")
                prefs.edit().remove(PREF_MAC).apply()
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Write fallido status=$status")
            } else {
                Log.d(TAG, "Write ACK recibido")
            }
            // Despachar al main thread para evitar condicion de carrera con enqueue()
            mainHandler.post {
                writeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
                writeTimeoutRunnable = null
                writeInProgress = false
                drainQueue()
            }
        }
    }

    // ---- Write queue ----

    private fun enqueue(frame: ByteArray) {
        if (writeQueue.size >= 32) {
            Log.w(TAG, "Cola llena, descartando frame")
            return
        }
        writeQueue.offer(frame)
        if (rxChar != null) drainQueue()
        else Log.d(TAG, "Sin conexion BLE aun, frame en cola (${writeQueue.size})")
    }

    // Debe llamarse siempre desde el main thread para evitar condicion de carrera
    private fun drainQueue() {
        if (writeInProgress) return
        val frame = writeQueue.poll() ?: return
        val char = rxChar ?: return
        val g = gatt ?: return
        writeInProgress = true
        // Cancelar timeout anterior y registrar uno nuevo
        writeTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val timeout = Runnable {
            if (writeInProgress) {
                Log.w(TAG, "Timeout write, desbloqueando cola")
                writeTimeoutRunnable = null
                writeInProgress = false
                drainQueue()
            }
        }
        writeTimeoutRunnable = timeout
        try {
            // WRITE_TYPE_DEFAULT (con respuesta): garantiza que onCharacteristicWrite siempre
            // se llame, evitando que writeInProgress quede bloqueado en true.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(char, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                @Suppress("DEPRECATION")
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                char.value = frame
                @Suppress("DEPRECATION")
                g.writeCharacteristic(char)
            }
            Log.d(TAG, "Frame enviado len=${frame.size} ${frame.toHexString()}")
            mainHandler.postDelayed(timeout, 4000)
        } catch (e: Exception) {
            Log.e(TAG, "Error al escribir: ${e.message}")
            writeTimeoutRunnable = null
            writeInProgress = false
        }
    }

    // ---- Helpers ----

    private fun broadcastBleState(connected: Boolean) {
        val intent = Intent(ACTION_BLE_STATE)
            .setPackage(context.packageName)
            .putExtra(EXTRA_CONNECTED, connected)
        context.sendBroadcast(intent)
    }

    /** Permite que MainActivity consulte el estado actual al abrirse. */
    fun broadcastCurrentState() {
        broadcastBleState(rxChar != null)
    }

    private fun nextSeq(): UByte {
        val current = seq
        seq = (seq.toInt() + 1).and(0xFF).toUByte()
        return current
    }

    private fun ByteArray.toHexString() = joinToString(" ") { "%02X".format(it) }
}
