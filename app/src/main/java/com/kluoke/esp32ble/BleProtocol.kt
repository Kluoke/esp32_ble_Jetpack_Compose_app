Exit code: 0
Wall time: 1.2 seconds
Output:
package com.kluoke.esp32ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Versioned application protocol shared by the Android client and ESP32 firmware. */
object BleProtocol {
    const val VERSION: Byte = 1
    private const val MAGIC_0: Byte = 0xAA.toByte()
    private const val MAGIC_1: Byte = 0x55
    private const val HEADER_SIZE = 9
    private const val CRC_SIZE = 2
    const val MAX_PAYLOAD = 240

    object Command {
        const val SET_WIFI: Byte = 0x01
        const val SET_MQTT: Byte = 0x03
        const val REBOOT: Byte = 0x06
        const val GET_STATUS: Byte = 0x08
        const val WIFI_SCAN: Byte = 0x09
        const val OTA_BEGIN: Byte = 0x60
        const val OTA_END: Byte = 0x61
        const val OTA_ABORT: Byte = 0x62
        const val OTA_QUERY: Byte = 0x63
        const val ACK: Byte = 0x80.toByte()
        const val ERROR: Byte = 0xC0.toByte()
        const val WIFI_STATUS: Byte = 0xE0.toByte()
        const val WIFI_SCAN_RESULT: Byte = 0xE1.toByte()
        const val OTA_STATUS: Byte = 0xE2.toByte()
        const val LED_STATE: Byte = 0xE3.toByte()
    }

    data class Packet(val flags: Byte, val command: Byte, val sequence: Int, val payload: ByteArray)

    fun setWifiPayload(ssid: String, password: String): ByteArray {
        val ssidBytes = ssid.encodeToByteArray()
        val passwordBytes = password.encodeToByteArray()
        require(ssidBytes.isNotEmpty() && ssidBytes.size <= 32) { "SSID must be 1..32 bytes" }
        require(passwordBytes.size <= 64) { "Password must be at most 64 bytes" }
        return byteArrayOf(ssidBytes.size.toByte(), passwordBytes.size.toByte()) + ssidBytes + passwordBytes
    }

    fun encode(command: Byte, sequence: Int, payload: ByteArray = ByteArray(0), flags: Byte = 0): ByteArray {
        require(payload.size <= MAX_PAYLOAD)
        val frame = ByteArray(HEADER_SIZE + payload.size + CRC_SIZE)
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC_0).put(MAGIC_1).put(VERSION).put(flags).put(command)
        buffer.putShort(sequence.toShort()).putShort(payload.size.toShort()).put(payload)
        buffer.putShort(crc16(frame, 0, HEADER_SIZE + payload.size).toShort())
        return frame
    }

    /** Incremental decoder: notification and write boundaries are not protocol boundaries. */
    class Decoder {
        private val bytes = ArrayList<Byte>()

        fun append(chunk: ByteArray): List<Packet> {
            bytes.addAll(chunk.toList())
            val packets = mutableListOf<Packet>()
            while (bytes.size >= HEADER_SIZE + CRC_SIZE) {
                if (bytes[0] != MAGIC_0 || bytes[1] != MAGIC_1) { bytes.removeAt(0); continue }
                if (bytes[2] != VERSION) { bytes.removeAt(0); continue }
                val length = (bytes[7].toInt() and 0xFF) or ((bytes[8].toInt() and 0xFF) shl 8)
                if (length > MAX_PAYLOAD) { bytes.removeAt(0); continue }
                val frameSize = HEADER_SIZE + length + CRC_SIZE
                if (bytes.size < frameSize) break
                val frame = bytes.take(frameSize).toByteArray()
                val actualCrc = (frame[frameSize - 2].toInt() and 0xFF) or
                    ((frame[frameSize - 1].toInt() and 0xFF) shl 8)
                if (crc16(frame, 0, frameSize - CRC_SIZE) == actualCrc) {
                    val sequence = (frame[5].toInt() and 0xFF) or ((frame[6].toInt() and 0xFF) shl 8)
                    packets += Packet(frame[3], frame[4], sequence, frame.copyOfRange(HEADER_SIZE, HEADER_SIZE + length))
                }
                repeat(frameSize) { bytes.removeAt(0) }
            }
            return packets
        }
    }

    private fun crc16(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFF
        for (index in offset until offset + length) {
            crc = crc xor (data[index].toInt() and 0xFF)
            repeat(8) { crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc and 0xFFFF
    }
}

