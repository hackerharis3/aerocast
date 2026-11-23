package app.airmusic.modern.sinks

import android.os.Build
import app.airmusic.modern.server.KtorLowLatencyAudioServer

/**
 * Clean-room replacement for AirMusic's l1.a–l1.c (UPnPMediaServer) and ContentDirectoryService.
 *
 * Implements the Xbox / Windows Media Player Sharing 12.0 User-Agent spoofing discovered in l1.a:
 * - When an Xbox console ("Xbox" or "FDSSDP" in User-Agent) requests the UPnP device descriptor,
 *   dynamically reports manufacturer="Microsoft Corporation" and modelName="Windows Media Player Sharing"
 *   (modelNumber="12.0") so Xbox dashboard background audio streaming works out of the box.
 * - Exposes 3 virtual live audio tracks in ContentDirectory:1 Browse responses:
 *   1. Live Audio (WAV - Uncompressed 44.1 kHz Stereo)
 *   2. Live Audio (MP3 - 320 kbps CBR)
 */
class UpnpConsoleMediaServer(
    private val ktorServer: KtorLowLatencyAudioServer,
    private val serverUdn: String = "uuid:airmusic-modern-mediaserver-001"
) {

    fun buildDeviceDescriptorXml(clientUserAgent: String): String {
        val isXboxOrWindows = clientUserAgent.contains("Xbox", ignoreCase = true) ||
            clientUserAgent.contains("FDSSDP", ignoreCase = true)

        val friendlyName = if (isXboxOrWindows) {
            "Aerocast (${Build.MODEL}): 1 : Windows Media Connect"
        } else {
            "Aerocast (${Build.MODEL})"
        }
        val manufacturer = if (isXboxOrWindows) "Microsoft Corporation" else "Aerocast"
        val modelName = if (isXboxOrWindows) "Windows Media Player Sharing" else "Aerocast UPnP MediaServer"
        val modelNumber = if (isXboxOrWindows) "12.0" else "2.0"

        return """
            <?xml version="1.0" encoding="utf-8"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
                <specVersion><major>1</major><minor>0</minor></specVersion>
                <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
                    <friendlyName>$friendlyName</friendlyName>
                    <manufacturer>$manufacturer</manufacturer>
                    <modelName>$modelName</modelName>
                    <modelNumber>$modelNumber</modelNumber>
                    <UDN>$serverUdn</UDN>
                    <serviceList>
                        <service>
                            <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                            <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
                            <SCPDURL>/upnp/ContentDirectory.xml</SCPDURL>
                            <controlURL>/upnp/control/ContentDirectory</controlURL>
                            <eventSubURL>/upnp/event/ContentDirectory</eventSubURL>
                        </service>
                        <service>
                            <serviceType>urn:microsoft.com:service:X_MS_MediaReceiverRegistrar:1</serviceType>
                            <serviceId>urn:microsoft.com:serviceId:X_MS_MediaReceiverRegistrar</serviceId>
                            <SCPDURL>/upnp/MediaReceiverRegistrar.xml</SCPDURL>
                            <controlURL>/upnp/control/MediaReceiverRegistrar</controlURL>
                            <eventSubURL>/upnp/event/MediaReceiverRegistrar</eventSubURL>
                        </service>
                    </serviceList>
                </device>
            </root>
        """.trimIndent()
    }

    fun buildContentDirectoryBrowseResponseSoap(): String {
        val wavUrl = ktorServer.buildAuthorizedStreamUrl("upnp_console_sink", preferWav = true)
        val mp3Url = ktorServer.buildAuthorizedStreamUrl("upnp_console_sink", preferWav = false)

        val didlResult = """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                       xmlns:dc="http://purl.org/dc/elements/1.1/"
                       xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
                <item id="track_wav" parentID="0" restricted="1">
                    <dc:title>Live Audio (WAV Lossless)</dc:title>
                    <dc:creator>Aerocast</dc:creator>
                    <upnp:class>object.item.audioItem.musicTrack</upnp:class>
                    <res protocolInfo="http-get:*:audio/wav:DLNA.ORG_PN=WAV;DLNA.ORG_OP=00;DLNA.ORG_CI=1">$wavUrl</res>
                </item>
                <item id="track_mp3" parentID="0" restricted="1">
                    <dc:title>Live Audio (MP3 320 kbps)</dc:title>
                    <dc:creator>Aerocast</dc:creator>
                    <upnp:class>object.item.audioItem.musicTrack</upnp:class>
                    <res protocolInfo="http-get:*:audio/mpeg:DLNA.ORG_PN=MP3;DLNA.ORG_OP=00;DLNA.ORG_CI=1">$mp3Url</res>
                </item>
            </DIDL-Lite>
        """.trimIndent()
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        return """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                        s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                        <Result>$didlResult</Result>
                        <NumberReturned>2</NumberReturned>
                        <TotalMatches>2</TotalMatches>
                        <UpdateID>1</UpdateID>
                    </u:BrowseResponse>
                </s:Body>
            </s:Envelope>
        """.trimIndent()
    }
}
