package com.whisperonnx.backend;

import android.content.SharedPreferences;

import java.net.URI;

/**
 * The three transcription choices shown in Settings and on the test screen, and how they map
 * to preferences: "backend_type" (local / openai) plus, for local, "local_engine".
 */
public final class Backends {

    /** Positions in the dropdowns; labels are R.array.backend_labels in the same order. */
    public static final int WHISPER = 0, PARAKEET = 1, REMOTE = 2;

    private Backends() {}

    public static int current(SharedPreferences sp) {
        if (OpenAITranscriber.BACKEND_OPENAI.equals(
                sp.getString(OpenAITranscriber.PREF_BACKEND_TYPE, OpenAITranscriber.BACKEND_LOCAL))) {
            return REMOTE;
        }
        return Whisper.ENGINE_PARAKEET.equals(Whisper.localEngine(sp)) ? PARAKEET : WHISPER;
    }

    public static void select(SharedPreferences sp, int choice) {
        SharedPreferences.Editor ed = sp.edit().putString(OpenAITranscriber.PREF_BACKEND_TYPE,
                choice == REMOTE ? OpenAITranscriber.BACKEND_OPENAI : OpenAITranscriber.BACKEND_LOCAL);
        if (choice != REMOTE) {
            ed.putString(Whisper.PREF_LOCAL_ENGINE, choice == PARAKEET ? Whisper.ENGINE_PARAKEET : Whisper.ENGINE_WHISPER);
        }
        ed.apply();
    }

    /** One line saying what will actually run for {@code choice}. */
    public static String describe(SharedPreferences sp, int choice, boolean parakeetInstalled) {
        switch (choice) {
            case REMOTE:
                String url = sp.getString(OpenAITranscriber.PREF_OPENAI_ENDPOINT, "");
                String model = sp.getString(OpenAITranscriber.PREF_OPENAI_MODEL, "");
                String host = url;
                try {
                    URI u = new URI(url.trim());
                    if (u.getHost() != null) host = u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
                } catch (Exception ignored) { }
                if (host == null || host.isEmpty()) return "Server not set up. Add it in Settings";
                return "Server " + host + (model.isEmpty() ? "" : " · model " + model);
            case PARAKEET:
                return parakeetInstalled
                        ? "On this phone · NVIDIA Parakeet TDT 0.6B"
                        : "Not downloaded yet. Whisper small is used until it is";
            default:
                return "On this phone · Whisper small (int8)";
        }
    }
}
