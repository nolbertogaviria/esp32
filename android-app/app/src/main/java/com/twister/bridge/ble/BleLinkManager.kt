package com.twister.bridge.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.twister.bridge.protocol.TwisterMessageType
import com.twister.bridge.protocol.TwisterNotifSource
import com.twister.bridge.protocol.TwisterProtocol

class BleLinkManager(private val context: Context) {
    private var seq: UByte = 0u

    fun connect(device: BluetoothDevice) {
        Log.i("TwisterBle", "TODO connect BLE device: ${device.address}")
    }

    fun sendTurn(direction: Int, distanceM: Int, roadName: String) {
        val payload = TwisterProtocol.buildTurnPayload(direction, distanceM, roadName)
        val frame = TwisterProtocol.buildFrame(TwisterMessageType.TURN, nextSeq(), payload)
        sendFrame(frame)
    }

    fun sendNotification(source: Int, title: String, body: String) {
        val safeSource = TwisterNotifSource.fromRaw(source)
        val payload = TwisterProtocol.buildNotifPayload(safeSource, title, body)
        val frame = TwisterProtocol.buildFrame(TwisterMessageType.NOTIF, nextSeq(), payload)
        sendFrame(frame)
    }

    fun sendHeartbeat(uptimeSeconds: UInt, flags: UByte = 0u) {
        val payload = TwisterProtocol.buildHeartbeatPayload(uptimeSeconds, flags)
        val frame = TwisterProtocol.buildFrame(TwisterMessageType.HEARTBEAT, nextSeq(), payload)
        sendFrame(frame)
    }

    private fun nextSeq(): UByte {
        val current = seq
        seq = (seq.toInt() + 1).and(0xFF).toUByte()
        return current
    }

    private fun sendFrame(frame: ByteArray) {
        // TODO conectar a writeCharacteristic BLE cuando el enlace GATT este implementado.
        Log.i("TwisterBle", "Frame len=${frame.size} data=${frame.toHexString()}")
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(separator = " ") { each -> "%02X".format(each) }
    }
}
