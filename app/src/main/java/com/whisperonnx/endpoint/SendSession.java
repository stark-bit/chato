package com.whisperonnx.endpoint;

import java.util.concurrent.Executor;

/**
 * Routes the transcribed chunks of one recording either to the cursor or to an endpoint.
 * The endpoint is fixed when the recording starts. If the endpoint combines chunks, they are
 * buffered and sent as one message once the recording has ended and every chunk is transcribed.
 *
 * Every method must be called on the main thread. {@code mainThread} posts to the end of that
 * thread's queue, so a flush scheduled there runs after results that were posted earlier.
 */
public final class SendSession {

    public interface Output {
        /** Types the text at the cursor. */
        void type(String text);
        /** Sends the text to the endpoint. */
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

    /** A recording starts. A null or unconfigured endpoint means type at the cursor. */
    public void begin(EndpointSender.Endpoint target) {
        flush();  // leftovers from a previous recording still go to its endpoint
        endpoint = target != null && target.isConfigured() ? target : null;
        ended = false;
    }

    /** One transcribed chunk. */
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

    /**
     * The recording stopped. With no chunks left, every result was already posted to the main
     * thread, so a flush posted now runs after them. Otherwise {@link #onQueueEmpty} flushes.
     */
    public void end(boolean chunksPending) {
        ended = true;
        if (!chunksPending) mainThread.execute(this::flush);
    }

    /** The transcription queue drained (posted after the last result). */
    public void onQueueEmpty() {
        if (ended) flush();
    }

    /** Sends any buffered text now. */
    public void flush() {
        if (endpoint == null || buffer.length() == 0) return;
        String text = buffer.toString();
        buffer.setLength(0);
        output.send(endpoint, text);
    }
}
