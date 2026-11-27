package com.whisperonnx.voiceinput;

import com.whisperonnx.R;
import com.whisperonnx.backend.TranscriptionStatus;
import com.whisperonnx.backend.Whisper;
import com.whisperonnx.backend.WhisperResult;
import com.whisperonnx.recording.Recorder;
import com.whisperonnx.recording.RecorderEvent;
import com.whisperonnx.ui.Components;
import com.whisperonnx.ui.HapticFeedback;

import static android.speech.SpeechRecognizer.ERROR_CLIENT;
import static android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;

public class VoiceInputService extends RecognitionService {
    private static final String TAG = "VoiceInputService";
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private boolean recognitionCancelled = false;

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback callback) {
        checkRecordPermission(callback);

        initModel(callback);

        mRecorder = new Recorder(this);
        mRecorder.setListener((event, detail) -> onRecorderUpdate(event, callback));

        if (!mWhisper.isInProgress()) {
            HapticFeedback.vibrate(this);
            startRecording();
            call(callback::beginningOfSpeech);
        }
    }

    private void onRecorderUpdate(RecorderEvent event, Callback callback) {
        switch (event) {
            case RECORDING:
                call(() -> callback.rmsChanged(10));
                break;
            case DONE:
                HapticFeedback.vibrate(this);
                call(() -> callback.rmsChanged(-20.0f));
                startTranscription();
                break;
            case TOO_SHORT:
                call(() -> callback.error(ERROR_CLIENT));
                break;
        }
    }

    private interface RemoteCall {
        void run() throws RemoteException;
    }

    private static void call(RemoteCall remoteCall) {
        try {
            remoteCall.run();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private void stopRecording() {
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
    }

    @Override
    protected void onCancel(Callback callback) {
        Log.d(TAG,"cancel");
        stopRecording();
        deinitModel();
        recognitionCancelled = true;
    }

    @Override
    protected void onStopListening(Callback callback) {
        Log.d(TAG,"StopListening");
        stopRecording();
    }

    private void initModel(Callback callback) {
        mWhisper = new Whisper(this);
        if (mWhisper.isModelMissing()) Components.openSetup(this);
        mWhisper.loadModel();
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(TranscriptionStatus status, String detail) { }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                String result = whisperResult.getResult().trim();
                if (result.isEmpty()) return;
                Log.d(TAG, result);
                call(() -> deliver(callback, result));
            }
        });
    }

    private void deliver(Callback callback, String result) throws RemoteException {
        callback.endOfSpeech();
        deinitModel();
        Bundle results = new Bundle();
        ArrayList<String> resultList = new ArrayList<>();
        resultList.add(result);
        results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, resultList);
        callback.results(results);
    }

    private void startRecording() {
        mRecorder.initVad();
        mRecorder.start();
        recognitionCancelled = false;
    }

    private void startTranscription() {
        if (recognitionCancelled) return;
        new Handler(Looper.getMainLooper()).post(this::showProcessingToast);
        mWhisper.setTranslate(false);
        mWhisper.start();
        Log.d(TAG,"Start Transcription");
    }

    private void showProcessingToast() {
        Toast toast = new Toast(this);
        toast.setDuration(Toast.LENGTH_SHORT);
        toast.setText(R.string.processing);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            toast.addCallback(new Toast.Callback() {
                @Override
                public void onToastHidden() {
                    super.onToastHidden();
                    if (mWhisper != null) toast.show();
                }
            });
        }
        toast.show();
    }

    @Override
    public void onDestroy (){
        deinitModel();
    }
    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }

    private void checkRecordPermission(Callback callback) {
        int permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO);
        if (permission != PackageManager.PERMISSION_GRANTED){
            Log.d(TAG,getString(R.string.need_record_audio_permission));
            try {
                callback.error(ERROR_INSUFFICIENT_PERMISSIONS);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        }
    }

}
