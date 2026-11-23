package app.airmusic.modern.sinks

import app.airmusic.modern.model.ReceiverDeviceState
import app.airmusic.modern.server.KtorLowLatencyAudioServer

/**
 * Clean-room Denon HEOS (urn:schemas-denon-com:device:AiosDevice:1) controller replacing AirMusic's x1.a & x1.b.
 * Forces WAV live broadcast streaming with DIDL-Lite class "object.item.audioItem.audioBroadcast.stream".
 */
class DenonHeosSinkController(
    receiverId: String,
    ktorServer: KtorLowLatencyAudioServer
) : DlnaSoapSinkController(receiverId, ktorServer) {

    override val didlUpnpClass: String = "object.item.audioItem.audioBroadcast.stream"
    override val avTransportControlPath: String = "/upnp/control/renderer_dvc/AVTransport"
    override val renderingControlPath: String = "/upnp/control/renderer_dvc/RenderingControl"

    override fun resolveStreamUrl(receiver: ReceiverDeviceState): String {
        // Denon HEOS requires uncompressed WAV for low-latency live streaming
        return ktorServer.buildAuthorizedStreamUrl(receiver.id, preferWav = true)
    }
}
