package app.airmusic.modern.sinks.airplay

import android.content.Context
import android.media.AudioManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import android.view.KeyEvent
import app.airmusic.modern.state.MultiRoomStateRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Clean-room AirPlay DACP (Digital Audio Control Protocol) Reverse Remote Server
 * replacing AirMusic's app.airmusic.sinks.airplay.f (NanoHTTPD).
 *
 * Advertises "_dacp._tcp." via Android NsdManager and listens on "/ctrl-int/1/{command}"
 * so hardware play, pause, next, previous, volume, and mute buttons on an AirPlay speaker
 * control the active Android music app via AudioManager.dispatchMediaKeyEvent.
 */
class AirPlayDacpRemoteServer(
    private val context: Context,
    private val dacpIdHex: String,
    private val listenPort: Int = 3689
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun startDacpServer() {
        if (server != null) return

        server = embeddedServer(CIO, port = listenPort, host = "0.0.0.0") {
            routing {
                get("/ctrl-int/1/{command}") {
                    val cmd = call.parameters["command"]?.lowercase().orEmpty()
                    when (cmd) {
                        "play" -> dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                        "pause" -> dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                        "playpause" -> dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                        "stop" -> dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_STOP)
                        "nextitem" -> dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                        "previtem" -> dispatchSystemMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                        "volumeup" -> MultiRoomStateRepository.setMasterVolumeDelta(+5)
                        "volumedown" -> MultiRoomStateRepository.setMasterVolumeDelta(-5)
                    }
                    call.respondText("", ContentType.Text.Plain, HttpStatusCode.NoContent)
                }
            }
        }.start(wait = false)

        registerMdnsDacpService()
    }

    fun stopDacpServer() {
        unregisterMdnsDacpService()
        server?.stop(300, 600)
        server = null
    }

    private fun dispatchSystemMediaKey(keyCode: Int) {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        val now = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun registerMdnsDacpService() {
        try {
            val nsd = context.getSystemService(NsdManager::class.java) ?: return
            val info = NsdServiceInfo().apply {
                serviceName = "iTunes_Ctrl_$dacpIdHex"
                serviceType = "_dacp._tcp."
                port = listenPort
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
            registrationListener = listener
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Throwable) {
        }
    }

    private fun unregisterMdnsDacpService() {
        val listener = registrationListener ?: return
        try {
            val nsd = context.getSystemService(NsdManager::class.java)
            nsd?.unregisterService(listener)
        } catch (_: Throwable) {
        }
        registrationListener = null
    }
}
