package com.whisperonnx.backend;

import java.util.ArrayList;
import java.util.List;

public final class TdtDecoder {
    public static final int MAX_TOKENS_PER_STEP = 10;

    public interface Joint {
        float[] step(int frame, int prevToken) throws Exception;

        void commit();
    }

    public int steps;

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

    // External: SentencePiece marks the start of a word with "▁"
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
