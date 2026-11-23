package app.airmusic.modern.wear

import app.airmusic.modern.service.MediaSessionMetadataListener
import app.airmusic.modern.state.MultiRoomStateRepository
import org.json.JSONObject

/**
 * Phase 7 Wear OS Companion Message & Tile State Bridge.
 *
 * Processes wrist commands sent from the Wear OS Tile / Watch App over the Wearable Data Layer
 * (paths "/airmusic/wear/toggle_group", "/airmusic/wear/volume_delta", "/airmusic/wear/mute_all")
 * and serializes compact JSON state payloads for the watch tile.
 */
object WearCompanionBridge {

    const val PATH_TOGGLE_GROUP = "/airmusic/wear/toggle_group"
    const val PATH_VOLUME_DELTA = "/airmusic/wear/volume_delta"
    const val PATH_STATE_SNAPSHOT = "/airmusic/wear/state_snapshot"

    fun handleWatchCommand(path: String, payloadUtf8: String): String {
        when (path) {
            PATH_TOGGLE_GROUP -> {
                val anyActive = MultiRoomStateRepository.receiversFlow.value.any { it.isConnected }
                if (anyActive) {
                    MultiRoomStateRepository.disconnectAllReceivers()
                } else {
                    MultiRoomStateRepository.groupsFlow.value.firstOrNull()?.let { group ->
                        MultiRoomStateRepository.activateQuickConnectGroup(group)
                    }
                }
            }
            PATH_VOLUME_DELTA -> {
                val delta = payloadUtf8.toIntOrNull() ?: 0
                if (delta != 0) {
                    MultiRoomStateRepository.setMasterVolumeDelta(delta)
                }
            }
        }
        return buildWatchTileSnapshotJson()
    }

    fun buildWatchTileSnapshotJson(): String {
        val meta = MediaSessionMetadataListener.metadataFlow.value
        val active = MultiRoomStateRepository.receiversFlow.value.filter { it.isConnected }
        val avgVolume = if (active.isNotEmpty()) active.map { it.volumePercent }.average().toInt() else 0

        return JSONObject().apply {
            put("activeCount", active.size)
            put("roomSummary", if (active.isNotEmpty()) active.joinToString(", ") { it.displayTitle } else "Idle")
            put("trackTitle", meta.title)
            put("trackArtist", meta.artist)
            put("masterVolume", avgVolume)
        }.toString()
    }
}
