package com.whisperonnx.backend;

import com.whisperonnx.recording.RecordBuffer;
import com.whisperonnx.thirdparty.rtranslator.NeuralNetworkApi;
import com.whisperonnx.thirdparty.rtranslator.voice.Recognizer;
import com.whisperonnx.thirdparty.rtranslator.voice.RecognizerListener;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.File;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Whisper {
    public interface WhisperListener {
        void onUpdateReceived(String message);
        void onResultReceived(WhisperResult result);
    }

    private static final String TAG = "Whisper";
    public static final String MSG_PROCESSING = "Processing...";
    public static final String MSG_PROCESSING_DONE = "Processing done...!";
    public static final String MSG_QUEUE_EMPTY = "Queue empty";
    public static final String MSG_MODEL_LOADED = "Model loaded";

    public static final String PREF_LOCAL_ENGINE = "local_engine";
    public static final String ENGINE_WHISPER = "whisper";
    public static final String ENGINE_PARAKEET = "parakeet";

    public static String localEngine(SharedPreferences sp) {
        return sp.getString(PREF_LOCAL_ENGINE, ENGINE_WHISPER);
    }

    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private Recognizer.Action mAction;
    public static final String LANGUAGE = "en";
    private final String mLangCode = LANGUAGE;
    private WhisperListener mUpdateListener;

    private final Lock taskLock = new ReentrantLock();
    private final Condition hasTask = taskLock.newCondition();
    private volatile boolean taskAvailable = false;
    private Recognizer recognizer = null;
    private Context mContext;
    private long startTime;
    private OpenAITranscriber openaiTranscriber;
    private String mBackendType;
    private final String mLocalEngine;
    private boolean needsSetup;
    private volatile boolean localModelRequested;
    private volatile ParakeetRecognizer parakeet;
    private final CountDownLatch parakeetReady = new CountDownLatch(1);

    private final BlockingQueue<byte[]> chunkQueue = new LinkedBlockingQueue<>();
    private final AtomicInteger chunksPending = new AtomicInteger(0);
    private Thread chunkThread;
    private Thread recordBufferThread;
    private volatile CountDownLatch localResultLatch;

    public Whisper(Context context) {
        mContext = context;

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        mBackendType = sp.getString(OpenAITranscriber.PREF_BACKEND_TYPE, OpenAITranscriber.BACKEND_LOCAL);
        String engine = localEngine(sp);
        if (engine.equals(ENGINE_PARAKEET) && !ParakeetRecognizer.isInstalled(context)) {
            Log.w(TAG, "Parakeet selected but not installed in " + ParakeetRecognizer.dir(context) + ", using Whisper");
            engine = ENGINE_WHISPER;
        }
        mLocalEngine = engine;

        if (mBackendType.equals(OpenAITranscriber.BACKEND_OPENAI) || mLocalEngine.equals(ENGINE_PARAKEET)) {
            recordBufferThread = new Thread(this::processRecordBufferLoop, "whisper-record");
            recordBufferThread.start();
            return;
        }

        File sdcardDataFolder = WhisperModel.dir(mContext);
        if (sdcardDataFolder != null && !sdcardDataFolder.exists() && !sdcardDataFolder.mkdirs()) {
            Log.e(TAG, "Failed to make directory: " + sdcardDataFolder);
            return;
        }

        if (!WhisperModel.isInstalled(mContext)) {
            needsSetup = true;
        } else {
            recordBufferThread = new Thread(this::processRecordBufferLoop, "whisper-record");
            recordBufferThread.start();
        }

    }

    public boolean modelInstalledSince() {
        return needsSetup && (WhisperModel.isInstalled(mContext) || ParakeetRecognizer.isInstalled(mContext));
    }

    public boolean hasLocalModel() {
        return localModelRequested;
    }

    public boolean isModelMissing() {
        return needsSetup;
    }

    public String getLocalEngine() {
        return mLocalEngine;
    }

    public void setListener(WhisperListener listener) {
        this.mUpdateListener = listener;
    }

    public void loadModel() {
        if (mBackendType.equals(OpenAITranscriber.BACKEND_OPENAI)) {
            return;
        }
        localModelRequested = true;
        if (needsSetup) return;
        if (mLocalEngine.equals(ENGINE_PARAKEET)) {
            new Thread(() -> {
                try {
                    parakeet = new ParakeetRecognizer(ParakeetRecognizer.dir(mContext));
                    sendUpdate(MSG_MODEL_LOADED);
                } catch (Throwable e) {
                    Log.e(TAG, "Parakeet failed to load", e);
                    sendUpdate("Parakeet failed to load: " + e.getMessage());
                } finally {
                    parakeetReady.countDown();
                }
            }, "parakeet-load").start();
            return;
        }
        recognizer = new Recognizer(mContext, false, new NeuralNetworkApi.InitListener() {
            @Override
            public void onInitializationFinished() {
                Log.d(TAG, "Recognizer initialized");
                sendUpdate(MSG_MODEL_LOADED);
            }

            @Override
            public void onError(int[] reasons, long value) {
                Log.d(TAG, "Recognizer init error");
                sendUpdate("Whisper failed to load");
            }
        });

        recognizer.addCallback(new RecognizerListener() {
            @Override
            public void onSpeechRecognizedResult(String text, String languageCode, double confidenceScore, boolean isFinal) {
                Log.d(TAG, languageCode + " " + text);
                WhisperResult whisperResult = new WhisperResult(text,languageCode, mAction);

                sendResult(whisperResult);
                CountDownLatch latch = localResultLatch;
                if (latch != null) latch.countDown();

                long timeTaken = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Time Taken for transcription: " + timeTaken + "ms");
                sendUpdate(MSG_PROCESSING_DONE);
            }

            @Override
            public void onError(int[] reasons, long value) {
                Log.d(TAG, "ERROR during recognition");
                CountDownLatch latch = localResultLatch;
                if (latch != null) latch.countDown();
            }
        });
    }

    public void unloadModel() {
        clearChunks();
        if (chunkThread != null) {
            chunkThread.interrupt();
            chunkThread = null;
        }
        if (recordBufferThread != null) {
            recordBufferThread.interrupt();
            recordBufferThread = null;
        }
        if (recognizer != null) {
            recognizer.destroy();
        }
        if (parakeet != null) {
            parakeet.close();
            parakeet = null;
        }
        if (openaiTranscriber != null) {
            openaiTranscriber.shutdown();
            openaiTranscriber = null;
        }
    }

    // External: Whisper's translate task turns speech into English text
    public void setTranslate(boolean translate) {
        this.mAction = translate ? Recognizer.ACTION_TRANSLATE : Recognizer.ACTION_TRANSCRIBE;
    }

    public void start() {
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Execution is already in progress...");
            return;
        }
        taskLock.lock();
        try {
            taskAvailable = true;
            hasTask.signal();
        } finally {
            taskLock.unlock();
        }
    }

    public void stop() {
        mInProgress.set(false);
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    public synchronized void enqueueChunk(byte[] pcm) {
        if (chunkThread == null) {
            chunkThread = new Thread(this::processChunkLoop, "whisper-chunks");
            chunkThread.start();
        }
        chunksPending.incrementAndGet();
        chunkQueue.offer(pcm);
    }

    public boolean hasPendingChunks() {
        return chunksPending.get() > 0;
    }

    public void clearChunks() {
        int dropped = 0;
        while (chunkQueue.poll() != null) dropped++;
        chunksPending.addAndGet(-dropped);
    }

    private void processChunkLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            byte[] pcm;
            try {
                pcm = chunkQueue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                transcribeChunkBlocking(pcm);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Log.e(TAG, "Chunk transcription failed", e);
                sendUpdate("Transcription failed: " + e.getMessage());
            } finally {
                if (chunksPending.decrementAndGet() <= 0) {
                    chunksPending.set(0);
                    sendUpdate(MSG_QUEUE_EMPTY);
                }
            }
        }
    }

    private void transcribeChunkBlocking(byte[] pcm) throws InterruptedException {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        String backend = sp.getString(OpenAITranscriber.PREF_BACKEND_TYPE, OpenAITranscriber.BACKEND_LOCAL);
        startTime = System.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(1);

        if (backend.equals(OpenAITranscriber.BACKEND_OPENAI)) {
            if (openaiTranscriber == null) openaiTranscriber = new OpenAITranscriber();
            openaiTranscriber.transcribe(mContext, pcm, new OpenAITranscriber.Callback() {
                @Override
                public void onSuccess(String transcription) {
                    Log.d(TAG, "OpenAI chunk time taken: " + (System.currentTimeMillis() - startTime) + "ms");
                    sendResult(new WhisperResult(transcription, LANGUAGE, mAction));
                    latch.countDown();
                }

                @Override
                public void onError(String errorMessage) {
                    Log.e(TAG, "OpenAI chunk error: " + errorMessage);
                    sendUpdate(errorMessage);
                    latch.countDown();
                }
            });
        } else if (mLocalEngine.equals(ENGINE_PARAKEET)) {
            transcribeParakeet(RecordBuffer.getSamples(pcm));
            return;
        } else {
            if (recognizer == null) {
                sendUpdate("Local model not loaded");
                return;
            }
            localResultLatch = latch;
            recognizer.recognize(RecordBuffer.getSamples(pcm), 1, mLangCode, mAction);
        }

        if (!latch.await(120, TimeUnit.SECONDS)) {
            Log.e(TAG, "Chunk transcription timed out");
            sendUpdate("Transcription timed out");
        }
        localResultLatch = null;
    }

    private void processRecordBufferLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            taskLock.lock();
            try {
                while (!taskAvailable) {
                    hasTask.await();
                }
                processRecordBuffer();
                taskAvailable = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                taskLock.unlock();
            }
        }
    }

    private void processRecordBuffer() {
        try {
            if (RecordBuffer.getOutputBuffer() == null) {
                sendUpdate("No audio data available");
                return;
            }

            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
            mBackendType = sp.getString(OpenAITranscriber.PREF_BACKEND_TYPE, OpenAITranscriber.BACKEND_LOCAL);

            if (mBackendType.equals(OpenAITranscriber.BACKEND_OPENAI)) {
                Log.d(TAG, "Routing to REMOTE (OpenAI) backend");
                processRecordBufferRemote();
            } else {
                Log.d(TAG, "Routing to LOCAL (ONNX) backend");
                processRecordBufferLocal();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during transcription", e);
            sendUpdate("Transcription failed: " + e.getMessage());
        } finally {
            mInProgress.set(false);
        }
    }

    private void processRecordBufferLocal() throws Exception {
        startTime = System.currentTimeMillis();
        sendUpdate(MSG_PROCESSING);
        if (mLocalEngine.equals(ENGINE_PARAKEET)) {
            transcribeParakeet(RecordBuffer.getSamples());
            sendUpdate(MSG_PROCESSING_DONE);
            return;
        }
        recognizer.recognize(RecordBuffer.getSamples(), 1, mLangCode, mAction);
    }

    private void processRecordBufferRemote() {
        startTime = System.currentTimeMillis();
        sendUpdate(MSG_PROCESSING);

        if (openaiTranscriber == null) {
            openaiTranscriber = new OpenAITranscriber();
        }

        byte[] pcmData = RecordBuffer.getOutputBuffer();
        if (pcmData.length < 6400) {
            sendUpdate("Recording too short");
            return;
        }

        openaiTranscriber.transcribe(mContext, pcmData, new OpenAITranscriber.Callback() {
            @Override
            public void onSuccess(String transcription) {
                long timeTaken = System.currentTimeMillis() - startTime;
                Log.d(TAG, "OpenAI time taken: " + timeTaken + "ms");

                WhisperResult result = new WhisperResult(transcription, LANGUAGE, mAction);
                sendResult(result);
                sendUpdate(MSG_PROCESSING_DONE);
            }

            @Override
            public void onError(String errorMessage) {
                Log.e(TAG, "OpenAI transcription error: " + errorMessage);
                sendUpdate(errorMessage);
            }
        });
    }

    private void transcribeParakeet(float[] samples) throws InterruptedException {
        if (!parakeetReady.await(60, TimeUnit.SECONDS) || parakeet == null) {
            sendUpdate("Parakeet model not loaded");
            return;
        }
        try {
            String text = parakeet.transcribe(samples);
            Log.d(TAG, "Parakeet time taken: " + (System.currentTimeMillis() - startTime) + "ms");
            sendResult(new WhisperResult(text, LANGUAGE, mAction));
        } catch (Exception e) {
            Log.e(TAG, "Parakeet transcription failed", e);
            sendUpdate("Transcription failed: " + e.getMessage());
        }
    }

    private void sendUpdate(String message) {
        if (mUpdateListener != null) {
            mUpdateListener.onUpdateReceived(message);
        }
    }

    private void sendResult(WhisperResult whisperResult) {
        if (mUpdateListener != null) {
            mUpdateListener.onResultReceived(whisperResult);
        }
    }

}
