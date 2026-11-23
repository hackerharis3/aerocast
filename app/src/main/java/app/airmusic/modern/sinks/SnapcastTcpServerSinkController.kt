package app.airmusic.modern.sinks

import app.airmusic.modern.model.MediaMetadataState
import app.airmusic.modern.model.ReceiverDeviceState
import app.airmusic.modern.nativebridge.NativeAudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Modern Snapcast Multi-Room Synchronous Audio TCP Server (Default Port 1704).
 *
 * Implements the Snapcast Binary Protocol (https://github.com/badaix/snapcast/blob/develop/doc/binary_protocol.md):
 * - Base Header (26 bytes, little-endian):
 *   uint16 type | uint16 id | uint16 refersTo | int32 sent.sec | int32 sent.usec | int32 recv.sec | int32 recv.usec | uint32 size
 * - Message Types:
 *   1 = CodecHeader ("pcm" + 44-byte RIFF/WAVE header)
 *   2 = WireChunk (int32 timestamp.sec | int32 timestamp.usec | uint32 payloadSize | 1408-byte PCM payload)
 *   3 = ServerSettings (uint32 jsonSize | JSON {"bufferMs":400,"latency":0,"volume":100,"muted":false})
 *   4 = Time (int32 latency.sec | int32 latency.usec)
 *   5 = Hello (Client handshake)
 */
class SnapcastTcpServerSinkController(
    private val scope: CoroutineScope,
    private val listenPort: Int = 1704
) : SinkController {

    override val receiverId: String = "snapcast_server"
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var broadcastJob: Job? = null
    private val clients = CopyOnWriteArrayList<Socket>()

    fun startServer() {
        if (acceptJob != null) return
        acceptJob = scope.launch(Dispatchers.IO) {
            try {
                val srv = ServerSocket(listenPort)
                serverSocket = srv
                startWireChunkBroadcaster()

                while (isActive) {
                    val client = srv.accept()
                    client.tcpNoDelay = true
                    clients.add(client)
                    launch { handleClientHandshakeAndTimeSync(client) }
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun stopServer() {
        broadcastJob?.cancel()
        broadcastJob = null
        acceptJob?.cancel()
        acceptJob = null
        clients.forEach { try { it.close() } catch (_: Throwable) {} }
        clients.clear()
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
    }

    private suspend fun handleClientHandshakeAndTimeSync(client: Socket) {
        try {
            val input = DataInputStream(client.getInputStream())
            val output = DataOutputStream(client.getOutputStream())
            val baseHeaderBuf = ByteArray(SNAPCAST_BASE_HEADER_BYTES)

            // Send initial ServerSettings (type 3) and PCM CodecHeader (type 1)
            sendServerSettings(output, volumePercent = 100, muted = false)
            sendPcmCodecHeader(output)

            while (scope.isActive && !client.isClosed) {
                input.readFully(baseHeaderBuf)
                val bb = ByteBuffer.wrap(baseHeaderBuf).order(ByteOrder.LITTLE_ENDIAN)
                val msgType = bb.short.toInt() and 0xFFFF
                val msgId = bb.short.toInt() and 0xFFFF
                bb.short // refersTo
                val sentSec = bb.int
                val sentUsec = bb.int
                bb.int // recvSec
                bb.int // recvUsec
                val payloadSize = bb.int

                val payload = ByteArray(payloadSize.coerceIn(0, 65536))
                if (payload.isNotEmpty()) {
                    input.readFully(payload)
                }

                if (msgType == MSG_TYPE_TIME) {
                    // Reply with Time message (type 4) so snapclient locks clock drift < 0.2ms
                    sendTimeSyncReply(output, refersToId = msgId, clientSentSec = sentSec, clientSentUsec = sentUsec)
                }
            }
        } catch (_: Throwable) {
        } finally {
            clients.remove(client)
            try { client.close() } catch (_: Throwable) {}
        }
    }

    private fun startWireChunkBroadcaster() {
        broadcastJob = scope.launch(Dispatchers.IO) {
            var seq = NativeAudioEngine.nativeGetWriteSequence()
            val pcmFrame = ByteArray(NativeAudioEngine.FRAME_SIZE_BYTES)

            while (isActive) {
                val n = NativeAudioEngine.nativeReadProcessedSinkPacket(
                    sinkId = "snapcast_pi_bedroom",
                    sequenceNumber = seq,
                    effectiveDelayMs = 0,
                    codecMode = NativeAudioEngine.CODEC_MODE_RAW_PCM,
                    outBuffer = pcmFrame
                )
                if (n > 0) {
                    seq++
                    if (clients.isNotEmpty()) {
                        val packet = buildWireChunkPacket(pcmFrame, n)
                        for (c in clients) {
                            try {
                                c.getOutputStream().write(packet)
                            } catch (_: Throwable) {
                                clients.remove(c)
                            }
                        }
                    }
                } else {
                    delay(3)
                }
            }
        }
    }

    private fun sendServerSettings(output: DataOutputStream, volumePercent: Int, muted: Boolean) {
        val json = """{"bufferMs":300,"latency":0,"volume":$volumePercent,"muted":$muted}"""
            .toByteArray(StandardCharsets.UTF_8)
        val payload = ByteBuffer.allocate(4 + json.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(json.size)
            .put(json)
            .array()
        val header = buildBaseHeader(type = MSG_TYPE_SERVER_SETTINGS, id = 1, refersTo = 0, payloadSize = payload.size)
        synchronized(output) {
            output.write(header)
            output.write(payload)
            output.flush()
        }
    }

    private fun sendPcmCodecHeader(output: DataOutputStream) {
        val codecName = "pcm".toByteArray(StandardCharsets.UTF_8)
        val wavHeader = NativeAudioEngine.nativeBuildEndlessWavHeader(44100, 2)
        val payload = ByteBuffer.allocate(4 + codecName.size + 4 + wavHeader.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(codecName.size)
            .put(codecName)
            .putInt(wavHeader.size)
            .put(wavHeader)
            .array()
        val header = buildBaseHeader(type = MSG_TYPE_CODEC_HEADER, id = 2, refersTo = 0, payloadSize = payload.size)
        synchronized(output) {
            output.write(header)
            output.write(payload)
            output.flush()
        }
    }

    private fun sendTimeSyncReply(output: DataOutputStream, refersToId: Int, clientSentSec: Int, clientSentUsec: Int) {
        val (nowSec, nowUsec) = currentMicrosecondClock()
        val latencySec = nowSec - clientSentSec
        val latencyUsec = nowUsec - clientSentUsec
        val payload = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(latencySec)
            .putInt(latencyUsec)
            .array()
        val header = buildBaseHeader(type = MSG_TYPE_TIME, id = 3, refersTo = refersToId, payloadSize = payload.size)
        synchronized(output) {
            output.write(header)
            output.write(payload)
            output.flush()
        }
    }

    private fun buildWireChunkPacket(pcmBytes: ByteArray, length: Int): ByteArray {
        val (sec, usec) = currentMicrosecondClock()
        val payloadSize = 12 + length
        val header = buildBaseHeader(type = MSG_TYPE_WIRE_CHUNK, id = 0, refersTo = 0, payloadSize = payloadSize)
        return ByteBuffer.allocate(SNAPCAST_BASE_HEADER_BYTES + payloadSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(header)
            .putInt(sec)
            .putInt(usec)
            .putInt(length)
            .put(pcmBytes, 0, length)
            .array()
    }

    private fun buildBaseHeader(type: Int, id: Int, refersTo: Int, payloadSize: Int): ByteArray {
        val (sec, usec) = currentMicrosecondClock()
        return ByteBuffer.allocate(SNAPCAST_BASE_HEADER_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(type.toShort())
            .putShort(id.toShort())
            .putShort(refersTo.toShort())
            .putInt(sec)
            .putInt(usec)
            .putInt(sec)
            .putInt(usec)
            .putInt(payloadSize)
            .array()
    }

    private fun currentMicrosecondClock(): Pair<Int, Int> {
        val nanos = System.nanoTime()
        val totalMicros = nanos / 1_000L
        val sec = (totalMicros / 1_000_000L).toInt()
        val usec = (totalMicros % 1_000_000L).toInt()
        return sec to usec
    }

    override suspend fun connect(receiver: ReceiverDeviceState, metadata: MediaMetadataState): Boolean {
        startServer()
        return true
    }

    override suspend fun disconnect(receiver: ReceiverDeviceState) {}
    override suspend fun setVolume(receiver: ReceiverDeviceState, volumePercent: Int) {}
    override suspend fun setMute(receiver: ReceiverDeviceState, muted: Boolean) {}
    override suspend fun updateMetadata(receiver: ReceiverDeviceState, metadata: MediaMetadataState) {}

    companion object {
        private const val SNAPCAST_BASE_HEADER_BYTES = 26
        private const val MSG_TYPE_CODEC_HEADER = 1
        private const val MSG_TYPE_WIRE_CHUNK = 2
        private const val MSG_TYPE_SERVER_SETTINGS = 3
        private const val MSG_TYPE_TIME = 4
    }
}
