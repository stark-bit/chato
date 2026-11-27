package com.whisperonnx.backend;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TdtDecoderTest {
    private static final int V = 4, BLANK = 3;

    private static float[] out(int token, int duration) {
        float[] o = new float[V + 5];
        o[token] = 1f;
        o[V + duration] = 1f;
        return o;
    }

    private static class Script implements TdtDecoder.Joint {
        final List<float[]> outputs;
        final List<String> calls = new ArrayList<>();
        int i, commits;
        Script(float[]... outputs) { this.outputs = Arrays.asList(outputs); }
        @Override public float[] step(int frame, int prevToken) {
            calls.add(frame + "/" + prevToken);
            return outputs.get(i++);
        }
        @Override public void commit() { commits++; }
    }

    @Test public void durationSkipsFrames() throws Exception {
        Script s = new Script(out(0, 2), out(1, 3), out(BLANK, 1));
        List<Integer> tokens = new TdtDecoder().decode(6, V, s);
        assertEquals(Arrays.asList(0, 1), tokens);
        assertEquals(Arrays.asList("0/3", "2/0", "5/1"), s.calls);
        assertEquals(2, s.commits);
    }

    @Test public void blankWithZeroDurationStillAdvances() throws Exception {
        Script s = new Script(out(BLANK, 0), out(BLANK, 0));
        assertEquals(0, new TdtDecoder().decode(2, V, s).size());
        assertEquals(Arrays.asList("0/3", "1/3"), s.calls);
        assertEquals(0, s.commits);
    }

    @Test public void severalTokensOnOneFrame() throws Exception {
        Script s = new Script(out(0, 0), out(1, 0), out(BLANK, 1));
        TdtDecoder d = new TdtDecoder();
        assertEquals(Arrays.asList(0, 1), d.decode(1, V, s));
        assertEquals(Arrays.asList("0/3", "0/0", "0/1"), s.calls);
        assertEquals(3, d.steps);
    }

    @Test public void tokenLoopIsCappedPerFrame() throws Exception {
        float[][] script = new float[TdtDecoder.MAX_TOKENS_PER_STEP][];
        Arrays.fill(script, out(2, 0));
        Script s = new Script(script);
        List<Integer> tokens = new TdtDecoder().decode(1, V, s);
        assertEquals(TdtDecoder.MAX_TOKENS_PER_STEP, tokens.size());
    }

    @Test public void detokenizeJoinsWordPieces() {
        String[] vocab = {"▁hel", "lo", "▁world", ",", "<blk>"};
        assertEquals("hello world,", TdtDecoder.detokenize(Arrays.asList(0, 1, 2, 3), vocab));
        assertEquals("", TdtDecoder.detokenize(new ArrayList<>(), vocab));
    }
}
