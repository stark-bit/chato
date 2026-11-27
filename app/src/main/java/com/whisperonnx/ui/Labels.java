package com.whisperonnx.ui;

import com.whisperonnx.R;
import com.whisperonnx.backend.Backends;
import com.whisperonnx.backend.TranscriptionStatus;
import com.whisperonnx.endpoint.EndpointSender;
import com.whisperonnx.endpoint.SendFailure;

import android.content.Context;

public final class Labels {
    private Labels() {}

    public static String endpoint(Context context, EndpointSender.Endpoint ep) {
        return ep.name.isEmpty() ? context.getString(R.string.endpoint_title, ep.index) : ep.name;
    }

    public static String problem(Context context, TranscriptionStatus status, String detail) {
        return withDetail(context, context.getString(problemText(status)), detail);
    }

    public static String recordingFailed(Context context, String detail) {
        return withDetail(context, context.getString(R.string.status_recording_failed), detail);
    }

    public static String sendFailure(Context context, SendFailure failure) {
        switch (failure.kind) {
            case HTTP:
                return context.getString(R.string.send_failed_http, failure.detail);
            case INVALID_CONFIG:
                return withDetail(context, context.getString(R.string.send_failed_config), failure.detail);
            default:
                return withDetail(context, context.getString(R.string.send_failed_network), failure.detail);
        }
    }

    public static String backend(Context context, Backends.Description d) {
        switch (d.kind) {
            case SERVER:
                return d.model.isEmpty()
                        ? context.getString(R.string.backend_server_description, d.host)
                        : context.getString(R.string.backend_server_model_description, d.host, d.model);
            case SERVER_NOT_SET_UP:
                return context.getString(R.string.status_server_not_set_up);
            case PARAKEET:
                return context.getString(R.string.backend_parakeet_description);
            case PARAKEET_MISSING:
                return context.getString(R.string.backend_parakeet_missing_description);
            default:
                return context.getString(R.string.backend_whisper_description);
        }
    }

    private static int problemText(TranscriptionStatus status) {
        switch (status) {
            case MODEL_FAILED: return R.string.status_model_failed;
            case MODEL_NOT_LOADED: return R.string.status_model_not_loaded;
            case NO_AUDIO: return R.string.status_no_audio;
            case TOO_SHORT: return R.string.status_too_short;
            case TIMED_OUT: return R.string.status_timed_out;
            case SERVER_NOT_SET_UP: return R.string.status_server_not_set_up;
            case SERVER_REJECTED: return R.string.status_server_rejected;
            case NETWORK_ERROR: return R.string.status_network_error;
            default: return R.string.status_failed;
        }
    }

    private static String withDetail(Context context, String text, String detail) {
        return detail == null || detail.isEmpty() ? text : context.getString(R.string.status_with_detail, text, detail);
    }
}
