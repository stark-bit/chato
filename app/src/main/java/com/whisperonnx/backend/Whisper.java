package com.whisperonnx.backend;

import android.content.Context;
import android.util.Log;
import com.whisperonnx.prefs.Prefs;
import com.whisperonnx.recording.RecordBuffer;
import com.whisperonnx.thirdparty.rtranslator.NeuralNetworkApi;
import com.whisperonnx.thirdparty.rtranslator.voice.Recognizer;
import com.whisperonnx.thirdparty.rtranslator.voice.RecognizerListener;
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
        void onUpdateReceived(TranscriptionStatus status, String detail);

        void onResultReceived(WhisperResult result);
    }

    private static final String TAG = "Whisper";

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
    private boolean mUseServer;
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

        Prefs prefs = Prefs.of(context);
        mUseServer = prefs.useServer();
        String engine = prefs.localEngine();
        if (engine.equals(Prefs.ENGINE_PARAKEET) && !ParakeetRecognizer.isInstalled(context)) {
            Log.w(TAG, "Parakeet selected but not installed in " + ParakeetRecognizer.dir(context) + ", using Whisper");
            engine = Prefs.ENGINE_WHISPER;
        }
        mLocalEngine = engine;

        if (mUseServer || mLocalEngine.equals(Prefs.ENGINE_PARAKEET)) {
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
        if (mUseServer) {
            return;
        }
        localModelRequested = true;
        if (needsSetup) return;
        if (mLocalEngine.equals(Prefs.ENGINE_PARAKEET)) {
            new Thread(
                            () -> {
                                try {
                                    parakeet = new ParakeetRecognizer(ParakeetRecognizer.dir(mContext));
                                    sendUpdate(TranscriptionStatus.MODEL_LOADED);
                                } catch (Throwable e) {
                                    Log.e(TAG, "Parakeet failed to load", e);
                                    sendUpdate(TranscriptionStatus.MODEL_FAILED, e.getMessage());
                                } finally {
                                    parakeetReady.countDown();
                                }
                            },
                            "parakeet-load")
                    .start();
            return;
        }
        recognizer = new Recognizer(mContext, false, new NeuralNetworkApi.InitListener() {
            @Override
            public void onInitializationFinished() {
                Log.d(TAG, "Recognizer initialized");
                sendUpdate(TranscriptionStatus.MODEL_LOADED);
            }

            @Override
            public void onError(int[] reasons, long value) {
                Log.d(TAG, "Recognizer init error");
                sendUpdate(TranscriptionStatus.MODEL_FAILED);
            }
        });

        recognizer.addCallback(new RecognizerListener() {
            @Override
            public void onSpeechRecognizedResult(
                    String text, String languageCode, double confidenceScore, boolean isFinal) {
                Log.d(TAG, languageCode + " " + text);
                WhisperResult whisperResult = new WhisperResult(text, languageCode, mAction);

                sendResult(whisperResult);
                CountDownLatch latch = localResultLatch;
                if (latch != null) latch.countDown();

                long timeTaken = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Time Taken for transcription: " + timeTaken + "ms");
                sendUpdate(TranscriptionStatus.PROCESSING_DONE);
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
                sendUpdate(TranscriptionStatus.FAILED, e.getMessage());
            } finally {
                if (chunksPending.decrementAndGet() <= 0) {
                    chunksPending.set(0);
                    sendUpdate(TranscriptionStatus.QUEUE_EMPTY);
                }
            }
        }
    }

    private void transcribeChunkBlocking(byte[] pcm) throws InterruptedException {
        startTime = System.currentTimeMillis();
        CountDownLatch latch = new CountDownLatch(1);

        if (Prefs.of(mContext).useServer()) {
            if (openaiTranscriber == null) openaiTranscriber = new OpenAITranscriber();
            openaiTranscriber.transcribe(mContext, pcm, new OpenAITranscriber.Callback() {
                @Override
                public void onSuccess(String transcription) {
                    Log.d(TAG, "OpenAI chunk time taken: " + (System.currentTimeMillis() - startTime) + "ms");
                    sendResult(new WhisperResult(transcription, LANGUAGE, mAction));
                    latch.countDown();
                }

                @Override
                public void onError(TranscriptionStatus status, String detail) {
                    Log.e(TAG, "OpenAI chunk error: " + status + " " + detail);
                    sendUpdate(status, detail);
                    latch.countDown();
                }
            });
        } else if (mLocalEngine.equals(Prefs.ENGINE_PARAKEET)) {
            transcribeParakeet(RecordBuffer.getSamples(pcm));
            return;
        } else {
            if (recognizer == null) {
                sendUpdate(TranscriptionStatus.MODEL_NOT_LOADED);
                return;
            }
            localResultLatch = latch;
            recognizer.recognize(RecordBuffer.getSamples(pcm), 1, mLangCode, mAction);
        }

        if (!latch.await(120, TimeUnit.SECONDS)) {
            Log.e(TAG, "Chunk transcription timed out");
            sendUpdate(TranscriptionStatus.TIMED_OUT);
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
                sendUpdate(TranscriptionStatus.NO_AUDIO);
                return;
            }

            mUseServer = Prefs.of(mContext).useServer();

            if (mUseServer) {
                Log.d(TAG, "Routing to REMOTE (OpenAI) backend");
                processRecordBufferRemote();
            } else {
                Log.d(TAG, "Routing to LOCAL (ONNX) backend");
                processRecordBufferLocal();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during transcription", e);
            sendUpdate(TranscriptionStatus.FAILED, e.getMessage());
        } finally {
            mInProgress.set(false);
        }
    }

    private void processRecordBufferLocal() throws Exception {
        startTime = System.currentTimeMillis();
        sendUpdate(TranscriptionStatus.PROCESSING);
        if (mLocalEngine.equals(Prefs.ENGINE_PARAKEET)) {
            transcribeParakeet(RecordBuffer.getSamples());
            sendUpdate(TranscriptionStatus.PROCESSING_DONE);
            return;
        }
        recognizer.recognize(RecordBuffer.getSamples(), 1, mLangCode, mAction);
    }

    private void processRecordBufferRemote() {
        startTime = System.currentTimeMillis();
        sendUpdate(TranscriptionStatus.PROCESSING);

        if (openaiTranscriber == null) {
            openaiTranscriber = new OpenAITranscriber();
        }

        byte[] pcmData = RecordBuffer.getOutputBuffer();
        if (pcmData.length < 6400) {
            sendUpdate(TranscriptionStatus.TOO_SHORT);
            return;
        }

        openaiTranscriber.transcribe(mContext, pcmData, new OpenAITranscriber.Callback() {
            @Override
            public void onSuccess(String transcription) {
                long timeTaken = System.currentTimeMillis() - startTime;
                Log.d(TAG, "OpenAI time taken: " + timeTaken + "ms");

                WhisperResult result = new WhisperResult(transcription, LANGUAGE, mAction);
                sendResult(result);
                sendUpdate(TranscriptionStatus.PROCESSING_DONE);
            }

            @Override
            public void onError(TranscriptionStatus status, String detail) {
                Log.e(TAG, "OpenAI transcription error: " + status + " " + detail);
                sendUpdate(status, detail);
            }
        });
    }

    private void transcribeParakeet(float[] samples) throws InterruptedException {
        if (!parakeetReady.await(60, TimeUnit.SECONDS) || parakeet == null) {
            sendUpdate(TranscriptionStatus.MODEL_NOT_LOADED);
            return;
        }
        try {
            String text = parakeet.transcribe(samples);
            Log.d(TAG, "Parakeet time taken: " + (System.currentTimeMillis() - startTime) + "ms");
            sendResult(new WhisperResult(text, LANGUAGE, mAction));
        } catch (Exception e) {
            Log.e(TAG, "Parakeet transcription failed", e);
            sendUpdate(TranscriptionStatus.FAILED, e.getMessage());
        }
    }

    private void sendUpdate(TranscriptionStatus status) {
        sendUpdate(status, null);
    }

    private void sendUpdate(TranscriptionStatus status, String detail) {
        if (mUpdateListener != null) {
            mUpdateListener.onUpdateReceived(status, detail);
        }
    }

    private void sendResult(WhisperResult whisperResult) {
        if (mUpdateListener != null) {
            mUpdateListener.onResultReceived(whisperResult);
        }
    }
}
