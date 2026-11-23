package app.airmusic.modern.state

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import android.media.audiofx.DynamicsProcessing
import app.airmusic.modern.model.AudioCodecProfile
import app.airmusic.modern.model.EqPreset
import app.airmusic.modern.model.GlobalSettingsState
import app.airmusic.modern.model.QuickConnectGroup
import app.airmusic.modern.model.ReceiverDeviceState
import app.airmusic.modern.model.SinkProtocol
import app.airmusic.modern.model.defaultTenBandEq
import app.airmusic.modern.nativebridge.NativeAudioEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Reactive Single-Source-of-Truth replacing AirMusic's fragmented SharedPreferences
 * (SinkManagerKnownDevices, SinkManagerVirtualDevices, StreamControlBroadcastReceiverQuickConnectSinks)
 * and bridging Android 11+ DynamicsProcessing 10-band EQ & Limiter stages.
 */
object MultiRoomStateRepository {

    private val _receiversFlow = MutableStateFlow<List<ReceiverDeviceState>>(emptyList())
    val receiversFlow: StateFlow<List<ReceiverDeviceState>> = _receiversFlow.asStateFlow()

    // Groups are user-defined; none exist until the user creates one from discovered speakers.
    private val _groupsFlow = MutableStateFlow<List<QuickConnectGroup>>(emptyList())
    val groupsFlow: StateFlow<List<QuickConnectGroup>> = _groupsFlow.asStateFlow()

    private val _settingsFlow = MutableStateFlow(GlobalSettingsState())
    val settingsFlow: StateFlow<GlobalSettingsState> = _settingsFlow.asStateFlow()

    private var dynamicsProcessing: DynamicsProcessing? = null

    fun toggleReceiverConnection(receiverId: String) {
        _receiversFlow.update { list ->
            list.map { r ->
                if (r.id == receiverId) r.copy(isConnected = !r.isConnected, isExpanded = !r.isConnected) else r
            }
        }
    }

    fun toggleReceiverExpanded(receiverId: String) {
        _receiversFlow.update { list ->
            list.map { r -> if (r.id == receiverId) r.copy(isExpanded = !r.isExpanded) else r }
        }
    }

    fun setReceiverVolume(receiverId: String, volumePercent: Int) {
        val clamped = volumePercent.coerceIn(0, 100)
        _receiversFlow.update { list ->
            list.map { r -> if (r.id == receiverId) r.copy(volumePercent = clamped, isMuted = clamped == 0) else r }
        }
    }

    fun setMasterVolumeDelta(deltaPercent: Int) {
        _receiversFlow.update { list ->
            list.map { r ->
                if (r.isConnected) {
                    val nextVol = (r.volumePercent + deltaPercent).coerceIn(0, 100)
                    r.copy(volumePercent = nextVol, isMuted = nextVol == 0)
                } else r
            }
        }
    }

    fun toggleReceiverMute(receiverId: String) {
        _receiversFlow.update { list ->
            list.map { r -> if (r.id == receiverId) r.copy(isMuted = !r.isMuted) else r }
        }
    }

    const val MAX_SYNC_DELAY_MS = 2000

    /** Current sync delay for a speaker (0 if unknown, e.g. browser listeners). */
    fun syncDelayMs(receiverId: String): Int =
        receiversFlow.value.find { it.id == receiverId }?.delayOffsetMs?.coerceIn(0, MAX_SYNC_DELAY_MS) ?: 0

    fun setReceiverDelayOffset(receiverId: String, delayMs: Int) {
        // Positive delay only (a speaker can be held back, not played before capture), 10 ms steps.
        val snapped = (delayMs.coerceIn(0, MAX_SYNC_DELAY_MS) / 10) * 10
        _receiversFlow.update { list ->
            list.map { r -> if (r.id == receiverId) r.copy(delayOffsetMs = snapped) else r }
        }
    }

    fun runAutoChirpCalibration(receiverId: String) {
        // Applies protocol-typical acoustic round-trip compensation baseline
        _receiversFlow.update { list ->
            list.map { r ->
                if (r.id == receiverId) {
                    val calibratedMs = when (r.protocol) {
                        SinkProtocol.AIRPLAY -> -120
                        SinkProtocol.SONOS -> -340
                        SinkProtocol.CHROMECAST -> -180
                        SinkProtocol.DLNA, SinkProtocol.DENON_HEOS -> -250
                        SinkProtocol.HTTP_WEBSOCKET, SinkProtocol.SNAPCAST -> -40
                        else -> 0
                    }
                    r.copy(delayOffsetMs = calibratedMs)
                } else r
            }
        }
    }

    fun setReceiverCodec(receiverId: String, codec: AudioCodecProfile) {
        _receiversFlow.update { list ->
            list.map { r -> if (r.id == receiverId) r.copy(activeCodec = codec) else r }
        }
    }

    fun setReceiverAlias(receiverId: String, newAlias: String) {
        _receiversFlow.update { list ->
            list.map { r -> if (r.id == receiverId) r.copy(alias = newAlias.trim()) else r }
        }
    }

    fun toggleStereoToMono(receiverId: String) {
        _receiversFlow.update { list ->
            list.map { r -> if (r.id == receiverId) r.copy(stereoToMono = !r.stereoToMono) else r }
        }
    }

    fun toggleReceiverEq(receiverId: String, enabled: Boolean) {
        _receiversFlow.update { list ->
            list.map { r -> if (r.id == receiverId) r.copy(eqEnabled = enabled) else r }
        }
    }

    fun toggleReceiverLimiter(receiverId: String, enabled: Boolean) {
        _receiversFlow.update { list ->
            list.map { r -> if (r.id == receiverId) r.copy(limiterEnabled = enabled) else r }
        }
    }

    fun applyEqPreset(receiverId: String, preset: EqPreset) {
        _receiversFlow.update { list ->
            list.map { r ->
                if (r.id == receiverId) {
                    val updatedBands = r.eqBands.mapIndexed { idx, band ->
                        band.copy(gainDb = preset.gainsDb.getOrElse(idx) { 0f })
                    }
                    r.copy(eqEnabled = true, eqPreset = preset, eqBands = updatedBands)
                } else r
            }
        }
    }

    fun setEqBandGain(receiverId: String, bandIndex: Int, gainDb: Float) {
        val clamped = gainDb.coerceIn(-12.0f, 12.0f)
        _receiversFlow.update { list ->
            list.map { r ->
                if (r.id == receiverId) {
                    val updated = r.eqBands.map { b -> if (b.index == bandIndex) b.copy(gainDb = clamped) else b }
                    r.copy(eqEnabled = true, eqBands = updated)
                } else r
            }
        }
        applyHardwareDynamicsProcessingBand(bandIndex, clamped)
    }

    /**
     * Applies 10-band Pre-EQ and Limiter stages via Android's DynamicsProcessing API (API 28 / Android 11+).
     */
    fun bindHardwareDynamicsProcessing(audioSessionId: Int) {
        try {
            dynamicsProcessing?.release()
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2, // Stereo channels
                true, 10, // 10-band Pre-EQ
                false, 0, // No MBC
                true, 10, // 10-band Post-EQ
                true      // Hardware limiter enabled
            ).build()
            dynamicsProcessing = DynamicsProcessing(0, audioSessionId, config).apply {
                enabled = true
            }
        } catch (_: Throwable) {
            // Hardware effect optional if session ID is 0
        }
    }

    private fun syncSinkDspToNative(receiver: ReceiverDeviceState) {
        NativeAudioEngine.nativeConfigureSinkDsp(
            sinkId = receiver.id,
            volumePercent = receiver.volumePercent,
            muted = receiver.isMuted,
            stereoToMono = receiver.stereoToMono,
            eqEnabled = receiver.eqEnabled,
            limiterEnabled = receiver.limiterEnabled
        )
        receiver.eqBands.forEach { band ->
            NativeAudioEngine.nativeSetSinkEqBandGain(receiver.id, band.index, band.gainDb)
        }
    }

    private fun applyHardwareDynamicsProcessingBand(bandIndex: Int, gainDb: Float) {
        val dp = dynamicsProcessing ?: return
        try {
            val band = dp.getPreEqBandByChannelIndex(0, bandIndex)
            band.isEnabled = true
            band.gain = gainDb
            dp.setPreEqBandAllChannelsTo(bandIndex, band)
        } catch (_: Throwable) {
        }
    }

    // ---- Speaker groups (saved on the phone so they survive restarts) ----
    private var groupPrefs: SharedPreferences? = null

    /** Called once at app start: loads saved groups. */
    fun initGroups(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("speaker_groups", Context.MODE_PRIVATE)
        groupPrefs = prefs
        val loaded = runCatching {
            val arr = JSONArray(prefs.getString("groups", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val ids = o.getJSONArray("receiverIds")
                QuickConnectGroup(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    receiverIds = (0 until ids.length()).map { ids.getString(it) }.toSet()
                )
            }
        }.getOrDefault(emptyList())
        _groupsFlow.value = loaded
    }

    private fun saveGroups(groups: List<QuickConnectGroup>) {
        val arr = JSONArray()
        groups.forEach { g ->
            arr.put(JSONObject().put("id", g.id).put("name", g.name).put("receiverIds", JSONArray(g.receiverIds.toList())))
        }
        groupPrefs?.edit()?.putString("groups", arr.toString())?.apply()
    }

    fun saveGroup(id: String?, name: String, receiverIds: Set<String>) {
        val cleanName = name.trim().ifBlank { "Group" }
        _groupsFlow.update { list ->
            val updated = if (id == null) {
                list + QuickConnectGroup("grp_${UUID.randomUUID().toString().take(8)}", cleanName, receiverIds)
            } else {
                list.map { if (it.id == id) it.copy(name = cleanName, receiverIds = receiverIds) else it }
            }
            saveGroups(updated)
            updated
        }
    }

    fun deleteGroup(id: String) {
        _groupsFlow.update { list ->
            val updated = list.filterNot { it.id == id }
            saveGroups(updated)
            updated
        }
    }

    fun activateQuickConnectGroup(group: QuickConnectGroup) {
        _receiversFlow.update { list ->
            list.map { r -> r.copy(isConnected = r.id in group.receiverIds) }
        }
    }

    fun disconnectAllReceivers() {
        _receiversFlow.update { list ->
            list.map { r -> r.copy(isConnected = false, isExpanded = false) }
        }
    }

    fun addManualAirPlayReceiver(name: String, ipAddress: String, port: Int, requiresAuthSetup: Boolean) {
        if (ipAddress.isBlank()) return
        val id = "airplay_manual_${UUID.randomUUID().toString().take(8)}"
        val newDevice = ReceiverDeviceState(
            id = id,
            name = name.ifBlank { "Manual AirPlay ($ipAddress)" },
            protocol = SinkProtocol.AIRPLAY,
            ipAddress = ipAddress,
            port = port,
            isConnected = true,
            isExpanded = true,
            delayOffsetMs = if (requiresAuthSetup) -120 else 0
        )
        _receiversFlow.update { list -> listOf(newDevice) + list }
    }


    fun upsertDiscoveredReceiver(
        id: String,
        name: String,
        protocol: SinkProtocol,
        ipAddress: String,
        port: Int,
        airplayEncryptionTypes: Set<Int> = emptySet(),
        avTransportControlUrl: String = "",
        renderingControlUrl: String = ""
    ) {
        _receiversFlow.update { currentList ->
            val existingIndex = currentList.indexOfFirst {
                it.id == id || (it.ipAddress == ipAddress && it.protocol == protocol)
            }
            if (existingIndex >= 0) {
                currentList.mapIndexed { idx, item ->
                    if (idx == existingIndex) item.copy(
                        name = name,
                        ipAddress = ipAddress,
                        port = port,
                        airplayEncryptionTypes = airplayEncryptionTypes.ifEmpty { item.airplayEncryptionTypes },
                        avTransportControlUrl = avTransportControlUrl.ifBlank { item.avTransportControlUrl },
                        renderingControlUrl = renderingControlUrl.ifBlank { item.renderingControlUrl }
                    ) else item
                }
            } else {
                currentList + ReceiverDeviceState(
                    id = id,
                    name = name,
                    protocol = protocol,
                    ipAddress = ipAddress,
                    port = port,
                    airplayEncryptionTypes = airplayEncryptionTypes,
                    avTransportControlUrl = avTransportControlUrl,
                    renderingControlUrl = renderingControlUrl
                )
            }
        }
    }

    fun updateSettings(transform: (GlobalSettingsState) -> GlobalSettingsState) {
        _settingsFlow.update(transform)
    }
}
