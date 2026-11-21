package com.whisperonnx;


import static com.whisperonnx.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSLATE;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
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

import com.whisperonnx.asr.OpenAITranscriber;
import com.whisperonnx.asr.Recorder;
import com.whisperonnx.asr.Whisper;
import com.whisperonnx.asr.WhisperResult;
import com.whisperonnx.utils.EndpointSender;
import com.whisperonnx.utils.SendSession;
import com.whisperonnx.utils.TextEdits;
import com.whisperonnx.utils.HapticFeedback;
import com.whisperonnx.utils.OtherAudioPauser;
import com.whisperonnx.utils.GradientBorderDrawable;

public class WhisperInputMethodService extends InputMethodService {
    private static final String TAG = "WhisperInputMethodService";
    private ImageButton btnRecord;
    private ImageButton btnKeyboard;
    private ImageButton btnModeAuto;
    private ImageButton btnEnter;
    private ImageButton btnDel;
    private ImageButton btnBackend;
    private ImageButton btnPauseAudio;
    private OtherAudioPauser otherAudio;
    private boolean pauseOtherAudio;
    private ImageButton btnDot;
    private ImageButton btnSpace;
    private ImageButton btnDash;
    private ImageButton btnBullet;
    private ImageButton btnSendTarget;
    private ImageButton btnSettings;
    private TextView tvSendTargetBadge;
    // 0 = type at cursor, otherwise 1-based endpoint index
    private int sendTarget = 0;
    // Endpoint routing for the current recording; null = type at cursor
    private TextView tvStatus;        // line above the main row, used while the top row is collapsed
    private TextView tvStatusInline;  // gap in the top row, used while it is expanded
    private String statusMessage = "";
    private String loadingStatus;     // "Loading Parakeet…" while a model loads after a switch
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private SharedPreferences sp = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SendSession session = new SendSession(new SendSession.Output() {
        @Override public void type(String text) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.commitText(text, 1);
        }
        @Override public void send(EndpointSender.Endpoint ep, String text) {
            EndpointSender.post(ep, text, WhisperInputMethodService.this::onEndpointResult);
        }
    }, handler::post);
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

    /**
     * Keeps the screen awake while recording. Auto mode runs hands-free, so without this the
     * screen times out mid-dictation. Applies only while the keyboard is showing.
     */
    private void keepScreenOn(boolean on) {
        handler.post(() -> {
            if (layoutButtons != null) layoutButtons.setKeepScreenOn(on);
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
        otherAudio = new OtherAudioPauser(this);
    }

    @Override
    public void onDestroy() {
        deinitModel();
        otherAudio.release();
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

        // Reload if the local engine was changed in Settings, or a model was installed since loading
        if (mWhisper != null && (!mWhisper.getLocalEngine().equals(Whisper.localEngine(sp)) || mWhisper.modelInstalledSince())
                && !(mRecorder != null && mRecorder.isInProgress())) {
            deinitModel();
        }
        if (mWhisper == null) {
            initModel();
        } else if (needsLocalModel() && !isBusy()) {
            reloadModel();  // switched to local in Settings while this keyboard used the server
        }

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
        btnPauseAudio = view.findViewById(R.id.btnPauseAudio);
        btnDot = view.findViewById(R.id.btnDot);
        btnSpace = view.findViewById(R.id.btnSpace);
        btnDash = view.findViewById(R.id.btnDash);
        btnBullet = view.findViewById(R.id.btnBullet);
        btnSendTarget = view.findViewById(R.id.btnSendTarget);
        btnSettings = view.findViewById(R.id.btnSettings);
        tvSendTargetBadge = view.findViewById(R.id.tvSendTargetBadge);
        String backendType = sp.getString("backend_type", "local");
        btnBackend.setActivated(backendType.equals("openai"));

        tvStatus = view.findViewById(R.id.tv_status);
        tvStatusInline = view.findViewById(R.id.tv_status_inline);
        modeAuto = sp.getBoolean("imeModeAuto",false);
        btnModeAuto.setActivated(modeAuto);
        pauseOtherAudio = sp.getBoolean("imePauseOtherAudio", false);
        btnPauseAudio.setActivated(pauseOtherAudio);
        layoutButtons = view.findViewById(R.id.layout_buttons);
        layoutToolbar = view.findViewById(R.id.layout_toolbar);
        btnToolbarToggle = view.findViewById(R.id.btnToolbarToggle);
        toolbarExpanded = sp.getBoolean("imeToolbarExpanded", true);
        updateToolbarVisibility();
        sendTarget = sp.getInt("imeSendTarget", 0);
        if (sendTarget != 0 && !EndpointSender.load(sp, sendTarget).isConfigured()) sendTarget = 0;
        updateSendTargetUi();
        checkRecordPermission();

        // Audio recording functionality
        mRecorder = new Recorder(this);
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (message.equals(Recorder.MSG_RECORDING)) {
                    applyGradientBackground();
                    keepScreenOn(true);
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    HapticFeedback.vibrate(mContext);
                    keepScreenOn(false);
                    otherAudio.release();
                    revertBackground();
                    handler.post(() -> onSessionEnded());
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    HapticFeedback.vibrate(mContext);
                    keepScreenOn(false);
                    otherAudio.release();
                    handler.post(() -> onSessionEnded());
                    handler.post(() -> {
                        revertBackground();
                        showStatus(getString(R.string.error_no_input));
                    });
                } else {
                    keepScreenOn(false);  // recorder failed (permission, device error)
                    otherAudio.release();
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
                btnModeAuto, btnToolbarToggle, btnPauseAudio, btnBackend, btnSendTarget, btnSettings}) {
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
            ic.commitText(TextEdits.bulletInsert(ic.getTextBeforeCursor(1, 0)), 1);
        });

        btnModeAuto.setOnClickListener(v -> {
            // Switching modes mid-recording would leave the mic in the wrong state
            if (mRecorder != null && mRecorder.isInProgress()) stopMicRecording();
            modeAuto = !modeAuto;
            sp.edit().putBoolean("imeModeAuto", modeAuto).apply();
            btnModeAuto.setActivated(modeAuto);
        });

        // Cycle: cursor -> endpoint 1 -> endpoint 2 -> cursor, skipping endpoints without a URL
        btnSendTarget.setOnClickListener(v -> {
            if (mRecorder != null && mRecorder.isInProgress()) stopMicRecording();
            int next = 0;
            for (int i = sendTarget + 1; i <= EndpointSender.COUNT; i++) {
                if (EndpointSender.load(sp, i).isConfigured()) { next = i; break; }
            }
            if (next == 0 && sendTarget == 0) {
                showStatus(getString(R.string.endpoint_none_configured));
                return;
            }
            sendTarget = next;
            sp.edit().putInt("imeSendTarget", sendTarget).apply();
            updateSendTargetUi();
            showTransientStatus(sendTarget == 0
                    ? getString(R.string.endpoint_target_cursor)
                    : getString(R.string.endpoint_target, EndpointSender.load(sp, sendTarget).displayName()));
        });

        btnSettings.setOnClickListener(v -> {
            requestHideSelf(0);
            startActivity(new Intent(this, SettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        });

        btnToolbarToggle.setOnClickListener(v -> {
            toolbarExpanded = !toolbarExpanded;
            sp.edit().putBoolean("imeToolbarExpanded", toolbarExpanded).apply();
            updateToolbarVisibility();
        });

        // Pause other audio key: takes effect at once, also mid-recording
        btnPauseAudio.setOnClickListener(v -> {
            pauseOtherAudio = !pauseOtherAudio;
            sp.edit().putBoolean("imePauseOtherAudio", pauseOtherAudio).apply();
            btnPauseAudio.setActivated(pauseOtherAudio);
            if (!pauseOtherAudio) otherAudio.release();
            else if (mRecorder != null && mRecorder.isInProgress()) otherAudio.pause();
            showTransientStatus(getString(pauseOtherAudio ? R.string.pause_audio_on : R.string.pause_audio_off));
        });

        btnBackend.setOnClickListener(v -> {
            String current = sp.getString("backend_type", "local");
            String next = current.equals("openai") ? "local" : "openai";
            boolean toLocal = next.equals("local");
            if (toLocal && isBusy()) {
                // Loading a model would cancel the recording or the chunks still being transcribed
                showTransientStatus(getString(R.string.please_wait));
                return;
            }
            Log.d(TAG, "btnBackend: " + current + " -> " + next);
            sp.edit().putString("backend_type", next).apply();
            btnBackend.setActivated(!toLocal);
            if (toLocal && needsLocalModel()) reloadModel();
        });

        return view;
    }

    private void startRecording() {
        if (mWhisper != null) {
            mWhisper.setAction(ACTION_TRANSLATE);
        }
        // Auto mode streams text every 8 s; hold-to-talk sends 30 s pieces for better context
        mRecorder.setChunkMs(modeAuto ? 8000 : 30000);
        mRecorder.start();
    }

    private void startMicRecording() {
        applyGradientBackground();
        if (!checkRecordPermission()) return;
        if (mWhisper != null && !mWhisper.isInProgress()) {
            HapticFeedback.vibrate(this);
            beginSession();
            // Before the mic opens, so the first words aren't mixed with the phone's audio
            if (pauseOtherAudio) otherAudio.pause();
            startRecording();
            handler.post(() -> {
                clearStatus();
            });
        } else {
            handler.post(() -> {
                showStatus(getString(R.string.please_wait));
            });
        }
    }

    private void stopMicRecording() {
        revertBackground();
        keepScreenOn(false);
        otherAudio.release();
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
    }

    private boolean isBusy() {
        return (mRecorder != null && mRecorder.isInProgress())
                || (mWhisper != null && (mWhisper.isInProgress() || mWhisper.hasPendingChunks()));
    }

    /**
     * True if the local backend is selected but this keyboard's model instance has no local model,
     * because it was created while the server was selected.
     */
    private boolean needsLocalModel() {
        return mWhisper != null && !mWhisper.hasLocalModel()
                && !OpenAITranscriber.BACKEND_OPENAI.equals(
                        sp.getString(OpenAITranscriber.PREF_BACKEND_TYPE, OpenAITranscriber.BACKEND_LOCAL));
    }

    /** Creates a fresh model instance and shows "Loading <model>…" until it is ready. */
    private void reloadModel() {
        deinitModel();
        initModel();
        if (mWhisper.hasLocalModel() && !mWhisper.isModelMissing()) {
            loadingStatus = getString(R.string.loading_model, getString(
                    Whisper.ENGINE_PARAKEET.equals(mWhisper.getLocalEngine()) ? R.string.model_parakeet : R.string.model_whisper));
            showStatus(loadingStatus);
        }
    }

    // Model initialization
    private void initModel() {

        mWhisper = new Whisper(this);
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (message.equals(Whisper.MSG_MODEL_LOADED)) {
                    handler.post(() -> {
                        if (loadingStatus != null && loadingStatus.equals(statusMessage)) clearStatus();
                        loadingStatus = null;
                    });
                    return;
                }
                if (message.equals(Whisper.MSG_QUEUE_EMPTY)) {
                    // Posted after the last result, so a combined message is complete here
                    handler.post(() -> session.onQueueEmpty());
                }
                if (modeAuto && message.equals(OpenAITranscriber.MSG_EMPTY_TRANSCRIPTION)) {
                    // Auto mode records pauses too; silent chunks are expected, not an error
                    Log.d(TAG, "Ignoring empty transcription in auto mode");
                } else if (!message.equals(Whisper.MSG_PROCESSING)
                        && !message.equals(Whisper.MSG_PROCESSING_DONE)
                        && !message.equals(Whisper.MSG_QUEUE_EMPTY)) {  // status messages, not errors
                    handler.post(() -> {  // errors, e.g. network / API
                        showStatus(message);
                    });
                }
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                handler.post(() -> {
                    clearStatus();
                });

                String text = whisperResult.getResult().trim();
                handler.post(() -> session.deliver(text));
            }
        });
        mWhisper.loadModel();
    }

    /** Called when a recording starts: locks in the target for this recording. */
    private void beginSession() {
        session.begin(sendTarget == 0 ? null : EndpointSender.load(sp, sendTarget));
    }

    private void onSessionEnded() {
        session.end(mWhisper != null && mWhisper.hasPendingChunks());
    }

    private void onEndpointResult(EndpointSender.Endpoint ep, String text, String error) {
        if (error == null) {
            HapticFeedback.tick(mContext);
            showTransientStatus(getString(R.string.endpoint_sent, ep.displayName()));
        } else {
            HapticFeedback.vibrate(mContext);
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Dictation", text));
            showStatus(getString(R.string.endpoint_failed, ep.displayName(), error));
        }
    }

    private void updateSendTargetUi() {
        boolean active = sendTarget != 0;
        btnSendTarget.setActivated(active);
        tvSendTargetBadge.setVisibility(active ? View.VISIBLE : View.GONE);
        tvSendTargetBadge.setText(String.valueOf(sendTarget));
        // Mic turns accent-coloured so the target is visible even with the top row collapsed
        btnRecord.setImageTintList(ColorStateList.valueOf(
                getColor(active ? R.color.ime_accent : R.color.ime_on_key)));
    }

    /** Shows a status/error message without moving the keys (see updateStatusViews). Main thread only. */
    private void showStatus(String message) {
        handler.removeCallbacks(hideStatus);
        statusMessage = message;
        updateStatusViews();
    }

    private void clearStatus() {
        handler.removeCallbacks(hideStatus);
        statusMessage = "";
        updateStatusViews();
    }

    private final Runnable hideStatus = this::clearStatus;

    /**
     * With the top row expanded the message sits in its empty middle, so the keyboard height
     * never changes. Collapsed, it falls back to a line above the main row.
     */
    private void updateStatusViews() {
        boolean empty = statusMessage.isEmpty();
        tvStatusInline.setText(toolbarExpanded ? statusMessage : "");
        tvStatus.setText(toolbarExpanded ? "" : statusMessage);
        tvStatus.setVisibility(!toolbarExpanded && !empty ? View.VISIBLE : View.GONE);
    }

    private void showTransientStatus(String message) {
        showStatus(message);
        handler.postDelayed(hideStatus, 1500);
    }

    private void stopTranscription() {
        mWhisper.clearChunks();
        mWhisper.stop();
    }

    private boolean checkRecordPermission() {
        int permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            showStatus(getString(R.string.need_record_audio_permission));
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
        updateStatusViews();
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
        int n = TextEdits.wordDeleteLength(ic.getTextBeforeCursor(64, 0));
        if (n > 0) ic.deleteSurroundingText(n, 0);
    }
}
