package ai.cocog.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import ai.cocog.assistant.inference.LiteRTEngine
import ai.cocog.assistant.inference.ModelConfig
import ai.cocog.assistant.memory.MemoryStore
import ai.cocog.assistant.session.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CoCogApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var engine: LiteRTEngine
        private set
    lateinit var sessionManager: SessionManager
        private set
    lateinit var memoryStore: MemoryStore
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        sessionManager = SessionManager()
        memoryStore = MemoryStore(filesDir)
        engine = LiteRTEngine()

        // Load model eagerly on a background thread so it's ready when the first session starts.
        val modelFile = ModelConfig.locate(this)
        if (modelFile != null) {
            appScope.launch {
                engine.load(ModelConfig(modelFile.absolutePath))
            }
        }

        // Single drain coroutine serialises all disk writes for the lifetime of the process.
        appScope.launch {
            memoryStore.drainLoop()
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTURE,
                "Audio Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Running while microphone is active" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INTERVENTION,
                "Focus Interventions",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Nudges when you drift off-task" }
        )
    }

    companion object {
        const val CHANNEL_CAPTURE = "cocog_capture"
        const val CHANNEL_INTERVENTION = "cocog_intervention"
    }
}
