package ai.cocog.assistant.audio

import ai.cocog.assistant.CoCogApp
import ai.cocog.assistant.inference.ChunkResult
import ai.cocog.assistant.session.SessionManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Long-running foreground service that owns the microphone and drives the
 * audio → chunk → inference → memory pipeline.
 *
 * Lifecycle:
 *   startForegroundService(intent with EXTRA_GOALS) → captures audio until
 *   stopService() or explicit STOP action.
 */
class AudioCaptureService : LifecycleService() {

    private val chunker = AudioChunker()
    private var captureJob: Job? = null
    private var processJob: Job? = null

    private val app get() = application as CoCogApp

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }

        val goals = intent?.getStringArrayListExtra(EXTRA_GOALS) ?: emptyList<String>()
        app.sessionManager.startSession(goals)

        startForeground(NOTIF_ID, buildNotification())
        startCapture()
        return START_STICKY
    }

    // --- capture -----------------------------------------------------------

    private fun startCapture() {
        if (captureJob?.isActive == true) return

        // Inference pipeline: consume chunks as they arrive.
        processJob = chunker.chunks
            .onEach { chunk -> processChunk(chunk) }
            .launchIn(lifecycleScope)

        // Capture thread: reads AudioRecord and feeds the chunker.
        captureJob = lifecycleScope.launch(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(
                AudioChunker.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            // Read in 0.5-second bursts — keeps the capture loop tight without
            // flooding the chunker with individually tiny calls.
            val readFrames = AudioChunker.SAMPLE_RATE / 2   // 8 000 samples
            val readBuf    = ShortArray(readFrames)
            val hwBuf      = maxOf(minBuf, readFrames * 2)  // bytes

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AudioChunker.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                hwBuf,
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                notifyStatus("Mic init failed — check permission")
                stopSelf()
                return@launch
            }

            recorder.startRecording()
            Log.i(TAG, "Capture started: ${AudioChunker.SAMPLE_RATE} Hz mono, " +
                    "chunk=${AudioChunker.CHUNK_DURATION_S}s / overlap=${AudioChunker.OVERLAP_DURATION_S}s")
            notifyStatus("Listening…")

            try {
                while (isActive) {
                    val read = recorder.read(readBuf, 0, readFrames)
                    when {
                        read > 0 -> chunker.write(readBuf, read)
                        read < 0 -> {
                            Log.e(TAG, "AudioRecord.read error code $read")
                            break
                        }
                    }
                }
            } finally {
                recorder.stop()
                recorder.release()
                chunker.close()
                Log.i(TAG, "Capture stopped")
            }
        }
    }

    private suspend fun processChunk(chunk: AudioChunk) {
        val engine = app.engine
        if (!engine.isLoaded()) {
            Log.w(TAG, "Model not yet loaded — skipping chunk ${chunk.index}")
            return
        }

        val goals = app.sessionManager.currentGoals()
        val result: ChunkResult = withContext(Dispatchers.Default) {
            engine.processChunk(chunk, goals)
        }

        Log.d(TAG, "Chunk ${chunk.index}: ${result.transcript.take(80)}… " +
                "[remember=${result.worthRemembering}, offTask=${result.offTaskSignal}]")

        if (result.worthRemembering) {
            app.memoryStore.enqueue(result)
        }

        if (result.offTaskSignal) {
            app.sessionManager.recordDrift(result)
        } else {
            app.sessionManager.recordChunk()
            app.sessionManager.clearDrift()   // back on task — reset consecutive-drift counter
        }
    }

    // --- lifecycle ---------------------------------------------------------

    private fun stopCapture() {
        captureJob?.cancel()
        processJob?.cancel()
        captureJob = null
        processJob = null
        app.sessionManager.endSession()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    // --- notification ------------------------------------------------------

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, AudioCaptureService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CoCogApp.CHANNEL_CAPTURE)
            .setContentTitle("Co-Cog — listening")
            .setContentText("Monitoring focus in background")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .build()
    }

    private fun notifyStatus(msg: String) {
        Log.i(TAG, msg)
        // Broadcast to MainActivity so the UI can reflect service state.
        sendBroadcast(Intent(ACTION_STATUS).putExtra(EXTRA_STATUS_MSG, msg))
    }

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val NOTIF_ID = 1001

        const val ACTION_STOP       = "ai.cocog.assistant.STOP_CAPTURE"
        const val ACTION_STATUS     = "ai.cocog.assistant.CAPTURE_STATUS"
        const val EXTRA_GOALS       = "goals"
        const val EXTRA_STATUS_MSG  = "status_msg"
    }
}
