package com.whisperonnx.backend;

import static org.junit.Assert.assertEquals;

import com.whisperonnx.prefs.FakeSharedPreferences;
import com.whisperonnx.prefs.Prefs;

import org.junit.Test;

public class BackendsTest {
    private final Prefs prefs = new Prefs(new FakeSharedPreferences());

    @Test public void defaultIsWhisper() {
        assertEquals(Backends.WHISPER, Backends.current(prefs));
    }

    @Test public void selectRoundTrips() {
        for (int choice : new int[]{Backends.PARAKEET, Backends.REMOTE, Backends.WHISPER, Backends.PARAKEET}) {
            Backends.select(prefs, choice);
            assertEquals(choice, Backends.current(prefs));
        }
    }

    @Test public void remoteKeepsTheLocalEngine() {
        Backends.select(prefs, Backends.PARAKEET);
        Backends.select(prefs, Backends.REMOTE);
        prefs.setUseServer(false);
        assertEquals(Backends.PARAKEET, Backends.current(prefs));
    }

    @Test public void describeRemoteShowsHostAndModel() {
        prefs.setServer("http://192.168.1.9:5092/v1/audio/transcriptions", "key", "parakeet");
        assertEquals("Server 192.168.1.9:5092 · model parakeet", Backends.describe(prefs, Backends.REMOTE, false));

        prefs.setServer("https://api.openai.com/v1/audio/transcriptions", "key", "");
        assertEquals("Server api.openai.com", Backends.describe(prefs, Backends.REMOTE, false));
    }

    @Test public void describeRemoteWithoutUrlOrKey() {
        prefs.setServer("", "key", "");
        assertEquals("Server not set up. Add it in Settings", Backends.describe(prefs, Backends.REMOTE, false));
        prefs.setServer("http://192.168.1.9:5092/v1/audio/transcriptions", "", "");
        assertEquals("Server not set up. Add it in Settings", Backends.describe(prefs, Backends.REMOTE, false));
    }

    @Test public void describeParakeetDependsOnInstall() {
        assertEquals("On this phone · NVIDIA Parakeet TDT 0.6B", Backends.describe(prefs, Backends.PARAKEET, true));
        assertEquals("Not downloaded yet. Whisper small is used until it is",
                Backends.describe(prefs, Backends.PARAKEET, false));
    }
}
