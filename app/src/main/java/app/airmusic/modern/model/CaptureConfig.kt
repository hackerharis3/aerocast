package app.airmusic.modern.model

import android.graphics.Bitmap

enum class CaptureSourceMode {
    NATIVE_PLAYBACK_CAPTURE, // Android 10/11+ AudioPlaybackCaptureConfiguration
    MICROPHONE               // 44.1 kHz Stereo AudioRecord fallback
}

enum class AppUidFilterMode(val label: String) {
    CAPTURE_ALL_MEDIA("All apps"),
    INCLUDE_ONLY_SELECTED_UIDS("Only selected apps"),
    EXCLUDE_SELECTED_UIDS("All except selected")
}

data class AudioCaptureSessionConfig(
    val sourceMode: CaptureSourceMode = CaptureSourceMode.NATIVE_PLAYBACK_CAPTURE,
    val uidFilterMode: AppUidFilterMode = AppUidFilterMode.CAPTURE_ALL_MEDIA,
    val targetAppUids: Set<Int> = emptySet(),
    val muteLocalSpeakerDuringCapture: Boolean = true
)

data class MediaMetadataState(
    val title: String = "Aerocast Live Stream",
    val artist: String = "System Audio",
    val album: String = "",
    val sourcePackage: String = "",
    val artwork: Bitmap? = null,
    val paletteSeedArgb: Int? = null
)

data class AudioEngineTelemetry(
    val isCapturing: Boolean = false,
    val activeSourceMode: CaptureSourceMode = CaptureSourceMode.NATIVE_PLAYBACK_CAPTURE,
    val framesCaptured: Long = 0L,
    val latestRmsDb: Float = -96.0f,
    val lastNonSilentTimestampMs: Long = 0L
)
