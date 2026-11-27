package com.whisperonnx.testscreen;

import com.whisperonnx.prefs.Prefs;
import com.whisperonnx.BuildConfig;
import com.whisperonnx.R;
import com.whisperonnx.backend.Backends;
import com.whisperonnx.backend.ParakeetRecognizer;
import com.whisperonnx.backend.Whisper;
import com.whisperonnx.backend.WhisperModel;
import com.whisperonnx.backend.WhisperResult;
import com.whisperonnx.recording.Recorder;
import com.whisperonnx.ui.Permissions;
import com.whisperonnx.ui.Components;
import com.whisperonnx.ui.RecordCountdown;
import com.whisperonnx.ui.RecordGlow;
import com.whisperonnx.ui.HapticFeedback;
import com.whisperonnx.ui.ModelInstaller;
import com.whisperonnx.ui.ThemeUtils;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import androidx.appcompat.app.AppCompatDelegate;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.woheller69.freeDroidWarn.FreeDroidWarn;

import java.util.List;

public class TestScreenActivity extends AppCompatActivity {
    private Context mContext;
    private static final String TAG = "TestScreenActivity";

    private TextView tvStatus;
    private TextView tvResult;
    private FloatingActionButton fabCopy;
    private ImageButton btnRecord;
    private MaterialSwitch append;
    private Spinner spinnerBackend;
    private TextView tvBackendInfo;
    private int loadedBackend = -1;
    private final ModelInstaller modelInstaller = new ModelInstaller(this);
    private ProgressBar processingBar;
    private ImageButton btnInfo;
    private RecordGlow glow;

    private Recorder mRecorder = null;
    private Whisper mWhisper = null;

    private Prefs prefs = null;

    private CountDownTimer countDownTimer;
    private long startTime = 0;

    @Override
    protected void onDestroy() {
        deinitModel();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        stopProcessing();
        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_main, menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_settings){
            Components.open(this, Components.SETTINGS);
        }

        return super.onOptionsItemSelected(item);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = this;
        setContentView(R.layout.activity_main);
        ThemeUtils.setStatusBarAppearance(this);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        checkInputMethodEnabled();
        processingBar = findViewById(R.id.processing_bar);
        prefs = Prefs.of(this);
        append = findViewById(R.id.mode_append);
        findViewById(R.id.row_append).setOnClickListener(v -> append.toggle());
        tvStatus = findViewById(R.id.tvStatus);
        tvStatus.setText(R.string.test_ready);
        setupBackendPicker();

        btnInfo = findViewById(R.id.btnInfo);
        btnInfo.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/woheller69/whisperIMEplus#Donate"))));

        btnRecord = findViewById(R.id.btnRecord);
        glow = new RecordGlow(btnRecord, 10, null, R.drawable.ime_mic_background, this::runOnUiThread);

        btnRecord.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) onRecordPressed();
            if (event.getAction() == MotionEvent.ACTION_UP) onRecordReleased();
            return true;
        });

        tvResult = findViewById(R.id.tvResult);
        fabCopy = findViewById(R.id.fabCopy);
        fabCopy.setOnClickListener(v -> {
            String textToCopy = tvResult.getText().toString().trim();

            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(getString(R.string.model_output), textToCopy);
            clipboard.setPrimaryClip(clip);
        });

        mRecorder = new Recorder(this);
        mRecorder.setListener(this::onRecorderUpdate);
        FreeDroidWarn.showWarningOnUpgrade(this, BuildConfig.VERSION_CODE);
        if (GithubStar.shouldShowStarDialog(this)) GithubStar.starDialog(this, "https://github.com/woheller69/whisperIMEplus");
        checkPermissions();

    }

    private void onRecordPressed() {
        glow.start();
        Log.d(TAG, "Start recording...");
        if (mWhisper.isInProgress()) {
            Toast.makeText(this, getString(R.string.please_wait), Toast.LENGTH_SHORT).show();
            return;
        }
        HapticFeedback.vibrate(this);
        startRecording();
        countDownTimer = RecordCountdown.start(this, processingBar);
    }

    private void onRecordReleased() {
        glow.stop();
        if (mRecorder == null || !mRecorder.isInProgress()) return;
        Log.d(TAG, "Recording is in progress... stopping...");
        stopRecording();
    }

    private void onRecorderUpdate(String message) {
        Log.d(TAG, "Update is received, Message: " + message);
        switch (message) {
            case Recorder.MSG_RECORDING:
                runOnUiThread(() -> tvStatus.setText(getString(R.string.test_recording, getString(R.string.record_button))));
                runOnUiThread(this::clearResultUnlessAppending);
                glow.start();
                break;
            case Recorder.MSG_RECORDING_DONE:
                HapticFeedback.vibrate(mContext);
                glow.stop();
                startProcessing();
                break;
            case Recorder.MSG_RECORDING_ERROR:
                HapticFeedback.vibrate(mContext);
                cancelCountDown();
                runOnUiThread(() -> {
                    glow.stop();
                    processingBar.setProgress(0);
                    tvStatus.setText(getString(R.string.error_no_input));
                });
                break;
        }
    }

    private void clearResultUnlessAppending() {
        if (!append.isChecked()) tvResult.setText("");
    }

    private void cancelCountDown() {
        if (countDownTimer != null) countDownTimer.cancel();
    }

    private void onWhisperUpdate(String message) {
        Log.d(TAG, "Update is received, Message: " + message);
        switch (message) {
            case Whisper.MSG_MODEL_LOADED:
                runOnUiThread(() -> tvStatus.setText(R.string.test_ready));
                break;
            case Whisper.MSG_PROCESSING:
                runOnUiThread(() -> tvStatus.setText(getString(R.string.processing)));
                startTime = System.currentTimeMillis();
                break;
            case Whisper.MSG_PROCESSING_DONE:
            case Whisper.MSG_QUEUE_EMPTY:
                break;
            default:
                runOnUiThread(() -> {
                    processingBar.setIndeterminate(false);
                    tvStatus.setText(message);
                });
        }
    }

    private void showResult(String text, long timeTaken) {
        processingBar.setIndeterminate(false);
        tvStatus.setText(getString(R.string.test_done_with_backend, getString(R.string.test_done, timeTaken),
                getResources().getStringArray(R.array.backend_labels)[loadedBackend]));
        if (text.isEmpty()) return;
        if (tvResult.length() > 0 && !Character.isWhitespace(tvResult.getText().charAt(tvResult.length() - 1))) {
            tvResult.append(" ");
        }
        tvResult.append(text);
    }

    private void checkInputMethodEnabled() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> enabledInputMethodList = imm.getEnabledInputMethodList();

        String myInputMethodId = Components.keyboardId(this);
        boolean inputMethodEnabled = false;
        for (InputMethodInfo imi : enabledInputMethodList) {
            if (imi.getId().equals(myInputMethodId)) {
                inputMethodEnabled = true;
                break;
            }
        }
        if (!inputMethodEnabled) {
            Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
            startActivity(intent);
        }
    }

    private void initModel() {
        mWhisper = new Whisper(this);
        if (mWhisper.isModelMissing()) Components.openSetup(this);
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                onWhisperUpdate(message);
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                long timeTaken = System.currentTimeMillis() - startTime;
                String text = whisperResult.getResult() == null ? "" : whisperResult.getResult().trim();
                Log.d(TAG, "Result: " + text);
                runOnUiThread(() -> showResult(text, timeTaken));
            }
        });
        boolean remote = prefs.useServer();
        if (!remote && !mWhisper.isModelMissing()) {
            tvStatus.setText(getString(R.string.loading_model, getString(
                    Prefs.ENGINE_PARAKEET.equals(mWhisper.getLocalEngine()) ? R.string.model_parakeet : R.string.model_whisper)));
        } else {
            tvStatus.setText(R.string.test_ready);
        }
        mWhisper.loadModel();
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }

    private void setupBackendPicker() {
        spinnerBackend = findViewById(R.id.spinner_backend);
        tvBackendInfo = findViewById(R.id.tvBackendInfo);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, getResources().getStringArray(R.array.backend_labels));
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerBackend.setAdapter(adapter);
        spinnerBackend.setSelection(Backends.current(prefs), false);
        loadBackend(Backends.current(prefs));

        spinnerBackend.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == loadedBackend) return;
                if ((mRecorder != null && mRecorder.isInProgress()) || (mWhisper != null && mWhisper.isInProgress())) {
                    Toast.makeText(mContext, getString(R.string.please_wait), Toast.LENGTH_SHORT).show();
                    spinnerBackend.setSelection(loadedBackend);
                    return;
                }
                int previous = loadedBackend;
                Runnable use = () -> {
                    Backends.select(prefs, position);
                    loadBackend(position);
                };
                Runnable back = () -> spinnerBackend.setSelection(previous);
                if (position == Backends.PARAKEET && !ParakeetRecognizer.isInstalled(mContext)) {
                    modelInstaller.offerParakeet(use, back);
                } else if (position == Backends.WHISPER && !WhisperModel.isInstalled(mContext)) {
                    modelInstaller.offerWhisper(use, back);
                } else {
                    use.run();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void loadBackend(int backend) {
        deinitModel();
        initModel();
        loadedBackend = backend;
        tvBackendInfo.setText(Backends.describe(prefs, backend, ParakeetRecognizer.isInstalled(this)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (spinnerBackend == null) return;
        int current = Backends.current(prefs);
        if (current != loadedBackend || (mWhisper != null && mWhisper.modelInstalledSince())) {
            spinnerBackend.setSelection(current, false);
            loadBackend(current);
        } else {
            tvBackendInfo.setText(Backends.describe(prefs, current, ParakeetRecognizer.isInstalled(this)));
        }
    }

    private void checkPermissions() {
        Permissions.requestMissing(this, prefs.bluetooth());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Record permission is granted");
        } else {
            Log.d(TAG, "Record permission is not granted");
        }
    }

    private void startRecording() {
        checkPermissions();
        mRecorder.start();
    }

    private void stopRecording() {
        mRecorder.stop();
    }

    private void startProcessing() {
        if (countDownTimer!=null) { countDownTimer.cancel();}
        runOnUiThread(() -> {
            processingBar.setProgress(0);
            processingBar.setIndeterminate(true);
        });
        mWhisper.setTranslate(false);
        mWhisper.start();
    }

    private void stopProcessing() {
        processingBar.setIndeterminate(false);
        if (mWhisper != null && mWhisper.isInProgress()) mWhisper.stop();
    }

}