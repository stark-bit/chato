package com.whisperonnx.backend;

import com.whisperonnx.recording.WavUtils;

import android.content.Context;
import android.util.Log;

import com.whisperonnx.prefs.Prefs;

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
        void onError(TranscriptionStatus status, String detail);
    }

    private static final String TAG = "OpenAITranscriber";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    public void transcribe(Context context, byte[] pcmData, Callback callback) {
        executor.execute(() -> {
            try {
                transcribeNow(Prefs.of(context), pcmData, callback);
            } catch (IOException e) {
                Log.e(TAG, "Network error", e);
                callback.onError(TranscriptionStatus.NETWORK_ERROR, e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error", e);
                callback.onError(TranscriptionStatus.FAILED, e.getMessage());
            }
        });
    }

    private void transcribeNow(Prefs prefs, byte[] pcmData, Callback callback) throws IOException {
        String endpoint = prefs.serverUrl();
        String apiKey = prefs.serverApiKey();
        if (endpoint.isEmpty() || apiKey.isEmpty()) {
            callback.onError(TranscriptionStatus.SERVER_NOT_SET_UP, null);
            return;
        }

        Response response = client.newCall(request(endpoint, apiKey, prefs.serverModel(), pcmData)).execute();
        if (!response.isSuccessful()) {
            String errorBody = response.body() != null ? response.body().string().trim() : "";
            Log.e(TAG, "OpenAI error: " + errorBody);
            callback.onError(TranscriptionStatus.SERVER_REJECTED,
                    errorBody.isEmpty() ? String.valueOf(response.code()) : response.code() + ": " + errorBody);
            return;
        }

        String transcription = response.body() != null
                ? response.body().string().trim()
                : "";
        if (transcription.isEmpty()) {
            callback.onError(TranscriptionStatus.EMPTY_TRANSCRIPTION, null);
            return;
        }
        callback.onSuccess(transcription);
    }

    private static Request request(String endpoint, String apiKey, String model, byte[] pcmData) {
        RequestBody fileBody = RequestBody.create(
                WavUtils.pcmToWav(pcmData, 16000, 1, 16),
                MediaType.parse("audio/wav")
        );
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "audio.wav", fileBody)
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "text")
                .addFormDataPart("language", Whisper.LANGUAGE)
                .build();
        return new Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();
    }

    public void shutdown() {
        executor.shutdown();
    }
}
