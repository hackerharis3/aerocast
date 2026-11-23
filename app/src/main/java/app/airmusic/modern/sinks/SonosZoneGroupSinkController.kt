package app.airmusic.modern.sinks

import app.airmusic.modern.model.AudioCodecProfile
import app.airmusic.modern.model.ReceiverDeviceState
import app.airmusic.modern.server.KtorLowLatencyAudioServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Clean-room SONOS ZonePlayer controller replacing AirMusic's app.airmusic.sinks.sonos.a & b.
 *
 * Key Protocol Specializations Verified in AirMusic v2.0.3:
 * 1. Rewrites "http://" MP3 stream URLs to "x-rincon-mp3radio://" so SONOS treats the stream
 *    as a low-latency live radio broadcast.
 * 2. Sets DIDL-Lite <upnp:class> to "object.item.audioItem.audioBroadcast".
 * 3. Uses GroupRenderingControl:1 (SnapshotGroupVolume / SetGroupVolume / SetGroupMute)
 *    for synchronized SONOS ZoneGroup volume adjustments on port 1400.
 */
class SonosZoneGroupSinkController(
    receiverId: String,
    ktorServer: KtorLowLatencyAudioServer
) : DlnaSoapSinkController(receiverId, ktorServer) {

    override val didlUpnpClass: String = "object.item.audioItem.audioBroadcast"
    private val groupRenderingControlPath = "/MediaRenderer/GroupRenderingControl/Control"

    override fun resolveStreamUrl(receiver: ReceiverDeviceState): String {
        val rawHttpUrl = ktorServer.buildAuthorizedStreamUrl(
            sinkId = receiver.id,
            preferWav = receiver.activeCodec == AudioCodecProfile.WAV
        )
        return if (rawHttpUrl.endsWith(".mp3", ignoreCase = true)) {
            rawHttpUrl.replaceFirst("http://", "x-rincon-mp3radio://")
        } else {
            rawHttpUrl
        }
    }

    override suspend fun setVolume(receiver: ReceiverDeviceState, volumePercent: Int) {
        withContext(Dispatchers.IO) {
            val clamped = volumePercent.coerceIn(0, 100)
            // First snapshot proportional group ratios on the coordinator
            val snapshotXml = """
                <u:SnapshotGroupVolume xmlns:u="$URN_GROUP_RENDERING_CONTROL">
                    <InstanceID>0</InstanceID>
                </u:SnapshotGroupVolume>
            """.trimIndent()
            sendSoapAction(
                ip = receiver.ipAddress,
                port = receiver.port,
                controlPath = groupRenderingControlPath,
                serviceUrn = URN_GROUP_RENDERING_CONTROL,
                actionName = "SnapshotGroupVolume",
                innerXml = snapshotXml
            )

            // Then apply the desired group volume percentage
            val groupVolXml = """
                <u:SetGroupVolume xmlns:u="$URN_GROUP_RENDERING_CONTROL">
                    <InstanceID>0</InstanceID>
                    <DesiredVolume>$clamped</DesiredVolume>
                </u:SetGroupVolume>
            """.trimIndent()
            val groupOk = sendSoapAction(
                ip = receiver.ipAddress,
                port = receiver.port,
                controlPath = groupRenderingControlPath,
                serviceUrn = URN_GROUP_RENDERING_CONTROL,
                actionName = "SetGroupVolume",
                innerXml = groupVolXml
            )
            if (!groupOk) {
                super.setVolume(receiver, clamped)
            }
        }
    }

    override suspend fun setMute(receiver: ReceiverDeviceState, muted: Boolean) {
        withContext(Dispatchers.IO) {
            val muteBit = if (muted) 1 else 0
            val groupMuteXml = """
                <u:SetGroupMute xmlns:u="$URN_GROUP_RENDERING_CONTROL">
                    <InstanceID>0</InstanceID>
                    <DesiredMute>$muteBit</DesiredMute>
                </u:SetGroupMute>
            """.trimIndent()
            val ok = sendSoapAction(
                ip = receiver.ipAddress,
                port = receiver.port,
                controlPath = groupRenderingControlPath,
                serviceUrn = URN_GROUP_RENDERING_CONTROL,
                actionName = "SetGroupMute",
                innerXml = groupMuteXml
            )
            if (!ok) {
                super.setMute(receiver, muted)
            }
        }
    }

    companion object {
        private const val URN_GROUP_RENDERING_CONTROL = "urn:schemas-upnp-org:service:GroupRenderingControl:1"
    }
}
