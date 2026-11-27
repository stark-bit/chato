package com.whisperonnx.backend;

import static org.junit.Assert.assertEquals;

import com.whisperonnx.prefs.FakeSharedPreferences;
import com.whisperonnx.prefs.Prefs;
import org.junit.Test;

public class BackendsTest {
    private final Prefs prefs = new Prefs(new FakeSharedPreferences());

    @Test
    public void defaultIsWhisper() {
        assertEquals(Backends.WHISPER, Backends.current(prefs));
    }

    @Test
    public void selectRoundTrips() {
        for (int choice : new int[] {Backends.PARAKEET, Backends.REMOTE, Backends.WHISPER, Backends.PARAKEET}) {
            Backends.select(prefs, choice);
            assertEquals(choice, Backends.current(prefs));
        }
    }

    @Test
    public void remoteKeepsTheLocalEngine() {
        Backends.select(prefs, Backends.PARAKEET);
        Backends.select(prefs, Backends.REMOTE);
        prefs.setUseServer(false);
        assertEquals(Backends.PARAKEET, Backends.current(prefs));
    }

    @Test
    public void describeRemoteShowsHostAndModel() {
        prefs.setServer("http://192.168.1.9:5092/v1/audio/transcriptions", "key", "parakeet");
        assertDescription(
                Backends.Description.Kind.SERVER,
                "192.168.1.9:5092",
                "parakeet",
                Backends.describe(prefs, Backends.REMOTE, false));

        prefs.setServer("https://api.openai.com/v1/audio/transcriptions", "key", "");
        assertDescription(
                Backends.Description.Kind.SERVER,
                "api.openai.com",
                "",
                Backends.describe(prefs, Backends.REMOTE, false));
    }

    @Test
    public void describeRemoteWithoutUrlOrKey() {
        prefs.setServer("", "key", "");
        assertEquals(
                Backends.Description.Kind.SERVER_NOT_SET_UP, Backends.describe(prefs, Backends.REMOTE, false).kind);
        prefs.setServer("http://192.168.1.9:5092/v1/audio/transcriptions", "", "");
        assertEquals(
                Backends.Description.Kind.SERVER_NOT_SET_UP, Backends.describe(prefs, Backends.REMOTE, false).kind);
    }

    @Test
    public void describeParakeetDependsOnInstall() {
        assertEquals(Backends.Description.Kind.PARAKEET, Backends.describe(prefs, Backends.PARAKEET, true).kind);
        assertEquals(
                Backends.Description.Kind.PARAKEET_MISSING, Backends.describe(prefs, Backends.PARAKEET, false).kind);
    }

    private static void assertDescription(
            Backends.Description.Kind kind, String host, String model, Backends.Description actual) {
        assertEquals(kind, actual.kind);
        assertEquals(host, actual.host);
        assertEquals(model, actual.model);
    }
}
