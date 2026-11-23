package app.airmusic.modern.sinks

import app.airmusic.modern.model.MediaMetadataState
import app.airmusic.modern.model.ReceiverDeviceState
import app.airmusic.modern.server.KtorLowLatencyAudioServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Clean-room Roku External Control Protocol (ECP) controller replacing AirMusic's y1.a, y1.b, and y1.c.
 * Launches Roku Media Player (channel ID 15985) via HTTP POST on port 8060 pointing to the Ktor WAV stream.
 */
class RokuEcpSinkController(
    override val receiverId: String,
    private val ktorServer: KtorLowLatencyAudioServer
) : SinkController {

    override suspend fun connect(
        receiver: ReceiverDeviceState,
        metadata: MediaMetadataState
    ): Boolean = withContext(Dispatchers.IO) {
        val wavUrl = ktorServer.buildAuthorizedStreamUrl(receiver.id, preferWav = true)
        val encodedUrl = URLEncoder.encode(wavUrl, StandardCharsets.UTF_8.name())
        val encodedTitle = URLEncoder.encode(metadata.title, StandardCharsets.UTF_8.name())
        val encodedArtist = URLEncoder.encode(metadata.artist, StandardCharsets.UTF_8.name())

        // Channel 15985 = Roku Media Player; t=a (audio), a=sta (start live playback), fmt=wav
        val ecpPath = "/input/$ROKU_MEDIA_PLAYER_CHANNEL_ID?t=a&u=$encodedUrl&a=sta&tr=$encodedTitle&ar=$encodedArtist&fmt=wav"
        sendEcpPost(receiver.ipAddress, receiver.port, ecpPath)
    }

    override suspend fun disconnect(receiver: ReceiverDeviceState) {
        withContext(Dispatchers.IO) {
            sendEcpPost(receiver.ipAddress, receiver.port, "/input?a=sto")
            sendEcpPost(receiver.ipAddress, receiver.port, "/keypress/Home")
        }
    }

    override suspend fun setVolume(receiver: ReceiverDeviceState, volumePercent: Int) {
        // Software volume attenuation is applied directly inside Phase 3 C++ SinkDspProcessor!
    }

    override suspend fun setMute(receiver: ReceiverDeviceState, muted: Boolean) {
        withContext(Dispatchers.IO) {
            sendEcpPost(receiver.ipAddress, receiver.port, "/keypress/VolumeMute")
        }
    }

    override suspend fun updateMetadata(receiver: ReceiverDeviceState, metadata: MediaMetadataState) {}

    private fun sendEcpPost(ip: String, port: Int, path: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://$ip:$port$path")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2500
                readTimeout = 2500
                doOutput = true
                setFixedLengthStreamingMode(0)
            }
            conn.responseCode in 200..299
        } catch (_: Throwable) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val ROKU_MEDIA_PLAYER_CHANNEL_ID = 15985
    }
}
