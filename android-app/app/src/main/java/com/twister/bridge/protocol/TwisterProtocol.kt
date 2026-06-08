package com.twister.bridge.protocol

enum class TwisterMessageType(val raw: UByte) {
    TURN(0x01u),
    NOTIF(0x02u),
    HEARTBEAT(0x03u),
    STATUS(0x04u),
    CMD(0x05u),
    GPS_RAW(0x06u)
}

enum class TwisterNotifSource(val raw: UByte) {
    CALL(0u),
    WHATSAPP(1u),
    TELEGRAM(2u),
    SYSTEM(3u);

    companion object {
        fun fromRaw(value: Int): TwisterNotifSource {
            return entries.firstOrNull { it.raw.toInt() == value } ?: CALL
        }
    }
}

object TwisterProtocol {
    private const val MAX_ROAD_NAME = 40
    private const val MAX_TITLE = 32
    private const val MAX_BODY = 64

    fun buildFrame(type: TwisterMessageType, seq: UByte, payload: ByteArray): ByteArray {
        require(payload.size <= 0xFF) { "Payload demasiado grande para v1" }

        val output = ByteArray(3 + payload.size + 1)
        output[0] = type.raw.toByte()
        output[1] = seq.toByte()
        output[2] = payload.size.toUByte().toByte()
        payload.copyInto(output, destinationOffset = 3)
        output[output.lastIndex] = crc8Xor(output, output.size - 1).toByte()
        return output
    }

    fun buildTurnPayload(direction: Int, distanceM: Int, roadName: String, exitNumber: Int = 0): ByteArray {
        val safeRoad = roadName.trim().take(MAX_ROAD_NAME).encodeToByteArray()
        val safeDistance = distanceM.coerceIn(0, 65535)
        val output = ByteArray(5 + safeRoad.size)

        output[0] = direction.coerceIn(0, 6).toByte()   // 6 = ROUNDABOUT
        output[1] = (safeDistance and 0xFF).toByte()
        output[2] = ((safeDistance shr 8) and 0xFF).toByte()
        output[3] = exitNumber.coerceIn(0, 9).toByte()
        output[4] = safeRoad.size.toByte()
        safeRoad.copyInto(output, destinationOffset = 5)

        return output
    }

    fun buildNotifPayload(source: TwisterNotifSource, title: String, body: String): ByteArray {
        val safeTitle = title.trim().take(MAX_TITLE).encodeToByteArray()
        val safeBody = body.trim().take(MAX_BODY).encodeToByteArray()

        val output = ByteArray(3 + safeTitle.size + safeBody.size)
        var i = 0
        output[i++] = source.raw.toByte()
        output[i++] = safeTitle.size.toByte()
        safeTitle.copyInto(output, destinationOffset = i)
        i += safeTitle.size
        output[i++] = safeBody.size.toByte()
        safeBody.copyInto(output, destinationOffset = i)

        return output
    }

    fun buildHeartbeatPayload(uptimeSeconds: UInt, flags: UByte = 0u): ByteArray {
        val output = ByteArray(5)
        output[0] = (uptimeSeconds and 0xFFu).toByte()
        output[1] = ((uptimeSeconds shr 8) and 0xFFu).toByte()
        output[2] = ((uptimeSeconds shr 16) and 0xFFu).toByte()
        output[3] = ((uptimeSeconds shr 24) and 0xFFu).toByte()
        output[4] = flags.toByte()
        return output
    }

    fun buildCmdPayload(cmdId: Byte): ByteArray = byteArrayOf(cmdId)

    fun buildGpsRawPayload(text: String): ByteArray =
        text.trim().take(80).encodeToByteArray()

    private fun crc8Xor(data: ByteArray, len: Int): UByte {
        var crc = 0
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
        }
        return crc.toUByte()
    }
}
