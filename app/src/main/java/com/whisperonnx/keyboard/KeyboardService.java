package com.whisperonnx.keyboard;

import com.whisperonnx.prefs.Prefs;
import com.whisperonnx.R;
import com.whisperonnx.backend.TranscriptionStatus;
import com.whisperonnx.backend.Whisper;
import com.whisperonnx.backend.WhisperResult;
import com.whisperonnx.endpoint.EndpointSender;
import com.whisperonnx.endpoint.SendFailure;
import com.whisperonnx.endpoint.SendSession;
import com.whisperonnx.recording.OtherAudioPauser;
import com.whisperonnx.recording.Recorder;
import com.whisperonnx.recording.RecorderEvent;
import com.whisperonnx.ui.Components;
import com.whisperonnx.ui.Labels;
import com.whisperonnx.ui.RecordGlow;
import com.whisperonnx.ui.HapticFeedback;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;

import android.os.Handler;
import android.os.Looper;
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
    private Prefs prefs = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SendSession session = new SendSession(new SendSession.Output() {
        @Override public void type(String text) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.commitText(text, 1);
        }
        @Override public void send(EndpointSender.Endpoint ep, String text) {
            EndpointSender.post(ep, Labels.endpoint(KeyboardService.this, ep), text, KeyboardService.this::onEndpointResult);
        }
    }, handler::post);
    private Context mContext;
    private boolean modeAuto = false;
    private ViewGroup layoutButtons;
    private ViewGroup layoutToolbar;
    private ImageButton btnToolbarToggle;
    private boolean toolbarExpanded;
    private RecordGlow glow;

    // External: Android turns the screen off mid-dictation unless the window keeps it on
    private void keepScreenOn(boolean on) {
        handler.post(() -> {
            if (layoutButtons != null) layoutButtons.setKeepScreenOn(on);
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
        if (mWhisper != null && (!mWhisper.getLocalEngine().equals(prefs.localEngine()) || mWhisper.modelInstalledSince())
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
        prefs = Prefs.of(this);
        View view = getLayoutInflater().inflate(R.layout.voice_service, null);
        bindViews(view);
        restoreState();
        checkRecordPermission();
        createRecorder();
        setUpKeys();
        return view;
    }

    private void bindViews(View view) {
        btnRecord = view.findViewById(R.id.btnRecord);
        glow = new RecordGlow(btnRecord, 8, getColor(R.color.ime_key_pressed), R.drawable.ime_mic_background, handler::post);
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
        tvStatus = view.findViewById(R.id.tv_status);
        tvStatusInline = view.findViewById(R.id.tv_status_inline);
        layoutButtons = view.findViewById(R.id.layout_buttons);
        layoutToolbar = view.findViewById(R.id.layout_toolbar);
        btnToolbarToggle = view.findViewById(R.id.btnToolbarToggle);
    }

    private void restoreState() {
        btnBackend.setActivated(prefs.useServer());
        modeAuto = prefs.modeAuto();
        btnModeAuto.setActivated(modeAuto);
        pauseOtherAudio = prefs.pauseOtherAudio();
        btnPauseAudio.setActivated(pauseOtherAudio);
        toolbarExpanded = prefs.toolbarExpanded();
        updateToolbarVisibility();
        sendTarget = prefs.sendTarget();
        if (sendTarget != 0 && !EndpointSender.load(prefs, sendTarget).isConfigured()) sendTarget = 0;
        updateSendTargetUi();
    }

    private void createRecorder() {
        mRecorder = new Recorder(this);
        mRecorder.setListener(this::onRecorderUpdate);
        mRecorder.setChunkListener(pcm -> {
            Whisper whisper = mWhisper;
            if (whisper != null) whisper.enqueueChunk(pcm);
        });
    }

    private void onRecorderUpdate(RecorderEvent event, String detail) {
        switch (event) {
            case RECORDING:
                glow.start();
                keepScreenOn(true);
                break;
            case DONE:
                endRecording();
                glow.stop();
                handler.post(this::onSessionEnded);
                break;
            case TOO_SHORT:
                endRecording();
                handler.post(this::onSessionEnded);
                handler.post(() -> {
                    glow.stop();
                    showStatus(getString(R.string.error_no_input));
                });
                break;
            default:
                keepScreenOn(false);
                otherAudio.release();
        }
    }

    private void endRecording() {
        HapticFeedback.vibrate(mContext);
        keepScreenOn(false);
        otherAudio.release();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setUpKeys() {
        btnDel.setOnTouchListener(new DeleteKeyTouch());
        btnRecord.setOnTouchListener((v, event) -> {
            onRecordTouch(event.getActionMasked());
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
        btnEnter.setOnClickListener(v -> pressEnter());
        btnDot.setOnClickListener(v -> getCurrentInputConnection().commitText(".", 1));
        btnSpace.setOnClickListener(v -> getCurrentInputConnection().commitText(" ", 1));
        float dp = getResources().getDisplayMetrics().density;
        CursorDrag cursorDrag = new CursorDrag(
                ViewConfiguration.get(mContext).getScaledTouchSlop(), 12 * dp, 24 * dp);
        btnSpace.setOnTouchListener((v, event) -> {
            onSpaceTouch(v, event, cursorDrag);
            return true;
        });
        btnDash.setOnClickListener(v -> getCurrentInputConnection().commitText("-", 1));
        btnBullet.setOnClickListener(v -> insertBullet());
        btnModeAuto.setOnClickListener(v -> toggleModeAuto());
        btnSendTarget.setOnClickListener(v -> cycleSendTarget());
        btnSettings.setOnClickListener(v -> {
            requestHideSelf(0);
            startActivity(Components.intent(this, Components.SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        });
        btnToolbarToggle.setOnClickListener(v -> {
            toolbarExpanded = !toolbarExpanded;
            prefs.setToolbarExpanded(toolbarExpanded);
            updateToolbarVisibility();
        });
        btnPauseAudio.setOnClickListener(v -> togglePauseAudio());
        btnBackend.setOnClickListener(v -> toggleBackend());
    }

    private final class DeleteKeyTouch implements View.OnTouchListener {
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

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sendKey(KeyEvent.KEYCODE_DEL);
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
    }

    private void sendKey(int keyCode) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    private void onRecordTouch(int action) {
        if (modeAuto) {
            if (action == MotionEvent.ACTION_DOWN) toggleRecording();
            return;
        }
        if (action == MotionEvent.ACTION_DOWN) startMicRecording();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) stopMicRecording();
    }

    private void toggleRecording() {
        if (mRecorder != null && mRecorder.isInProgress()) stopMicRecording();
        else startMicRecording();
    }

    private void onSpaceTouch(View v, MotionEvent event, CursorDrag cursorDrag) {
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
                releaseSpace(v, cursorDrag);
                break;
            case MotionEvent.ACTION_CANCEL:
                v.setPressed(false);
                break;
        }
    }

    private void releaseSpace(View v, CursorDrag cursorDrag) {
        v.setPressed(false);
        if (!cursorDrag.isDragging()) v.performClick();
    }

    private void pressEnter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        EditorInfo info = getCurrentInputEditorInfo();
        int action = TextEdits.enterAction(info == null ? 0 : info.imeOptions);
        if (action != EditorInfo.IME_ACTION_NONE && ic.performEditorAction(action)) return;
        sendKey(KeyEvent.KEYCODE_ENTER);
    }

    private void insertBullet() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(TextEdits.bulletInsert(ic.getTextBeforeCursor(1, 0)), 1);
    }

    private void toggleModeAuto() {
        if (mRecorder != null && mRecorder.isInProgress()) stopMicRecording();
        modeAuto = !modeAuto;
        prefs.setModeAuto(modeAuto);
        btnModeAuto.setActivated(modeAuto);
    }

    private void cycleSendTarget() {
        if (mRecorder != null && mRecorder.isInProgress()) stopMicRecording();
        int next = nextConfiguredEndpoint();
        if (next == 0 && sendTarget == 0) {
            showStatus(getString(R.string.endpoint_none_configured));
            return;
        }
        sendTarget = next;
        prefs.setSendTarget(sendTarget);
        updateSendTargetUi();
        showTransientStatus(sendTarget == 0
                ? getString(R.string.endpoint_target_cursor)
                : getString(R.string.endpoint_target, Labels.endpoint(this, EndpointSender.load(prefs, sendTarget))));
    }

    private int nextConfiguredEndpoint() {
        for (int i = sendTarget + 1; i <= EndpointSender.COUNT; i++) {
            if (EndpointSender.load(prefs, i).isConfigured()) return i;
        }
        return 0;
    }

    private void togglePauseAudio() {
        pauseOtherAudio = !pauseOtherAudio;
        prefs.setPauseOtherAudio(pauseOtherAudio);
        btnPauseAudio.setActivated(pauseOtherAudio);
        if (!pauseOtherAudio) otherAudio.release();
        else if (mRecorder != null && mRecorder.isInProgress()) otherAudio.pause();
        showTransientStatus(getString(pauseOtherAudio ? R.string.pause_audio_on : R.string.pause_audio_off));
    }

    private void toggleBackend() {
        boolean toLocal = prefs.useServer();
        if (toLocal && isBusy()) {
            showTransientStatus(getString(R.string.please_wait));
            return;
        }
        Log.d(TAG, "btnBackend: server " + !toLocal);
        prefs.setUseServer(!toLocal);
        btnBackend.setActivated(!toLocal);
        if (toLocal && needsLocalModel()) reloadModel();
    }

    private void startRecording() {
        if (mWhisper != null) {
            mWhisper.setTranslate(true);
        }
        mRecorder.setChunkMs(modeAuto ? 8000 : 30000);
        mRecorder.start();
    }

    private void startMicRecording() {
        glow.start();
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
        glow.stop();
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
                && !prefs.useServer();
    }

    private void reloadModel() {
        deinitModel();
        initModel();
        if (mWhisper.hasLocalModel() && !mWhisper.isModelMissing()) {
            loadingStatus = getString(R.string.loading_model, getString(
                    Prefs.ENGINE_PARAKEET.equals(mWhisper.getLocalEngine()) ? R.string.model_parakeet : R.string.model_whisper));
            showStatus(loadingStatus);
        }
    }

    private void initModel() {
        mWhisper = new Whisper(this);
        if (mWhisper.isModelMissing()) Components.openSetup(this);
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(TranscriptionStatus status, String detail) {
                onWhisperUpdate(status, detail);
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

    private void onWhisperUpdate(TranscriptionStatus status, String detail) {
        switch (status) {
            case MODEL_LOADED:
                handler.post(this::onModelLoaded);
                break;
            case QUEUE_EMPTY:
                handler.post(() -> session.onQueueEmpty());
                break;
            case EMPTY_TRANSCRIPTION:
                onEmptyTranscription();
                break;
            default:
                showProblem(status, detail);
        }
    }

    private void onEmptyTranscription() {
        if (modeAuto) return;
        handler.post(() -> showStatus(getString(R.string.error_no_input)));
    }

    private void showProblem(TranscriptionStatus status, String detail) {
        if (!status.isProblem) return;
        handler.post(() -> showStatus(Labels.problem(this, status, detail)));
    }

    private void onModelLoaded() {
        if (loadingStatus != null && loadingStatus.equals(statusMessage)) clearStatus();
        loadingStatus = null;
    }

    private void beginSession() {
        session.begin(sendTarget == 0 ? null : EndpointSender.load(prefs, sendTarget));
    }

    private void onSessionEnded() {
        session.end(mWhisper != null && mWhisper.hasPendingChunks());
    }

    private void onEndpointResult(EndpointSender.Endpoint ep, String text, SendFailure failure) {
        if (failure == null) {
            HapticFeedback.tick(mContext);
            showTransientStatus(getString(R.string.endpoint_sent, Labels.endpoint(this, ep)));
        } else {
            HapticFeedback.vibrate(mContext);
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Dictation", text));
            showStatus(getString(R.string.endpoint_failed, Labels.endpoint(this, ep), Labels.sendFailure(this, failure)));
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
