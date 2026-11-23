package app.airmusic.modern.sinks

import android.content.Context
import android.net.Uri
import app.airmusic.modern.model.MediaMetadataState
import app.airmusic.modern.model.ReceiverDeviceState
import app.airmusic.modern.model.SinkProtocol
import app.airmusic.modern.server.KtorLowLatencyAudioServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified coroutine-driven wireless audio sink controller contract replacing AirMusic's t1.d (Sink).
 */
interface SinkController {
    val receiverId: String
    suspend fun connect(receiver: ReceiverDeviceState, metadata: MediaMetadataState): Boolean
    suspend fun disconnect(receiver: ReceiverDeviceState)
    suspend fun setVolume(receiver: ReceiverDeviceState, volumePercent: Int)
    suspend fun setMute(receiver: ReceiverDeviceState, muted: Boolean)
    suspend fun updateMetadata(receiver: ReceiverDeviceState, metadata: MediaMetadataState)
}

/**
 * Dispatches connect, disconnect, volume, mute, and live MediaSession metadata updates
 * to protocol-specific sink controllers.
 */
class MultiProtocolSinkOrchestrator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val ktorServer: KtorLowLatencyAudioServer,
    private val safTreeUriProvider: () -> Uri?
) {
    private val activeControllers = ConcurrentHashMap<String, SinkController>()
    private val snapcastServer = SnapcastTcpServerSinkController(scope)

    fun startBackgroundServers() {
        ktorServer.startServer()
        snapcastServer.startServer()
    }

    fun stopBackgroundServers() {
        snapcastServer.stopServer()
        ktorServer.stopServer()
    }

    fun onReceiverConnectionToggled(receiver: ReceiverDeviceState, metadata: MediaMetadataState) {
        scope.launch(Dispatchers.IO) {
            if (receiver.isConnected) {
                val controller = createControllerFor(receiver)
                activeControllers[receiver.id] = controller
                controller.connect(receiver, metadata)
            } else {
                activeControllers.remove(receiver.id)?.disconnect(receiver)
            }
        }
    }

    fun onReceiverVolumeChanged(receiver: ReceiverDeviceState) {
        scope.launch(Dispatchers.IO) {
            activeControllers[receiver.id]?.setVolume(receiver, receiver.volumePercent)
        }
    }

    fun onReceiverMuteChanged(receiver: ReceiverDeviceState) {
        scope.launch(Dispatchers.IO) {
            activeControllers[receiver.id]?.setMute(receiver, receiver.isMuted)
        }
    }

    fun onMetadataChanged(connectedReceivers: List<ReceiverDeviceState>, metadata: MediaMetadataState) {
        scope.launch(Dispatchers.IO) {
            connectedReceivers.forEach { receiver ->
                activeControllers[receiver.id]?.updateMetadata(receiver, metadata)
            }
        }
    }

    private fun createControllerFor(receiver: ReceiverDeviceState): SinkController {
        return when (receiver.protocol) {
            SinkProtocol.DLNA -> DlnaSoapSinkController(receiver.id, ktorServer)
            SinkProtocol.SONOS -> SonosZoneGroupSinkController(receiver.id, ktorServer)
            SinkProtocol.DENON_HEOS -> DenonHeosSinkController(receiver.id, ktorServer)
            SinkProtocol.ROKU -> RokuEcpSinkController(receiver.id, ktorServer)
            SinkProtocol.FILE_MP3_RECORDER -> SafMp3RecorderSinkController(
                receiverId = receiver.id,
                context = context,
                scope = scope,
                treeUriProvider = safTreeUriProvider
            )
            SinkProtocol.SNAPCAST -> snapcastServer
            else -> DlnaSoapSinkController(receiver.id, ktorServer)
        }
    }
}
