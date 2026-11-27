package com.whisperonnx.voiceinput;

import com.whisperonnx.R;
import com.whisperonnx.backend.Whisper;
import com.whisperonnx.backend.WhisperResult;
import com.whisperonnx.recording.Recorder;
import com.whisperonnx.ui.Components;
import com.whisperonnx.ui.HapticFeedback;

import static android.speech.SpeechRecognizer.ERROR_CLIENT;
import static android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS;

import android.content.Intent;
import android.content.SharedPreferences;
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
import androidx.preference.PreferenceManager;

import java.util.ArrayList;

public class VoiceInputService extends RecognitionService {
    private static final String TAG = "VoiceInputService";
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private boolean recognitionCancelled = false;
    private SharedPreferences sp = null;

    @Override
    protected void onStartListening(Intent recognizerIntent, Callback callback) {
        sp = PreferenceManager.getDefaultSharedPreferences(this);

        checkRecordPermission(callback);

        initModel(callback);

        mRecorder = new Recorder(this);
        mRecorder.setListener(message -> {
            if (message.equals(Recorder.MSG_RECORDING)){
                try {
                    callback.rmsChanged(10);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                HapticFeedback.vibrate(this);
                try {
                    callback.rmsChanged(-20.0f);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
                startTranscription();
            } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                try {
                    callback.error(ERROR_CLIENT);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        if (!mWhisper.isInProgress()) {
            HapticFeedback.vibrate(this);
            startRecording();
            try {
                callback.beginningOfSpeech();
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
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
            public void onUpdateReceived(String message) { }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                if (whisperResult.getResult().trim().length() > 0){
                    Log.d(TAG, whisperResult.getResult().trim());
                    try {
                        callback.endOfSpeech();
                        deinitModel();
                        Bundle results = new Bundle();
                        ArrayList<String> resultList = new ArrayList<>();

                        String result = whisperResult.getResult();
                        resultList.add(result.trim());
                        results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, resultList);
                        callback.results(results);
                    } catch (RemoteException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        });
    }

    private void startRecording() {
        mRecorder.initVad();
        mRecorder.start();
        recognitionCancelled = false;
    }

    private void startTranscription() {
        if (!recognitionCancelled){
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(()-> {
                Toast toast = new Toast(this);
                toast.setDuration(Toast.LENGTH_SHORT);
                toast.setText(R.string.processing);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    toast.addCallback(new Toast.Callback() {
                        @Override
                        public void onToastHidden() {
                            super.onToastHidden();
                            if (mWhisper!=null) toast.show();
                        }
                    });
                }
                toast.show();
            });
            mWhisper.setTranslate(false);
            mWhisper.start();
            Log.d(TAG,"Start Transcription");
        }
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
