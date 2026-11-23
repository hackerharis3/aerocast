package app.airmusic.modern.service

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import androidx.palette.graphics.Palette
import app.airmusic.modern.model.MediaMetadataState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Clean-room single-process MediaSession watcher replacing AirMusic's :notificationListenerService
 * and temporary cache/artwork*.tmp PNG files with an in-memory StateFlow + Palette color extraction
 * for Android 11 (API 30)+ album-art UI theming.
 */
class MediaSessionMetadataListener : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private var activeControllers: List<MediaController> = emptyList()

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        registerControllerCallbacks(controllers.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateActiveMetadata()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val manager = getSystemService(MediaSessionManager::class.java)
        sessionManager = manager
        val component = ComponentName(this, MediaSessionMetadataListener::class.java)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
            registerControllerCallbacks(manager.getActiveSessions(component))
        } catch (_: SecurityException) {
            // Notification access not yet granted by user in Settings
        }
    }

    override fun onListenerDisconnected() {
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        activeControllers.forEach { it.unregisterCallback(controllerCallback) }
        activeControllers = emptyList()
        super.onListenerDisconnected()
    }

    private fun registerControllerCallbacks(controllers: List<MediaController>) {
        activeControllers.forEach { it.unregisterCallback(controllerCallback) }
        activeControllers = controllers
        controllers.forEach { it.registerCallback(controllerCallback) }
        updateActiveMetadata()
    }

    private fun updateActiveMetadata() {
        val controller = activeControllers.firstOrNull { it.metadata != null } ?: return
        val meta = controller.metadata ?: return

        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?.takeIf { it.isNotBlank() } ?: "AirMusic Live Stream"
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() } ?: "System Audio"
        val album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val artwork = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)

        // Extract dominant/vibrant color via androidx.palette for Android 11+ dynamic card tinting
        val seedColor = artwork?.let { bmp ->
            val palette = Palette.from(bmp).maximumColorCount(16).generate()
            palette.getVibrantColor(palette.getDominantColor(DEFAULT_SEED_COLOR))
        }

        _metadataFlow.value = MediaMetadataState(
            title = title,
            artist = artist,
            album = album,
            sourcePackage = controller.packageName,
            artwork = artwork,
            paletteSeedArgb = seedColor
        )
    }

    companion object {
        private const val DEFAULT_SEED_COLOR = 0xFFFF5722.toInt()
        private val _metadataFlow = MutableStateFlow(MediaMetadataState())
        val metadataFlow: StateFlow<MediaMetadataState> = _metadataFlow.asStateFlow()
    }
}
