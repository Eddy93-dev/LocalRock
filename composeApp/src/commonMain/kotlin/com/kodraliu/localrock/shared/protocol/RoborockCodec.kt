package com.kodraliu.localrock.shared.protocol

import com.kodraliu.localrock.shared.crypto.Aes128

class RoborockProtocolException(message: String) : RuntimeException(message)


/** MQTT framing: one or more frames back to back, no length prefix. */
class RoborockCodec(private val localKey: String) {

    fun encode(message: RoborockMessage): ByteArray {
        require(message.version == "1.0") { "Only version 1.0 is implemented (got ${message.version})" }
        val token = deriveToken(localKey, message.timestamp)
        val encryptedPayload = if (message.payload.isEmpty()) {
            ByteArray(0)
        } else {
            Aes128.encryptEcb(message.payload, token)
        }

        val headerLen = 3 + 4 + 4 + 4 + 2 + 2
        val bodyLen = headerLen + encryptedPayload.size
        val totalLen = bodyLen + 4 // body + CRC
        val frame = ByteArray(totalLen)
        var off = 0
        message.version.encodeToByteArray().copyInto(frame, off); off += 3
        putIntBe(frame, off, message.seq); off += 4
        putIntBe(frame, off, message.random); off += 4
        putIntBe(frame, off, message.timestamp); off += 4
        putShortBe(frame, off, message.protocol); off += 2
        putShortBe(frame, off, encryptedPayload.size); off += 2
        encryptedPayload.copyInto(frame, off); off += encryptedPayload.size
        val crc = Crc32.compute(frame, 0, bodyLen)
        putIntBe(frame, off, crc)
        return frame
    }


    fun decode(bytes: ByteArray): List<RoborockMessage> {
        val out = mutableListOf<RoborockMessage>()
        var pos = 0
        while (true) {
            val versionStart = findVersionHeader(bytes, pos) ?: break
            if (bytes.size - versionStart < 19) break
            val bodyLen = 19 + readShortBe(bytes, versionStart + 17)
            if (bytes.size - versionStart < bodyLen + 4) break
            out += parseMessage(bytes, versionStart, bodyLen)
            pos = versionStart + bodyLen + 4
        }
        return out
    }

    private fun parseMessage(data: ByteArray, off: Int, bodyLen: Int): RoborockMessage {
        val version = data.decodeToString(off, off + 3)
        require(version == "1.0") { "Only version 1.0 is supported (got '$version')" }
        val seq = readIntBe(data, off + 3)
        val random = readIntBe(data, off + 7)
        val timestamp = readIntBe(data, off + 11)
        val protocol = readShortBe(data, off + 15)
        val payloadLen = readShortBe(data, off + 17)
        if (payloadLen != bodyLen - 19) {
            throw RoborockProtocolException("Inconsistent payload length")
        }
        val payloadCt = data.copyOfRange(off + 19, off + 19 + payloadLen)
        val expectedCrc = readIntBe(data, off + bodyLen)
        val actualCrc = Crc32.compute(data, off, bodyLen)
        if (expectedCrc != actualCrc) {
            throw RoborockProtocolException("CRC32 mismatch")
        }
        val payload = if (payloadCt.isEmpty()) {
            ByteArray(0)
        } else {
            val token = deriveToken(localKey, timestamp)
            Aes128.decryptEcb(payloadCt, token)
        }
        return RoborockMessage(
            protocol = protocol,
            payload = payload,
            seq = seq,
            random = random,
            timestamp = timestamp,
            version = version,
        )
    }

    private fun findVersionHeader(data: ByteArray, from: Int): Int? {
        var i = from
        while (i <= data.size - 3) {
            val v0 = data[i].toInt() and 0xff
            val v1 = data[i + 1].toInt() and 0xff
            val v2 = data[i + 2].toInt() and 0xff
            // "1.0" = 0x31,0x2e,0x30 ; "A01" = 0x41,0x30,0x31 ; "B01" = 0x42,0x30,0x31 ; "L01" = 0x4c,0x30,0x31
            if (v0 == 0x31 && v1 == 0x2e && v2 == 0x30) return i
            if ((v0 == 0x41 || v0 == 0x42 || v0 == 0x4c) && v1 == 0x30 && v2 == 0x31) return i
            i++
        }
        return null
    }

    private fun putIntBe(out: ByteArray, off: Int, v: Int) {
        out[off] = (v ushr 24).toByte()
        out[off + 1] = (v ushr 16).toByte()
        out[off + 2] = (v ushr 8).toByte()
        out[off + 3] = v.toByte()
    }

    private fun putShortBe(out: ByteArray, off: Int, v: Int) {
        out[off] = (v ushr 8).toByte()
        out[off + 1] = v.toByte()
    }

    private fun readIntBe(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xff) shl 24) or
            ((data[off + 1].toInt() and 0xff) shl 16) or
            ((data[off + 2].toInt() and 0xff) shl 8) or
            (data[off + 3].toInt() and 0xff)

    private fun readShortBe(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xff) shl 8) or (data[off + 1].toInt() and 0xff)
}
