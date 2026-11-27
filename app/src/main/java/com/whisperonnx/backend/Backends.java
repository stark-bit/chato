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

    public static final class Description {
        public enum Kind { WHISPER, PARAKEET, PARAKEET_MISSING, SERVER, SERVER_NOT_SET_UP }

        public final Kind kind;
        public final String host;
        public final String model;

        Description(Kind kind, String host, String model) {
            this.kind = kind;
            this.host = host;
            this.model = model;
        }
    }

    public static Description describe(Prefs prefs, int choice, boolean parakeetInstalled) {
        switch (choice) {
            case REMOTE:
                return describeServer(prefs);
            case PARAKEET:
                return new Description(parakeetInstalled ? Description.Kind.PARAKEET : Description.Kind.PARAKEET_MISSING,
                        "", "");
            default:
                return new Description(Description.Kind.WHISPER, "", "");
        }
    }

    private static Description describeServer(Prefs prefs) {
        String host = hostOf(prefs.serverUrl());
        if (host == null || host.isEmpty() || prefs.serverApiKey().isEmpty()) {
            return new Description(Description.Kind.SERVER_NOT_SET_UP, "", "");
        }
        return new Description(Description.Kind.SERVER, host, prefs.serverModel());
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
