package app.airmusic.modern.sinks.airplay

import android.util.Log
import android.graphics.Bitmap
import android.util.LruCache
import app.airmusic.modern.model.MediaMetadataState
import app.airmusic.modern.model.ReceiverDeviceState
import app.airmusic.modern.nativebridge.NativeAudioEngine
import app.airmusic.modern.server.KtorLowLatencyAudioServer
import app.airmusic.modern.sinks.SinkController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.random.Random

/**
 * Clean-room Apple AirPlay (RAOP) RTSP + AES-128-CBC + UDP RTP/NTP Transmitter replacing
 * AirMusic's app.airmusic.sinks.airplay.d and app.airmusic.sinks.airplay.g.
 *
 * Implements the full RAOP session lifecycle verified in AirMusic v2.0.3:
 * 1. RTSP/1.0 TCP Handshake: OPTIONS -> ANNOUNCE (SDP with 352-frame ALAC & RSA-OAEP AES key)
 *    -> SETUP (negotiates UDP server_port, control_port, timing_port) -> RECORD.
 * 2. Real-time UDP RTP ALAC packet stream (352 stereo frames = 7.98 ms per packet) with a
 *    1,000-packet LruCache retransmit buffer responding to 0x80 0xD5 retransmit requests.
 * 3. Periodic 1,000 ms RTP-to-NTP synchronization packets (0x80 0xD4) on control_port and
 *    NTP timing responder (0x80 0xD3) on timing_port.
 * 4. Logarithmic SET_PARAMETER volume (-30.0 dB .. 0.0 dB, or -144.0 dB mute), DMAP track
 *    metadata (mlit/minm/asar/asal), and JPEG cover art transfer.
 */
class AirPlayRaopSinkController(
    override val receiverId: String,
    private val scope: CoroutineScope
) : SinkController {

    private val crypto = AirPlayRaopCrypto()

    @Volatile private var useEncryption = false
    private val cseqCounter = AtomicInteger(1)
    private val rtspLock = Any()
    private val sessionId = Random.nextInt(100_000, 999_999).toString()
    private val activeRemoteToken = Random.nextInt(100_000_000, 999_999_999).toString()
    private val dacpIdHex = Random.nextLong().toULong().toString(16).uppercase()

    private var rtspSocket: Socket? = null
    private var rtspOutput: OutputStream? = null
    private var rtspReader: BufferedReader? = null
    private var rtspSessionHeader: String? = null

    private var rtpAudioSocket: DatagramSocket? = null
    private var rtpControlSocket: DatagramSocket? = null
    private var rtpTimingSocket: DatagramSocket? = null

    private var remoteServerPort: Int = 6000
    private var remoteControlPort: Int = 6001
    private var remoteTimingPort: Int = 6002

    private var audioStreamJob: Job? = null
    private var controlListenerJob: Job? = null
    private var timingListenerJob: Job? = null

    // 1,000-packet RTP retransmission ring cache matching AirMusic's f1035o LruCache(1000)
    private val rtpPacketCache = LruCache<Int, ByteArray>(1000)

    override suspend fun connect(
        receiver: ReceiverDeviceState,
        metadata: MediaMetadataState
    ): Boolean = withContext(Dispatchers.IO) {
        disconnect(receiver)
        // Send unencrypted audio when the speaker accepts it (et includes 0) or doesn't say.
        // The bundled RSA key is not a valid Apple AirPort key, so RSA-only speakers can't work yet.
        useEncryption = receiver.airplayEncryptionTypes.isNotEmpty() && 0 !in receiver.airplayEncryptionTypes
        Log.i(TAG, "Connecting to ${receiver.name} at ${receiver.ipAddress}:${receiver.port} " +
            "(et=${receiver.airplayEncryptionTypes}, encrypted=$useEncryption)")
        try {
            val localIp = KtorLowLatencyAudioServer.resolveLocalLanIpv4()
            val targetAddr = InetAddress.getByName(receiver.ipAddress)

            val socket = Socket()
            socket.tcpNoDelay = true
            socket.soTimeout = 6000
            socket.connect(InetSocketAddress(targetAddr, receiver.port), 4000)

            val out = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            rtspSocket = socket
            rtspOutput = out
            rtspReader = reader

            val rtspUrl = "rtsp://$localIp/$sessionId"

            // Step 1: OPTIONS with Apple-Challenge
            val challengeHeaders = mapOf("Apple-Challenge" to crypto.generateAppleChallengeBase64())
            val optResp = sendRtspRequest("OPTIONS", "*", challengeHeaders, null)
            Log.i(TAG, "OPT -> ${optResp.statusCode} ${optResp.headers}")
            if (optResp.statusCode !in 200..299) return@withContext false

            // Step 2: ANNOUNCE SDP (352-frame ALAC @ 44,100 Hz + RSA-OAEP AES-128 key + AES IV)
            val sdpBody = ("""
                v=0
                o=iTunes $sessionId 0 IN IP4 $localIp
                s=iTunes
                c=IN IP4 ${receiver.ipAddress}
                t=0 0
                m=audio 0 RTP/AVP 96
                a=rtpmap:96 AppleLossless
                a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100
            """.trimIndent() + (if (useEncryption) {
                "\na=rsaaeskey:${crypto.encryptedRsaAesKeyBase64}\na=aesiv:${crypto.aesIvBase64}"
            } else "")).replace("\n", "\r\n") + "\r\n"

            val announceResp = sendRtspRequest(
                method = "ANNOUNCE",
                uri = rtspUrl,
                extraHeaders = mapOf("Content-Type" to "application/sdp"),
                body = sdpBody.toByteArray(StandardCharsets.UTF_8)
            )
            Log.i(TAG, "ANNOUNCE -> ${announceResp.statusCode} ${announceResp.headers}")
            if (announceResp.statusCode !in 200..299) return@withContext false

            // Bind local UDP sockets for RTP Audio, Control (Retransmit/Sync), and NTP Timing
            val audioSock = DatagramSocket(0)
            val controlSock = DatagramSocket(0)
            val timingSock = DatagramSocket(0)
            rtpAudioSocket = audioSock
            rtpControlSocket = controlSock
            rtpTimingSocket = timingSock

            // Step 3: SETUP negotiating UDP ports
            val transportReq = "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=${controlSock.localPort};timing_port=${timingSock.localPort}"
            val setupResp = sendRtspRequest(
                method = "SETUP",
                uri = rtspUrl,
                extraHeaders = mapOf("Transport" to transportReq),
                body = null
            )
            Log.i(TAG, "SETUP -> ${setupResp.statusCode} ${setupResp.headers}")
            if (setupResp.statusCode !in 200..299) return@withContext false

            rtspSessionHeader = setupResp.headers["SESSION"]?.substringBefore(';')?.trim() ?: "1"
            parseTransportPorts(setupResp.headers["TRANSPORT"].orEmpty())

            // The speaker sends NTP timing requests after SETUP and withholds the RECORD reply
            // until they are answered, so the timing/control responders must run before RECORD.
            startTimingResponderLoop(timingSock)
            startControlRetransmitLoop(controlSock, targetAddr)

            // Step 4: RECORD starting RTP stream
            val recordResp = sendRtspRequest(
                method = "RECORD",
                uri = rtspUrl,
                extraHeaders = mapOf(
                    "Range" to "npt=0-",
                    "RTP-Info" to "seq=1;rtptime=0"
                ),
                body = null
            )
            Log.i(TAG, "RECORD -> ${recordResp.statusCode} ${recordResp.headers}")
            if (recordResp.statusCode !in 200..299) return@withContext false

            // Step 5: Launch the ALAC RTP sender (timing/control responders already running)
            startRtpAlacAudioLoop(audioSock, controlSock, targetAddr, receiver)

            // Step 6: Set initial volume and push track metadata + artwork
            setVolume(receiver, receiver.volumePercent)
            updateMetadata(receiver, metadata)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "AirPlay connect to ${receiver.ipAddress}:${receiver.port} failed", t)
            disconnect(receiver)
            false
        }
    }

    private fun startRtpAlacAudioLoop(
        audioSock: DatagramSocket,
        controlSock: DatagramSocket,
        targetAddr: InetAddress,
        receiver: ReceiverDeviceState
    ) {
        audioStreamJob = scope.launch(Dispatchers.IO) {
            var readSeq = NativeAudioEngine.nativeGetWriteSequence()
            var rtpSeq = 1
            var rtpTimestamp = 0L
            var packetsSinceLastSync = 0
            val alacBuf = ByteArray(NativeAudioEngine.ALAC_PACKET_SIZE_BYTES)
            val ssrc = Random.nextInt()

            while (isActive) {
                val alacLen = NativeAudioEngine.nativeReadProcessedSinkPacket(
                    sinkId = receiver.id,
                    sequenceNumber = readSeq,
                    effectiveDelayMs = 0,
                    codecMode = NativeAudioEngine.CODEC_MODE_ALAC,
                    outBuffer = alacBuf
                )

                if (alacLen > 0) {
                    readSeq++
                    val encryptedPayload = if (useEncryption) crypto.encryptAlacPacket(alacBuf, alacLen) else alacBuf.copyOf(alacLen)
                    val rtpPacket = ByteBuffer.allocate(12 + encryptedPayload.size)
                        .order(ByteOrder.BIG_ENDIAN)
                        .put(0x80.toByte())
                        .put(if (rtpSeq == 1) 0xE0.toByte() else 0x60.toByte())
                        .putShort((rtpSeq and 0xFFFF).toShort())
                        .putInt((rtpTimestamp and 0xFFFFFFFFL).toInt())
                        .putInt(ssrc)
                        .put(encryptedPayload)
                        .array()

                    rtpPacketCache.put(rtpSeq and 0xFFFF, rtpPacket)
                    audioSock.send(DatagramPacket(rtpPacket, rtpPacket.size, targetAddr, remoteServerPort))

                    // Every 125 packets (~1.0 second), send an RTP-to-NTP Sync packet (0x80 0xD4)
                    if (packetsSinceLastSync >= 125) {
                        sendRtpSyncBeacon(controlSock, targetAddr, rtpTimestamp)
                        packetsSinceLastSync = 0
                    }

                    if (rtpTimestamp % (352L * 500L) == 0L) {
                        Log.i(TAG, "RTP audio: sent ${rtpTimestamp / 352L + 1} packets (${alacLen}B ALAC) to port $remoteServerPort")
                    }
                    rtpSeq = (rtpSeq + 1) and 0xFFFF
                    rtpTimestamp += 352L
                    packetsSinceLastSync++
                } else {
                    delay(3)
                }
            }
        }
    }

    private fun sendRtpSyncBeacon(controlSock: DatagramSocket, targetAddr: InetAddress, rtpTimestamp: Long) {
        try {
            val (ntpSec, ntpFrac) = currentNtpTimestamp()
            val syncPacket = ByteBuffer.allocate(20)
                .order(ByteOrder.BIG_ENDIAN)
                .put(0x80.toByte())
                .put(0xD4.toByte())
                .putShort(7)
                .putInt(((rtpTimestamp - 11025L) and 0xFFFFFFFFL).toInt())
                .putInt(ntpSec)
                .putInt(ntpFrac)
                .putInt((rtpTimestamp and 0xFFFFFFFFL).toInt())
                .array()
            controlSock.send(DatagramPacket(syncPacket, syncPacket.size, targetAddr, remoteControlPort))
        } catch (_: Throwable) {
        }
    }

    /**
     * Listens on [controlSock] for 0x80 0xD5 RTP retransmit requests from the AirPlay speaker
     * and immediately re-sends requested sequence numbers from [rtpPacketCache] wrapped in 0x80 0xD6.
     */
    private fun startControlRetransmitLoop(controlSock: DatagramSocket, targetAddr: InetAddress) {
        controlListenerJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(64)
            while (isActive && !controlSock.isClosed) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    controlSock.receive(pkt)
                    if (pkt.length >= 8 && (buf[1].toInt() and 0xFF) == 0xD5) {
                        val bb = ByteBuffer.wrap(buf, 0, pkt.length).order(ByteOrder.BIG_ENDIAN)
                        bb.int // header
                        val firstSeq = bb.short.toInt() and 0xFFFF
                        val count = (bb.short.toInt() and 0xFFFF).coerceIn(1, 64)

                        for (i in 0 until count) {
                            val seq = (firstSeq + i) and 0xFFFF
                            val cachedRtp = rtpPacketCache.get(seq) ?: continue
                            val retransmit = ByteArray(4 + cachedRtp.size)
                            retransmit[0] = 0x80.toByte()
                            retransmit[1] = 0xD6.toByte()
                            retransmit[2] = (seq ushr 8).toByte()
                            retransmit[3] = (seq and 0xFF).toByte()
                            System.arraycopy(cachedRtp, 0, retransmit, 4, cachedRtp.size)
                            controlSock.send(DatagramPacket(retransmit, retransmit.size, targetAddr, remoteControlPort))
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun startTimingResponderLoop(timingSock: DatagramSocket) {
        timingListenerJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(32)
            while (isActive && !timingSock.isClosed) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    timingSock.receive(pkt)
                    if (pkt.length >= 32 && (buf[1].toInt() and 0xFF) == 0xD2) {
                        val (ntpSec, ntpFrac) = currentNtpTimestamp()
                        val reply = ByteArray(32)
                        reply[0] = 0x80.toByte()
                        reply[1] = 0xD3.toByte()
                        reply[2] = 0
                        reply[3] = 7
                        // Copy reference timestamp from speaker's transmit timestamp (bytes 24..31 -> 8..15)
                        System.arraycopy(buf, 24, reply, 8, 8)
                        ByteBuffer.wrap(reply, 16, 16).order(ByteOrder.BIG_ENDIAN)
                            .putInt(ntpSec)
                            .putInt(ntpFrac)
                            .putInt(ntpSec)
                            .putInt(ntpFrac)
                        timingSock.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    override suspend fun setVolume(receiver: ReceiverDeviceState, volumePercent: Int) {
        withContext(Dispatchers.IO) {
            val attenuationDb = if (receiver.isMuted || volumePercent <= 0) {
                -144.0f
            } else {
                // Logarithmic mapping from 1..100% into -30.0 dB .. 0.0 dB
                (-30.0 + 30.0 * (log10(volumePercent.coerceIn(1, 100).toDouble()) / 2.0)).toFloat()
            }
            val body = "volume: ${String.format(java.util.Locale.US, "%.6f", attenuationDb)}\r\n".toByteArray(StandardCharsets.UTF_8)
            val localIp = KtorLowLatencyAudioServer.resolveLocalLanIpv4()
            try {
                val resp = sendRtspRequest(
                    method = "SET_PARAMETER",
                    uri = "rtsp://$localIp/$sessionId",
                    extraHeaders = mapOf("Content-Type" to "text/parameters"),
                    body = body
                )
                Log.i(TAG, "Volume ${volumePercent}% (${"%.1f".format(java.util.Locale.US, attenuationDb)} dB) -> ${resp.statusCode}")
            } catch (t: Throwable) {
                Log.w(TAG, "Volume change failed", t)
            }
        }
    }

    override suspend fun setMute(receiver: ReceiverDeviceState, muted: Boolean) {
        // AirPlay has no separate mute: mute = -144 dB. Unmuting is handled by the coordinator's
        // follow-up setVolume, so sending the volume here too would just duplicate it.
        if (muted) setVolume(receiver.copy(isMuted = true), 0)
    }

    override suspend fun updateMetadata(receiver: ReceiverDeviceState, metadata: MediaMetadataState) {
        withContext(Dispatchers.IO) {
            val localIp = KtorLowLatencyAudioServer.resolveLocalLanIpv4()
            val rtspUrl = "rtsp://$localIp/$sessionId"

            // 1. Send DMAP Track Metadata (minm, asar, asal)
            val dmapBytes = crypto.buildDmapMetadataPayload(
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.album.ifBlank { "AirMusic Modern" }
            )
            sendRtspRequest(
                method = "SET_PARAMETER",
                uri = rtspUrl,
                extraHeaders = mapOf(
                    "Content-Type" to "application/x-dmap-tagged",
                    "RTP-Info" to "rtptime=0"
                ),
                body = dmapBytes
            )

            // 2. Send Album Art JPEG if available
            metadata.artwork?.let { bmp ->
                val jpegOut = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, jpegOut)
                sendRtspRequest(
                    method = "SET_PARAMETER",
                    uri = rtspUrl,
                    extraHeaders = mapOf(
                        "Content-Type" to "image/jpeg",
                        "RTP-Info" to "rtptime=0"
                    ),
                    body = jpegOut.toByteArray()
                )
            }
        }
    }

    override suspend fun disconnect(receiver: ReceiverDeviceState) {
        audioStreamJob?.cancel()
        audioStreamJob = null
        controlListenerJob?.cancel()
        controlListenerJob = null
        timingListenerJob?.cancel()
        timingListenerJob = null

        withContext(Dispatchers.IO) {
            try {
                if (rtspSocket?.isConnected == true) {
                    val localIp = KtorLowLatencyAudioServer.resolveLocalLanIpv4()
                    sendRtspRequest("TEARDOWN", "rtsp://$localIp/$sessionId", emptyMap(), null)
                }
            } catch (_: Throwable) {
            }
            try { rtpAudioSocket?.close() } catch (_: Throwable) {}
            try { rtpControlSocket?.close() } catch (_: Throwable) {}
            try { rtpTimingSocket?.close() } catch (_: Throwable) {}
            try { rtspSocket?.close() } catch (_: Throwable) {}
            rtpAudioSocket = null
            rtpControlSocket = null
            rtpTimingSocket = null
            rtspSocket = null
            rtspOutput = null
            rtspReader = null
            rtspSessionHeader = null
        }
    }

    private fun parseTransportPorts(transportHeader: String) {
        transportHeader.split(';').forEach { token ->
            val parts = token.trim().split('=')
            if (parts.size == 2) {
                val key = parts[0].trim().lowercase()
                val portVal = parts[1].substringBefore('-').toIntOrNull() ?: return@forEach
                when (key) {
                    "server_port" -> remoteServerPort = portVal
                    "control_port" -> remoteControlPort = portVal
                    "timing_port" -> remoteTimingPort = portVal
                }
            }
        }
    }

    private data class RtspResponse(val statusCode: Int, val headers: Map<String, String>)

    @Synchronized
    private fun sendRtspRequest(
        method: String,
        uri: String,
        extraHeaders: Map<String, String>,
        body: ByteArray?
    ): RtspResponse {
      synchronized(rtspLock) {
        // One request/response at a time on the shared RTSP connection.
        val out = rtspOutput ?: return RtspResponse(500, emptyMap())
        val reader = rtspReader ?: return RtspResponse(500, emptyMap())
        val cseq = cseqCounter.getAndIncrement()

        val sb = StringBuilder()
        sb.append("$method $uri RTSP/1.0\r\n")
        sb.append("CSeq: $cseq\r\n")
        sb.append("User-Agent: iTunes/12.11 (Macintosh; OS X 10.15.7) AppleWebKit/605.1.15\r\n")
        sb.append("Client-Instance: $dacpIdHex\r\n")
        sb.append("DACP-ID: $dacpIdHex\r\n")
        sb.append("Active-Remote: $activeRemoteToken\r\n")
        rtspSessionHeader?.let { sb.append("Session: $it\r\n") }
        extraHeaders.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        if (body != null) {
            sb.append("Content-Length: ${body.size}\r\n")
        }
        sb.append("\r\n")

        out.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
        if (body != null) {
            out.write(body)
        }
        out.flush()

        val statusLine = reader.readLine() ?: return RtspResponse(500, emptyMap())
        val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 500
        val headers = mutableMapOf<String, String>()

        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().uppercase()] = line.substring(colon + 1).trim()
            }
        }
        return RtspResponse(statusCode, headers)
      }
    }

    private fun currentNtpTimestamp(): Pair<Int, Int> {
        val nowMs = System.currentTimeMillis()
        val ntpSeconds = ((nowMs / 1000L) + 2208988800L).toInt()
        val ntpFraction = (((nowMs % 1000L) * 4294967296L) / 1000L).toInt()
        return ntpSeconds to ntpFraction
    }

    private companion object { const val TAG = "AirMusicAirPlay" }
}
