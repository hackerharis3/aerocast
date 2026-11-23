package app.airmusic.modern

import app.airmusic.modern.nativebridge.NativeAudioEngine
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import android.content.Context
import android.net.Uri
import app.airmusic.modern.automation.HomeAssistantRestAndMqttBridge
import app.airmusic.modern.discovery.Android11NsdDiscoveryManager
import app.airmusic.modern.discovery.SsdpMulticastScanner
import app.airmusic.modern.model.ReceiverDeviceState
import app.airmusic.modern.model.SinkProtocol
import app.airmusic.modern.server.KtorLowLatencyAudioServer
import app.airmusic.modern.service.MediaSessionMetadataListener
import app.airmusic.modern.sinks.DenonHeosSinkController
import app.airmusic.modern.sinks.DlnaSoapSinkController
import app.airmusic.modern.sinks.RokuEcpSinkController
import app.airmusic.modern.sinks.SafMp3RecorderSinkController
import app.airmusic.modern.sinks.SinkController
import app.airmusic.modern.sinks.SnapcastTcpServerSinkController
import app.airmusic.modern.sinks.SonosZoneGroupSinkController
import app.airmusic.modern.sinks.airplay.AirPlayDacpRemoteServer
import app.airmusic.modern.sinks.airplay.AirPlayRaopSinkController
import app.airmusic.modern.sinks.cast.ChromecastTlsSinkController
import app.airmusic.modern.state.MultiRoomStateRepository
import app.airmusic.modern.storage.KeystoreEncryptedDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Master lifecycle coordinator uniting Phases 1 through 7 into a cohesive reactive runtime.
 */
object AirMusicRuntimeCoordinator {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var encryptedDataStore: KeystoreEncryptedDataStore
        private set
    lateinit var ktorServer: KtorLowLatencyAudioServer
        private set
    lateinit var nsdDiscovery: Android11NsdDiscoveryManager
        private set
    lateinit var ssdpScanner: SsdpMulticastScanner
        private set
    lateinit var dacpRemoteServer: AirPlayDacpRemoteServer
        private set
    lateinit var snapcastServer: SnapcastTcpServerSinkController
        private set
    lateinit var homeAssistantBridge: HomeAssistantRestAndMqttBridge
        private set

    private val activeSinkControllers = ConcurrentHashMap<String, SinkController>()
    private var previousConnectionStates = emptyMap<String, Boolean>()
    private var previousReceiverStates = emptyMap<String, ReceiverDeviceState>()
    private val pendingControlJobs = ConcurrentHashMap<String, Job>()
    private val controlLocks = ConcurrentHashMap<String, Mutex>()

    /**
     * Push the receiver's *current* volume and mute to its controller. Runs one at a time per
     * speaker and always reads the latest state, so the last command sent matches the UI even
     * when slider drags fire many updates.
     */
    private fun CoroutineScope.pushVolumeAndMute(receiverId: String, ctrl: SinkController) {
        pendingControlJobs.remove(receiverId)?.cancel()
        pendingControlJobs[receiverId] = launch(Dispatchers.IO) {
            controlLocks.getOrPut(receiverId) { Mutex() }.withLock {
                ensureActive()
                val latest = MultiRoomStateRepository.receiversFlow.value.find { it.id == receiverId } ?: return@withLock
                if (!latest.isConnected) return@withLock
                ctrl.setMute(latest, latest.isMuted)
                if (!latest.isMuted) ctrl.setVolume(latest, latest.volumePercent)
            }
        }
    }
    @Volatile
    var persistedSafRecordingTreeUri: Uri? = null

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        encryptedDataStore = KeystoreEncryptedDataStore(appContext)
        ktorServer = KtorLowLatencyAudioServer(appContext, listenPort = 8080)
        nsdDiscovery = Android11NsdDiscoveryManager(appContext, appScope)
        ssdpScanner = SsdpMulticastScanner(appScope)
        dacpRemoteServer = AirPlayDacpRemoteServer(appContext, dacpIdHex = "A1B2C3D4E5F60718")
        snapcastServer = SnapcastTcpServerSinkController(appScope, listenPort = 1704)

        // Start local Ktor HTTP/WebSocket streamer, Snapcast server, DACP server, and mDNS/SSDP discovery
        ktorServer.startServer()
        snapcastServer.startServer()
        dacpRemoteServer.startDacpServer()
        nsdDiscovery.startDiscovery()
        ssdpScanner.startPeriodicScanning()

        appScope.launch {
            val token = encryptedDataStore.getDecryptedSecret("ha_bearer_token") ?: run {
                val bytes = ByteArray(24)
                SecureRandom().nextBytes(bytes)
                val newToken = bytes.joinToString("") { "%02x".format(it) }
                encryptedDataStore.putEncryptedSecret("ha_bearer_token", newToken)
                newToken
            }
            homeAssistantBridge = HomeAssistantRestAndMqttBridge(appScope, token)
        }

        // Observe receiver state transitions -> connect/disconnect Phase 5 & Phase 6 sink controllers
        appScope.launch {
            MultiRoomStateRepository.receiversFlow.collect { receivers ->
                val metadata = MediaSessionMetadataListener.metadataFlow.value
                receivers.forEach { receiver ->
                    // Push EQ / limiter / mono to the native per-speaker DSP whenever they change.
                    // Volume stays at 100% in the DSP: each speaker's own volume control handles it,
                    // so the app never attenuates twice.
                    val prevDsp = previousReceiverStates[receiver.id]
                    if (prevDsp == null ||
                        prevDsp.eqEnabled != receiver.eqEnabled ||
                        prevDsp.limiterEnabled != receiver.limiterEnabled ||
                        prevDsp.stereoToMono != receiver.stereoToMono ||
                        prevDsp.eqBands != receiver.eqBands
                    ) {
                        NativeAudioEngine.nativeConfigureSinkDsp(
                            sinkId = receiver.id,
                            volumePercent = 100,
                            muted = false,
                            stereoToMono = receiver.stereoToMono,
                            eqEnabled = receiver.eqEnabled,
                            limiterEnabled = receiver.limiterEnabled
                        )
                        receiver.eqBands.forEach { band ->
                            NativeAudioEngine.nativeSetSinkEqBandGain(receiver.id, band.index, band.gainDb)
                        }
                    }

                    val wasConnected = previousConnectionStates[receiver.id] ?: false
                    if (receiver.isConnected && !wasConnected) {
                        val controller = buildControllerFor(appContext, receiver)
                        activeSinkControllers[receiver.id] = controller
                        launch(Dispatchers.IO) {
                            val ok = runCatching { controller.connect(receiver, metadata) }
                            android.util.Log.i("AirMusicCoordinator", "connect ${receiver.protocol} ${receiver.name} -> $ok")
                            // Bring the speaker in line with the app's volume/mute right after connecting.
                            if (ok.getOrNull() == true) pushVolumeAndMute(receiver.id, controller)
                        }
                    } else if (!receiver.isConnected && wasConnected) {
                        activeSinkControllers.remove(receiver.id)?.let { ctrl ->
                            launch(Dispatchers.IO) { ctrl.disconnect(receiver) }
                        }
                    } else if (receiver.isConnected) {
                        // Only push volume/mute when they actually changed, and drop any update still
                        // queued for this speaker so slider drags send just the latest value.
                        val prev = previousReceiverStates[receiver.id]
                        val volumeChanged = prev == null || prev.volumePercent != receiver.volumePercent
                        val muteChanged = prev == null || prev.isMuted != receiver.isMuted
                        if (volumeChanged || muteChanged) {
                            activeSinkControllers[receiver.id]?.let { ctrl -> pushVolumeAndMute(receiver.id, ctrl) }
                            launch(Dispatchers.IO) {
                                encryptedDataStore.saveReceiverProfile(receiver)
                            }
                        }
                    }
                }
                previousConnectionStates = receivers.associate { it.id to it.isConnected }
                previousReceiverStates = receivers.associateBy { it.id }
            }
        }

        // Observe active MediaSession track metadata -> push live updates to all connected sinks
        appScope.launch {
            MediaSessionMetadataListener.metadataFlow
                .collect { metadata ->
                    val connected = MultiRoomStateRepository.receiversFlow.value.filter { it.isConnected }
                    connected.forEach { receiver ->
                        activeSinkControllers[receiver.id]?.let { ctrl ->
                            launch(Dispatchers.IO) { ctrl.updateMetadata(receiver, metadata) }
                        }
                    }
                }
        }
    }

    private fun buildControllerFor(context: Context, receiver: ReceiverDeviceState): SinkController {
        return when (receiver.protocol) {
            SinkProtocol.AIRPLAY -> AirPlayRaopSinkController(receiver.id, appScope)
            SinkProtocol.CHROMECAST -> ChromecastTlsSinkController(receiver.id, appScope, ktorServer)
            SinkProtocol.SONOS -> SonosZoneGroupSinkController(receiver.id, ktorServer)
            SinkProtocol.DENON_HEOS -> DenonHeosSinkController(receiver.id, ktorServer)
            SinkProtocol.ROKU -> RokuEcpSinkController(receiver.id, ktorServer)
            SinkProtocol.FILE_MP3_RECORDER -> SafMp3RecorderSinkController(
                receiverId = receiver.id,
                context = context,
                scope = appScope,
                treeUriProvider = { persistedSafRecordingTreeUri }
            )
            SinkProtocol.SNAPCAST -> snapcastServer
            else -> DlnaSoapSinkController(receiver.id, ktorServer)
        }
    }
}
