package com.kodraliu.localrock.shared.vacuum

import com.kodraliu.localrock.shared.mqtt.MqttClient
import com.kodraliu.localrock.shared.mqtt.MqttCreds
import com.kodraliu.localrock.shared.mqtt.MqttNativeConnection
import com.kodraliu.localrock.shared.mqtt.MqttNativeTransport
import com.kodraliu.localrock.shared.mqtt.MqttTopics
import com.kodraliu.localrock.shared.protocol.RoborockCodec
import com.kodraliu.localrock.shared.protocol.RoborockMessage
import com.kodraliu.localrock.shared.protocol.RoborockProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class VacuumSessionTest {

    private val key = "0123456789abcdef"
    private val topics = MqttTopics(publishTopic = "rr/m/i/u/n/robot", subscribeTopic = "rr/m/o/u/n/robot")
    private val creds = MqttCreds(host = "localhost", port = 8883, tls = true, username = "u", password = "p")

    /** A broker stand-in: hands published frames to the test and lets it push frames back. */
    private class FakeBroker : MqttNativeTransport, MqttNativeConnection {
        val published = Channel<ByteArray>(Channel.UNLIMITED)
        lateinit var deliver: (topic: String, payload: ByteArray) -> Unit

        override val isConnected: Boolean = true
        override fun subscribe(topic: String, qos: Int) {}
        override fun unsubscribe(topic: String) {}
        override fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean) {
            published.trySend(payload)
        }
        override fun close() {}

        override fun open(
            host: String,
            port: Int,
            tls: Boolean,
            username: String,
            password: String,
            clientId: String,
            keepAliveSeconds: Int,
            onConnected: () -> Unit,
            onFailed: (reason: String) -> Unit,
            onMessage: (topic: String, payload: ByteArray) -> Unit,
            onDisconnected: () -> Unit,
        ): MqttNativeConnection {
            deliver = onMessage
            onConnected()
            return this
        }
    }

    private fun requestId(frame: ByteArray): Int {
        val outer = Json.parseToJsonElement(RoborockCodec(key).decode(frame).single().payload.decodeToString())
        val inner = Json.parseToJsonElement(outer.jsonObject["dps"]!!.jsonObject["101"]!!.jsonPrimitive.content)
        return inner.jsonObject["id"]!!.jsonPrimitive.int
    }

    private fun reply(id: Int, result: JsonArray, localKey: String = key): ByteArray {
        val inner = buildJsonObject {
            put("id", id)
            put("result", result)
        }.toString()
        val payload = buildJsonObject {
            put("dps", buildJsonObject { put("102", inner) })
            put("t", 1)
        }.toString().encodeToByteArray()
        return RoborockCodec(localKey).encode(
            RoborockMessage(RoborockProtocol.RPC_RESPONSE, payload, seq = 1, random = 1, timestamp = 0x65f3a200)
        )
    }

    @Test
    fun retry_reply_waits_for_the_real_answer_and_a_bad_frame_does_not_stop_the_session() = runTest {
        // Real time: the replies come from another thread, which virtual time would not wait for.
        withContext(Dispatchers.Default) {
            val broker = FakeBroker()
            val mqtt = MqttClient(broker)
            mqtt.start(creds, clientId = "test")
            mqtt.connected.first { it }
            val session = VacuumSession(key, mqtt, { topics }, nowEpochSeconds = { 0x65f3a200 })
            session.start()
            try {
                val first = async { session.sendCommand("get_status") }
                val id = requestId(withTimeout(5_000) { broker.published.receive() })
                broker.deliver(topics.subscribeTopic, reply(id, JsonArray(listOf(JsonPrimitive("retry")))))
                // Encrypted with another key: fails AES padding, which used to kill the collector.
                broker.deliver(topics.subscribeTopic, reply(id, JsonArray(emptyList()), localKey = "fedcba9876543210"))
                broker.deliver(topics.subscribeTopic, reply(id, JsonArray(listOf(JsonPrimitive("ok")))))
                assertEquals(JsonArray(listOf(JsonPrimitive("ok"))), first.await().result)

                val second = async { session.sendCommand("get_status") }
                val id2 = requestId(withTimeout(5_000) { broker.published.receive() })
                broker.deliver(topics.subscribeTopic, reply(id2, JsonArray(listOf(JsonPrimitive(1)))))
                assertEquals(JsonArray(listOf(JsonPrimitive(1))), second.await().result)
            } finally {
                session.close()
                mqtt.disconnect()
            }
        }
    }
}
