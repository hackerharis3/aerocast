package app.airmusic.modern.nativebridge

object NativeAudioEngine {
    const val FRAME_SIZE_BYTES = 1408       // 352 stereo 16-bit PCM samples @ 44,100 Hz
    const val ALAC_PACKET_SIZE_BYTES = 1412 // 352-frame AirPlay ALAC verbatim packet
    const val CODEC_MODE_RAW_PCM = 0
    const val CODEC_MODE_ALAC = 1
    const val CODEC_MODE_MP3_320 = 2

    init {
        System.loadLibrary("airmusic_audio_engine")
    }

    external fun nativeResetEngine()
    external fun nativePushPcmFrame(pcmChunk: ByteArray, length: Int)
    external fun nativeReadDelayedFrame(sequenceNumber: Long, effectiveDelayMs: Int, outBuffer: ByteArray): Boolean

    /**
     * Dequeues a delayed 352-sample frame for [sinkId], applies its 10-band biquad EQ,
     * stereo-to-mono downmix, volume curve, and soft limiter, and encodes into [codecMode].
     */
    external fun nativeReadProcessedSinkPacket(
        sinkId: String,
        sequenceNumber: Long,
        effectiveDelayMs: Int,
        codecMode: Int,
        outBuffer: ByteArray
    ): Int

    external fun nativeConfigureSinkDsp(
        sinkId: String,
        volumePercent: Int,
        muted: Boolean,
        stereoToMono: Boolean,
        eqEnabled: Boolean,
        limiterEnabled: Boolean
    )

    external fun nativeSetSinkEqBandGain(
        sinkId: String,
        bandIndex: Int,
        gainDb: Float
    )

    external fun nativeBuildEndlessWavHeader(sampleRate: Int = 44100, channels: Int = 2): ByteArray
    external fun nativeGenerateCalibrationChirpPcm(): ByteArray
    external fun nativeEstimateChirpLatencyMs(micMonoPcm: ShortArray): Int

    external fun nativeGetWriteSequence(): Long
    external fun nativeGetLatestRmsDb(): Float
    external fun nativeGetLastNonSilentTimestampMs(): Long
}
