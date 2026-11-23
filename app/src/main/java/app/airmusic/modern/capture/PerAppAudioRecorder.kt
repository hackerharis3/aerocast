package app.airmusic.modern.capture

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Process
import app.airmusic.modern.model.AppUidFilterMode
import app.airmusic.modern.model.AudioCaptureSessionConfig
import app.airmusic.modern.model.CaptureSourceMode
import app.airmusic.modern.nativebridge.NativeAudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Clean-room replacement for AirMusic's r1.b (NativeRecorder) and r1.a (MicRecorder).
 * Adds Android 11+ per-app UID audio isolation (addMatchingUid / excludeUid) and feeds
 * exact 1,408-byte (352-sample) stereo 16-bit PCM frames into NativeAudioEngine.
 */
class PerAppAudioRecorder(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onTelemetryUpdate: (frames: Long, rmsDb: Float, lastActiveMs: Long) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var savedMusicStreamVolume: Int = -1

    @SuppressLint("MissingPermission")
    fun startCapture(
        config: AudioCaptureSessionConfig,
        mediaProjection: MediaProjection?
    ): Boolean {
        stopCapture()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE_HZ)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(NativeAudioEngine.FRAME_SIZE_BYTES) * BUFFER_MULTIPLIER

        val recordInstance = if (config.sourceMode == CaptureSourceMode.NATIVE_PLAYBACK_CAPTURE && mediaProjection != null) {
            val captureConfig = buildPlaybackCaptureConfig(mediaProjection, config)
            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufferSize)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } else {
            AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize
            )
        }

        if (recordInstance.state != AudioRecord.STATE_INITIALIZED) {
            recordInstance.release()
            return false
        }

        NativeAudioEngine.nativeResetEngine()

        if (config.sourceMode == CaptureSourceMode.NATIVE_PLAYBACK_CAPTURE && config.muteLocalSpeakerDuringCapture) {
            muteLocalSpeaker()
        }

        audioRecord = recordInstance
        recordInstance.startRecording()

        captureJob = scope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val frameBuffer = ByteArray(NativeAudioEngine.FRAME_SIZE_BYTES)
            var totalFrames = 0L

            while (currentCoroutineContext().isActive) {
                var offset = 0
                while (offset < NativeAudioEngine.FRAME_SIZE_BYTES && currentCoroutineContext().isActive) {
                    val read = recordInstance.read(
                        frameBuffer,
                        offset,
                        NativeAudioEngine.FRAME_SIZE_BYTES - offset
                    )
                    if (read <= 0) break
                    offset += read
                }

                if (offset == NativeAudioEngine.FRAME_SIZE_BYTES) {
                    NativeAudioEngine.nativePushPcmFrame(frameBuffer, NativeAudioEngine.FRAME_SIZE_BYTES)
                    totalFrames++
                    if (totalFrames % 12L == 0L) { // ~10fps UI telemetry update
                        onTelemetryUpdate(
                            totalFrames,
                            NativeAudioEngine.nativeGetLatestRmsDb(),
                            NativeAudioEngine.nativeGetLastNonSilentTimestampMs()
                        )
                    }
                }
            }
        }
        return true
    }

    private fun buildPlaybackCaptureConfig(
        mediaProjection: MediaProjection,
        config: AudioCaptureSessionConfig
    ): AudioPlaybackCaptureConfiguration {
        val currentAppUid = Process.myUid()
        val builder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)

        // Note: Android API enforces that addMatchingUid and excludeUid are mutually exclusive.
        when (config.uidFilterMode) {
            AppUidFilterMode.CAPTURE_ALL_MEDIA -> {
                builder.excludeUid(currentAppUid)
            }
            AppUidFilterMode.INCLUDE_ONLY_SELECTED_UIDS -> {
                val validUids = config.targetAppUids.filter { it != currentAppUid }
                if (validUids.isEmpty()) {
                    builder.excludeUid(currentAppUid)
                } else {
                    validUids.forEach { uid -> builder.addMatchingUid(uid) }
                }
            }
            AppUidFilterMode.EXCLUDE_SELECTED_UIDS -> {
                builder.excludeUid(currentAppUid)
                config.targetAppUids.forEach { uid -> builder.excludeUid(uid) }
            }
        }
        return builder.build()
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        try {
            audioRecord?.stop()
        } catch (_: IllegalStateException) {
        }
        audioRecord?.release()
        audioRecord = null
        restoreLocalSpeaker()
    }

    private fun muteLocalSpeaker() {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return
        if (savedMusicStreamVolume == -1) {
            savedMusicStreamVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        }
    }

    private fun restoreLocalSpeaker() {
        if (savedMusicStreamVolume != -1) {
            val audioManager = context.getSystemService(AudioManager::class.java)
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicStreamVolume, 0)
            savedMusicStreamVolume = -1
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 44100
        private const val BUFFER_MULTIPLIER = 10
    }
}
