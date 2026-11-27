package com.whisperonnx.endpoint;

import java.util.concurrent.Executor;

public final class SendSession {
    public interface Output {
        void type(String text);

        void send(EndpointSender.Endpoint endpoint, String text);
    }

    private final Output output;
    private final Executor mainThread;
    private final StringBuilder buffer = new StringBuilder();
    private EndpointSender.Endpoint endpoint;
    private boolean ended = true;

    public SendSession(Output output, Executor mainThread) {
        this.output = output;
        this.mainThread = mainThread;
    }

    public void begin(EndpointSender.Endpoint target) {
        flush();
        endpoint = target != null && target.isConfigured() ? target : null;
        ended = false;
    }

    public void deliver(String text) {
        if (text == null || text.isEmpty()) return;
        if (endpoint == null) {
            output.type(text + " ");
        } else if (endpoint.combine) {
            if (buffer.length() > 0) buffer.append(' ');
            buffer.append(text);
        } else {
            output.send(endpoint, text);
        }
    }

    public void end(boolean chunksPending) {
        ended = true;
        if (!chunksPending) mainThread.execute(this::flush);
    }

    public void onQueueEmpty() {
        if (ended) flush();
    }

    public void flush() {
        if (endpoint == null || buffer.length() == 0) return;
        String text = buffer.toString();
        buffer.setLength(0);
        output.send(endpoint, text);
    }
}
