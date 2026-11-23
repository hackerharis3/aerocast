package app.airmusic.modern.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.airmusic.modern.nativebridge.NativeAudioEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 3 Mic-Assisted Acoustic Auto-Chirp Calibration Coordinator:
 * Emits the 20-frame (159.6 ms) 1.0–4.5 kHz linear chirp into the ring buffer,
 * records 1.0 second of mono 44.1 kHz microphone audio, and calls C++ matched-filter
 * cross-correlation to compute the speaker's acoustic delay offset in milliseconds.
 */
object AutoChirpCalibrationManager {

    private const val SAMPLE_RATE_HZ = 44100
    private const val RECORD_WINDOW_SAMPLES = 44100 // 1.0 second window

    @SuppressLint("MissingPermission")
    suspend fun measureSpeakerDelayOffsetMs(): Int = withContext(Dispatchers.IO) {
        val chirpBytes = NativeAudioEngine.nativeGenerateCalibrationChirpPcm()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(RECORD_WINDOW_SAMPLES * 2)

        val micRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf
        )

        if (micRecord.state != AudioRecord.STATE_INITIALIZED) {
            micRecord.release()
            return@withContext -180 // Fallback baseline if mic is busy
        }

        try {
            micRecord.startRecording()

            // Push the 20 chirp frames (20 * 1,408 = 28,160 bytes) into the live native ring buffer
            var offset = 0
            val frameSlice = ByteArray(NativeAudioEngine.FRAME_SIZE_BYTES)
            while (offset + NativeAudioEngine.FRAME_SIZE_BYTES <= chirpBytes.size) {
                System.arraycopy(chirpBytes, offset, frameSlice, 0, NativeAudioEngine.FRAME_SIZE_BYTES)
                NativeAudioEngine.nativePushPcmFrame(frameSlice, NativeAudioEngine.FRAME_SIZE_BYTES)
                offset += NativeAudioEngine.FRAME_SIZE_BYTES
            }

            // Capture 1.0 second of room audio from the microphone
            val micSamples = ShortArray(RECORD_WINDOW_SAMPLES)
            var samplesRead = 0
            while (samplesRead < RECORD_WINDOW_SAMPLES) {
                val r = micRecord.read(micSamples, samplesRead, RECORD_WINDOW_SAMPLES - samplesRead)
                if (r <= 0) break
                samplesRead += r
            }

            return@withContext NativeAudioEngine.nativeEstimateChirpLatencyMs(micSamples)
        } finally {
            try {
                micRecord.stop()
            } catch (_: IllegalStateException) {
            }
            micRecord.release()
        }
    }
}
