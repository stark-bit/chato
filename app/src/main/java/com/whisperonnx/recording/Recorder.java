package com.whisperonnx.recording;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.preference.PreferenceManager;

import com.konovalov.vad.webrtc.Vad;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;

import java.io.ByteArrayOutputStream;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Recorder {
    public interface RecorderListener {
        void onUpdateReceived(String message);
    }

    public interface ChunkListener {
        void onChunk(byte[] pcm);
    }

    public static final int CHUNK_MS = 8000;
    private static final int CUT_SEARCH_MS = 1000;

    private static final String TAG = "Recorder";
    public static final String ACTION_STOP = "Stop";
    public static final String ACTION_RECORD = "Record";
    public static final String MSG_RECORDING = "Recording...";
    public static final String MSG_RECORDING_DONE = "Recording done...!";
    public static final String MSG_RECORDING_ERROR = "Recording error...";
    public static final String MSG_NO_PERMISSION = "No mic permission";

    private final Context mContext;
    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private RecorderListener mListener;
    private volatile ChunkListener mChunkListener;
    private volatile int mChunkMs = CHUNK_MS;
    private final Lock lock = new ReentrantLock();
    private final Condition hasTask = lock.newCondition();
    private final Object fileSavedLock = new Object();

    private volatile boolean shouldStartRecording = false;
    private boolean useVAD = false;
    private VadWebRTC vad = null;
    static final int VAD_FRAME_SIZE = 480;
    private SharedPreferences sp;

    private final Thread workerThread;

    public Recorder(Context context) {
        this.mContext = context;
        sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        workerThread = new Thread(this::recordLoop);
        workerThread.start();
    }

    public void setListener(RecorderListener listener) {
        this.mListener = listener;
    }

    public void setChunkListener(ChunkListener listener) {
        this.mChunkListener = listener;
    }

    // External: the Whisper model tops out at 30 s
    public void setChunkMs(int chunkMs) {
        this.mChunkMs = Math.min(chunkMs, 30000);
    }

    public void start() {
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Recording is already in progress...");
            return;
        }
        lock.lock();
        try {
            Log.d(TAG, "Recording starts now");
            shouldStartRecording = true;
            hasTask.signal();
        } finally {
            lock.unlock();
        }
    }

    public void initVad(){
        int silenceDurationMs = sp.getInt("silenceDurationMs", 3000);
        vad = Vad.builder()
                .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                .setFrameSize(FrameSize.FRAME_SIZE_480)
                .setMode(Mode.VERY_AGGRESSIVE)
                .setSilenceDurationMs(silenceDurationMs)
                .setSpeechDurationMs(200)
                .build();
        useVAD = true;
        Log.d(TAG, "VAD initialized");
    }

    public void stop() {
        Log.d(TAG, "Recording stopped");
        mInProgress.set(false);

        synchronized (fileSavedLock) {
            try {
                fileSavedLock.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void release() {
        workerThread.interrupt();
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void sendUpdate(String message) {
        if (mListener != null)
            mListener.onUpdateReceived(message);
    }

    private void recordLoop() {
        while (true) {
            lock.lock();
            try {
                while (!shouldStartRecording) {
                    hasTask.await();
                }
                shouldStartRecording = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                lock.unlock();
            }

            try {
                recordAudio();
            } catch (Exception e) {
                Log.e(TAG, "Recording error...", e);
                sendUpdate(e.getMessage());
            } finally {
                mInProgress.set(false);
            }
        }
    }

    private void recordAudio() {
        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "AudioRecord permission is not granted");
            sendUpdate(MSG_NO_PERMISSION);
            return;
        }

        int channels = 1;
        int bytesPerSample = 2;
        int sampleRateInHz = 16000;
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        int audioSource = MediaRecorder.AudioSource.MIC;

        int bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        if (bufferSize < VAD_FRAME_SIZE * 2) bufferSize = VAD_FRAME_SIZE * 2;
        bufferSize *= 4;  // External: a larger buffer prevents dropped frames

        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        if (sp.getBoolean("bluetooth", false)){
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);
        }

        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(new AudioFormat.Builder()
                        .setChannelMask(channelConfig)
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRateInHz)
                        .build())
                .setBufferSizeInBytes(bufferSize);

        AudioRecord audioRecord = builder.build();
        audioRecord.startRecording();

        int bytesForThirtySeconds = sampleRateInHz * bytesPerSample * channels * 30;

        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();

        ChunkListener chunkListener = mChunkListener;
        boolean chunked = chunkListener != null;
        int bytesPerChunk = sampleRateInHz * bytesPerSample * channels * mChunkMs / 1000;
        int bytesCutSearch = sampleRateInHz * bytesPerSample * channels * CUT_SEARCH_MS / 1000;

        byte[] audioData = new byte[bufferSize];
        int totalBytesRead = 0;

        boolean isSpeech;
        boolean isRecording = false;
        byte[] vadAudioBuffer = new byte[VAD_FRAME_SIZE * 2];  // External: the VAD needs 16-bit samples

        while (mInProgress.get() && (chunked || totalBytesRead < bytesForThirtySeconds)) {
            int bytesRead = audioRecord.read(audioData, 0, VAD_FRAME_SIZE * 2);
            if (bytesRead > 0) {
                outputBuffer.write(audioData, 0, bytesRead);
                totalBytesRead += bytesRead;
            } else {
                Log.d(TAG, "AudioRecord error, bytes read: " + bytesRead);
                break;
            }

            if (chunked && outputBuffer.size() >= bytesPerChunk) {
                byte[] pcm = outputBuffer.toByteArray();
                int cut = findQuietCut(pcm, bytesPerChunk - bytesCutSearch, bytesPerChunk);
                chunkListener.onChunk(java.util.Arrays.copyOfRange(pcm, 0, cut));
                outputBuffer.reset();
                outputBuffer.write(pcm, cut, pcm.length - cut);
                Log.d(TAG, "Chunk emitted: " + cut + " bytes, carried over " + (pcm.length - cut));
            }

            if (useVAD){
                if (bytesRead == VAD_FRAME_SIZE * 2) {
                    System.arraycopy(audioData, 0, vadAudioBuffer, 0, VAD_FRAME_SIZE * 2);

                    isSpeech = vad.isSpeech(vadAudioBuffer);
                    if (isSpeech) {
                        if (!isRecording) {
                            Log.d(TAG, "VAD Speech detected: recording starts");
                            sendUpdate(MSG_RECORDING);
                        }
                        isRecording = true;
                    } else {
                        if (isRecording) {
                            isRecording = false;
                            mInProgress.set(false);
                        }
                    }
                }
            } else {
                if (!isRecording) sendUpdate(MSG_RECORDING);
                isRecording = true;
            }
        }
        Log.d(TAG, "Total bytes recorded: " + totalBytesRead);

        if (useVAD){
            useVAD = false;
            vad.close();
            vad = null;
            Log.d(TAG, "Closing VAD");
        }
        audioRecord.stop();
        audioRecord.release();
        audioManager.stopBluetoothSco();
        audioManager.setBluetoothScoOn(false);

        if (chunked) {
            if (outputBuffer.size() > 6400) chunkListener.onChunk(outputBuffer.toByteArray());
        } else {
            RecordBuffer.setOutputBuffer(outputBuffer.toByteArray());
        }
        if (totalBytesRead > 6400){
            sendUpdate(MSG_RECORDING_DONE);
        } else {
            sendUpdate(MSG_RECORDING_ERROR);
        }

        synchronized (fileSavedLock) {
            fileSavedLock.notify();
        }

    }

    static int findQuietCut(byte[] pcm, int from, int to) {
        final int frameBytes = VAD_FRAME_SIZE * 2;
        from = Math.max(0, from) & ~1;
        to = Math.min(pcm.length, to) & ~1;
        int bestStart = -1;
        long bestEnergy = Long.MAX_VALUE;
        for (int start = from; start + frameBytes <= to; start += frameBytes / 2) {
            long energy = 0;
            for (int i = start; i < start + frameBytes; i += 2) {
                int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
                energy += (long) sample * sample;
            }
            if (energy < bestEnergy) {
                bestEnergy = energy;
                bestStart = start;
            }
        }
        if (bestStart < 0) return to;
        return (bestStart + frameBytes / 2) & ~1;
    }
}
