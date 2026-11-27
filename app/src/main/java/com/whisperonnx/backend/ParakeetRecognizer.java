package com.whisperonnx.backend;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// External: NVIDIA Parakeet TDT 0.6B v2 (CC-BY-4.0), int8 ONNX export from onnx-asr
public class ParakeetRecognizer implements AutoCloseable {
    private static final String TAG = "Parakeet";
    public static final String DIR = "parakeet";
    static final String PRE = "nemo128.onnx";
    static final String ENC = "encoder-model.int8.onnx";
    static final String DEC = "decoder_joint-model.int8.onnx";
    static final String VOCAB = "vocab.txt";
    private static final int STATE_SIZE = 640;

    // External: pinned to one commit, so the Hugging Face checksums stay valid
    private static final String REPO =
            "https://huggingface.co/istupakov/parakeet-tdt-0.6b-v2-onnx/resolve/0bbb45a3365852604aef28b538a8f066f4ccaa85/";
    public static final String SOURCE = "huggingface.co/istupakov/parakeet-tdt-0.6b-v2-onnx";

    public static final List<ModelDownloader.FileSpec> FILES = Collections.unmodifiableList(Arrays.asList(
            new ModelDownloader.FileSpec(
                    VOCAB, REPO + VOCAB, 9384, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
            new ModelDownloader.FileSpec(
                    PRE, REPO + PRE, 139764, "a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f"),
            new ModelDownloader.FileSpec(
                    DEC, REPO + DEC, 8998286, "a449f49acd68979d418651dd2dcb737cc0f1bf0225e009e29ee326354edbf7d3"),
            new ModelDownloader.FileSpec(
                    ENC, REPO + ENC, 652184014, "3e0581fda6ab843888b51e56d7ee78b6d5bc3237ec113af1f732d1d5286aa155")));

    public static class Timing {
        public long preMs, encMs, decMs, steps, frames;

        @Override
        public String toString() {
            return "pre " + preMs + "ms, enc " + encMs + "ms, dec " + decMs + "ms (" + steps + " steps / " + frames
                    + " frames)";
        }
    }

    private final OrtEnvironment env;
    private final OrtSession pre, enc, dec;
    private final String[] vocab;
    private final TdtDecoder tdt = new TdtDecoder();
    public final long loadMs;
    public final Timing lastTiming = new Timing();

    public static File dir(Context context) {
        return new File(context.getExternalFilesDir(null), DIR);
    }

    public static boolean isInstalled(Context context) {
        return ModelDownloader.isComplete(FILES, dir(context));
    }

    public ParakeetRecognizer(File dir) throws OrtException, IOException {
        long t0 = System.currentTimeMillis();
        env = OrtEnvironment.getEnvironment();
        vocab = loadVocab(new File(dir, VOCAB));
        pre = env.createSession(new File(dir, PRE).getPath(), options());
        enc = env.createSession(new File(dir, ENC).getPath(), options());
        dec = env.createSession(new File(dir, DEC).getPath(), options());
        loadMs = System.currentTimeMillis() - t0;
        Log.d(TAG, "Loaded in " + loadMs + "ms, vocab " + vocab.length);
    }

    private static OrtSession.SessionOptions options() throws OrtException {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        // External: ONNX Runtime keeps arena and pattern-cache memory after a run; an IME must stay small
        o.setCPUArenaAllocator(false);
        o.setMemoryPatternOptimization(false);
        return o;
    }

    private static String[] loadVocab(File f) throws IOException {
        Map<Integer, String> byId = new HashMap<>();
        try (BufferedReader r =
                new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int sp = line.lastIndexOf(' ');
                if (sp <= 0) continue;
                byId.put(Integer.parseInt(line.substring(sp + 1).trim()), line.substring(0, sp));
            }
        }
        String[] v = new String[byId.size()];
        for (Map.Entry<Integer, String> e : byId.entrySet()) v[e.getKey()] = e.getValue();
        return v;
    }

    public synchronized String transcribe(float[] samples) throws Exception {
        if (samples.length < 1600) return "";
        long t0 = System.currentTimeMillis();

        // External: the preprocessor outputs log-mel features [1, 128, T]
        float[] feats;
        long featFrames, featLen;
        try (OnnxTensor wav = OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), new long[] {1, samples.length});
                OnnxTensor wavLen =
                        OnnxTensor.createTensor(env, LongBuffer.wrap(new long[] {samples.length}), new long[] {1});
                OrtSession.Result r = pre.run(map("waveforms", wav, "waveforms_lens", wavLen))) {
            OnnxTensor f = (OnnxTensor) r.get(0);
            featFrames = f.getInfo().getShape()[2];
            feats = toArray(f.getFloatBuffer());
            featLen = ((long[]) r.get(1).getValue())[0];
        }
        long t1 = System.currentTimeMillis();

        // External: the encoder outputs [1, 1024, T'], stored as [dim][frame]
        float[] encoded;
        int dim, frames, validFrames;
        try (OnnxTensor x = OnnxTensor.createTensor(env, FloatBuffer.wrap(feats), new long[] {1, 128, featFrames});
                OnnxTensor len = OnnxTensor.createTensor(env, LongBuffer.wrap(new long[] {featLen}), new long[] {1});
                OrtSession.Result r = enc.run(map("audio_signal", x, "length", len))) {
            OnnxTensor out = (OnnxTensor) r.get(0);
            long[] shape = out.getInfo().getShape();
            dim = (int) shape[1];
            frames = (int) shape[2];
            encoded = toArray(out.getFloatBuffer());
            validFrames = (int) ((long[]) r.get(1).getValue())[0];
        }
        long t2 = System.currentTimeMillis();

        final int fDim = dim, fFrames = frames;
        final float[] frame = new float[dim];
        final float[][] state = {new float[2 * STATE_SIZE], new float[2 * STATE_SIZE]};
        final float[][] pending = new float[2][];
        List<Integer> tokens = tdt.decode(validFrames, vocab.length, new TdtDecoder.Joint() {
            @Override
            public float[] step(int t, int prevToken) throws Exception {
                for (int d = 0; d < fDim; d++) frame[d] = encoded[d * fFrames + t];
                try (OnnxTensor e = OnnxTensor.createTensor(env, FloatBuffer.wrap(frame), new long[] {1, fDim, 1});
                        OnnxTensor tg =
                                OnnxTensor.createTensor(env, IntBuffer.wrap(new int[] {prevToken}), new long[] {1, 1});
                        OnnxTensor tl = OnnxTensor.createTensor(env, IntBuffer.wrap(new int[] {1}), new long[] {1});
                        OnnxTensor s1 = OnnxTensor.createTensor(
                                env, FloatBuffer.wrap(state[0]), new long[] {2, 1, STATE_SIZE});
                        OnnxTensor s2 = OnnxTensor.createTensor(
                                env, FloatBuffer.wrap(state[1]), new long[] {2, 1, STATE_SIZE});
                        OrtSession.Result r = dec.run(map(
                                "encoder_outputs",
                                e,
                                "targets",
                                tg,
                                "target_length",
                                tl,
                                "input_states_1",
                                s1,
                                "input_states_2",
                                s2))) {
                    pending[0] = toArray(((OnnxTensor) r.get(2)).getFloatBuffer());
                    pending[1] = toArray(((OnnxTensor) r.get(3)).getFloatBuffer());
                    return toArray(((OnnxTensor) r.get(0)).getFloatBuffer());
                }
            }

            @Override
            public void commit() {
                state[0] = pending[0];
                state[1] = pending[1];
            }
        });
        long t3 = System.currentTimeMillis();

        lastTiming.preMs = t1 - t0;
        lastTiming.encMs = t2 - t1;
        lastTiming.decMs = t3 - t2;
        lastTiming.steps = tdt.steps;
        lastTiming.frames = validFrames;
        String text = TdtDecoder.detokenize(tokens, vocab);
        Log.d(TAG, (samples.length / 16) + "ms audio: " + lastTiming + " -> " + text);
        return text;
    }

    private static Map<String, OnnxTensor> map(Object... kv) {
        Map<String, OnnxTensor> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (OnnxTensor) kv[i + 1]);
        return m;
    }

    private static float[] toArray(FloatBuffer b) {
        float[] a = new float[b.remaining()];
        b.get(a);
        return a;
    }

    @Override
    public void close() {
        for (OrtSession s : new OrtSession[] {pre, enc, dec}) {
            try {
                if (s != null) s.close();
            } catch (OrtException ignored) {
            }
        }
    }
}
