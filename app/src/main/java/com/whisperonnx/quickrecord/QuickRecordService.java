package com.whisperonnx.quickrecord;

import com.whisperonnx.R;
import com.whisperonnx.backend.OpenAITranscriber;
import com.whisperonnx.backend.Whisper;
import com.whisperonnx.backend.WhisperResult;
import com.whisperonnx.endpoint.EndpointSender;
import com.whisperonnx.endpoint.SendSession;
import com.whisperonnx.recording.OtherAudioPauser;
import com.whisperonnx.recording.Recorder;
import com.whisperonnx.ui.Components;
import com.whisperonnx.ui.HapticFeedback;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

public class QuickRecordService extends Service {
    public static final String ACTION_TOGGLE = "dev.starkbit.chato.action.QUICK_RECORD_TOGGLE";
    public static final String EXTRA_ENDPOINT = "endpoint";
    static final int MAX_MS = 30_000;

    private static final String TAG = "QuickRecord";
    private static final String CHANNEL = "quick_record";
    private static final int NOTIFICATION_ID = 7;

    enum State { IDLE, RECORDING, SENDING }

    private static volatile State state = State.IDLE;

    private static volatile int activeEndpoint;

    static boolean isRecordingTo(int index) {
        return state == State.RECORDING && activeEndpoint == index;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stopAtLimit = this::stopRecording;
    private Recorder recorder;
    private Whisper whisper;
    private SendSession session;
    private OtherAudioPauser otherAudio;
    private EndpointSender.Endpoint endpoint;
    private boolean recordingEnded;
    private boolean transcriptionFinished;
    private int pendingSends;
    private int sent;
    private String lastError;

    public static void toggle(Context context, int endpoint) {
        ContextCompat.startForegroundService(context, new Intent(context, QuickRecordService.class)
                .setAction(ACTION_TOGGLE).putExtra(EXTRA_ENDPOINT, endpoint));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // External: every startForegroundService() call needs a matching startForeground()
        startForeground(state == State.IDLE ? getString(R.string.quick_record_starting) : currentText());
        if (intent == null || !ACTION_TOGGLE.equals(intent.getAction())) {
            if (state == State.IDLE) stopSelf();
            return START_NOT_STICKY;
        }
        switch (state) {
            case IDLE: begin(intent.getIntExtra(EXTRA_ENDPOINT, 0)); break;
            case RECORDING: stopRecording(); break;
            case SENDING: toast(getString(R.string.quick_record_busy)); break;
        }
        return START_NOT_STICKY;
    }

    private void begin(int wanted) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        if (wanted >= 1 && wanted <= EndpointSender.COUNT) {
            EndpointSender.Endpoint ep = EndpointSender.load(sp, wanted);
            if (!ep.isConfigured()) {
                finish(getString(R.string.quick_record_no_url, ep.displayName()));
                return;
            }
        } else {
            boolean[] configured = new boolean[EndpointSender.COUNT];
            for (int i = 1; i <= EndpointSender.COUNT; i++) configured[i - 1] = EndpointSender.load(sp, i).isConfigured();
            wanted = EndpointSender.pickQuickRecord(sp.getInt(EndpointSender.PREF_QUICK_RECORD, 1), configured);
            if (wanted == 0) {
                finish(getString(R.string.endpoint_none_configured));
                return;
            }
        }
        endpoint = EndpointSender.load(sp, wanted);
        activeEndpoint = wanted;

        whisper = new Whisper(this);
        if (whisper.isModelMissing()) {
            Components.openSetup(this);
            finish(null);
            return;
        }
        whisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (message.equals(Whisper.MSG_QUEUE_EMPTY)) {
                    handler.post(() -> {
                        session.onQueueEmpty();
                        if (recordingEnded) onTranscriptionFinished();
                    });
                } else if (!message.equals(Whisper.MSG_PROCESSING) && !message.equals(Whisper.MSG_PROCESSING_DONE)
                        && !message.equals(Whisper.MSG_MODEL_LOADED)
                        && !message.equals(OpenAITranscriber.MSG_EMPTY_TRANSCRIPTION)) {
                    handler.post(() -> lastError = message);
                }
            }

            @Override
            public void onResultReceived(WhisperResult result) {
                String text = result.getResult().trim();
                handler.post(() -> session.deliver(text));
            }
        });
        whisper.loadModel();

        session = new SendSession(new SendSession.Output() {
            @Override public void type(String text) { }
            @Override public void send(EndpointSender.Endpoint ep, String text) {
                pendingSends++;
                EndpointSender.post(ep, text, QuickRecordService.this::onSent);
            }
        }, handler::post);
        session.begin(endpoint);

        recorder = new Recorder(this);
        recorder.setChunkMs(MAX_MS);
        recorder.setChunkListener(pcm -> whisper.enqueueChunk(pcm));
        recorder.setListener(message -> {
            if (message.equals(Recorder.MSG_RECORDING)) return;
            handler.post(() -> {
                if (recorder == null) return;
                if (message.equals(Recorder.MSG_RECORDING_DONE)) onRecordingEnded();
                else if (message.equals(Recorder.MSG_RECORDING_ERROR)) finish(getString(R.string.error_no_input));
                else if (message.equals(Recorder.MSG_NO_PERMISSION)) finish(getString(R.string.need_record_audio_permission));
                else finish(message);
            });
        });

        otherAudio = new OtherAudioPauser(this);
        if (sp.getBoolean("imePauseOtherAudio", false)) otherAudio.pause();
        recorder.start();
        handler.postDelayed(stopAtLimit, MAX_MS);
        setState(State.RECORDING);
        HapticFeedback.vibrate(this);
    }

    private void stopRecording() {
        handler.removeCallbacks(stopAtLimit);
        Recorder r = recorder;
        if (r != null && r.isInProgress()) new Thread(r::stop, "quick-record-stop").start();
    }

    private void onRecordingEnded() {
        if (state != State.RECORDING) return;
        handler.removeCallbacks(stopAtLimit);
        otherAudio.release();
        HapticFeedback.vibrate(this);
        recordingEnded = true;
        setState(State.SENDING);
        boolean pending = whisper.hasPendingChunks();
        session.end(pending);
        if (!pending) handler.post(this::onTranscriptionFinished);
    }

    private void onTranscriptionFinished() {
        if (transcriptionFinished) return;
        transcriptionFinished = true;
        maybeFinish();
    }

    private void onSent(EndpointSender.Endpoint ep, String text, String error) {
        pendingSends--;
        if (error == null) {
            sent++;
        } else {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Dictation", text));
            lastError = getString(R.string.endpoint_failed, ep.displayName(), error);
            HapticFeedback.vibrate(this);
        }
        maybeFinish();
    }

    private void maybeFinish() {
        if (!transcriptionFinished || pendingSends > 0) return;
        if (lastError != null) finish(lastError);
        else if (sent > 0) finish(getString(R.string.endpoint_sent, endpoint.displayName()));
        else finish(getString(R.string.error_no_input));
    }

    private void finish(String message) {
        if (message != null) toast(message);
        Log.d(TAG, "Finished: " + message);
        cleanUp();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void cleanUp() {
        handler.removeCallbacks(stopAtLimit);
        if (otherAudio != null) otherAudio.release();
        if (recorder != null) {
            if (recorder.isInProgress()) stopRecording();
            recorder.release();
            recorder = null;
        }
        if (whisper != null) {
            whisper.unloadModel();
            whisper = null;
        }
        setState(State.IDLE);
    }

    @Override
    public void onDestroy() {
        cleanUp();
        super.onDestroy();
    }

    private void setState(State s) {
        state = s;
        QuickRecordWidgetProvider.updateAll(this);
        if (s != State.IDLE) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(currentText()));
        }
    }

    private String currentText() {
        return state == State.RECORDING ? getString(R.string.quick_record_recording)
                : getString(R.string.endpoint_target, endpoint == null ? "" : endpoint.displayName());
    }

    private void startForeground(String text) {
        Notification n = notification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private Notification notification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                    getString(R.string.quick_record), NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent toggle = PendingIntent.getActivity(this, 0,
                new Intent(this, QuickRecordActivity.class), PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_mic_48dp)
                .setContentTitle(getString(R.string.quick_record))
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(toggle);
        if (state == State.RECORDING) b.addAction(0, getString(R.string.quick_record_stop), toggle);
        return b.build();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
