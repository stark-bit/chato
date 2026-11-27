package com.whisperonnx.recording;

// External: OpenAI /v1/audio/transcriptions needs a file with an extension; a PCM WAV qualifies
public class WavUtils {
    public static byte[] pcmToWav(byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
        int headerSize = 44;
        int dataSize = pcmData.length;
        byte[] wav = new byte[headerSize + dataSize];

        // External: the canonical 44-byte RIFF WAVE header, fields in spec order
        writeFourCC(wav, 0, "RIFF");
        writeIntLE(wav, 4, 36 + dataSize);
        writeFourCC(wav, 8, "WAVE");

        writeFourCC(wav, 12, "fmt ");
        writeIntLE(wav, 16, 16);
        writeShortLE(wav, 20, (short) 1);
        writeShortLE(wav, 22, (short) channels);
        writeIntLE(wav, 24, sampleRate);
        writeIntLE(wav, 28, sampleRate * channels * bitsPerSample / 8);
        writeShortLE(wav, 32, (short) (channels * bitsPerSample / 8));
        writeShortLE(wav, 34, (short) bitsPerSample);

        writeFourCC(wav, 36, "data");
        writeIntLE(wav, 40, dataSize);

        System.arraycopy(pcmData, 0, wav, headerSize, dataSize);

        return wav;
    }

    private static void writeFourCC(byte[] dest, int offset, String fourCC) {
        for (int i = 0; i < 4; i++) {
            dest[offset + i] = (byte) fourCC.charAt(i);
        }
    }

    private static void writeIntLE(byte[] dest, int offset, int value) {
        dest[offset]     = (byte) (value & 0xFF);
        dest[offset + 1] = (byte) ((value >> 8) & 0xFF);
        dest[offset + 2] = (byte) ((value >> 16) & 0xFF);
        dest[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void writeShortLE(byte[] dest, int offset, short value) {
        dest[offset]     = (byte) (value & 0xFF);
        dest[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }
}
