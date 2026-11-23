package app.airmusic.modern.service

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.airmusic.modern.AirMusicModernApp
import app.airmusic.modern.capture.PerAppAudioRecorder
import app.airmusic.modern.model.AppUidFilterMode
import app.airmusic.modern.model.AudioCaptureSessionConfig
import app.airmusic.modern.model.AudioEngineTelemetry
import app.airmusic.modern.model.CaptureSourceMode
import app.airmusic.modern.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Clean-room replacement for AirMusic's NotificationService + StreamControlBroadcastReceiver.
 * Uses in-process StateFlows instead of exported BroadcastReceivers (remediating SEC-02)
 * and promotes foregroundServiceType properly for Android 11 (API 30) through Android 15 (API 35).
 */
class AudioCaptureForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var recorder: PerAppAudioRecorder
    private var mediaProjection: MediaProjection? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        recorder = PerAppAudioRecorder(
            context = applicationContext,
            scope = serviceScope,
            onTelemetryUpdate = { frames, rmsDb, lastActiveMs ->
                _telemetryFlow.value = _telemetryFlow.value.copy(
                    isCapturing = true,
                    framesCaptured = frames,
                    latestRmsDb = rmsDb,
                    lastNonSilentTimestampMs = lastActiveMs
                )
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_PROJECTION_CAPTURE -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    // Android 11+ requirement: enter foreground with MEDIA_PROJECTION type BEFORE getMediaProjection()
                    promoteToForeground(
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                    acquireLocks()

                    val projectionManager = getSystemService(MediaProjectionManager::class.java)
                    mediaProjection?.stop()
                    val projection = projectionManager.getMediaProjection(resultCode, resultData)
                    projection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            stopCaptureAndSelf()
                        }
                    }, null)
                    mediaProjection = projection

                    val config = _sessionConfigFlow.value.copy(
                        sourceMode = CaptureSourceMode.NATIVE_PLAYBACK_CAPTURE
                    )
                    _sessionConfigFlow.value = config
                    val started = recorder.startCapture(config, projection)
                    _telemetryFlow.value = _telemetryFlow.value.copy(
                        isCapturing = started,
                        activeSourceMode = CaptureSourceMode.NATIVE_PLAYBACK_CAPTURE
                    )
                }
            }
            ACTION_START_MIC_CAPTURE -> {
                promoteToForeground(
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
                acquireLocks()
                val config = _sessionConfigFlow.value.copy(
                    sourceMode = CaptureSourceMode.MICROPHONE
                )
                _sessionConfigFlow.value = config
                val started = recorder.startCapture(config, null)
                _telemetryFlow.value = _telemetryFlow.value.copy(
                    isCapturing = started,
                    activeSourceMode = CaptureSourceMode.MICROPHONE
                )
            }
            ACTION_STOP_CAPTURE -> {
                stopCaptureAndSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground(foregroundServiceType: Int) {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, AudioCaptureForegroundService::class.java).apply {
            action = ACTION_STOP_CAPTURE
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val metadata = MediaSessionMetadataListener.metadataFlow.value
        val notification: Notification = NotificationCompat.Builder(this, AirMusicModernApp.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(app.airmusic.modern.R.drawable.ic_stat_airmusic)
            .setContentTitle("Casting: ${metadata.title}")
            .setContentText("${metadata.artist} • 44.1 kHz Stereo (352-frame sync)")
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_pause, "Disconnect", pendingStop)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundServiceType
        )
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirMusicModern::AudioCaptureWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (multicastLock == null) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            multicastLock = wm.createMulticastLock("AirMusicModern::MulticastDiscoveryLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    private fun stopCaptureAndSelf() {
        recorder.stopCapture()
        mediaProjection?.stop()
        mediaProjection = null
        releaseLocks()
        _telemetryFlow.value = AudioEngineTelemetry(isCapturing = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        recorder.stopCapture()
        mediaProjection?.stop()
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_PROJECTION_CAPTURE = "app.airmusic.modern.action.START_PROJECTION_CAPTURE"
        const val ACTION_START_MIC_CAPTURE = "app.airmusic.modern.action.START_MIC_CAPTURE"
        const val ACTION_STOP_CAPTURE = "app.airmusic.modern.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private val _telemetryFlow = MutableStateFlow(AudioEngineTelemetry())
        val telemetryFlow: StateFlow<AudioEngineTelemetry> = _telemetryFlow.asStateFlow()

        private val _sessionConfigFlow = MutableStateFlow(AudioCaptureSessionConfig())
        val sessionConfigFlow: StateFlow<AudioCaptureSessionConfig> = _sessionConfigFlow.asStateFlow()

        fun updateUidFilter(mode: AppUidFilterMode, targetUids: Set<Int>) {
            _sessionConfigFlow.value = _sessionConfigFlow.value.copy(
                uidFilterMode = mode,
                targetAppUids = targetUids
            )
        }

        fun startProjectionCapture(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, AudioCaptureForegroundService::class.java).apply {
                action = ACTION_START_PROJECTION_CAPTURE
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            context.startForegroundService(intent)
        }

        fun stopCapture(context: Context) {
            val intent = Intent(context, AudioCaptureForegroundService::class.java).apply {
                action = ACTION_STOP_CAPTURE
            }
            context.startService(intent)
        }
    }
}
