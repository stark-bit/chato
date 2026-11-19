package com.whisperonnx.asr;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedy decoding for a TDT (token-and-duration transducer) model such as NVIDIA Parakeet.
 * Pure logic, no ONNX, so it can be unit tested with a scripted joint network.
 *
 * Each joint step looks at one encoder frame and the last emitted token, and returns
 * {@code vocabSize} token logits followed by duration logits. The duration says how many
 * frames to skip ahead, which is what makes TDT fast: most frames are never visited.
 */
public final class TdtDecoder {

    /** Upper bound on tokens emitted without advancing a frame, guards against loops. */
    public static final int MAX_TOKENS_PER_STEP = 10;

    public interface Joint {
        /**
         * Runs the decoder + joint network on {@code frame} given {@code prevToken}
         * and returns token logits then duration logits. The new decoder state is held
         * as pending until {@link #commit()}.
         */
        float[] step(int frame, int prevToken) throws Exception;

        /** Keeps the pending decoder state. Called only when a non-blank token is emitted. */
        void commit();
    }

    /** Number of joint steps the last decode took, for benchmarking. */
    public int steps;

    /**
     * @param frames    encoder frames to decode
     * @param vocabSize number of token logits, including blank, which is the last token
     */
    public List<Integer> decode(int frames, int vocabSize, Joint joint) throws Exception {
        int blank = vocabSize - 1;
        List<Integer> tokens = new ArrayList<>();
        int t = 0, emitted = 0;
        steps = 0;
        while (t < frames) {
            float[] out = joint.step(t, tokens.isEmpty() ? blank : tokens.get(tokens.size() - 1));
            steps++;
            int token = argmax(out, 0, vocabSize);
            int duration = argmax(out, vocabSize, out.length) - vocabSize;
            if (token != blank) {
                joint.commit();
                tokens.add(token);
                emitted++;
            }
            if (duration > 0) {
                t += duration;
                emitted = 0;
            } else if (token == blank || emitted == MAX_TOKENS_PER_STEP) {
                t++;
                emitted = 0;
            }
        }
        return tokens;
    }

    /** Joins SentencePiece tokens; "▁" marks the start of a word. */
    public static String detokenize(List<Integer> tokens, String[] vocab) {
        StringBuilder sb = new StringBuilder();
        for (int id : tokens) sb.append(vocab[id]);
        return sb.toString().replace('▁', ' ').trim();
    }

    static int argmax(float[] a, int from, int to) {
        int best = from;
        for (int i = from + 1; i < to; i++) if (a[i] > a[best]) best = i;
        return best;
    }
}
