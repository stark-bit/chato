package com.whisperonnx;


import static com.whisperonnx.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSLATE;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;

import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.ImageButton;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.whisperonnx.asr.OpenAITranscriber;
import com.whisperonnx.asr.Recorder;
import com.whisperonnx.asr.Whisper;
import com.whisperonnx.asr.WhisperResult;
import com.whisperonnx.utils.HapticFeedback;
import com.whisperonnx.utils.GradientBorderDrawable;

public class WhisperInputMethodService extends InputMethodService {
    private static final String TAG = "WhisperInputMethodService";
    private ImageButton btnRecord;
    private ImageButton btnKeyboard;
    private ImageButton btnModeAuto;
    private ImageButton btnEnter;
    private ImageButton btnDel;
    private ImageButton btnBackend;
    private ImageButton btnDot;
    private ImageButton btnSpace;
    private ImageButton btnDash;
    private ImageButton btnBullet;
    private TextView tvStatus;
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private SharedPreferences sp = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Context mContext;
    private boolean modeAuto = false;
    private ViewGroup layoutButtons;
    private ViewGroup layoutToolbar;
    private ImageButton btnToolbarToggle;
    private boolean toolbarExpanded;
    private GradientBorderDrawable gradientDrawable;

    private GradientBorderDrawable getGradientDrawable() {
        if (gradientDrawable == null) {
            float density = getResources().getDisplayMetrics().density;
            gradientDrawable = new GradientBorderDrawable(
                8 * density,    // cornerRadius (matches ime_mic_background 8dp)
                0.5f * density, // borderWidth (near-invisible)
                15 * density    // glowBlur (CSS blur(15px))
            );
            gradientDrawable.setCenterColor(getColor(R.color.ime_key_pressed));
        }
        return gradientDrawable;
    }

    private void applyGradientBackground() {
        handler.post(() -> {
            GradientBorderDrawable drawable = getGradientDrawable();
            drawable.setHostView(btnRecord);
            GradientBorderDrawable.enableGlowClipping(btnRecord);
            btnRecord.setBackground(drawable);
            drawable.startAnimation();
        });
    }

    private void revertBackground() {
        handler.post(() -> {
            if (gradientDrawable != null) gradientDrawable.stopAnimation();
            GradientBorderDrawable.restoreGlowClipping(btnRecord);
            btnRecord.setBackgroundResource(R.drawable.ime_mic_background);
        });
    }

    @Override
    public void onCreate() {
        mContext = this;
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        deinitModel();
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
        super.onDestroy();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        if (attribute.inputType ==  EditorInfo.TYPE_NULL) {
            Log.d(TAG, "Cancelling: onStartInput: inputType=" + attribute.inputType + ", package=" + attribute.packageName + ", fieldId=" + attribute.fieldId);
            deinitModel();
            if (mRecorder != null && mRecorder.isInProgress()) {
                mRecorder.stop();
            }
        }
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        // A toggled (auto mode) recording must not keep running with the keyboard hidden
        if (mRecorder != null && mRecorder.isInProgress()) stopMicRecording();
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting){

        if (mWhisper == null)  initModel();

    }


    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateInputView() {  //runs before onStartInputView
        sp = PreferenceManager.getDefaultSharedPreferences(this);

        View view = getLayoutInflater().inflate(R.layout.voice_service, null);
        btnRecord = view.findViewById(R.id.btnRecord);
        btnKeyboard = view.findViewById(R.id.btnKeyboard);
        btnModeAuto = view.findViewById(R.id.btnModeAuto);
        btnEnter = view.findViewById(R.id.btnEnter);
        btnDel = view.findViewById(R.id.btnDel);
        btnBackend = view.findViewById(R.id.btnBackend);
        btnDot = view.findViewById(R.id.btnDot);
        btnSpace = view.findViewById(R.id.btnSpace);
        btnDash = view.findViewById(R.id.btnDash);
        btnBullet = view.findViewById(R.id.btnBullet);
        String backendType = sp.getString("backend_type", "local");
        btnBackend.setActivated(backendType.equals("openai"));

        tvStatus = view.findViewById(R.id.tv_status);
        modeAuto = sp.getBoolean("imeModeAuto",false);
        btnModeAuto.setActivated(modeAuto);
        layoutButtons = view.findViewById(R.id.layout_buttons);
        layoutToolbar = view.findViewById(R.id.layout_toolbar);
        btnToolbarToggle = view.findViewById(R.id.btnToolbarToggle);
        toolbarExpanded = sp.getBoolean("imeToolbarExpanded", true);
        updateToolbarVisibility();
        checkRecordPermission();

        // Audio recording functionality
        mRecorder = new Recorder(this);
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (message.equals(Recorder.MSG_RECORDING)) {
                    applyGradientBackground();
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    HapticFeedback.vibrate(mContext);
                    revertBackground();
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    HapticFeedback.vibrate(mContext);
                    handler.post(() -> {
                        revertBackground();
                        tvStatus.setText(getString(R.string.error_no_input));
                        tvStatus.setVisibility(View.VISIBLE);
                    });
                }
            }

        });
        // Stream chunks (Recorder.CHUNK_MS) to the transcriber while the mic is held
        mRecorder.setChunkListener(pcm -> {
            Whisper whisper = mWhisper;
            if (whisper != null) {
                whisper.enqueueChunk(pcm);
            }
        });


        // Tap: delete one character. Hold: delete whole words, accelerating.
        btnDel.setOnTouchListener(new View.OnTouchListener() {
            private static final long HOLD_DELAY_MS = 400;
            // Continuous ramp: 350ms -> 40ms over ~4.5s of holding (~30 words), then 25 words/s
            private static final long START_INTERVAL_MS = 350;
            private static final long MIN_INTERVAL_MS = 40;
            private static final float ACCELERATION = 0.93f;

            private long interval;

            private final Runnable repeatWordDelete = new Runnable() {
                @Override
                public void run() {
                    deleteWordBeforeCursor();
                    HapticFeedback.tick(mContext);
                    interval = Math.max(MIN_INTERVAL_MS, (long) (interval * ACCELERATION));
                    handler.postDelayed(this, interval);
                }
            };

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        InputConnection ic = getCurrentInputConnection();
                        if (ic != null) {
                            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
                        }
                        HapticFeedback.tick(mContext);
                        interval = START_INTERVAL_MS;
                        handler.postDelayed(repeatWordDelete, HOLD_DELAY_MS);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        handler.removeCallbacks(repeatWordDelete);
                        break;
                }
                return true;
            }
        });

        // Normal mode: hold to record. Auto mode: tap to start, tap again to stop.
        btnRecord.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            boolean recording = mRecorder != null && mRecorder.isInProgress();
            if (modeAuto) {
                if (action == MotionEvent.ACTION_DOWN) {
                    if (recording) stopMicRecording();
                    else startMicRecording();
                }
            } else if (action == MotionEvent.ACTION_DOWN) {
                startMicRecording();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                stopMicRecording();
            }
            return true;
        });

        // Same tick haptic as backspace, on press for all simple keys
        View.OnTouchListener tickOnPress = (v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) HapticFeedback.tick(mContext);
            return false; // let the click listener handle the action
        };
        for (View b : new View[]{btnKeyboard, btnEnter, btnDot, btnSpace, btnDash, btnBullet,
                btnModeAuto, btnToolbarToggle, btnBackend}) {
            b.setOnTouchListener(tickOnPress);
        }

        btnKeyboard.setOnClickListener(v -> {
            if (mWhisper != null) stopTranscription();
            switchToPreviousInputMethod();
        });

        btnEnter.setOnClickListener(v -> {
            getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        });

        btnDot.setOnClickListener(v -> getCurrentInputConnection().commitText(".", 1));
        btnSpace.setOnClickListener(v -> getCurrentInputConnection().commitText(" ", 1));
        btnDash.setOnClickListener(v -> getCurrentInputConnection().commitText("-", 1));
        // Bullet: start a new "- " list item on a new line (no leading newline on an empty line)
        btnBullet.setOnClickListener(v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            CharSequence before = ic.getTextBeforeCursor(1, 0);
            boolean atLineStart = before == null || before.length() == 0 || before.charAt(0) == '\n';
            ic.commitText(atLineStart ? "- " : "\n- ", 1);
        });

        btnModeAuto.setOnClickListener(v -> {
            // Switching modes mid-recording would leave the mic in the wrong state
            if (mRecorder != null && mRecorder.isInProgress()) stopMicRecording();
            modeAuto = !modeAuto;
            sp.edit().putBoolean("imeModeAuto", modeAuto).apply();
            btnModeAuto.setActivated(modeAuto);
        });

        btnToolbarToggle.setOnClickListener(v -> {
            toolbarExpanded = !toolbarExpanded;
            sp.edit().putBoolean("imeToolbarExpanded", toolbarExpanded).apply();
            updateToolbarVisibility();
        });

        btnBackend.setOnClickListener(v -> {
            String current = sp.getString("backend_type", "local");
            String next = current.equals("openai") ? "local" : "openai";
            Log.d(TAG, "btnBackend: " + current + " -> " + next);
            sp.edit().putString("backend_type", next).apply();
            btnBackend.setActivated(next.equals("openai"));
        });

        return view;
    }

    private void startRecording() {
        if (mWhisper != null) {
            mWhisper.setAction(ACTION_TRANSLATE);
            mWhisper.setLanguage(sp.getString("language", "auto"));
        }
        mRecorder.start();
    }

    private void startMicRecording() {
        applyGradientBackground();
        if (!checkRecordPermission()) return;
        if (mWhisper != null && !mWhisper.isInProgress()) {
            HapticFeedback.vibrate(this);
            startRecording();
            handler.post(() -> {
                tvStatus.setText("");
                tvStatus.setVisibility(View.GONE);
            });
        } else {
            handler.post(() -> {
                tvStatus.setText(getString(R.string.please_wait));
                tvStatus.setVisibility(View.VISIBLE);
            });
        }
    }

    private void stopMicRecording() {
        revertBackground();
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
    }

    // Model initialization
    private void initModel() {

        mWhisper = new Whisper(this);
        mWhisper.loadModel();
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (modeAuto && message.equals(OpenAITranscriber.MSG_EMPTY_TRANSCRIPTION)) {
                    // Auto mode records pauses too; silent chunks are expected, not an error
                    Log.d(TAG, "Ignoring empty transcription in auto mode");
                } else if (!message.equals(Whisper.MSG_PROCESSING)
                        && !message.equals(Whisper.MSG_PROCESSING_DONE)
                        && !message.equals(Whisper.MSG_QUEUE_EMPTY)) {  // status messages, not errors
                    handler.post(() -> {  // errors, e.g. network / API
                        tvStatus.setText(message);
                        tvStatus.setVisibility(View.VISIBLE);
                    });
                }
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                handler.post(() -> {
                    tvStatus.setText("");
                    tvStatus.setVisibility(View.GONE);
                });

                String result = whisperResult.getResult();
                if (whisperResult.getLanguage().equals("zh")){
                    boolean simpleChinese = sp.getBoolean("simpleChinese",false);
                    result = simpleChinese ? ZhConverterUtil.toSimple(result) : ZhConverterUtil.toTraditional(result);
                }
                String text = result.trim();
                handler.post(() -> {
                    InputConnection ic = getCurrentInputConnection();
                    if (text.length() > 0 && ic != null) ic.commitText(text + " ", 1);
                });
            }
        });
    }

    private void stopTranscription() {
        mWhisper.clearChunks();
        mWhisper.stop();
    }

    private boolean checkRecordPermission() {
        int permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText(getString(R.string.need_record_audio_permission));
        }
        return (permission == PackageManager.PERMISSION_GRANTED);
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }

    private void updateToolbarVisibility() {
        layoutToolbar.setVisibility(toolbarExpanded ? View.VISIBLE : View.GONE);
        btnToolbarToggle.setRotation(toolbarExpanded ? 180f : 0f);
    }

    /** Deletes the word (plus trailing whitespace) before the cursor, or the selection if any. */
    private void deleteWordBeforeCursor() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence selected = ic.getSelectedText(0);
        if (selected != null && selected.length() > 0) {
            ic.commitText("", 1);
            return;
        }
        CharSequence before = ic.getTextBeforeCursor(64, 0);
        if (before == null || before.length() == 0) return;
        int i = before.length();
        while (i > 0 && Character.isWhitespace(before.charAt(i - 1))) i--;
        if (i > 0 && Character.isLetterOrDigit(before.charAt(i - 1))) {
            while (i > 0 && Character.isLetterOrDigit(before.charAt(i - 1))) i--;
        } else {
            // punctuation / symbols / emoji run
            while (i > 0 && !Character.isWhitespace(before.charAt(i - 1))
                    && !Character.isLetterOrDigit(before.charAt(i - 1))) i--;
        }
        ic.deleteSurroundingText(before.length() - i, 0);
    }
}
