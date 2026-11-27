package com.whisperonnx.recording;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class WavUtilsTest {
    @Test public void writesStandardPcmHeader() {
        byte[] pcm = {1, 2, 3, 4, 5, 6};
        byte[] wav = WavUtils.pcmToWav(pcm, 16000, 1, 16);
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(44 + pcm.length, wav.length);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(36 + pcm.length, b.getInt(4));
        assertEquals("WAVE", new String(wav, 8, 4, StandardCharsets.US_ASCII));
        assertEquals("fmt ", new String(wav, 12, 4, StandardCharsets.US_ASCII));
        assertEquals(16, b.getInt(16));
        assertEquals(1, b.getShort(20));
        assertEquals(1, b.getShort(22));
        assertEquals(16000, b.getInt(24));
        assertEquals(32000, b.getInt(28));
        assertEquals(2, b.getShort(32));
        assertEquals(16, b.getShort(34));
        assertEquals("data", new String(wav, 36, 4, StandardCharsets.US_ASCII));
        assertEquals(pcm.length, b.getInt(40));
        for (int i = 0; i < pcm.length; i++) assertEquals(pcm[i], wav[44 + i]);
    }

    @Test public void emptyAudioIsJustAHeader() {
        byte[] wav = WavUtils.pcmToWav(new byte[0], 16000, 1, 16);
        assertEquals(44, wav.length);
        assertEquals(0, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(40));
    }
}
