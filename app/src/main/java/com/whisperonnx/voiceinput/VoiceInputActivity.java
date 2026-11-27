package com.whisperonnx.voiceinput;

import com.whisperonnx.prefs.Prefs;
import com.whisperonnx.R;
import com.whisperonnx.backend.TranscriptionStatus;
import com.whisperonnx.backend.Whisper;
import com.whisperonnx.backend.WhisperResult;
import com.whisperonnx.recording.Recorder;
import com.whisperonnx.recording.RecorderEvent;
import com.whisperonnx.ui.Components;
import com.whisperonnx.ui.RecordCountdown;
import com.whisperonnx.ui.RecordGlow;
import com.whisperonnx.ui.HapticFeedback;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.speech.RecognizerIntent;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;

public class VoiceInputActivity extends AppCompatActivity {
    private static final String TAG = "VoiceInputActivity";
    private ImageButton btnRecord;
    private ImageButton btnCancel;
    private ImageButton btnModeAuto;
    private ImageButton btnBackend;
    private ProgressBar processingBar = null;
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private Prefs prefs = null;
    private Context mContext;
    private CountDownTimer countDownTimer;
    private boolean modeAuto = false;
    private RecordGlow glow;

    private void onRecorderUpdate(RecorderEvent event, String detail) {
        switch (event) {
            case RECORDING:
                glow.start();
                break;
            case DONE:
                HapticFeedback.vibrate(mContext);
                glow.stop();
                startTranscription();
                break;
            case TOO_SHORT:
                HapticFeedback.vibrate(mContext);
                cancelCountDown();
                runOnUiThread(() -> {
                    glow.stop();
                    processingBar.setProgress(0);
                    Toast.makeText(mContext, R.string.error_no_input, Toast.LENGTH_SHORT).show();
                });
                break;
        }
    }

    private void cancelCountDown() {
        if (countDownTimer != null) countDownTimer.cancel();
    }

    private void startCountedRecording() {
        HapticFeedback.vibrate(this);
        startRecording();
        countDownTimer = RecordCountdown.start(this, processingBar);
    }

    private void onRecordPressed() {
        glow.start();
        if (!checkRecordPermission()) return;
        if (mWhisper.isInProgress()) {
            runOnUiThread(() -> Toast.makeText(this, getString(R.string.please_wait), Toast.LENGTH_SHORT).show());
            return;
        }
        startCountedRecording();
    }

    private void onRecordReleased() {
        glow.stop();
        if (mRecorder != null && mRecorder.isInProgress()) mRecorder.stop();
    }

    private void toggleModeAuto() {
        modeAuto = !modeAuto;
        prefs.setModeAuto(modeAuto);
        btnRecord.setVisibility(modeAuto ? View.GONE : View.VISIBLE);
        btnModeAuto.setImageResource(modeAuto ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
        if (mWhisper != null) stopTranscription();
        setResult(RESULT_CANCELED, null);
        finish();
    }

    private void toggleBackend() {
        boolean server = !prefs.useServer();
        prefs.setUseServer(server);
        btnBackend.setImageResource(server ? R.drawable.ic_cloud_on_36dp : R.drawable.ic_cloud_off_36dp);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = this;
        prefs = Prefs.of(this);

        initModel();

        setContentView(R.layout.activity_recognize);

        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height =  WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.BOTTOM;

        btnCancel = findViewById(R.id.btnCancel);
        btnRecord = findViewById(R.id.btnRecord);
        glow = new RecordGlow(btnRecord, 10, null, R.drawable.rounded_button_background, this::runOnUiThread);
        btnModeAuto = findViewById(R.id.btnModeAuto);
        btnBackend = findViewById(R.id.btnBackend);
        btnBackend.setImageResource(prefs.useServer() ? R.drawable.ic_cloud_on_36dp : R.drawable.ic_cloud_off_36dp);
        processingBar = findViewById(R.id.processing_bar);

        modeAuto = prefs.modeAuto();
        btnModeAuto.setImageResource(modeAuto ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);

        mRecorder = new Recorder(this);
        mRecorder.setListener(this::onRecorderUpdate);

        if (modeAuto) {
            btnRecord.setVisibility(View.GONE);
            startCountedRecording();
        }

        btnModeAuto.setOnClickListener(v -> toggleModeAuto());
        btnBackend.setOnClickListener(v -> toggleBackend());
        btnRecord.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) onRecordPressed();
            if (event.getAction() == MotionEvent.ACTION_UP) onRecordReleased();
            return true;
        });

        btnCancel.setOnClickListener(v -> {
            if (mWhisper != null) stopTranscription();
            setResult(RESULT_CANCELED, null);
            finish();
        });

    }
    private void startRecording() {
        if (modeAuto) mRecorder.initVad();
        mRecorder.start();
    }

    private void initModel() {
        mWhisper = new Whisper(this);
        if (mWhisper.isModelMissing()) Components.openSetup(this);
        mWhisper.loadModel();
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(TranscriptionStatus status, String detail) { }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                runOnUiThread(() -> processingBar.setIndeterminate(false));

                String result = whisperResult.getResult();
                if (result.trim().length() > 0){
                    sendResult(result.trim());
                }
            }
        });
    }

    private void sendResult(String result) {
        Intent sendResultIntent = new Intent();
        ArrayList<String> results = new ArrayList<>();
        results.add(result);
        sendResultIntent.putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, results);
        sendResultIntent.putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, new float[]{1.0f});
        setResult(RESULT_OK, sendResultIntent);
        finish();
    }

    private void startTranscription() {
        if (countDownTimer!=null) { countDownTimer.cancel();}
        runOnUiThread(() -> {
            processingBar.setProgress(0);
            processingBar.setIndeterminate(true);
        });
        if (mWhisper!=null){
            mWhisper.setTranslate(true);
            mWhisper.start();
            Log.d(TAG,"Start Transcription");
        }
    }

    private void stopTranscription() {
        runOnUiThread(() -> processingBar.setIndeterminate(false));
        mWhisper.stop();
    }

    private boolean checkRecordPermission() {
        int permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.need_record_audio_permission),Toast.LENGTH_SHORT).show();
        }
        return (permission == PackageManager.PERMISSION_GRANTED);
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }

    @Override
    public void onDestroy() {
        deinitModel();
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
        super.onDestroy();
    }
}
