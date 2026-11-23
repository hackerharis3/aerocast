package app.airmusic.modern.sinks

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.airmusic.modern.model.MediaMetadataState
import app.airmusic.modern.model.ReceiverDeviceState
import app.airmusic.modern.nativebridge.NativeAudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Clean-room Android 11+ Storage Access Framework (SAF) 320 kbps MP3 File Recorder
 * replacing AirMusic's v1.a & v1.b (FileStorageSink) without requiring legacy WRITE_EXTERNAL_STORAGE.
 */
class SafMp3RecorderSinkController(
    override val receiverId: String,
    private val context: Context,
    private val scope: CoroutineScope,
    private val treeUriProvider: () -> Uri?
) : SinkController {

    private var recordingJob: Job? = null
    private var activeStream: OutputStream? = null

    override suspend fun connect(
        receiver: ReceiverDeviceState,
        metadata: MediaMetadataState
    ): Boolean = withContext(Dispatchers.IO) {
        val treeUri = treeUriProvider() ?: return@withContext false
        val resolver = context.contentResolver

        val docTreeId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docTreeId)
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss"))
        val fileName = "Aerocast-$timestamp.mp3"

        val targetFileUri = DocumentsContract.createDocument(
            resolver,
            parentDocUri,
            "audio/mpeg",
            fileName
        ) ?: return@withContext false

        val outStream = resolver.openOutputStream(targetFileUri, "w") ?: return@withContext false
        activeStream = outStream

        recordingJob = scope.launch(Dispatchers.IO) {
            var readSeq = NativeAudioEngine.nativeGetWriteSequence()
            val mp3Buf = ByteArray(2048)

            try {
                while (isActive) {
                    val bytes = NativeAudioEngine.nativeReadProcessedSinkPacket(
                        sinkId = receiver.id,
                        sequenceNumber = readSeq,
                        effectiveDelayMs = 0,
                        codecMode = NativeAudioEngine.CODEC_MODE_MP3_320,
                        outBuffer = mp3Buf
                    )
                    if (bytes > 0) {
                        outStream.write(mp3Buf, 0, bytes)
                        readSeq++
                    } else {
                        delay(4)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                try {
                    outStream.flush()
                    outStream.close()
                } catch (_: Throwable) {
                }
            }
        }
        true
    }

    override suspend fun disconnect(receiver: ReceiverDeviceState) {
        recordingJob?.cancel()
        recordingJob = null
        withContext(Dispatchers.IO) {
            try {
                activeStream?.flush()
                activeStream?.close()
            } catch (_: Throwable) {
            }
            activeStream = null
        }
    }

    override suspend fun setVolume(receiver: ReceiverDeviceState, volumePercent: Int) {}
    override suspend fun setMute(receiver: ReceiverDeviceState, muted: Boolean) {}
    override suspend fun updateMetadata(receiver: ReceiverDeviceState, metadata: MediaMetadataState) {}
}
