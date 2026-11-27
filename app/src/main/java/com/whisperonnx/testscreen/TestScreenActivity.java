package com.whisperonnx.testscreen;

import com.whisperonnx.BuildConfig;
import com.whisperonnx.R;
import com.whisperonnx.backend.Backends;
import com.whisperonnx.backend.OpenAITranscriber;
import com.whisperonnx.backend.ParakeetRecognizer;
import com.whisperonnx.backend.Whisper;
import com.whisperonnx.backend.WhisperModel;
import com.whisperonnx.backend.WhisperResult;
import com.whisperonnx.recording.Recorder;
import com.whisperonnx.ui.Components;
import com.whisperonnx.ui.GradientBorderDrawable;
import com.whisperonnx.ui.HapticFeedback;
import com.whisperonnx.ui.ModelInstaller;
import com.whisperonnx.ui.ThemeUtils;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;
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
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.woheller69.freeDroidWarn.FreeDroidWarn;

import java.util.ArrayList;
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
    private GradientBorderDrawable gradientDrawable;

    private GradientBorderDrawable getGradientDrawable() {
        if (gradientDrawable == null) {
            float density = getResources().getDisplayMetrics().density;
            gradientDrawable = new GradientBorderDrawable(
                10 * density,
                0.5f * density,
                15 * density
            );
        }
        return gradientDrawable;
    }

    private void applyGradientBackground() {
        runOnUiThread(() -> {
            GradientBorderDrawable drawable = getGradientDrawable();
            drawable.setHostView(btnRecord);
            GradientBorderDrawable.enableGlowClipping(btnRecord);
            btnRecord.setBackground(drawable);
            drawable.startAnimation();
        });
    }

    private void revertBackground() {
        runOnUiThread(() -> {
            if (gradientDrawable != null) gradientDrawable.stopAnimation();
            GradientBorderDrawable.restoreGlowClipping(btnRecord);
            btnRecord.setBackgroundResource(R.drawable.ime_mic_background);
        });
    }

    private Recorder mRecorder = null;
    private Whisper mWhisper = null;

    private SharedPreferences sp = null;

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
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        append = findViewById(R.id.mode_append);
        findViewById(R.id.row_append).setOnClickListener(v -> append.toggle());
        tvStatus = findViewById(R.id.tvStatus);
        tvStatus.setText(R.string.test_ready);
        setupBackendPicker();

        btnInfo = findViewById(R.id.btnInfo);
        btnInfo.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/woheller69/whisperIMEplus#Donate"))));

        btnRecord = findViewById(R.id.btnRecord);

        btnRecord.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                applyGradientBackground();
                Log.d(TAG, "Start recording...");
                if (!mWhisper.isInProgress()) {
                    HapticFeedback.vibrate(this);
                    startRecording();
                    runOnUiThread(() -> processingBar.setProgress(100));
                    countDownTimer = new CountDownTimer(30000, 1000) {
                        @Override
                        public void onTick(long l) {
                            runOnUiThread(() -> processingBar.setProgress((int) (l / 300)));
                        }
                        @Override
                        public void onFinish() {}
                    };
                    countDownTimer.start();
                } else (Toast.makeText(this,getString(R.string.please_wait),Toast.LENGTH_SHORT)).show();

            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                revertBackground();
                if (mRecorder != null && mRecorder.isInProgress()) {
                    Log.d(TAG, "Recording is in progress... stopping...");
                    stopRecording();
                }
            }
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
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                Log.d(TAG, "Update is received, Message: " + message);
                if (message.equals(Recorder.MSG_RECORDING)) {
                    runOnUiThread(() -> tvStatus.setText(getString(R.string.test_recording, getString(R.string.record_button))));
                    if (!append.isChecked()) runOnUiThread(() -> tvResult.setText(""));
                    applyGradientBackground();
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    HapticFeedback.vibrate(mContext);
                    revertBackground();

                    startProcessing();
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    HapticFeedback.vibrate(mContext);
                    if (countDownTimer!=null) { countDownTimer.cancel();}
                    runOnUiThread(() -> {
                        revertBackground();
                        processingBar.setProgress(0);
                        tvStatus.setText(getString(R.string.error_no_input));
                    });
                }
            }

        });
        FreeDroidWarn.showWarningOnUpgrade(this, BuildConfig.VERSION_CODE);
        if (GithubStar.shouldShowStarDialog(this)) GithubStar.starDialog(this, "https://github.com/woheller69/whisperIMEplus");
        checkPermissions();

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
                Log.d(TAG, "Update is received, Message: " + message);

                if (message.equals(Whisper.MSG_MODEL_LOADED)) {
                    runOnUiThread(() -> tvStatus.setText(R.string.test_ready));
                } else if (message.equals(Whisper.MSG_PROCESSING)) {
                    runOnUiThread(() -> tvStatus.setText(getString(R.string.processing)));
                    startTime = System.currentTimeMillis();
                } else if (!message.equals(Whisper.MSG_PROCESSING_DONE) && !message.equals(Whisper.MSG_QUEUE_EMPTY)) {
                    runOnUiThread(() -> {
                        processingBar.setIndeterminate(false);
                        tvStatus.setText(message);
                    });
                }
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                long timeTaken = System.currentTimeMillis() - startTime;
                String text = whisperResult.getResult() == null ? "" : whisperResult.getResult().trim();
                Log.d(TAG, "Result: " + text);
                runOnUiThread(() -> {
                    processingBar.setIndeterminate(false);
                    tvStatus.setText(getString(R.string.test_done_with_backend, getString(R.string.test_done, timeTaken),
                            getResources().getStringArray(R.array.backend_labels)[loadedBackend]));
                    if (text.isEmpty()) return;
                    if (tvResult.length() > 0 && !Character.isWhitespace(tvResult.getText().charAt(tvResult.length() - 1))) {
                        tvResult.append(" ");
                    }
                    tvResult.append(text);
                });
            }
        });
        boolean remote = OpenAITranscriber.BACKEND_OPENAI.equals(
                sp.getString(OpenAITranscriber.PREF_BACKEND_TYPE, OpenAITranscriber.BACKEND_LOCAL));
        if (!remote && !mWhisper.isModelMissing()) {
            tvStatus.setText(getString(R.string.loading_model, getString(
                    Whisper.ENGINE_PARAKEET.equals(mWhisper.getLocalEngine()) ? R.string.model_parakeet : R.string.model_whisper)));
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
        spinnerBackend.setSelection(Backends.current(sp), false);
        loadBackend(Backends.current(sp));

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
                    Backends.select(sp, position);
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
        tvBackendInfo.setText(Backends.describe(sp, backend, ParakeetRecognizer.isInstalled(this)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (spinnerBackend == null) return;
        int current = Backends.current(sp);
        if (current != loadedBackend || (mWhisper != null && mWhisper.modelInstalledSince())) {
            spinnerBackend.setSelection(current, false);
            loadBackend(current);
        } else {
            tvBackendInfo.setText(Backends.describe(sp, current, ParakeetRecognizer.isInstalled(this)));
        }
    }

    private void checkPermissions() {
        List<String> perms = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO);
            Toast.makeText(this, getString(R.string.need_record_audio_permission), Toast.LENGTH_SHORT).show();
        }
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) && (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)){
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (sp.getBoolean("bluetooth", false)
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        }

        if (!perms.isEmpty()) {
            requestPermissions(perms.toArray(new String[] {}), 0);
        }
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