package com.whisperonnx.keyboard;

import com.whisperonnx.R;
import com.whisperonnx.backend.OpenAITranscriber;
import com.whisperonnx.backend.Whisper;
import com.whisperonnx.backend.WhisperResult;
import com.whisperonnx.endpoint.EndpointSender;
import com.whisperonnx.endpoint.SendSession;
import com.whisperonnx.recording.OtherAudioPauser;
import com.whisperonnx.recording.Recorder;
import com.whisperonnx.ui.Components;
import com.whisperonnx.ui.GradientBorderDrawable;
import com.whisperonnx.ui.HapticFeedback;

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
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.ImageButton;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

public class KeyboardService extends InputMethodService {
    private static final String TAG = "KeyboardService";
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
    private int sendTarget = 0;
    private TextView tvStatus;
    private TextView tvStatusInline;
    private String statusMessage = "";
    private String loadingStatus;
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
            EndpointSender.post(ep, text, KeyboardService.this::onEndpointResult);
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
                8 * density,
                0.5f * density,
                15 * density
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

    // External: Android turns the screen off mid-dictation unless the window keeps it on
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
        if (mRecorder != null && mRecorder.isInProgress()) stopMicRecording();
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting){
        if (mWhisper != null && (!mWhisper.getLocalEngine().equals(Whisper.localEngine(sp)) || mWhisper.modelInstalledSince())
                && !(mRecorder != null && mRecorder.isInProgress())) {
            deinitModel();
        }
        if (mWhisper == null) {
            initModel();
        } else if (needsLocalModel() && !isBusy()) {
            reloadModel();
        }

    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateInputView() {  // External: Android calls this before onStartInputView
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
                    keepScreenOn(false);
                    otherAudio.release();
                }
            }

        });
        mRecorder.setChunkListener(pcm -> {
            Whisper whisper = mWhisper;
            if (whisper != null) {
                whisper.enqueueChunk(pcm);
            }
        });

        btnDel.setOnTouchListener(new View.OnTouchListener() {
            private static final long HOLD_DELAY_MS = 400;
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

        View.OnTouchListener tickOnPress = (v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) HapticFeedback.tick(mContext);
            return false;
        };
        for (View b : new View[]{btnKeyboard, btnEnter, btnDot, btnDash, btnBullet,
                btnModeAuto, btnToolbarToggle, btnPauseAudio, btnBackend, btnSendTarget, btnSettings}) {
            b.setOnTouchListener(tickOnPress);
        }

        btnKeyboard.setOnClickListener(v -> {
            if (mWhisper != null) stopTranscription();
            switchToPreviousInputMethod();
        });

        btnEnter.setOnClickListener(v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            EditorInfo info = getCurrentInputEditorInfo();
            int action = TextEdits.enterAction(info == null ? 0 : info.imeOptions);
            if (action != EditorInfo.IME_ACTION_NONE && ic.performEditorAction(action)) return;
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        });

        btnDot.setOnClickListener(v -> getCurrentInputConnection().commitText(".", 1));
        btnSpace.setOnClickListener(v -> getCurrentInputConnection().commitText(" ", 1));
        float dp = getResources().getDisplayMetrics().density;
        CursorDrag cursorDrag = new CursorDrag(
                ViewConfiguration.get(mContext).getScaledTouchSlop(), 12 * dp, 24 * dp);
        btnSpace.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    HapticFeedback.tick(mContext);
                    cursorDrag.start(event.getRawX(), event.getRawY());
                    v.setPressed(true);
                    break;
                case MotionEvent.ACTION_MOVE:
                    int[] steps = cursorDrag.move(event.getRawX(), event.getRawY());
                    moveCursor(steps[0], KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT);
                    moveCursor(steps[1], KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN);
                    break;
                case MotionEvent.ACTION_UP:
                    v.setPressed(false);
                    if (!cursorDrag.isDragging()) v.performClick();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    break;
            }
            return true;
        });
        btnDash.setOnClickListener(v -> getCurrentInputConnection().commitText("-", 1));
        btnBullet.setOnClickListener(v -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            ic.commitText(TextEdits.bulletInsert(ic.getTextBeforeCursor(1, 0)), 1);
        });

        btnModeAuto.setOnClickListener(v -> {
            if (mRecorder != null && mRecorder.isInProgress()) stopMicRecording();
            modeAuto = !modeAuto;
            sp.edit().putBoolean("imeModeAuto", modeAuto).apply();
            btnModeAuto.setActivated(modeAuto);
        });

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
            startActivity(Components.intent(this, Components.SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        });

        btnToolbarToggle.setOnClickListener(v -> {
            toolbarExpanded = !toolbarExpanded;
            sp.edit().putBoolean("imeToolbarExpanded", toolbarExpanded).apply();
            updateToolbarVisibility();
        });

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
            mWhisper.setTranslate(true);
        }
        mRecorder.setChunkMs(modeAuto ? 8000 : 30000);
        mRecorder.start();
    }

    private void startMicRecording() {
        applyGradientBackground();
        if (!checkRecordPermission()) return;
        if (mWhisper != null && !mWhisper.isInProgress()) {
            HapticFeedback.vibrate(this);
            beginSession();
            // External: pause before the mic opens, so the first words aren't mixed with the phone's audio
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

    private boolean needsLocalModel() {
        return mWhisper != null && !mWhisper.hasLocalModel()
                && !OpenAITranscriber.BACKEND_OPENAI.equals(
                        sp.getString(OpenAITranscriber.PREF_BACKEND_TYPE, OpenAITranscriber.BACKEND_LOCAL));
    }

    private void reloadModel() {
        deinitModel();
        initModel();
        if (mWhisper.hasLocalModel() && !mWhisper.isModelMissing()) {
            loadingStatus = getString(R.string.loading_model, getString(
                    Whisper.ENGINE_PARAKEET.equals(mWhisper.getLocalEngine()) ? R.string.model_parakeet : R.string.model_whisper));
            showStatus(loadingStatus);
        }
    }

    private void initModel() {
        mWhisper = new Whisper(this);
        if (mWhisper.isModelMissing()) Components.openSetup(this);
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
                    handler.post(() -> session.onQueueEmpty());
                }
                if (modeAuto && message.equals(OpenAITranscriber.MSG_EMPTY_TRANSCRIPTION)) {
                    Log.d(TAG, "Ignoring empty transcription in auto mode");
                } else if (!message.equals(Whisper.MSG_PROCESSING)
                        && !message.equals(Whisper.MSG_PROCESSING_DONE)
                        && !message.equals(Whisper.MSG_QUEUE_EMPTY)) {
                    handler.post(() -> {
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
        btnRecord.setImageTintList(ColorStateList.valueOf(
                getColor(active ? R.color.ime_accent : R.color.ime_on_key)));
    }

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

    private void moveCursor(int steps, int back, int forward) {
        if (steps == 0) return;
        int key = steps < 0 ? back : forward;
        for (int i = Math.abs(steps); i > 0; i--) sendDownUpKeyEvents(key);
        HapticFeedback.tick(mContext);
    }

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
