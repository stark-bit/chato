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

        Endpoint(int index, String name, String url, String headers, String body, boolean combine) {
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

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    private static final ExecutorService sendQueue = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void post(Endpoint ep, String text, ResultCallback callback) {
        Request request;
        try {
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
            request = builder.post(RequestBody.create(body, MediaType.parse(contentType))).build();
        } catch (IllegalArgumentException e) {  // malformed URL or header
            callback.onDone(ep, text, "Invalid endpoint config: " + e.getMessage());
            return;
        }

        // One request at a time so per-chunk messages arrive in speech order
        final Request req = request;
        sendQueue.execute(() -> {
            String error;
            try (Response response = client.newCall(req).execute()) {
                error = response.isSuccessful() ? null : "HTTP " + response.code();
            } catch (IOException e) {
                Log.e(TAG, "POST to " + ep.url + " failed", e);
                error = "Network error: " + e.getMessage();
            }
            final String err = error;
            mainHandler.post(() -> callback.onDone(ep, text, err));
        });
    }

    static String fillTemplate(Endpoint ep, String text) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date());
        String template = ep.body.isEmpty() ? DEFAULT_BODY : ep.body;
        return template
                .replace("{{text_raw}}", text)
                .replace("{{text}}", jsonEscape(text))
                .replace("{{timestamp}}", timestamp)
                .replace("{{name}}", jsonEscape(ep.displayName()));
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
