package com.whisperonnx.recording;

import com.whisperonnx.prefs.Prefs;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;

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
        void onUpdateReceived(RecorderEvent event, String detail);
    }

    public interface ChunkListener {
        void onChunk(byte[] pcm);
    }

    public static final int CHUNK_MS = 8000;
    private static final int CUT_SEARCH_MS = 1000;
    private static final int SAMPLE_RATE = 16000;
    private static final int BYTES_PER_SECOND = SAMPLE_RATE * 2;

    private static final String TAG = "Recorder";

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
    private final Prefs prefs;

    private final Thread workerThread;

    public Recorder(Context context) {
        this.mContext = context;
        prefs = Prefs.of(mContext);
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
        int silenceDurationMs = prefs.silenceMs();
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

    private void sendUpdate(RecorderEvent event) {
        sendUpdate(event, null);
    }

    private void sendUpdate(RecorderEvent event, String detail) {
        if (mListener != null)
            mListener.onUpdateReceived(event, detail);
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
                sendUpdate(RecorderEvent.FAILED, e.getMessage());
            } finally {
                mInProgress.set(false);
            }
        }
    }

    private void recordAudio() {
        if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "AudioRecord permission is not granted");
            sendUpdate(RecorderEvent.NO_PERMISSION);
            return;
        }

        int bufferSize = bufferSize();
        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        if (prefs.bluetooth()){
            audioManager.startBluetoothSco();
            audioManager.setBluetoothScoOn(true);
        }
        AudioRecord audioRecord = openAudioRecord(bufferSize);
        audioRecord.startRecording();

        ChunkListener chunkListener = mChunkListener;
        boolean chunked = chunkListener != null;
        int bytesPerChunk = BYTES_PER_SECOND * mChunkMs / 1000;
        ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
        byte[] audioData = new byte[bufferSize];
        byte[] vadAudioBuffer = new byte[VAD_FRAME_SIZE * 2];  // External: the VAD needs 16-bit samples
        int totalBytesRead = 0;
        boolean isRecording = false;

        while (mInProgress.get() && (chunked || totalBytesRead < BYTES_PER_SECOND * 30)) {
            int bytesRead = audioRecord.read(audioData, 0, VAD_FRAME_SIZE * 2);
            if (bytesRead <= 0) {
                Log.d(TAG, "AudioRecord error, bytes read: " + bytesRead);
                break;
            }
            outputBuffer.write(audioData, 0, bytesRead);
            totalBytesRead += bytesRead;
            if (chunked) emitChunkIfFull(outputBuffer, chunkListener, bytesPerChunk);
            isRecording = trackSpeech(audioData, bytesRead, isRecording, vadAudioBuffer);
        }
        Log.d(TAG, "Total bytes recorded: " + totalBytesRead);

        closeVad();
        audioRecord.stop();
        audioRecord.release();
        audioManager.stopBluetoothSco();
        audioManager.setBluetoothScoOn(false);

        deliver(outputBuffer, chunkListener);
        sendUpdate(totalBytesRead > 6400 ? RecorderEvent.DONE : RecorderEvent.TOO_SHORT);

        synchronized (fileSavedLock) {
            fileSavedLock.notify();
        }
    }

    private static int bufferSize() {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        // External: a larger buffer prevents dropped frames
        return Math.max(min, VAD_FRAME_SIZE * 2) * 4;
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private static AudioRecord openAudioRecord(int bufferSize) {
        return new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(new AudioFormat.Builder()
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .build();
    }

    private static void emitChunkIfFull(ByteArrayOutputStream outputBuffer, ChunkListener chunkListener, int bytesPerChunk) {
        if (outputBuffer.size() < bytesPerChunk) return;
        byte[] pcm = outputBuffer.toByteArray();
        int cut = findQuietCut(pcm, bytesPerChunk - BYTES_PER_SECOND * CUT_SEARCH_MS / 1000, bytesPerChunk);
        chunkListener.onChunk(java.util.Arrays.copyOfRange(pcm, 0, cut));
        outputBuffer.reset();
        outputBuffer.write(pcm, cut, pcm.length - cut);
        Log.d(TAG, "Chunk emitted: " + cut + " bytes, carried over " + (pcm.length - cut));
    }

    private boolean trackSpeech(byte[] audioData, int bytesRead, boolean isRecording, byte[] vadAudioBuffer) {
        if (!useVAD) {
            if (!isRecording) sendUpdate(RecorderEvent.RECORDING);
            return true;
        }
        if (bytesRead != VAD_FRAME_SIZE * 2) return isRecording;
        System.arraycopy(audioData, 0, vadAudioBuffer, 0, VAD_FRAME_SIZE * 2);
        boolean isSpeech = vad.isSpeech(vadAudioBuffer);
        if (isSpeech && !isRecording) {
            Log.d(TAG, "VAD Speech detected: recording starts");
            sendUpdate(RecorderEvent.RECORDING);
        }
        if (!isSpeech && isRecording) mInProgress.set(false);
        return isSpeech;
    }

    private void closeVad() {
        if (!useVAD) return;
        useVAD = false;
        vad.close();
        vad = null;
        Log.d(TAG, "Closing VAD");
    }

    private static void deliver(ByteArrayOutputStream outputBuffer, ChunkListener chunkListener) {
        if (chunkListener == null) {
            RecordBuffer.setOutputBuffer(outputBuffer.toByteArray());
            return;
        }
        if (outputBuffer.size() > 6400) chunkListener.onChunk(outputBuffer.toByteArray());
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
