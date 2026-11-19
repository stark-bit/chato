package com.whisperonnx;


import static com.whisperonnx.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSLATE;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;

import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.ImageButton;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
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
    private ImageButton btnLang1;
    private ImageButton btnLang2;
    private ImageButton btnBackend;
    private ImageButton btnDot;
    private Button btnSpace;
    private ImageButton btnDash;
    private TextView tvStatus;
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private ProgressBar processingBar = null;
    private SharedPreferences sp = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Context mContext;
    private CountDownTimer countDownTimer;
    private boolean modeAuto = false;
    private ViewGroup layoutButtons;
    private GradientBorderDrawable gradientDrawable;

    private GradientBorderDrawable getGradientDrawable() {
        if (gradientDrawable == null) {
            float density = getResources().getDisplayMetrics().density;
            gradientDrawable = new GradientBorderDrawable(
                10 * density,   // cornerRadius (matches XML 10dp)
                0.5f * density, // borderWidth (near-invisible)
                15 * density    // glowBlur (CSS blur(15px))
            );
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
            btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
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
    public void onStartInputView(EditorInfo attribute, boolean restarting){

        if (mWhisper == null)  initModel();

    }


    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateInputView() {  //runs before onStartInputView
        sp = PreferenceManager.getDefaultSharedPreferences(this);

        String langCodeIME = sp.getString("language", "auto");

        if (!sp.contains("langSelected")){
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt("langSelected",1);
            editor.putString("language1",langCodeIME);
            editor.putString("language2","auto");
            editor.commit();
        }

        View view = getLayoutInflater().inflate(R.layout.voice_service, null);
        btnRecord = view.findViewById(R.id.btnRecord);
        btnKeyboard = view.findViewById(R.id.btnKeyboard);
        btnModeAuto = view.findViewById(R.id.btnModeAuto);
        btnEnter = view.findViewById(R.id.btnEnter);
        btnDel = view.findViewById(R.id.btnDel);
        btnLang1 = view.findViewById(R.id.btnLang1);
        btnLang2 = view.findViewById(R.id.btnLang2);
        btnBackend = view.findViewById(R.id.btnBackend);
        btnDot = view.findViewById(R.id.btnDot);
        btnSpace = view.findViewById(R.id.btnSpace);
        btnDash = view.findViewById(R.id.btnDash);
        String backendType = sp.getString("backend_type", "local");
        btnBackend.setImageResource(backendType.equals("openai") ? R.drawable.ic_cloud_on_36dp : R.drawable.ic_cloud_off_36dp);
        int langSelected = sp.getInt("langSelected", 1);
        if (langSelected == 1) {
            btnLang1.setImageResource(R.drawable.ic_counter_1_on_36dp);
            btnLang2.setImageResource(R.drawable.ic_counter_2_off_36dp);
        } else {
            btnLang1.setImageResource(R.drawable.ic_counter_1_off_36dp);
            btnLang2.setImageResource(R.drawable.ic_counter_2_on_36dp);
        }

        processingBar = view.findViewById(R.id.processing_bar);
        tvStatus = view.findViewById(R.id.tv_status);
        modeAuto = sp.getBoolean("imeModeAuto",false);
        btnModeAuto.setImageResource(modeAuto ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
        layoutButtons = view.findViewById(R.id.layout_buttons);
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
                    startTranscription();
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    HapticFeedback.vibrate(mContext);
                    if (countDownTimer!=null) { countDownTimer.cancel();}
                    handler.post(() -> {
                        revertBackground();
                        tvStatus.setText(getString(R.string.error_no_input));
                        tvStatus.setVisibility(View.VISIBLE);
                        processingBar.setProgress(0);
                    });
                }
            }

        });

        if (modeAuto) {
            layoutButtons.setVisibility(View.GONE);
            HapticFeedback.vibrate(this);
            startRecording();
            handler.post(() -> processingBar.setProgress(100));
            countDownTimer = new CountDownTimer(30000, 1000) {
                @Override
                public void onTick(long l) {
                    handler.post(() -> processingBar.setProgress((int) (l / 300)));
                }
                @Override
                public void onFinish() {}
            };
            countDownTimer.start();
            handler.post(() -> {
                tvStatus.setText("");
                tvStatus.setVisibility(View.GONE);
            });
        }

        // Tap: delete one character. Hold: delete whole words, accelerating.
        btnDel.setOnTouchListener(new View.OnTouchListener() {
            private static final long HOLD_DELAY_MS = 400;
            private static final long START_INTERVAL_MS = 300;
            private static final long MIN_INTERVAL_MS = 60;
            private static final float ACCELERATION = 0.8f;

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

        btnRecord.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                // Pressed
                applyGradientBackground();
                if (checkRecordPermission()){
                    if (!mWhisper.isInProgress()) {
                        HapticFeedback.vibrate(this);
                        startRecording();
                        handler.post(() -> processingBar.setProgress(100));
                        countDownTimer = new CountDownTimer(30000, 1000) {
                            @Override
                            public void onTick(long l) {
                                handler.post(() -> processingBar.setProgress((int) (l / 300)));
                            }
                            @Override
                            public void onFinish() {}
                        };
                        countDownTimer.start();
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
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                // Released
                revertBackground();
                if (mRecorder != null && mRecorder.isInProgress()) {
                    mRecorder.stop();
                }
            }
            return true;
        });

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

        btnModeAuto.setOnClickListener(v -> {
            modeAuto = !modeAuto;
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean("imeModeAuto", modeAuto);
            editor.apply();
            layoutButtons.setVisibility(modeAuto ? View.GONE : View.VISIBLE);
            btnModeAuto.setImageResource(modeAuto ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
            switchToPreviousInputMethod();
        });

        btnLang1.setOnClickListener(v -> {
            String lang = sp.getString("language1", "auto");
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt("langSelected", 1);
            editor.putString("language", lang);
            editor.apply();
            btnLang1.setImageResource(R.drawable.ic_counter_1_on_36dp);
            btnLang2.setImageResource(R.drawable.ic_counter_2_off_36dp);
        });

        btnLang2.setOnClickListener(v -> {
            String lang = sp.getString("language2", "auto");
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt("langSelected", 2);
            editor.putString("language", lang);
            editor.apply();
            btnLang1.setImageResource(R.drawable.ic_counter_1_off_36dp);
            btnLang2.setImageResource(R.drawable.ic_counter_2_on_36dp);
        });

        btnBackend.setOnClickListener(v -> {
            String current = sp.getString("backend_type", "local");
            String next = current.equals("openai") ? "local" : "openai";
            Log.d(TAG, "btnBackend: " + current + " -> " + next);
            sp.edit().putString("backend_type", next).apply();
            btnBackend.setImageResource(next.equals("openai") ? R.drawable.ic_cloud_on_36dp : R.drawable.ic_cloud_off_36dp);
        });

        return view;
    }

    private void startRecording() {
        if (modeAuto) mRecorder.initVad();
        mRecorder.start();
    }

    // Model initialization
    private void initModel() {

        mWhisper = new Whisper(this);
        mWhisper.loadModel();
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                handler.post(() -> processingBar.setIndeterminate(false));
                handler.post(() -> {
                    tvStatus.setText("");
                    tvStatus.setVisibility(View.GONE);
                });

                String result = whisperResult.getResult();
                if (whisperResult.getLanguage().equals("zh")){
                    boolean simpleChinese = sp.getBoolean("simpleChinese",false);
                    result = simpleChinese ? ZhConverterUtil.toSimple(result) : ZhConverterUtil.toTraditional(result);
                }
                boolean commitSuccess = false;
                if (result.trim().length() > 0) commitSuccess = getCurrentInputConnection().commitText(result.trim() + " ",1);
                if (modeAuto && commitSuccess) handler.postDelayed(() -> switchToPreviousInputMethod(), 100);  //slightly delayed, otherwise some apps, e.g. WhatsApp, do not accept the committed text (commitText on inactive InputConnection)
            }
        });
    }

    private void startTranscription() {
        if (countDownTimer!=null) { countDownTimer.cancel();}
        handler.post(() -> processingBar.setProgress(0));
        handler.post(() -> processingBar.setIndeterminate(true));
        if (mWhisper!=null){
            mWhisper.setAction(ACTION_TRANSLATE);

            String langCode = sp.getString("language", "auto");
            Log.d("WhisperIME","default langCode " + langCode);
            mWhisper.setLanguage(langCode);
            mWhisper.start();
        }
    }

    private void stopTranscription() {
        handler.post(() -> processingBar.setIndeterminate(false));
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
