package com.whisperonnx.prefs;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class FakeSharedPreferences implements SharedPreferences {
    private final Map<String, Object> m = new HashMap<>();

    @SuppressWarnings("unchecked")
    private <T> T get(String k, T d) {
        return m.containsKey(k) ? (T) m.get(k) : d;
    }

    @Override
    public Map<String, ?> getAll() {
        return m;
    }

    @Override
    public String getString(String k, String d) {
        return get(k, d);
    }

    @Override
    public Set<String> getStringSet(String k, Set<String> d) {
        return get(k, d);
    }

    @Override
    public int getInt(String k, int d) {
        return get(k, d);
    }

    @Override
    public long getLong(String k, long d) {
        return get(k, d);
    }

    @Override
    public float getFloat(String k, float d) {
        return get(k, d);
    }

    @Override
    public boolean getBoolean(String k, boolean d) {
        return get(k, d);
    }

    @Override
    public boolean contains(String k) {
        return m.containsKey(k);
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}

    @Override
    public Editor edit() {
        Map<String, Object> pending = new HashMap<>();
        Set<String> removed = new java.util.HashSet<>();
        return new Editor() {
            @Override
            public Editor putString(String k, String v) {
                pending.put(k, v);
                return this;
            }

            @Override
            public Editor putStringSet(String k, Set<String> v) {
                pending.put(k, v);
                return this;
            }

            @Override
            public Editor putInt(String k, int v) {
                pending.put(k, v);
                return this;
            }

            @Override
            public Editor putLong(String k, long v) {
                pending.put(k, v);
                return this;
            }

            @Override
            public Editor putFloat(String k, float v) {
                pending.put(k, v);
                return this;
            }

            @Override
            public Editor putBoolean(String k, boolean v) {
                pending.put(k, v);
                return this;
            }

            @Override
            public Editor remove(String k) {
                removed.add(k);
                return this;
            }

            @Override
            public Editor clear() {
                m.clear();
                return this;
            }

            @Override
            public boolean commit() {
                apply();
                return true;
            }

            @Override
            public void apply() {
                m.keySet().removeAll(removed);
                m.putAll(pending);
            }
        };
    }
}
