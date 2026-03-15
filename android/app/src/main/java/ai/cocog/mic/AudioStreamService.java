package ai.cocog.mic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Foreground service that captures microphone audio as 16kHz mono 16-bit PCM
 * and streams it over WebSocket as binary frames.
 */
public class AudioStreamService extends Service {
    private static final String TAG = "AudioStream";
    private static final String CHANNEL_ID = "cocog_mic_channel";

    // Audio config — matches co-cog server expectations
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    // 0.5s chunks (matches config.yaml chunk_duration_s)
    private static final int CHUNK_SAMPLES = SAMPLE_RATE / 2;
    private static final int CHUNK_BYTES = CHUNK_SAMPLES * 2; // 16-bit = 2 bytes per sample

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong bytesSent = new AtomicLong(0);
    private final AtomicLong framesSent = new AtomicLong(0);
    private Thread captureThread;
    private WebSocket webSocket;
    private OkHttpClient client;

    public static final String EXTRA_SERVER_URL = "server_url";

    // Callbacks for UI updates
    public interface StatusListener {
        void onStatusChanged(String status);
        void onStatsUpdated(long frames, long bytes);
    }

    private static StatusListener statusListener;

    public static void setStatusListener(StatusListener listener) {
        statusListener = listener;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running.get()) return START_STICKY;

        String url = intent.getStringExtra(EXTRA_SERVER_URL);
        if (url == null || url.isEmpty()) {
            notifyStatus("Error: no server URL");
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Co-Cog Mic")
                .setContentText("Streaming audio...")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();

        startForeground(1, notification);
        running.set(true);
        bytesSent.set(0);
        framesSent.set(0);

        connectAndStream(url);
        return START_STICKY;
    }

    private void connectAndStream(String url) {
        client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout for WebSocket
                .build();

        Request request = new Request.Builder().url(url).build();
        notifyStatus("Connecting...");

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                Log.i(TAG, "WebSocket connected to " + url);
                notifyStatus("Connected — streaming");
                startCapture(ws);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.e(TAG, "WebSocket error: " + t.getMessage());
                notifyStatus("Connection failed: " + t.getMessage());
                stop();
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Log.i(TAG, "WebSocket closed: " + reason);
                notifyStatus("Disconnected");
                stop();
            }
        });
    }

    private void startCapture(WebSocket ws) {
        int bufferSize = Math.max(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
                CHUNK_BYTES * 2
        );

        captureThread = new Thread(() -> {
            AudioRecord recorder = null;
            try {
                recorder = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize
                );

                if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize");
                    notifyStatus("Mic init failed");
                    return;
                }

                byte[] buffer = new byte[CHUNK_BYTES];
                recorder.startRecording();
                Log.i(TAG, "Recording started: " + SAMPLE_RATE + "Hz mono 16-bit, "
                        + CHUNK_BYTES + " bytes/chunk");

                while (running.get()) {
                    int read = recorder.read(buffer, 0, CHUNK_BYTES);
                    if (read > 0 && running.get()) {
                        boolean sent = ws.send(ByteString.of(buffer, 0, read));
                        if (!sent) {
                            Log.w(TAG, "WebSocket send failed (backpressure)");
                            continue;
                        }
                        long frames = framesSent.incrementAndGet();
                        long bytes = bytesSent.addAndGet(read);
                        if (frames % 20 == 0) { // update stats every ~10s
                            notifyStats(frames, bytes);
                        }
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord.read error: " + read);
                        break;
                    }
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Mic permission denied", e);
                notifyStatus("Permission denied");
            } finally {
                if (recorder != null) {
                    try {
                        recorder.stop();
                        recorder.release();
                    } catch (Exception e) {
                        Log.w(TAG, "Error stopping recorder", e);
                    }
                }
                Log.i(TAG, "Capture thread stopped. Sent " + framesSent.get() + " frames");
            }
        }, "audio-capture");

        captureThread.start();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;

        if (webSocket != null) {
            webSocket.close(1000, "User stopped");
            webSocket = null;
        }
        if (captureThread != null) {
            captureThread.interrupt();
            captureThread = null;
        }
        if (client != null) {
            client.dispatcher().executorService().shutdown();
            client = null;
        }
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Audio Streaming",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shows when microphone is streaming");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void notifyStatus(String status) {
        StatusListener l = statusListener;
        if (l != null) l.onStatusChanged(status);
    }

    private void notifyStats(long frames, long bytes) {
        StatusListener l = statusListener;
        if (l != null) l.onStatsUpdated(frames, bytes);
    }
}
