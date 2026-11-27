package com.whisperonnx.endpoint;

public final class SendFailure {
    public enum Kind { INVALID_CONFIG, HTTP, NETWORK }

    public final Kind kind;
    public final String detail;

    public SendFailure(Kind kind, String detail) {
        this.kind = kind;
        this.detail = detail;
    }
}
