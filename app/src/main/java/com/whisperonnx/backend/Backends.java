package com.whisperonnx.backend;

import com.whisperonnx.prefs.Prefs;

import java.net.URI;

public final class Backends {
    public static final int WHISPER = 0, PARAKEET = 1, REMOTE = 2;

    private Backends() {}

    public static int current(Prefs prefs) {
        if (prefs.useServer()) return REMOTE;
        return Prefs.ENGINE_PARAKEET.equals(prefs.localEngine()) ? PARAKEET : WHISPER;
    }

    public static void select(Prefs prefs, int choice) {
        if (choice == REMOTE) prefs.setUseServer(true);
        else prefs.setLocal(choice == PARAKEET ? Prefs.ENGINE_PARAKEET : Prefs.ENGINE_WHISPER);
    }

    public static String describe(Prefs prefs, int choice, boolean parakeetInstalled) {
        switch (choice) {
            case REMOTE:
                return describeServer(prefs);
            case PARAKEET:
                return parakeetInstalled
                        ? "On this phone · NVIDIA Parakeet TDT 0.6B"
                        : "Not downloaded yet. Whisper small is used until it is";
            default:
                return "On this phone · Whisper small (int8)";
        }
    }

    private static String describeServer(Prefs prefs) {
        String host = hostOf(prefs.serverUrl());
        if (host == null || host.isEmpty() || prefs.serverApiKey().isEmpty()) return "Server not set up. Add it in Settings";
        String model = prefs.serverModel();
        return "Server " + host + (model.isEmpty() ? "" : " · model " + model);
    }

    private static String hostOf(String url) {
        try {
            URI u = new URI(url.trim());
            if (u.getHost() == null) return url;
            return u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Exception ignored) {
            return url;
        }
    }
}
