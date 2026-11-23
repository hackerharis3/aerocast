package app.airmusic.modern.server

import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writeFully
import java.nio.charset.StandardCharsets

/**
 * Clean-room replacement for AirMusic's b2.b (IcyOutputStream).
 * Injects Shoutcast/Icecast StreamTitle='<Artist> - <Title>'; blocks every 8,192 audio bytes
 * when a DLNA or SONOS renderer requests header "Icy-MetaData: 1".
 */
class IcyMetadataChunkWriter(
    private val outputChannel: ByteWriteChannel,
    private val metaIntervalBytes: Int = DEFAULT_ICY_METAINT
) {
    private var bytesUntilNextMeta = metaIntervalBytes
    private var lastSentStreamTitle = ""

    suspend fun writeAudioWithIcyMetadata(
        audioBytes: ByteArray,
        length: Int,
        currentArtist: String,
        currentTitle: String
    ) {
        var offset = 0
        var remaining = length

        while (remaining > 0) {
            if (bytesUntilNextMeta == 0) {
                writeIcyMetadataBlock(currentArtist, currentTitle)
                bytesUntilNextMeta = metaIntervalBytes
            }

            val chunk = minOf(remaining, bytesUntilNextMeta)
            outputChannel.writeFully(audioBytes, offset, chunk)
            offset += chunk
            remaining -= chunk
            bytesUntilNextMeta -= chunk
        }
    }

    private suspend fun writeIcyMetadataBlock(artist: String, title: String) {
        val combined = "$artist - $title".replace("'", "")
        if (combined == lastSentStreamTitle) {
            // Send 1-byte zero length marker when metadata has not changed
            outputChannel.writeByte(0)
            return
        }
        lastSentStreamTitle = combined

        val rawMeta = "StreamTitle='$combined';".toByteArray(StandardCharsets.UTF_8)
        val blocksOf16 = ((rawMeta.size + 15) / 16).coerceAtMost(255)
        val paddedSize = blocksOf16 * 16
        val block = ByteArray(1 + paddedSize)
        block[0] = blocksOf16.toByte()
        System.arraycopy(rawMeta, 0, block, 1, minOf(rawMeta.size, paddedSize))
        outputChannel.writeFully(block, 0, block.size)
    }

    companion object {
        const val DEFAULT_ICY_METAINT = 8192
    }
}
