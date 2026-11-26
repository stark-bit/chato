package com.whisperonnx.utils;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Sends transcribed text as an HTTP POST to a user-configured endpoint instead of typing it.
 *
 * Body template placeholders:
 *   {{text}}      transcribed text, JSON-string-escaped (put it inside quotes in a JSON body)
 *   {{text_raw}}  transcribed text, unescaped
 *   {{timestamp}} ISO 8601 local time
 *   {{name}}      the endpoint's name
 */
public class EndpointSender {
    private static final String TAG = "EndpointSender";
    public static final int COUNT = 2;
    public static final String DEFAULT_BODY = "{\"text\": \"{{text}}\"}";

    public static class Endpoint {
        public final int index;  // 1-based
        public final String name;
        public final String url;
        public final String headers;
        public final String body;
        public final boolean combine;

        public Endpoint(int index, String name, String url, String headers, String body, boolean combine) {
            this.index = index;
            this.name = name;
            this.url = url;
            this.headers = headers;
            this.body = body;
            this.combine = combine;
        }

        public boolean isConfigured() { return !url.isEmpty(); }

        public String displayName() { return name.isEmpty() ? "Endpoint " + index : name; }
    }

    public interface ResultCallback {
        /** Called on the main thread. error is null on success. */
        void onDone(Endpoint endpoint, String text, String error);
    }

    public static String key(int index, String field) { return "endpoint" + index + "_" + field; }

    public static Endpoint load(SharedPreferences sp, int index) {
        return new Endpoint(index,
                sp.getString(key(index, "name"), "").trim(),
                sp.getString(key(index, "url"), "").trim(),
                sp.getString(key(index, "headers"), ""),
                sp.getString(key(index, "body"), DEFAULT_BODY),
                sp.getBoolean(key(index, "combine"), true));
    }

    /**
     * The endpoint quick record sends to: {@code preferred} if it has a URL, otherwise the first
     * endpoint that has one, or 0 if none does. {@code configured[i]} is endpoint i + 1.
     */
    public static int pickQuickRecord(int preferred, boolean[] configured) {
        if (preferred >= 1 && preferred <= configured.length && configured[preferred - 1]) return preferred;
        for (int i = 0; i < configured.length; i++) if (configured[i]) return i + 1;
        return 0;
    }

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private static final ExecutorService sendQueue = Executors.newSingleThreadExecutor();
    private static Handler mainHandler;  // created lazily so the class loads in JVM unit tests

    public static void post(Endpoint ep, String text, ResultCallback callback) {
        final Request req;
        try {
            req = buildRequest(ep, text);
        } catch (IllegalArgumentException e) {  // malformed URL or header
            callback.onDone(ep, text, "Invalid endpoint config: " + e.getMessage());
            return;
        }
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        final Handler main = mainHandler;

        // One request at a time so per-chunk messages arrive in speech order
        sendQueue.execute(() -> {
            String err = execute(client, req);
            main.post(() -> callback.onDone(ep, text, err));
        });
    }

    /** Builds the POST request. Throws IllegalArgumentException for a bad URL or header. */
    static Request buildRequest(Endpoint ep, String text) {
        Request.Builder builder = new Request.Builder().url(ep.url);
        String contentType = "application/json; charset=utf-8";
        for (String line : ep.headers.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Content-Type")) contentType = value;
            else builder.addHeader(name, value);
        }
        String body = fillTemplate(ep, text);
        return builder.post(RequestBody.create(body, MediaType.parse(contentType))).build();
    }

    /** Runs the request synchronously. Returns null on 2xx, otherwise an error message. */
    static String execute(OkHttpClient client, Request req) {
        try (Response response = client.newCall(req).execute()) {
            return response.isSuccessful() ? null : "HTTP " + response.code();
        } catch (IOException e) {
            Log.e(TAG, "POST to " + req.url() + " failed", e);
            return "Network error: " + e.getMessage();
        }
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(text_raw|text|timestamp|name)\\}\\}");

    /** Fills placeholders in one pass, so placeholder-like text in the dictation stays as-is. */
    static String fillTemplate(Endpoint ep, String text) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date());
        String template = ep.body.isEmpty() ? DEFAULT_BODY : ep.body;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuffer out = new StringBuffer(template.length() + text.length() + 16);
        while (m.find()) {
            String value;
            switch (m.group(1)) {
                case "text_raw": value = text; break;
                case "text": value = jsonEscape(text); break;
                case "timestamp": value = timestamp; break;
                default: value = jsonEscape(ep.displayName()); break;
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
