package com.whisperonnx.asr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Recorder.findQuietCut: chunks should be split in the quietest spot of the search window. */
public class RecorderCutTest {

    private static final int FRAME = Recorder.VAD_FRAME_SIZE * 2;  // bytes

    /** 16-bit little-endian PCM, every sample at the given amplitude. */
    private static byte[] tone(int bytes, int amplitude) {
        byte[] pcm = new byte[bytes];
        for (int i = 0; i + 1 < bytes; i += 2) {
            pcm[i] = (byte) (amplitude & 0xFF);
            pcm[i + 1] = (byte) ((amplitude >> 8) & 0xFF);
        }
        return pcm;
    }

    private static void silence(byte[] pcm, int from, int to) {
        for (int i = from; i < to; i++) pcm[i] = 0;
    }

    @Test public void cutsInTheMiddleOfTheQuietFrame() {
        byte[] pcm = tone(FRAME * 10, 10000);
        int quietStart = FRAME * 5;
        silence(pcm, quietStart, quietStart + FRAME);
        assertEquals(quietStart + FRAME / 2, Recorder.findQuietCut(pcm, 0, pcm.length));
    }

    @Test public void negativeSamplesCountAsLoud() {
        byte[] pcm = tone(FRAME * 10, -10000);
        silence(pcm, FRAME * 3, FRAME * 4);
        assertEquals(FRAME * 3 + FRAME / 2, Recorder.findQuietCut(pcm, 0, pcm.length));
    }

    @Test public void ignoresQuietAudioOutsideTheWindow() {
        byte[] pcm = tone(FRAME * 10, 10000);
        silence(pcm, 0, FRAME * 2);  // quiet, but before the window
        int from = FRAME * 4, to = FRAME * 10;
        int cut = Recorder.findQuietCut(pcm, from, to);
        assertTrue("cut " + cut, cut >= from && cut <= to);
    }

    @Test public void uniformAudioCutsAtFirstFrame() {
        byte[] pcm = tone(FRAME * 4, 500);
        assertEquals(FRAME / 2, Recorder.findQuietCut(pcm, 0, pcm.length));
    }

    @Test public void windowShorterThanAFrameCutsAtItsEnd() {
        byte[] pcm = tone(FRAME * 4, 500);
        assertEquals(FRAME * 2, Recorder.findQuietCut(pcm, FRAME * 2 - 10, FRAME * 2));
    }

    @Test public void windowIsClampedToTheBuffer() {
        byte[] pcm = tone(FRAME * 2, 500);
        int cut = Recorder.findQuietCut(pcm, -100, FRAME * 50);
        assertTrue("cut " + cut, cut >= 0 && cut <= pcm.length);
    }

    @Test public void cutIsAlwaysOnASampleBoundary() {
        byte[] pcm = tone(FRAME * 6 + 1, 500);
        silence(pcm, FRAME * 2, FRAME * 3);
        for (int from = 1; from < 20; from += 3) {
            int cut = Recorder.findQuietCut(pcm, from, pcm.length);
            assertEquals("from " + from, 0, cut % 2);
        }
    }
}
