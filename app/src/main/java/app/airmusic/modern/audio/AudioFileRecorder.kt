package app.airmusic.modern.audio

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaMuxer
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import app.airmusic.modern.nativebridge.NativeAudioEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Records the captured phone audio to an AAC .m4a file in a folder the user picked
 * (Storage Access Framework, so no storage permission is needed).
 */
object AudioFileRecorder {

    data class State(
        val isRecording: Boolean = false,
        val fileName: String = "",
        val seconds: Long = 0,
        val error: String? = null
    )

    private const val TAG = "AerocastRecorder"
    private const val PREFS = "recorder"
    private const val KEY_FOLDER = "folder_uri"
    private const val SINK_ID = "file_recorder"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun savedFolder(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FOLDER, null)?.let(Uri::parse)
            ?.takeIf { uri -> context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission } }

    /** Remember the folder chosen in the system picker (keeps access across restarts). */
    fun saveFolder(context: Context, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_FOLDER, treeUri.toString()).apply()
    }

    fun start(context: Context, treeUri: Uri) {
        if (job?.isActive == true) return
        val appContext = context.applicationContext
        job = scope.launch {
            val resolver = appContext.contentResolver
            val name = "Aerocast-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")) + ".m4a"
            var muxer: MediaMuxer? = null
            var encoder: AacEncoder? = null
            try {
                val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
                val fileUri = DocumentsContract.createDocument(resolver, parent, "audio/mp4", name)
                    ?: error("Couldn't create the file in that folder")
                val pfd = resolver.openFileDescriptor(fileUri, "rw") ?: error("Couldn't open the file")
                pfd.use { fd ->
                    val mux = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxer = mux
                    var track = -1
                    var started = false
                    val enc = AacEncoder(
                        onFormat = { fmt -> track = mux.addTrack(fmt); mux.start(); started = true },
                        onFrame = { data, info -> if (started) mux.writeSampleData(track, data, MediaCodec.BufferInfo().apply {
                            set(0, info.size, info.presentationTimeUs, info.flags)
                        }) }
                    )
                    encoder = enc
                    _state.value = State(isRecording = true, fileName = name)

                    try {
                    var seq = NativeAudioEngine.nativeGetWriteSequence()
                    val pcm = ByteArray(NativeAudioEngine.FRAME_SIZE_BYTES)
                    var frames = 0L
                    while (isActive) {
                        val n = NativeAudioEngine.nativeReadProcessedSinkPacket(
                            sinkId = SINK_ID,
                            sequenceNumber = seq,
                            effectiveDelayMs = 0,
                            codecMode = NativeAudioEngine.CODEC_MODE_RAW_PCM,
                            outBuffer = pcm
                        )
                        if (n > 0) {
                            enc.encode(pcm, n)
                            seq++
                            frames += 352
                            if (frames % (352L * 125) == 0L) {
                                _state.value = _state.value.copy(seconds = frames / 44_100)
                            }
                        } else {
                            // If we fell far behind (e.g. capture restarted), jump to live.
                            val live = NativeAudioEngine.nativeGetWriteSequence()
                            if (live > seq + 500) seq = live
                            delay(4)
                        }
                    }
                    } finally {
                        // Finish the file while it is still open, otherwise the .m4a is unreadable.
                        runCatching { enc.finish() }
                        runCatching { if (started) mux.stop() }
                        runCatching { mux.release() }
                        enc.release()
                        muxer = null
                        encoder = null
                    }
                }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Recording failed", t)
                    _state.value = State(error = t.message ?: "Recording failed")
                }
            } finally {
                runCatching { muxer?.release() }
                encoder?.release()
                if (_state.value.error == null) _state.value = _state.value.copy(isRecording = false)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
