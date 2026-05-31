package com.doltu.biofizic.presentation

import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Encapsulates the Paho MQTT client used to talk to the biofizic broker.
 *
 * The session owns the client instance, its connect / disconnect lifecycle
 * and the inbound message router. The Service supplies parse callbacks for
 * the three subscribed topics; the session knows nothing about HRV semantics.
 *
 * Reconnect logic lives in the Service watchdog, not here, because the
 * watchdog also revives the Samsung Health SDK connection on the same
 * thread. The session simply exposes [isAlive] and a fresh [connect] call.
 *
 * Callbacks receive the raw JSON string; the Service parses it. This keeps
 * the session free of org.json imports and free of any references to
 * WatchStateRepository.
 */
class MqttSession(
    private val brokerUrl: String,
    private val clientId: String,
    private val tag: String = "MqttSession",
    private val onEpochState: (String) -> Unit,
    private val onStateLive: (String) -> Unit,
    private val onCalibrationStatus: (String) -> Unit,
    /** Handshake reply: the server's ok/error verdict on our announced sensors. */
    private val onHelloAck: (String) -> Unit = {},
    /** Invoked after [publish] succeeds so the Service can bump a counter. */
    private val onMessagePublished: () -> Unit = {},
) {
    private lateinit var client: MqttClient
    @Volatile private var connecting: Boolean = false

    /**
     * Whether the most recent connect succeeded and the client is still
     * reporting a live connection. Used by the Service watchdog to decide
     * if a reconnect is needed.
     */
    val isAlive: Boolean
        get() = ::client.isInitialized && client.isConnected

    /**
     * Tear down any previous client and bring up a fresh one. Returns true
     * on success. Safe to call repeatedly; concurrent callers are coalesced.
     */
    fun connect(): Boolean {
        if (connecting) return false
        connecting = true
        try {
            if (::client.isInitialized) {
                try { client.disconnect(0) } catch (_: Exception) {}
                try { client.close() } catch (_: Exception) {}
            }
            client = MqttClient(brokerUrl, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isCleanSession = true
                connectionTimeout = 8
                keepAliveInterval = 45
                // The Service watchdog drives reconnects on its own cadence so
                // we get coordinated SDK + MQTT recovery, not racing libraries.
                isAutomaticReconnect = false
            }
            client.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(tag, "MQTT lost: ${cause?.message}")
                }
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (message == null || topic == null) return
                    try {
                        val json = message.payload.decodeToString()
                        when (topic) {
                            // Retained bootstrap on reconnect plus fresh 30 s epochs.
                            "biofizic/state" -> onEpochState(json)
                            "biofizic/state/live" -> onStateLive(json)
                            "biofizic/calibration/status" -> onCalibrationStatus(json)
                            "biofizic/hello/ack" -> onHelloAck(json)
                        }
                    } catch (e: Exception) {
                        Log.w(tag, "MQTT parse $topic: ${e.message}")
                    }
                }
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            client.connect(options)
            // QoS 1 on biofizic/state so the retained bootstrap survives a
            // reconnect; live updates and calibration status can drop one.
            client.subscribe("biofizic/state", 1)
            client.subscribe("biofizic/state/live", 0)
            client.subscribe("biofizic/calibration/status", 1)
            // Handshake reply: QoS 1 so the ack survives a reconnect.
            client.subscribe("biofizic/hello/ack", 1)
            Log.i(tag, "MQTT connected to $brokerUrl (+ state, state/live, calibration/status, hello/ack)")
            return true
        } catch (e: Exception) {
            Log.e(tag, "MQTT connect error on $brokerUrl: ${e.message}")
            return false
        } finally {
            connecting = false
        }
    }

    fun disconnect() {
        if (!::client.isInitialized) return
        try {
            if (client.isConnected) client.disconnect()
        } catch (_: Exception) {
        }
    }

    /** Publish a message at QoS 0. Returns true if the call did not throw. */
    fun publish(topic: String, payload: String, retain: Boolean = false): Boolean {
        return try {
            if (!isAlive) return false
            client.publish(
                topic,
                MqttMessage(payload.toByteArray()).apply {
                    qos = 0
                    isRetained = retain
                },
            )
            onMessagePublished()
            true
        } catch (e: Exception) {
            Log.w(tag, "MQTT publish error on $topic: ${e.message}")
            false
        }
    }
}
