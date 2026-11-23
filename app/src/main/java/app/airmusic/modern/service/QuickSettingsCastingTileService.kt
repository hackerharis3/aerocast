package app.airmusic.modern.service

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.airmusic.modern.state.MultiRoomStateRepository

/**
 * Clean-room single-process Quick Settings TileService replacing AirMusic's
 * app.airmusic.services.TileService (:tileService process + PING/PONG broadcasts).
 */
class QuickSettingsCastingTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        val receivers = MultiRoomStateRepository.receiversFlow.value
        val hasConnected = receivers.any { it.isConnected }

        if (hasConnected) {
            MultiRoomStateRepository.disconnectAllReceivers()
        } else {
            MultiRoomStateRepository.groupsFlow.value.firstOrNull()?.let { defaultGroup ->
                MultiRoomStateRepository.activateQuickConnectGroup(defaultGroup)
            }
        }
        refreshTileState()
    }

    private fun refreshTileState() {
        val tile = qsTile ?: return
        val activeReceivers = MultiRoomStateRepository.receiversFlow.value.filter { it.isConnected }
        if (activeReceivers.isNotEmpty()) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "AirMusic (${activeReceivers.size})"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = activeReceivers.joinToString(", ") { it.displayTitle }
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "AirMusic Cast"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Tap to Quick-Connect"
            }
        }
        tile.updateTile()
    }
}
