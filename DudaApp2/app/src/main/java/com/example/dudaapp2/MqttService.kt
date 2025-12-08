package com.example.dudaapp2

import android.util.Log
import org.eclipse.paho.client.mqttv3.*

object MqttService {

    private var client: MqttClient? = null

    fun setupMqtt(serverUri: String, token: String) {

        try {
            client = MqttClient(serverUri, MqttClient.generateClientId(), null)

            val options = MqttConnectOptions().apply {
                userName = token
                isCleanSession = true
            }

            client!!.connect(options)

            Log.d("MQTT", "Connected successfully!")

        } catch (e: Exception) {
            Log.e("MQTT", "Connection error: ${e.message}")
        }
    }

    fun sendNote(note: String) {
        try {
            val topic = "v1/devices/me/telemetry"
            val message = MqttMessage("""{"note":"$note"}""".toByteArray())
            message.qos = 1

            client?.publish(topic, message)

            Log.d("MQTT", "Sent note: $note")
        } catch (e: Exception) {
            Log.e("MQTT", "Sending error: ${e.message}")
        }
    }
}