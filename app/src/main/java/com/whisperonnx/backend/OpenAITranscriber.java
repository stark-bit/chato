package com.whisperonnx.backend;

import com.whisperonnx.recording.WavUtils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class OpenAITranscriber {
    public interface Callback {
        void onSuccess(String transcription);
        void onError(String errorMessage);
    }

    private static final String TAG = "OpenAITranscriber";
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions";
    private static final String DEFAULT_MODEL = "whisper-1";

    public static final String PREF_BACKEND_TYPE = "backend_type";
    public static final String PREF_OPENAI_ENDPOINT = "openai_endpoint";
    public static final String PREF_OPENAI_API_KEY = "openai_api_key";
    public static final String PREF_OPENAI_MODEL = "openai_model";

    public static final String BACKEND_LOCAL = "local";
    public static final String BACKEND_OPENAI = "openai";

    public static final String MSG_EMPTY_TRANSCRIPTION = "OpenAI returned empty transcription";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    public void transcribe(Context context, byte[] pcmData, Callback callback) {
        executor.execute(() -> {
            try {
                SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);

                String endpoint = sp.getString(PREF_OPENAI_ENDPOINT, DEFAULT_ENDPOINT);
                String apiKey = sp.getString(PREF_OPENAI_API_KEY, "");
                String model = sp.getString(PREF_OPENAI_MODEL, DEFAULT_MODEL);

                if (endpoint.isEmpty()) {
                    callback.onError("OpenAI endpoint not configured");
                    return;
                }
                if (apiKey.isEmpty()) {
                    callback.onError("Server API key not set. Add it in Settings");
                    return;
                }

                byte[] wavData = WavUtils.pcmToWav(pcmData, 16000, 1, 16);

                RequestBody fileBody = RequestBody.create(
                        wavData,
                        MediaType.parse("audio/wav")
                );

                MultipartBody.Builder builder = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", "audio.wav", fileBody)
                        .addFormDataPart("model", model)
                        .addFormDataPart("response_format", "text")
                        .addFormDataPart("language", Whisper.LANGUAGE);

                Request request = new Request.Builder()
                        .url(endpoint)
                        .addHeader("Authorization", "Bearer " + apiKey)
                        .post(builder.build())
                        .build();

                Response response = client.newCall(request).execute();

                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null
                            ? response.body().string()
                            : "HTTP " + response.code();
                    Log.e(TAG, "OpenAI error: " + errorBody);
                    callback.onError("OpenAI error " + response.code() + ": " + errorBody);
                    return;
                }

                String transcription = response.body() != null
                        ? response.body().string().trim()
                        : "";

                if (transcription.isEmpty()) {
                    callback.onError(MSG_EMPTY_TRANSCRIPTION);
                    return;
                }

                callback.onSuccess(transcription);

            } catch (IOException e) {
                Log.e(TAG, "Network error", e);
                callback.onError("Network error: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error", e);
                callback.onError("Error: " + e.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }
}
