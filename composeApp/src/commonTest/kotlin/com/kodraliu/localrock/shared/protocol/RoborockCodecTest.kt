package com.kodraliu.localrock.shared.protocol

import com.kodraliu.localrock.shared.testing.fromHex
import com.kodraliu.localrock.shared.testing.toHex
import kotlin.test.Test
import kotlin.test.assertEquals as assertEq
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoborockCodecTest {

    // Golden vector #1 (MQTT, no length prefix) — produced by python-roborock.
    @Test
    fun mqtt_goldenEncodeAndDecode() {
        val localKey = "0123456789abcdef"
        val msg = RoborockMessage(
            protocol = RoborockProtocol.RPC_REQUEST,
            payload = "7b226964223a312c226d6574686f64223a226765745f737461747573222c22706172616d73223a5b5d7d".fromHex(),
            seq = 42,
            random = 12345,
            timestamp = 0x65f3a200,
        )
        val expected = "312e300000002a0000303965f3a200006500303e1446a698dd03df8245c4c0a4bb077624f06b4838d63be7fddb946e68ec87bfb8f641897d2bf479c7df520ca1ffb25872b4663e"
        val codec = RoborockCodec(localKey)
        assertEq(expected, codec.encode(msg).toHex())
        val decoded = RoborockCodec(localKey).decode(expected.fromHex())
        assertEq(1, decoded.size)
        assertEq(msg, decoded[0])
    }

    @Test
    fun mqtt_skipsLeadingGarbage() {
        // python-roborock's PrefixedStruct scans forward to a valid version header.
        val codec = RoborockCodec("0123456789abcdef")
        val msg = RoborockMessage(
            protocol = RoborockProtocol.RPC_REQUEST,
            payload = "test".encodeToByteArray(),
            seq = 9,
            random = 200,
            timestamp = 0x65f3a500,
        )
        val encoded = codec.encode(msg)
        val garbage = "deadbeefcafe".fromHex()
        val decoded = RoborockCodec("0123456789abcdef").decode(garbage + encoded)
        assertEq(1, decoded.size)
        assertEq(msg, decoded[0])
    }

    @Test
    fun mqtt_decodesTwoFramesInOnePayload() {
        val codec = RoborockCodec("local_key")
        val msg1 = RoborockMessage(RoborockProtocol.PING_REQUEST, "a".encodeToByteArray(), 1, 1, 0x65f3b100)
        val msg2 = RoborockMessage(RoborockProtocol.PING_RESPONSE, "bb".encodeToByteArray(), 2, 2, 0x65f3b200)
        val decoded = RoborockCodec("local_key").decode(codec.encode(msg1) + codec.encode(msg2))
        assertEq(2, decoded.size)
        assertEq(msg1, decoded[0])
        assertEq(msg2, decoded[1])
    }

    @Test
    fun crcMismatch_throws() {
        val codec = RoborockCodec("0123456789abcdef")
        val msg = RoborockMessage(RoborockProtocol.RPC_REQUEST, "x".encodeToByteArray(), 1, 1, 0x65f3a200)
        val bytes = codec.encode(msg).copyOf()
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 0xff).toByte()
        assertFailsWith<RoborockProtocolException> {
            RoborockCodec("0123456789abcdef").decode(bytes)
        }
    }

    @Test
    fun roundTrip_variousProtocolsAndKeys() {
        val keys = listOf("0123456789abcdef", "local_key", "zzzzzzzzzzzzzzzz")
        for (key in keys) {
            for (proto in listOf(RoborockProtocol.RPC_REQUEST, RoborockProtocol.RPC_RESPONSE, RoborockProtocol.MAP_RESPONSE)) {
                val pt = ("hello-$key-$proto").encodeToByteArray()
                val msg = RoborockMessage(proto, pt, seq = 1, random = 2, timestamp = 0x60000000)
                val mqttCodec = RoborockCodec(key)
                val backMqtt = RoborockCodec(key).decode(mqttCodec.encode(msg))
                assertTrue(backMqtt.size == 1 && backMqtt[0] == msg, "mqtt round-trip $key/$proto")
            }
        }
    }
}
