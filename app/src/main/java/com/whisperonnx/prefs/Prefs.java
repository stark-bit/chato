package com.whisperonnx.prefs;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

public final class Prefs {
    public static final String ENGINE_WHISPER = "whisper";
    public static final String ENGINE_PARAKEET = "parakeet";
    public static final String DEFAULT_SERVER_URL = "https://api.openai.com/v1/audio/transcriptions";
    public static final String DEFAULT_SERVER_MODEL = "whisper-1";
    public static final String DEFAULT_ENDPOINT_BODY = "{\"text\": \"{{text}}\"}";
    public static final int DEFAULT_SILENCE_MS = 3000;

    private static final String BACKEND_TYPE = "backend_type";
    private static final String BACKEND_LOCAL = "local";
    private static final String BACKEND_SERVER = "openai";
    private static final String LOCAL_ENGINE = "local_engine";
    private static final String SERVER_URL = "openai_endpoint";
    private static final String SERVER_API_KEY = "openai_api_key";
    private static final String SERVER_MODEL = "openai_model";
    private static final String MODE_AUTO = "imeModeAuto";
    private static final String PAUSE_OTHER_AUDIO = "imePauseOtherAudio";
    private static final String TOOLBAR_EXPANDED = "imeToolbarExpanded";
    private static final String SEND_TARGET = "imeSendTarget";
    private static final String BLUETOOTH = "bluetooth";
    private static final String SILENCE_MS = "silenceDurationMs";
    private static final String QUICK_RECORD_ENDPOINT = "quickRecordEndpoint";
    private static final String ASK_FOR_STAR = "askForStar";
    private static final String STAR_VERSION_CODE = "versionCode";

    public enum EndpointField {
        NAME("name", ""), URL("url", ""), HEADERS("headers", ""), BODY("body", DEFAULT_ENDPOINT_BODY);

        final String key;
        final String fallback;

        EndpointField(String key, String fallback) {
            this.key = key;
            this.fallback = fallback;
        }
    }

    private final SharedPreferences sp;

    public Prefs(SharedPreferences sp) {
        this.sp = sp;
    }

    public static Prefs of(Context context) {
        return new Prefs(PreferenceManager.getDefaultSharedPreferences(context));
    }

    public boolean useServer() { return BACKEND_SERVER.equals(sp.getString(BACKEND_TYPE, BACKEND_LOCAL)); }
    public void setUseServer(boolean server) { putString(BACKEND_TYPE, server ? BACKEND_SERVER : BACKEND_LOCAL); }
    public String localEngine() { return sp.getString(LOCAL_ENGINE, ENGINE_WHISPER); }

    public void setLocal(String engine) {
        sp.edit().putString(BACKEND_TYPE, BACKEND_LOCAL).putString(LOCAL_ENGINE, engine).apply();
    }

    public String serverUrl() { return sp.getString(SERVER_URL, DEFAULT_SERVER_URL); }
    public String serverApiKey() { return sp.getString(SERVER_API_KEY, ""); }
    public String serverModel() { return sp.getString(SERVER_MODEL, DEFAULT_SERVER_MODEL); }

    public void setServer(String url, String apiKey, String model) {
        sp.edit().putString(SERVER_URL, url).putString(SERVER_API_KEY, apiKey).putString(SERVER_MODEL, model).apply();
    }

    public boolean modeAuto() { return sp.getBoolean(MODE_AUTO, false); }
    public void setModeAuto(boolean on) { putBoolean(MODE_AUTO, on); }
    public boolean pauseOtherAudio() { return sp.getBoolean(PAUSE_OTHER_AUDIO, false); }
    public void setPauseOtherAudio(boolean on) { putBoolean(PAUSE_OTHER_AUDIO, on); }
    public boolean toolbarExpanded() { return sp.getBoolean(TOOLBAR_EXPANDED, true); }
    public void setToolbarExpanded(boolean on) { putBoolean(TOOLBAR_EXPANDED, on); }
    public int sendTarget() { return sp.getInt(SEND_TARGET, 0); }
    public void setSendTarget(int endpoint) { putInt(SEND_TARGET, endpoint); }
    public boolean bluetooth() { return sp.getBoolean(BLUETOOTH, false); }
    public void setBluetooth(boolean on) { putBoolean(BLUETOOTH, on); }
    public int silenceMs() { return sp.getInt(SILENCE_MS, DEFAULT_SILENCE_MS); }
    public void setSilenceMs(int ms) { putInt(SILENCE_MS, ms); }
    public int quickRecordEndpoint() { return sp.getInt(QUICK_RECORD_ENDPOINT, 1); }
    public void setQuickRecordEndpoint(int endpoint) { putInt(QUICK_RECORD_ENDPOINT, endpoint); }

    public int widgetEndpoint(int widgetId) { return sp.getInt(widgetKey(widgetId), 0); }
    public void setWidgetEndpoint(int widgetId, int endpoint) { putInt(widgetKey(widgetId), endpoint); }

    public void forgetWidgets(int[] widgetIds) {
        SharedPreferences.Editor e = sp.edit();
        for (int id : widgetIds) e.remove(widgetKey(id));
        e.apply();
    }

    public String endpointText(int index, EndpointField field) {
        return sp.getString(endpointKey(index, field.key), field.fallback);
    }

    public void setEndpointText(int index, EndpointField field, String value) {
        putString(endpointKey(index, field.key), value);
    }

    public boolean endpointCombine(int index) { return sp.getBoolean(endpointKey(index, "combine"), true); }
    public void setEndpointCombine(int index, boolean on) { putBoolean(endpointKey(index, "combine"), on); }

    public boolean askForStar() { return sp.getBoolean(ASK_FOR_STAR, true); }
    public void setAskForStar(boolean ask) { putBoolean(ASK_FOR_STAR, ask); }

    public boolean starVersionIsOlderThan(int versionCode) {
        return sp.contains(STAR_VERSION_CODE) && sp.getInt(STAR_VERSION_CODE, 0) < versionCode;
    }

    public void setStarVersion(int versionCode) { putInt(STAR_VERSION_CODE, versionCode); }

    private static String widgetKey(int widgetId) { return "quickRecordWidget_" + widgetId; }
    private static String endpointKey(int index, String field) { return "endpoint" + index + "_" + field; }

    private void putString(String key, String value) { sp.edit().putString(key, value).apply(); }
    private void putBoolean(String key, boolean value) { sp.edit().putBoolean(key, value).apply(); }
    private void putInt(String key, int value) { sp.edit().putInt(key, value).apply(); }
}
