Exit code: 0
Wall time: 1.6 seconds
Output:
package com.kluoke.esp32ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        require(sequence in 1..0xFFFF) { "sequence must be 1..65535" }
        require(payload.size <= MAX_PAYLOAD)
        val frame = ByteArray(HEADER_SIZE + payload.size + CRC_SIZE)
        ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(MAGIC_0).put(MAGIC_1).put(VERSION).put(flags).put(command)
            putShort(sequence.toShort()).putShort(payload.size.toShort()).put(payload)
            putShort(crc16(frame, 0, HEADER_SIZE + payload.size).toShort())
        }
        return frame
    }

    /** Thread-safe incremental frame decoder with an offset-based buffer. */
    class Decoder {
        private var buffer = ByteArray(512)
        private var start = 0
        private var end = 0

        @Synchronized
        fun append(chunk: ByteArray): List<Packet> {
            ensureCapacity(chunk.size)
            chunk.copyInto(buffer, end)
            end += chunk.size
            val packets = mutableListOf<Packet>()
            while (end - start >= HEADER_SIZE + CRC_SIZE) {
                if (buffer[start] != MAGIC_0 || buffer[start + 1] != MAGIC_1 || buffer[start + 2] != VERSION) {
                    start++
                    continue
                }
                val length = (buffer[start + 7].toInt() and 0xFF) or ((buffer[start + 8].toInt() and 0xFF) shl 8)
                if (length > MAX_PAYLOAD) { start++; continue }
                val frameSize = HEADER_SIZE + length + CRC_SIZE
                if (end - start < frameSize) break
                val actualCrc = (buffer[start + frameSize - 2].toInt() and 0xFF) or
                    ((buffer[start + frameSize - 1].toInt() and 0xFF) shl 8)
                if (crc16(buffer, start, frameSize - CRC_SIZE) == actualCrc) {
                    val sequence = (buffer[start + 5].toInt() and 0xFF) or ((buffer[start + 6].toInt() and 0xFF) shl 8)
                    packets += Packet(buffer[start + 3], buffer[start + 4], sequence,
                        buffer.copyOfRange(start + HEADER_SIZE, start + HEADER_SIZE + length))
                }
                start += frameSize
            }
            compact()
            return packets
        }

        private fun ensureCapacity(incoming: Int) {
            if (buffer.size - end >= incoming) return
            compact()
            if (buffer.size - end >= incoming) return
            buffer = buffer.copyOf((end + incoming).coerceAtMost(HEADER_SIZE + MAX_PAYLOAD + CRC_SIZE).coerceAtLeast(buffer.size * 2))
        }

        private fun compact() {
            if (start == 0) return
            if (start < end) buffer.copyInto(buffer, 0, start, end)
            end -= start
            start = 0
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

