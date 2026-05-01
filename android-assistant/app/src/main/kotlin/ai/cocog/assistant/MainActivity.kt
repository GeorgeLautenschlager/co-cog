package ai.cocog.assistant

import ai.cocog.assistant.audio.AudioCaptureService
import ai.cocog.assistant.databinding.ActivityMainBinding
import ai.cocog.assistant.inference.LiteRTEngine
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app get() = application as CoCogApp

    private var capturing = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(AudioCaptureService.EXTRA_STATUS_MSG) ?: return
            binding.statusText.text = msg
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toggleButton.setOnClickListener {
            if (capturing) stopCapture() else requestPermissionsAndStart()
        }

        // Reflect engine load state in the UI.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Poll load state — swap for a StateFlow if the SDK supports it.
                while (true) {
                    updateModelStatus()
                    kotlinx.coroutines.delay(1_000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, statusReceiver,
            IntentFilter(AudioCaptureService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onPause() {
        unregisterReceiver(statusReceiver)
        super.onPause()
    }

    // -----------------------------------------------------------------------
    // Session control
    // -----------------------------------------------------------------------

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }

        if (needed.isEmpty()) {
            startCapture()
        } else {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMS)
        }
    }

    private fun startCapture() {
        if (!app.engine.isLoaded()) {
            Toast.makeText(this, "Model not loaded yet — check model file path", Toast.LENGTH_LONG).show()
            return
        }

        val goalsRaw = binding.goalsInput.text.toString().trim()
        val goals = goalsRaw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        val intent = Intent(this, AudioCaptureService::class.java).apply {
            putStringArrayListExtra(AudioCaptureService.EXTRA_GOALS, ArrayList(goals))
        }
        startForegroundService(intent)

        capturing = true
        binding.toggleButton.text = getString(R.string.stop_session)
        binding.goalsInput.isEnabled = false
        binding.statusText.text = getString(R.string.status_starting)
    }

    private fun stopCapture() {
        val intent = Intent(this, AudioCaptureService::class.java).apply {
            action = AudioCaptureService.ACTION_STOP
        }
        startService(intent)

        capturing = false
        binding.toggleButton.text = getString(R.string.start_session)
        binding.goalsInput.isEnabled = true
        binding.statusText.text = getString(R.string.status_stopped)
    }

    // -----------------------------------------------------------------------
    // Permissions
    // -----------------------------------------------------------------------

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCapture()
            } else {
                Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Model status
    // -----------------------------------------------------------------------

    private fun updateModelStatus() {
        val label = when (val s = app.engine.loadState) {
            is LiteRTEngine.LoadState.Idle    -> getString(R.string.model_not_found)
            is LiteRTEngine.LoadState.Loading -> getString(R.string.model_loading)
            is LiteRTEngine.LoadState.Ready   -> getString(R.string.model_ready)
            is LiteRTEngine.LoadState.Error   -> "Model error: ${s.cause.message}"
        }
        binding.modelStatusText.text = label
        binding.toggleButton.isEnabled = app.engine.isLoaded()
    }

    companion object {
        private const val REQ_PERMS = 10
    }
}
