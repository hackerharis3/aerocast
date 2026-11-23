package app.airmusic.modern.model

enum class AudioCodecProfile(val displayName: String, val bitrateInfo: String) {
    ALAC("ALAC", "Lossless 16-bit 44.1kHz (352f)"),
    WAV("WAV", "Uncompressed PCM 1411 kbps"),
    LPCM("LPCM", "Raw Stereo 16-bit 1411 kbps"),
    MP3_320("MP3 320k", "LAME CBR 320 kbps"),
    OPUS("Opus", "Ultra-Low Latency 192 kbps"),
    FLAC("FLAC", "Lossless ~700 kbps")
}

enum class SinkProtocol(
    val badgeTitle: String,
    val defaultPort: Int,
    val supportedCodecs: List<AudioCodecProfile>
) {
    AIRPLAY("AirPlay", 7000, listOf(AudioCodecProfile.ALAC)),
    CHROMECAST("Google Cast", 8009, listOf(AudioCodecProfile.WAV, AudioCodecProfile.MP3_320, AudioCodecProfile.OPUS, AudioCodecProfile.FLAC)),
    SONOS("SONOS", 1400, listOf(AudioCodecProfile.MP3_320, AudioCodecProfile.WAV, AudioCodecProfile.FLAC)),
    DLNA("DLNA / UPnP", 49152, listOf(AudioCodecProfile.WAV, AudioCodecProfile.LPCM, AudioCodecProfile.MP3_320, AudioCodecProfile.FLAC)),
    DENON_HEOS("Denon HEOS", 60006, listOf(AudioCodecProfile.WAV, AudioCodecProfile.MP3_320)),
    FIRE_TV("Amazon Fire TV", 8008, listOf(AudioCodecProfile.WAV, AudioCodecProfile.MP3_320)),
    ROKU("Roku ECP", 8060, listOf(AudioCodecProfile.WAV)),
    SAMSUNG_MULTIROOM("Samsung MSF", 8001, listOf(AudioCodecProfile.WAV, AudioCodecProfile.MP3_320)),
    SNAPCAST("Snapcast", 1704, listOf(AudioCodecProfile.WAV, AudioCodecProfile.FLAC, AudioCodecProfile.OPUS)),
    HTTP_WEBSOCKET("Browser / WebSocket", 8080, listOf(AudioCodecProfile.OPUS, AudioCodecProfile.MP3_320, AudioCodecProfile.WAV)),
    FILE_MP3_RECORDER("SAF Recorder", 0, listOf(AudioCodecProfile.MP3_320, AudioCodecProfile.FLAC))
}

data class EqualizerBand(
    val index: Int,
    val centerFreqHz: Int,
    val label: String,
    val gainDb: Float = 0.0f // -12.0 dB to +12.0 dB
)

enum class EqPreset(val displayName: String, val gainsDb: List<Float>) {
    FLAT("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    BASS_BOOST("Bass Boost", listOf(6.5f, 5.5f, 4.0f, 1.5f, 0f, 0f, 0f, 0.5f, 1.0f, 1.5f)),
    VOCAL_CLARITY("Vocal Clarity", listOf(-2.0f, -1.5f, 0f, 2.0f, 4.5f, 5.0f, 3.5f, 1.5f, 0f, -1.0f)),
    LOUDNESS("Loudness V-Curve", listOf(5.0f, 4.0f, 2.0f, 0f, -1.5f, -1.0f, 1.0f, 3.0f, 4.5f, 5.5f)),
    NIGHT_LIMITER("Night Mode", listOf(-4.5f, -3.5f, -1.5f, 1.0f, 2.5f, 2.5f, 1.0f, -1.0f, -2.5f, -3.5f))
}

data class ReceiverDeviceState(
    val id: String,
    val name: String,
    val alias: String = "",
    val protocol: SinkProtocol,
    val ipAddress: String,
    val port: Int = protocol.defaultPort,
    val isConnected: Boolean = false,
    val isExpanded: Boolean = false,
    val volumePercent: Int = 65,
    val isMuted: Boolean = false,
    val delayOffsetMs: Int = 0, // -5000 ms to +5000 ms (10 ms step)
    val activeCodec: AudioCodecProfile = protocol.supportedCodecs.first(),
    val stereoToMono: Boolean = false,
    val eqEnabled: Boolean = false,
    val limiterEnabled: Boolean = true,
    val eqPreset: EqPreset = EqPreset.FLAT,
    val eqBands: List<EqualizerBand> = defaultTenBandEq(),
    // AirPlay "et" TXT value: 0 = none, 1 = RSA, 3 = FairPlay, 4 = MFi-SAP. Empty = unknown.
    val airplayEncryptionTypes: Set<Int> = emptySet(),
    // UPnP control paths read from the device description (blank = use controller defaults).
    val avTransportControlUrl: String = "",
    val renderingControlUrl: String = ""
) {
    val displayTitle: String get() = alias.ifBlank { name }
}

data class QuickConnectGroup(
    val id: String,
    val name: String,
    val receiverIds: Set<String>
)

data class GlobalSettingsState(
    val autoDisconnectSilenceMinutes: Int = 5,
    val adaptiveBitrateOnLowWifi: Boolean = true,
    val exposeUpnpMediaServerXboxMode: Boolean = false,
    val keepLocalStreamVolumeMax: Boolean = true,
    val appLocaleTag: String = "en"
)

fun defaultTenBandEq(): List<EqualizerBand> {
    val freqs = listOf(
        31 to "31",
        62 to "62",
        125 to "125",
        250 to "250",
        500 to "500",
        1000 to "1k",
        2000 to "2k",
        4000 to "4k",
        8000 to "8k",
        16000 to "16k"
    )
    return freqs.mapIndexed { idx, (hz, lbl) ->
        EqualizerBand(index = idx, centerFreqHz = hz, label = lbl, gainDb = 0.0f)
    }
}
