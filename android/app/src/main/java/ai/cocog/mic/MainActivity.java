package ai.cocog.mic;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import android.app.Activity;

public class MainActivity extends Activity implements AudioStreamService.StatusListener {

    private EditText serverUrlInput;
    private Button toggleButton;
    private TextView statusText;
    private TextView statsText;
    private boolean streaming = false;

    private static final int PERMISSION_REQUEST = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        serverUrlInput = findViewById(R.id.serverUrl);
        toggleButton = findViewById(R.id.toggleButton);
        statusText = findViewById(R.id.statusText);
        statsText = findViewById(R.id.statsText);

        AudioStreamService.setStatusListener(this);

        toggleButton.setOnClickListener(v -> {
            if (streaming) {
                stopStreaming();
            } else {
                startStreaming();
            }
        });
    }

    private void startStreaming() {
        // Check mic permission
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.POST_NOTIFICATIONS
                    },
                    PERMISSION_REQUEST
            );
            return;
        }

        String url = serverUrlInput.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "Enter server URL", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, AudioStreamService.class);
        intent.putExtra(AudioStreamService.EXTRA_SERVER_URL, url);
        startForegroundService(intent);

        streaming = true;
        toggleButton.setText("Stop");
        serverUrlInput.setEnabled(false);
    }

    private void stopStreaming() {
        Intent intent = new Intent(this, AudioStreamService.class);
        stopService(intent);

        streaming = false;
        toggleButton.setText("Start");
        serverUrlInput.setEnabled(true);
        statusText.setText("Stopped");
        statsText.setText("");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == PERMISSION_REQUEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startStreaming();
            } else {
                Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onStatusChanged(String status) {
        runOnUiThread(() -> {
            statusText.setText(status);
            // If connection failed or disconnected, reset UI
            if (status.startsWith("Connection failed") || status.equals("Disconnected")
                    || status.equals("Permission denied") || status.equals("Mic init failed")) {
                streaming = false;
                toggleButton.setText("Start");
                serverUrlInput.setEnabled(true);
            }
        });
    }

    @Override
    public void onStatsUpdated(long frames, long bytes) {
        runOnUiThread(() -> {
            double seconds = frames * 0.5; // each frame is 0.5s
            double kb = bytes / 1024.0;
            statsText.setText(String.format(
                    "Frames: %d | %.0fs streamed | %.0f KB sent",
                    frames, seconds, kb
            ));
        });
    }

    @Override
    protected void onDestroy() {
        AudioStreamService.setStatusListener(null);
        super.onDestroy();
    }
}
