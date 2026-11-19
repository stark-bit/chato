package com.whisperonnx.asr;

import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.os.Debug;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.whisperonnx.voice_translation.neural_networks.NeuralNetworkApi;
import com.whisperonnx.voice_translation.neural_networks.voice.Recognizer;
import com.whisperonnx.voice_translation.neural_networks.voice.RecognizerListener;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Compares the two on-device engines on the same audio. Not a pass/fail test.
 *
 * Put 16 kHz mono 16-bit WAVs in {@code <external files>/bench/} ({@code make bench} does this),
 * run, and read {@code bench/results.txt}. Each clip is run twice: the first run includes
 * one-time warm-up costs, the second is what you'd see on later dictations.
 */
@RunWith(AndroidJUnit4.class)
public class LocalEngineBenchmark {

    private static final String TAG = "Bench";
    private final StringBuilder report = new StringBuilder();

    private void out(String line) {
        Log.i(TAG, line);
        report.append(line).append('\n');
    }

    @Test
    public void compareEngines() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File benchDir = new File(ctx.getExternalFilesDir(null), "bench");
        File[] wavs = benchDir.listFiles((d, n) -> n.endsWith(".wav"));
        assumeTrue("No WAVs in " + benchDir, wavs != null && wavs.length > 0);
        Arrays.sort(wavs);

        float[][] clips = new float[wavs.length][];
        for (int i = 0; i < wavs.length; i++) clips[i] = readWav(wavs[i]);

        out("Device: " + android.os.Build.MODEL + " / " + android.os.Build.SOC_MODEL
                + ", cores " + Runtime.getRuntime().availableProcessors());

        // Whisper first: memory freed by the first engine can make the second one's PSS
        // look smaller, and Parakeet's session close is the more reliable of the two
        benchWhisper(ctx, wavs, clips);
        System.gc();
        Thread.sleep(2000);
        if (ParakeetRecognizer.isInstalled(ctx)) {
            benchParakeet(ctx, wavs, clips);
        } else {
            out("Parakeet: not installed in " + ParakeetRecognizer.dir(ctx));
        }

        try (FileWriter w = new FileWriter(new File(benchDir, "results.txt"))) {
            w.write(report.toString());
        }
    }

    private void benchParakeet(Context ctx, File[] wavs, float[][] clips) throws Exception {
        long pssBefore = pssMb();
        try (ParakeetRecognizer p = new ParakeetRecognizer(ParakeetRecognizer.dir(ctx))) {
            out("\n== Parakeet TDT 0.6B int8 ==");
            out("load " + p.loadMs + "ms, memory +" + (pssMb() - pssBefore) + "MB (PSS)");
            for (int i = 0; i < clips.length; i++) {
                for (int run = 1; run <= 2; run++) {
                    long t0 = System.currentTimeMillis();
                    String text = p.transcribe(clips[i]);
                    long ms = System.currentTimeMillis() - t0;
                    out(String.format("%s run%d: %dms (%.2fx realtime) [%s]", wavs[i].getName(), run, ms,
                            ms / (clips[i].length / 16000.0 * 1000), p.lastTiming));
                    if (run == 2) out("  \"" + text + "\"");
                }
            }
            out("peak memory +" + (pssMb() - pssBefore) + "MB (PSS)");
        }
    }

    private void benchWhisper(Context ctx, File[] wavs, float[][] clips) throws Exception {
        long pssBefore = pssMb();
        long t0 = System.currentTimeMillis();
        CountDownLatch ready = new CountDownLatch(1);
        Recognizer r = new Recognizer(ctx, false, new NeuralNetworkApi.InitListener() {
            @Override public void onInitializationFinished() { ready.countDown(); }
            @Override public void onError(int[] reasons, long value) { ready.countDown(); }
        });
        if (!ready.await(120, TimeUnit.SECONDS)) {
            out("Whisper: load timed out");
            return;
        }
        out("\n== Whisper small int8 ==");
        out("load " + (System.currentTimeMillis() - t0) + "ms, memory +" + (pssMb() - pssBefore) + "MB (PSS)");

        final String[] result = new String[1];
        final CountDownLatch[] done = new CountDownLatch[1];
        r.addCallback(new RecognizerListener() {
            @Override public void onSpeechRecognizedResult(String text, String lang, double conf, boolean isFinal) {
                result[0] = text;
                done[0].countDown();
            }
            @Override public void onError(int[] reasons, long value) {
                result[0] = "ERROR " + Arrays.toString(reasons);
                done[0].countDown();
            }
        });

        for (int i = 0; i < clips.length; i++) {
            for (int run = 1; run <= 2; run++) {
                done[0] = new CountDownLatch(1);
                long s = System.currentTimeMillis();
                r.recognize(clips[i], 1, Whisper.LANGUAGE, Recognizer.Action.TRANSCRIBE);
                if (!done[0].await(120, TimeUnit.SECONDS)) result[0] = "TIMEOUT";
                long ms = System.currentTimeMillis() - s;
                out(String.format("%s run%d: %dms (%.2fx realtime)", wavs[i].getName(), run, ms,
                        ms / (clips[i].length / 16000.0 * 1000)));
                if (run == 2) out("  \"" + result[0] + "\"");
            }
        }
        out("peak memory +" + (pssMb() - pssBefore) + "MB (PSS)");
        r.destroy();
    }

    private static long pssMb() {
        Debug.MemoryInfo mi = new Debug.MemoryInfo();
        Debug.getMemoryInfo(mi);
        return mi.getTotalPss() / 1024;
    }

    /** Reads the "data" chunk of a 16-bit mono WAV as floats in [-1, 1]. */
    private static float[] readWav(File f) throws IOException {
        byte[] all = new byte[(int) f.length()];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) { raf.readFully(all); }
        ByteBuffer b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12;
        while (pos + 8 <= all.length) {
            String id = new String(all, pos, 4, "US-ASCII");
            int size = b.getInt(pos + 4);
            if (id.equals("data")) {
                int n = Math.min(size, all.length - pos - 8) / 2;
                float[] out = new float[n];
                for (int i = 0; i < n; i++) out[i] = b.getShort(pos + 8 + 2 * i) / 32768f;
                return out;
            }
            pos += 8 + size + (size & 1);
        }
        throw new IOException("No data chunk in " + f);
    }
}
