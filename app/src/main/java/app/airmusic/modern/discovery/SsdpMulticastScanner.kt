package app.airmusic.modern.discovery

import app.airmusic.modern.model.SinkProtocol
import app.airmusic.modern.state.MultiRoomStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Clean-room UDP SSDP Multicast Scanner replacing AirMusic's k1.g (DLNA), k1.n (SONOS),
 * k1.i (Denon HEOS), k1.l (Roku), and k1.o (raw M-SEARCH socket).
 */
class SsdpMulticastScanner(
    private val scope: CoroutineScope
) {
    private var scanJob: Job? = null

    private val searchTargets = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:device:ZonePlayer:1",
        "urn:schemas-denon-com:device:AiosDevice:1",
        "urn:roku-com:device:player:1",
        "urn:dial-multiscreen-org:service:dial:1"
    )

    fun startPeriodicScanning(intervalMs: Long = 15_000L) {
        stopScanning()
        scanJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                executeMsearchRound()
                delay(intervalMs)
            }
        }
    }

    fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
    }

    private fun executeMsearchRound() {
        var socket: DatagramSocket? = null
        try {
            val multicastGroup = InetAddress.getByName(SSDP_MULTICAST_IP)
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 2500
                bind(InetSocketAddress(0))
            }

            for (st in searchTargets) {
                val payload = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: $SSDP_MULTICAST_IP:$SSDP_PORT\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 2\r\n")
                    append("ST: $st\r\n\r\n")
                }.toByteArray(StandardCharsets.UTF_8)

                val packet = DatagramPacket(payload, payload.size, multicastGroup, SSDP_PORT)
                socket.send(packet)
            }

            val receiveBuf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + 2600L
            while (System.currentTimeMillis() < deadline) {
                val respPacket = DatagramPacket(receiveBuf, receiveBuf.size)
                try {
                    socket.receive(respPacket)
                    val rawHeaders = String(respPacket.data, 0, respPacket.length, StandardCharsets.UTF_8)
                    handleSsdpResponse(rawHeaders, respPacket.address.hostAddress ?: continue)
                } catch (_: SocketTimeoutException) {
                    break
                }
            }
        } catch (_: Throwable) {
        } finally {
            socket?.close()
        }
    }

    private fun handleSsdpResponse(headersText: String, responderIp: String) {
        val headers = mutableMapOf<String, String>()
        headersText.lines().forEach { line ->
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim().uppercase()
                val value = line.substring(colon + 1).trim()
                headers[key] = value
            }
        }

        val locationUrl = headers["LOCATION"] ?: return
        val st = headers["ST"].orEmpty()
        val server = headers["SERVER"].orEmpty()

        scope.launch(Dispatchers.IO) {
            inspectDeviceDescriptionXml(locationUrl, responderIp, st, server)
        }
    }

    private fun inspectDeviceDescriptionXml(
        locationUrl: String,
        responderIp: String,
        stHeader: String,
        serverHeader: String
    ) {
        try {
            val url = URL(locationUrl)
            val port = if (url.port > 0) url.port else url.defaultPort.takeIf { it > 0 } ?: 80
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 2500
                readTimeout = 2500
                requestMethod = "GET"
            }

            val xmlBody = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            var friendlyName = ""
            var manufacturer = ""
            var modelDescription = ""
            var udn = ""
            var currentServiceType = ""
            var avTransportControlUrl = ""
            var renderingControlUrl = ""

            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xmlBody))
            var event = parser.eventType
            var currentTag = ""

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> currentTag = parser.name
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty()) {
                            when (currentTag.lowercase()) {
                                "friendlyname" -> if (friendlyName.isEmpty()) friendlyName = text
                                "manufacturer" -> if (manufacturer.isEmpty()) manufacturer = text
                                "modeldescription" -> if (modelDescription.isEmpty()) modelDescription = text
                                "udn" -> if (udn.isEmpty()) udn = text
                                "servicetype" -> currentServiceType = text
                                "controlurl" -> when {
                                    currentServiceType.contains(":AVTransport:") && avTransportControlUrl.isEmpty() ->
                                        avTransportControlUrl = text
                                    currentServiceType.contains(":RenderingControl:") && renderingControlUrl.isEmpty() ->
                                        renderingControlUrl = text
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> currentTag = ""
                }
                event = parser.next()
            }

            val protocol = when {
                stHeader.contains("roku", ignoreCase = true) ||
                    manufacturer.contains("Roku", ignoreCase = true) -> SinkProtocol.ROKU
                modelDescription.contains("Sonos", ignoreCase = true) ||
                    manufacturer.contains("Sonos", ignoreCase = true) ||
                    serverHeader.contains("Sonos", ignoreCase = true) -> SinkProtocol.SONOS
                stHeader.contains("denon", ignoreCase = true) ||
                    manufacturer.contains("Denon", ignoreCase = true) -> SinkProtocol.DENON_HEOS
                else -> SinkProtocol.DLNA
            }

            val cleanId = (udn.ifBlank { "${protocol.name}_$responderIp" })
                .replace("uuid:", "")
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")

            MultiRoomStateRepository.upsertDiscoveredReceiver(
                id = cleanId,
                name = friendlyName.ifBlank { "${protocol.badgeTitle} ($responderIp)" },
                protocol = protocol,
                ipAddress = responderIp,
                port = if (protocol == SinkProtocol.ROKU) 8060 else port,
                avTransportControlUrl = avTransportControlUrl,
                renderingControlUrl = renderingControlUrl
            )
        } catch (t: Throwable) {
            android.util.Log.w("AirMusicSsdp", "Description fetch failed for $locationUrl", t)
        }
    }

    companion object {
        private const val SSDP_MULTICAST_IP = "239.255.255.250"
        private const val SSDP_PORT = 1900
    }
}
