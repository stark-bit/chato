package com.whisperonnx.backend;

public enum TranscriptionStatus {
    MODEL_LOADED(false),
    PROCESSING(false),
    PROCESSING_DONE(false),
    QUEUE_EMPTY(false),
    EMPTY_TRANSCRIPTION(false),
    MODEL_FAILED(true),
    MODEL_NOT_LOADED(true),
    NO_AUDIO(true),
    TOO_SHORT(true),
    TIMED_OUT(true),
    FAILED(true),
    SERVER_NOT_SET_UP(true),
    SERVER_REJECTED(true),
    NETWORK_ERROR(true);

    public final boolean isProblem;

    TranscriptionStatus(boolean isProblem) {
        this.isProblem = isProblem;
    }
}
