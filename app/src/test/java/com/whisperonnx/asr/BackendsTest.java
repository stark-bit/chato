package com.whisperonnx.asr;

import static org.junit.Assert.assertEquals;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class BackendsTest {

    /** Minimal in-memory SharedPreferences (strings only, which is all Backends uses). */
    private static class Prefs implements SharedPreferences {
        final Map<String, Object> m = new HashMap<>();
        @Override public Map<String, ?> getAll() { return m; }
        @Override public String getString(String k, String d) { return m.containsKey(k) ? (String) m.get(k) : d; }
        @Override public Set<String> getStringSet(String k, Set<String> d) { return d; }
        @Override public int getInt(String k, int d) { return d; }
        @Override public long getLong(String k, long d) { return d; }
        @Override public float getFloat(String k, float d) { return d; }
        @Override public boolean getBoolean(String k, boolean d) { return d; }
        @Override public boolean contains(String k) { return m.containsKey(k); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        @Override public Editor edit() {
            Map<String, Object> pending = new HashMap<>();
            return new Editor() {
                @Override public Editor putString(String k, String v) { pending.put(k, v); return this; }
                @Override public Editor putStringSet(String k, Set<String> v) { return this; }
                @Override public Editor putInt(String k, int v) { return this; }
                @Override public Editor putLong(String k, long v) { return this; }
                @Override public Editor putFloat(String k, float v) { return this; }
                @Override public Editor putBoolean(String k, boolean v) { return this; }
                @Override public Editor remove(String k) { return this; }
                @Override public Editor clear() { return this; }
                @Override public boolean commit() { m.putAll(pending); return true; }
                @Override public void apply() { m.putAll(pending); }
            };
        }
    }

    private final Prefs sp = new Prefs();

    @Test public void defaultIsWhisper() {
        assertEquals(Backends.WHISPER, Backends.current(sp));
    }

    @Test public void selectRoundTrips() {
        for (int choice : new int[]{Backends.PARAKEET, Backends.REMOTE, Backends.WHISPER, Backends.PARAKEET}) {
            Backends.select(sp, choice);
            assertEquals(choice, Backends.current(sp));
        }
    }

    @Test public void remoteKeepsTheLocalEngine() {
        // The keyboard's cloud key flips local <-> remote; coming back must keep Parakeet
        Backends.select(sp, Backends.PARAKEET);
        Backends.select(sp, Backends.REMOTE);
        sp.m.put(OpenAITranscriber.PREF_BACKEND_TYPE, OpenAITranscriber.BACKEND_LOCAL);
        assertEquals(Backends.PARAKEET, Backends.current(sp));
    }

    @Test public void describeRemoteShowsHostAndModel() {
        sp.m.put(OpenAITranscriber.PREF_OPENAI_ENDPOINT, "http://192.168.1.9:5092/v1/audio/transcriptions");
        sp.m.put(OpenAITranscriber.PREF_OPENAI_MODEL, "parakeet");
        assertEquals("Server 192.168.1.9:5092 · model parakeet", Backends.describe(sp, Backends.REMOTE, false));

        sp.m.put(OpenAITranscriber.PREF_OPENAI_ENDPOINT, "https://api.openai.com/v1/audio/transcriptions");
        sp.m.put(OpenAITranscriber.PREF_OPENAI_MODEL, "");
        assertEquals("Server api.openai.com", Backends.describe(sp, Backends.REMOTE, false));
    }

    @Test public void describeRemoteWithoutUrl() {
        sp.m.put(OpenAITranscriber.PREF_OPENAI_ENDPOINT, "");
        assertEquals("Server not set up. Add it in Settings", Backends.describe(sp, Backends.REMOTE, false));
    }

    @Test public void describeParakeetDependsOnInstall() {
        assertEquals("On device · NVIDIA Parakeet TDT 0.6B", Backends.describe(sp, Backends.PARAKEET, true));
        assertEquals("Not downloaded yet. Whisper small is used until it is",
                Backends.describe(sp, Backends.PARAKEET, false));
    }
}
