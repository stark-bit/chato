package com.whisperonnx.recording;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class RecordBuffer {
    private static byte[] outputBuffer;

    public static synchronized void setOutputBuffer(byte[] buffer) {
        outputBuffer = buffer;
    }

    public static synchronized byte[] getOutputBuffer() {
        return outputBuffer;
    }

    public static float[] getSamples() {
        return getSamples(RecordBuffer.getOutputBuffer());
    }

    public static float[] getSamples(byte[] pcm) {
        int numSamples = pcm.length / 2;
        ByteBuffer byteBuffer = ByteBuffer.wrap(pcm);
        byteBuffer.order(ByteOrder.nativeOrder());

        float[] samples = new float[numSamples];
        float maxAbsValue = 0.0f;

        for (int i = 0; i < numSamples; i++) {
            samples[i] = (float) (byteBuffer.getShort() / 32768.0);
            if (Math.abs(samples[i]) > maxAbsValue) {
                maxAbsValue = Math.abs(samples[i]);
            }
        }

        if (maxAbsValue > 0.0f) {
            for (int i = 0; i < numSamples; i++) {
                samples[i] /= maxAbsValue;
            }
        }

        return samples;
    }
}
