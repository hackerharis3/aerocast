package app.airmusic.modern.automation

import app.airmusic.modern.model.EqPreset
import app.airmusic.modern.service.AudioCaptureForegroundService
import app.airmusic.modern.service.MediaSessionMetadataListener
import app.airmusic.modern.state.MultiRoomStateRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Phase 7 Smart Home Automation Bridge (Home Assistant / Node-RED / MacroDroid).
 *
 * Remediates SEC-02 & SEC-09 (Unauthenticated exported broadcast receivers and Tasker plugins):
 * 1. Exposes Bearer-Token-Authenticated REST endpoints (/api/v1/status, /api/v1/group/{id}/activate,
 *    /api/v1/receiver/{id}/control) that can be mounted directly inside Phase 4's Ktor server.
 * 2. Includes a self-contained MQTT 3.1.1 TCP client that publishes Home Assistant MQTT Auto-Discovery
 *    ("homeassistant/media_player/airmusic_modern/config"), streams live telemetry to
 *    "airmusic/modern/state", and executes JSON commands from "airmusic/modern/command".
 */
class HomeAssistantRestAndMqttBridge(
    private val scope: CoroutineScope,
    private val apiBearerToken: String
) {
    private var mqttJob: Job? = null

    /**
     * Mounts authenticated REST automation routes inside the Ktor routing block.
     */
    fun Route.installAuthenticatedAutomationRoutes() {
        get("/api/v1/status") {
            if (!call.verifyBearerToken(apiBearerToken)) {
                call.respondText("""{"error":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@get
            }
            call.respondText(buildFullStatusJson(), ContentType.Application.Json)
        }

        post("/api/v1/group/{groupId}/activate") {
            if (!call.verifyBearerToken(apiBearerToken)) {
                call.respondText("""{"error":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }
            val groupId = call.parameters["groupId"].orEmpty()
            val group = MultiRoomStateRepository.groupsFlow.value.find { it.id == groupId }
            if (group == null) {
                call.respondText("""{"error":"Group not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }
            MultiRoomStateRepository.activateQuickConnectGroup(group)
            call.respondText(buildFullStatusJson(), ContentType.Application.Json)
        }

        post("/api/v1/receiver/{receiverId}/control") {
            if (!call.verifyBearerToken(apiBearerToken)) {
                call.respondText("""{"error":"Unauthorized"}""", ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }
            val receiverId = call.parameters["receiverId"].orEmpty()
            val bodyJson = runCatching { JSONObject(call.receiveText()) }.getOrNull() ?: JSONObject()
            applyReceiverControlJson(receiverId, bodyJson)
            call.respondText(buildFullStatusJson(), ContentType.Application.Json)
        }
    }

    /**
     * Connects to a local Home Assistant Mosquitto / MQTT 3.1.1 broker, publishes Auto-Discovery,
     * and processes incoming commands on "airmusic/modern/command".
     */
    fun startMqttBridge(
        brokerIp: String,
        brokerPort: Int = 1883,
        username: String? = null,
        password: String? = null
    ) {
        stopMqttBridge()
        mqttJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                var socket: Socket? = null
                try {
                    socket = Socket()
                    socket.connect(InetSocketAddress(brokerIp, brokerPort), 4000)
                    socket.soTimeout = 30_000
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())

                    // 1. Send MQTT 3.1.1 CONNECT packet
                    sendMqttConnect(output, "airmusic_modern_android", username, password)
                    val connAckType = input.readUnsignedByte()
                    val connAckLen = input.readUnsignedByte()
                    val connAckBody = ByteArray(connAckLen)
                    input.readFully(connAckBody)
                    if ((connAckType ushr 4) != 2 || connAckBody.getOrNull(1)?.toInt() != 0) {
                        delay(10_000L)
                        continue
                    }

                    // 2. Publish Home Assistant MQTT Auto-Discovery Config
                    publishMqttMessage(
                        output,
                        topic = "homeassistant/media_player/airmusic_modern/config",
                        payload = buildHomeAssistantDiscoveryJson(),
                        retain = true
                    )

                    // 3. Subscribe to command topic "airmusic/modern/command"
                    sendMqttSubscribe(output, packetId = 1, topic = TOPIC_COMMAND)

                    // 4. Periodic state publisher + incomingPUBLISH reader loop
                    val statePublisher = launch {
                        while (isActive) {
                            publishMqttMessage(output, TOPIC_STATE, buildFullStatusJson(), retain = false)
                            sendMqttPingReq(output)
                            delay(10_000L)
                        }
                    }

                    while (isActive && !socket.isClosed) {
                        val headerByte = input.readUnsignedByte()
                        val packetType = headerByte ushr 4
                        val remainingLen = readMqttRemainingLength(input)
                        val packetBody = ByteArray(remainingLen)
                        if (remainingLen > 0) input.readFully(packetBody)

                        if (packetType == 3 && remainingLen >= 2) { // PUBLISH
                            val topicLen = ((packetBody[0].toInt() and 0xFF) shl 8) or (packetBody[1].toInt() and 0xFF)
                            if (2 + topicLen <= packetBody.size) {
                                val messageText = String(
                                    packetBody,
                                    2 + topicLen,
                                    packetBody.size - (2 + topicLen),
                                    StandardCharsets.UTF_8
                                )
                                handleMqttCommandJson(messageText)
                                publishMqttMessage(output, TOPIC_STATE, buildFullStatusJson(), retain = false)
                            }
                        }
                    }
                    statePublisher.cancel()
                } catch (_: Throwable) {
                    delay(8000L)
                } finally {
                    try { socket?.close() } catch (_: Throwable) {}
                }
            }
        }
    }

    fun stopMqttBridge() {
        mqttJob?.cancel()
        mqttJob = null
    }

    private fun handleMqttCommandJson(rawJson: String) {
        val json = runCatching { JSONObject(rawJson) }.getOrNull() ?: return
        when (json.optString("action").uppercase()) {
            "DISCONNECT_ALL" -> MultiRoomStateRepository.disconnectAllReceivers()
            "GROUP_CONNECT" -> {
                val groupId = json.optString("groupId")
                MultiRoomStateRepository.groupsFlow.value.find { it.id == groupId }?.let {
                    MultiRoomStateRepository.activateQuickConnectGroup(it)
                }
            }
            "RECEIVER_CONTROL" -> {
                val receiverId = json.optString("receiverId")
                applyReceiverControlJson(receiverId, json)
            }
        }
    }

    private fun applyReceiverControlJson(receiverId: String, json: JSONObject) {
        if (json.has("connect")) {
            val wantConnect = json.optBoolean("connect")
            val current = MultiRoomStateRepository.receiversFlow.value.find { it.id == receiverId }
            if (current != null && current.isConnected != wantConnect) {
                MultiRoomStateRepository.toggleReceiverConnection(receiverId)
            }
        }
        if (json.has("volumePercent")) {
            MultiRoomStateRepository.setReceiverVolume(receiverId, json.optInt("volumePercent", 65))
        }
        if (json.has("delayOffsetMs")) {
            MultiRoomStateRepository.setReceiverDelayOffset(receiverId, json.optInt("delayOffsetMs", 0))
        }
        if (json.has("eqPreset")) {
            val presetName = json.optString("eqPreset")
            runCatching { EqPreset.valueOf(presetName.uppercase()) }.getOrNull()?.let { preset ->
                MultiRoomStateRepository.applyEqPreset(receiverId, preset)
            }
        }
    }

    private fun buildFullStatusJson(): String {
        val telemetry = AudioCaptureForegroundService.telemetryFlow.value
        val metadata = MediaSessionMetadataListener.metadataFlow.value
        val receivers = MultiRoomStateRepository.receiversFlow.value

        val receiversArr = JSONArray()
        receivers.forEach { r ->
            receiversArr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("name", r.displayTitle)
                    put("protocol", r.protocol.name)
                    put("ipAddress", r.ipAddress)
                    put("isConnected", r.isConnected)
                    put("volumePercent", r.volumePercent)
                    put("isMuted", r.isMuted)
                    put("delayOffsetMs", r.delayOffsetMs)
                    put("activeCodec", r.activeCodec.name)
                    put("eqPreset", r.eqPreset.name)
                }
            )
        }

        return JSONObject().apply {
            put("isCapturing", telemetry.isCapturing)
            put("latestRmsDb", telemetry.latestRmsDb.toDouble())
            put("trackTitle", metadata.title)
            put("trackArtist", metadata.artist)
            put("trackAlbum", metadata.album)
            put("connectedCount", receivers.count { it.isConnected })
            put("receivers", receiversArr)
        }.toString()
    }

    private fun buildHomeAssistantDiscoveryJson(): String {
        return JSONObject().apply {
            put("name", "AirMusic Modern Multi-Room Caster")
            put("unique_id", "airmusic_modern_caster_01")
            put("state_topic", TOPIC_STATE)
            put("command_topic", TOPIC_COMMAND)
            put("value_template", "{{ 'playing' if value_json.isCapturing else 'idle' }}")
        }.toString()
    }

    private fun ApplicationCall.verifyBearerToken(expectedToken: String): Boolean {
        val authHeader = request.headers["Authorization"].orEmpty()
        return authHeader == "Bearer $expectedToken"
    }

    // Minimal MQTT 3.1.1 binary packet helpers
    private fun sendMqttConnect(
        output: DataOutputStream,
        clientId: String,
        username: String?,
        password: String?
    ) {
        val body = ByteArrayOutputStream()
        writeMqttString(body, "MQTT")
        body.write(4) // Protocol Level 4 (MQTT 3.1.1)
        var connectFlags = 0x02 // Clean Session
        if (!username.isNullOrEmpty()) connectFlags = connectFlags or 0x80
        if (!password.isNullOrEmpty()) connectFlags = connectFlags or 0x40
        body.write(connectFlags)
        body.write(0)
        body.write(60) // KeepAlive = 60s
        writeMqttString(body, clientId)
        if (!username.isNullOrEmpty()) writeMqttString(body, username)
        if (!password.isNullOrEmpty()) writeMqttString(body, password)

        writeMqttPacket(output, headerByte = 0x10, body.toByteArray())
    }

    private fun publishMqttMessage(output: DataOutputStream, topic: String, payload: String, retain: Boolean) {
        val body = ByteArrayOutputStream()
        writeMqttString(body, topic)
        body.write(payload.toByteArray(StandardCharsets.UTF_8))
        val header = if (retain) 0x31 else 0x30
        writeMqttPacket(output, headerByte = header, body.toByteArray())
    }

    private fun sendMqttSubscribe(output: DataOutputStream, packetId: Int, topic: String) {
        val body = ByteArrayOutputStream()
        body.write((packetId ushr 8) and 0xFF)
        body.write(packetId and 0xFF)
        writeMqttString(body, topic)
        body.write(0) // QoS 0
        writeMqttPacket(output, headerByte = 0x82, body.toByteArray())
    }

    private fun sendMqttPingReq(output: DataOutputStream) {
        synchronized(output) {
            output.writeByte(0xC0)
            output.writeByte(0x00)
            output.flush()
        }
    }

    private fun writeMqttString(out: ByteArrayOutputStream, str: String) {
        val bytes = str.toByteArray(StandardCharsets.UTF_8)
        out.write((bytes.size ushr 8) and 0xFF)
        out.write(bytes.size and 0xFF)
        out.write(bytes)
    }

    private fun writeMqttPacket(output: DataOutputStream, headerByte: Int, bodyBytes: ByteArray) {
        synchronized(output) {
            output.writeByte(headerByte)
            var len = bodyBytes.size
            do {
                var encoded = len % 128
                len /= 128
                if (len > 0) encoded = encoded or 128
                output.writeByte(encoded)
            } while (len > 0)
            output.write(bodyBytes)
            output.flush()
        }
    }

    private fun readMqttRemainingLength(input: DataInputStream): Int {
        var multiplier = 1
        var value = 0
        do {
            val encodedByte = input.readUnsignedByte()
            value += (encodedByte and 127) * multiplier
            multiplier *= 128
        } while ((encodedByte and 128) != 0)
        return value
    }

    companion object {
        private const val TOPIC_STATE = "airmusic/modern/state"
        private const val TOPIC_COMMAND = "airmusic/modern/command"
    }
}
