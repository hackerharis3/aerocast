package app.airmusic.modern

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class AirMusicModernApp : Application() {

    override fun onCreate() {
        super.onCreate()
        app.airmusic.modern.ui.ThemePreferences.applySaved(this)
        createNotificationChannel()
        // Starts discovery (mDNS + SSDP), the Ktor stream server, DACP, Snapcast and sink wiring.
        AirMusicRuntimeCoordinator.initialize(this)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Aerocast live audio casting",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent multi-room wireless audio capture and volume controls"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "airmusic_modern_casting_channel"
    }
}
