package app.airmusic.modern.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * AAC-LC encoder for 44.1 kHz 16-bit stereo PCM, using Android's built-in MediaCodec
 * (no bundled third-party encoder, no licensing issues).
 *
 * Feed 1,408-byte PCM frames with [encode]; encoded AAC frames arrive through the callback.
 * [onFormat] fires once with the codec's output format (needed by MediaMuxer for .m4a files).
 */
class AacEncoder(
    bitRate: Int = 256_000,
    private val onFormat: (MediaFormat) -> Unit = {},
    private val onFrame: (data: ByteBuffer, info: MediaCodec.BufferInfo) -> Unit
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private var framesQueued = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Queue one block of interleaved 16-bit PCM and pass on any finished AAC frames. */
    fun encode(pcm: ByteArray, length: Int) {
        val inIndex = codec.dequeueInputBuffer(20_000)
        if (inIndex >= 0) {
            val buf = codec.getInputBuffer(inIndex) ?: return
            buf.clear()
            buf.put(pcm, 0, length)
            val ptsUs = framesQueued * 1_000_000L / SAMPLE_RATE
            framesQueued += length / (2 * CHANNELS)
            codec.queueInputBuffer(inIndex, 0, length, ptsUs, 0)
        }
        drain(endOfStream = false)
    }

    /** Flush remaining frames (used before closing a recording). */
    fun finish() {
        val inIndex = codec.dequeueInputBuffer(20_000)
        if (inIndex >= 0) {
            codec.queueInputBuffer(inIndex, 0, 0, framesQueued * 1_000_000L / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        drain(endOfStream = true)
    }

    private fun drain(endOfStream: Boolean) {
        var waits = 0
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, if (endOfStream) 20_000 else 0)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormat(codec.outputFormat)
                outIndex >= 0 -> {
                    val out = codec.getOutputBuffer(outIndex)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (out != null && info.size > 0 && !isConfig) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        onFrame(out, info)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // Normal operation: nothing more ready yet. At end of stream, wait (bounded) for the last frames.
                    if (!endOfStream || ++waits > 50) return
                }
            }
        }
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2

        /** 7-byte ADTS header so raw AAC frames can be streamed/played without a container. */
        fun adtsHeader(aacFrameLength: Int): ByteArray {
            val fullLength = aacFrameLength + 7
            val profile = 2          // AAC LC
            val freqIdx = 4          // 44.1 kHz
            val chanCfg = 2          // stereo
            return byteArrayOf(
                0xFF.toByte(),
                0xF1.toByte(),
                (((profile - 1) shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte(),
                (((chanCfg and 3) shl 6) + (fullLength shr 11)).toByte(),
                ((fullLength and 0x7FF) shr 3).toByte(),
                (((fullLength and 7) shl 5) + 0x1F).toByte(),
                0xFC.toByte()
            )
        }
    }
}
