package app.airmusic.modern.sinks

import app.airmusic.modern.model.AudioCodecProfile
import app.airmusic.modern.model.MediaMetadataState
import app.airmusic.modern.model.ReceiverDeviceState
import app.airmusic.modern.server.KtorLowLatencyAudioServer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Clean-room UPnP / DLNA MediaRenderer:1 SOAP controller replacing AirMusic's app.airmusic.sinks.dlna.k.
 * Sends SetAVTransportURI (with DIDL-Lite XML metadata), Play, Stop, SetVolume, and SetMute SOAP actions.
 */
open class DlnaSoapSinkController(
    override val receiverId: String,
    protected val ktorServer: KtorLowLatencyAudioServer
) : SinkController {

    protected open val avTransportControlPath: String = "/MediaRenderer/AVTransport/Control"
    protected open val renderingControlPath: String = "/MediaRenderer/RenderingControl/Control"
    protected open val didlUpnpClass: String = "object.item.audioItem.musicTrack"

    /** Prefer the control path the device advertised; fall back to this controller's default. */
    protected fun avTransportPathFor(receiver: ReceiverDeviceState): String =
        toPath(receiver.avTransportControlUrl).ifBlank { avTransportControlPath }

    protected fun renderingPathFor(receiver: ReceiverDeviceState): String =
        toPath(receiver.renderingControlUrl).ifBlank { renderingControlPath }

    private fun toPath(controlUrl: String): String = when {
        controlUrl.isBlank() -> ""
        controlUrl.startsWith("http", ignoreCase = true) -> runCatching {
            URL(controlUrl).let { u -> u.path + (u.query?.let { "?$it" } ?: "") }
        }.getOrDefault("")
        else -> controlUrl
    }

    override suspend fun connect(
        receiver: ReceiverDeviceState,
        metadata: MediaMetadataState
    ): Boolean = withContext(Dispatchers.IO) {
        val streamUrl = resolveStreamUrl(receiver)
        val didlXml = buildDidlLiteMetadataXml(receiver, metadata, streamUrl)
        val escapedDidl = xmlEscape(didlXml)

        val setUriBody = """
            <u:SetAVTransportURI xmlns:u="$URN_AV_TRANSPORT">
                <InstanceID>0</InstanceID>
                <CurrentURI>${xmlEscape(streamUrl)}</CurrentURI>
                <CurrentURIMetaData>$escapedDidl</CurrentURIMetaData>
            </u:SetAVTransportURI>
        """.trimIndent()

        // Stop any current playback first; many renderers refuse SetAVTransportURI/Play mid-transition.
        sendSoapAction(
            ip = receiver.ipAddress,
            port = receiver.port,
            controlPath = avTransportPathFor(receiver),
            serviceUrn = URN_AV_TRANSPORT,
            actionName = "Stop",
            innerXml = """<u:Stop xmlns:u="$URN_AV_TRANSPORT"><InstanceID>0</InstanceID></u:Stop>"""
        )

        val uriOk = sendSoapAction(
            ip = receiver.ipAddress,
            port = receiver.port,
            controlPath = avTransportPathFor(receiver),
            serviceUrn = URN_AV_TRANSPORT,
            actionName = "SetAVTransportURI",
            innerXml = setUriBody
        )
        if (!uriOk) return@withContext false

        val playBody = """
            <u:Play xmlns:u="$URN_AV_TRANSPORT">
                <InstanceID>0</InstanceID>
                <Speed>1</Speed>
            </u:Play>
        """.trimIndent()

        // Renderers may need a moment after SetAVTransportURI (UPnP 701); retry Play a few times.
        repeat(4) { attempt ->
            if (attempt > 0) delay(750)
            if (sendSoapAction(
                    ip = receiver.ipAddress,
                    port = receiver.port,
                    controlPath = avTransportPathFor(receiver),
                    serviceUrn = URN_AV_TRANSPORT,
                    actionName = "Play",
                    innerXml = playBody
                )
            ) return@withContext true
        }
        false
    }

    override suspend fun disconnect(receiver: ReceiverDeviceState) {
        withContext(Dispatchers.IO) {
            val stopBody = """
                <u:Stop xmlns:u="$URN_AV_TRANSPORT">
                    <InstanceID>0</InstanceID>
                </u:Stop>
            """.trimIndent()
            sendSoapAction(
                ip = receiver.ipAddress,
                port = receiver.port,
                controlPath = avTransportPathFor(receiver),
                serviceUrn = URN_AV_TRANSPORT,
                actionName = "Stop",
                innerXml = stopBody
            )
        }
    }

    override suspend fun setVolume(receiver: ReceiverDeviceState, volumePercent: Int) {
        withContext(Dispatchers.IO) {
            val clamped = volumePercent.coerceIn(0, 100)
            val body = """
                <u:SetVolume xmlns:u="$URN_RENDERING_CONTROL">
                    <InstanceID>0</InstanceID>
                    <Channel>Master</Channel>
                    <DesiredVolume>$clamped</DesiredVolume>
                </u:SetVolume>
            """.trimIndent()
            sendSoapAction(
                ip = receiver.ipAddress,
                port = receiver.port,
                controlPath = renderingPathFor(receiver),
                serviceUrn = URN_RENDERING_CONTROL,
                actionName = "SetVolume",
                innerXml = body
            )
        }
    }

    override suspend fun setMute(receiver: ReceiverDeviceState, muted: Boolean) {
        withContext(Dispatchers.IO) {
            val muteFlag = if (muted) 1 else 0
            val body = """
                <u:SetMute xmlns:u="$URN_RENDERING_CONTROL">
                    <InstanceID>0</InstanceID>
                    <Channel>Master</Channel>
                    <DesiredMute>$muteFlag</DesiredMute>
                </u:SetMute>
            """.trimIndent()
            sendSoapAction(
                ip = receiver.ipAddress,
                port = receiver.port,
                controlPath = renderingPathFor(receiver),
                serviceUrn = URN_RENDERING_CONTROL,
                actionName = "SetMute",
                innerXml = body
            )
        }
    }

    override suspend fun updateMetadata(receiver: ReceiverDeviceState, metadata: MediaMetadataState) {
        // For DLNA/SONOS streams using MP3, live track updates are injected inline via Phase 4 IcyMetadataChunkWriter.
    }

    protected open fun resolveStreamUrl(receiver: ReceiverDeviceState): String {
        // LPCM (audio/L16) is the one format every DLNA audio renderer must accept; WAV often isn't.
        if (receiver.activeCodec == AudioCodecProfile.MP3_320) {
            return ktorServer.buildAuthorizedStreamUrl(receiver.id, preferWav = false)
        }
        return ktorServer.buildAuthorizedLpcmStreamUrl(receiver.id)
    }

    protected open fun buildDidlLiteMetadataXml(
        receiver: ReceiverDeviceState,
        metadata: MediaMetadataState,
        streamUrl: String
    ): String {
        val protocolInfo = when {
            streamUrl.endsWith(".l16", ignoreCase = true) ->
                "http-get:*:${KtorLowLatencyAudioServer.LPCM_MIME}:${KtorLowLatencyAudioServer.LPCM_CONTENT_FEATURES}"
            streamUrl.endsWith(".wav", ignoreCase = true) ->
                "http-get:*:audio/wav:DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=81500000000000000000000000000000"
            else ->
                "http-get:*:audio/mpeg:DLNA.ORG_PN=MP3;DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=81500000000000000000000000000000"
        }
        val lanIp = KtorLowLatencyAudioServer.resolveLocalLanIpv4()
        val artUrl = "http://$lanIp:8080/stream/${ktorServer.sessionToken}/current.png?ts=${System.currentTimeMillis()}"

        return """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                       xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"
                       xmlns:dc="http://purl.org/dc/elements/1.1/">
                <item id="1" parentID="0" restricted="1">
                    <dc:title>${xmlEscape(metadata.title)}</dc:title>
                    <dc:creator>${xmlEscape(metadata.artist)}</dc:creator>
                    <upnp:class>$didlUpnpClass</upnp:class>
                    <upnp:album>${xmlEscape(metadata.album.ifBlank { "Aerocast Live" })}</upnp:album>
                    <upnp:artist>${xmlEscape(metadata.artist)}</upnp:artist>
                    <upnp:albumArtURI>${xmlEscape(artUrl)}</upnp:albumArtURI>
                    <res protocolInfo="$protocolInfo">${xmlEscape(streamUrl)}</res>
                </item>
            </DIDL-Lite>
        """.trimIndent()
    }

    protected fun sendSoapAction(
        ip: String,
        port: Int,
        controlPath: String,
        serviceUrn: String,
        actionName: String,
        innerXml: String
    ): Boolean {
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                        s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    $innerXml
                </s:Body>
            </s:Envelope>
        """.trimIndent()

        var conn: HttpURLConnection? = null
        return try {
            val normalizedPath = if (controlPath.startsWith("/")) controlPath else "/$controlPath"
            val url = URL("http://$ip:$port$normalizedPath")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 3000
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPAction", "\"$serviceUrn#$actionName\"")
            }
            val payloadBytes = envelope.toByteArray(StandardCharsets.UTF_8)
            conn.setFixedLengthStreamingMode(payloadBytes.size)
            conn.outputStream.use { it.write(payloadBytes) }
            val code = conn.responseCode
            if (code in 200..299) {
                Log.i(TAG, "$actionName -> $code ($ip:$port$normalizedPath)")
            } else {
                val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                Log.w(TAG, "$actionName -> $code ($ip:$port$normalizedPath) ${err?.take(400)}")
            }
            code in 200..299
        } catch (t: Throwable) {
            Log.w(TAG, "$actionName to $ip:$port failed", t)
            false
        } finally {
            conn?.disconnect()
        }
    }

    protected fun xmlEscape(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    companion object {
        private const val TAG = "AirMusicDlna"
        const val URN_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
        const val URN_RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    }
}
