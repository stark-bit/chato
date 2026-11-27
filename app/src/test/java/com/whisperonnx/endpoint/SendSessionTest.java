package com.whisperonnx.endpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SendSessionTest {
    private final List<String> events = new ArrayList<>();
    private final ArrayDeque<Runnable> looper = new ArrayDeque<>();
    private final SendSession session = new SendSession(new SendSession.Output() {
        @Override public void type(String text) { events.add("type:" + text); }
        @Override public void send(EndpointSender.Endpoint ep, String text) {
            events.add("send" + ep.index + ":" + text);
        }
    }, looper::add);

    private static EndpointSender.Endpoint endpoint(int index, boolean combine) {
        return new EndpointSender.Endpoint(index, "", "https://example.com/" + index, "", "", combine);
    }

    private void runLooper() {
        Runnable r;
        while ((r = looper.poll()) != null) r.run();
    }

    private void assertEvents(String... expected) {
        assertEquals(Arrays.asList(expected), events);
    }

    @Test public void noEndpoint_typesEachChunkWithTrailingSpace() {
        session.begin(null);
        session.deliver("hello");
        session.deliver("world");
        session.end(false);
        runLooper();
        assertEvents("type:hello ", "type:world ");
    }

    @Test public void unconfiguredEndpoint_fallsBackToCursor() {
        session.begin(new EndpointSender.Endpoint(1, "x", "", "", "", true));
        session.deliver("hello");
        session.end(false);
        runLooper();
        assertEvents("type:hello ");
    }

    @Test public void emptyChunksAreIgnored() {
        session.begin(null);
        session.deliver("");
        session.deliver(null);
        assertEvents();
    }

    @Test public void perChunk_sendsEachChunkImmediately() {
        session.begin(endpoint(1, false));
        session.deliver("one");
        session.deliver("two");
        assertEvents("send1:one", "send1:two");
        session.end(false);
        runLooper();
        assertEvents("send1:one", "send1:two");
    }

    @Test public void combined_sendsOnceAfterRecordingEnds() {
        session.begin(endpoint(1, true));
        session.deliver("one");
        session.deliver("two");
        assertEvents();
        session.end(false);
        assertEvents();
        runLooper();
        assertEvents("send1:one two");
    }

    @Test public void combined_resultAlreadyPostedIsIncluded() {
        session.begin(endpoint(1, true));
        session.deliver("one");
        looper.add(() -> session.deliver("two"));
        looper.add(() -> session.end(false));
        runLooper();
        assertEvents("send1:one two");
    }

    @Test public void combined_waitsForChunksStillTranscribing() {
        session.begin(endpoint(1, true));
        session.deliver("one");
        session.end(true);
        runLooper();
        assertEvents();

        looper.add(() -> session.deliver("two"));
        looper.add(session::onQueueEmpty);
        runLooper();
        assertEvents("send1:one two");
    }

    @Test public void combined_queueDrainingMidRecordingDoesNotSend() {
        session.begin(endpoint(1, true));
        session.deliver("one");
        session.onQueueEmpty();
        assertEvents();
        session.deliver("two");
        session.end(false);
        runLooper();
        assertEvents("send1:one two");
    }

    @Test public void combined_emptyRecordingSendsNothing() {
        session.begin(endpoint(1, true));
        session.deliver("");
        session.end(false);
        runLooper();
        session.onQueueEmpty();
        assertEvents();
    }

    @Test public void combined_flushIsNotRepeated() {
        session.begin(endpoint(1, true));
        session.deliver("one");
        session.end(false);
        runLooper();
        session.onQueueEmpty();
        session.flush();
        assertEvents("send1:one");
    }

    @Test public void newRecording_flushesLeftoversToPreviousEndpoint() {
        session.begin(endpoint(1, true));
        session.deliver("old");
        session.end(true);

        session.begin(endpoint(2, true));
        assertEvents("send1:old");
        session.deliver("new");
        session.end(false);
        runLooper();
        assertEvents("send1:old", "send2:new");
    }

    @Test public void newRecording_toCursorStillSendsPreviousEndpointText() {
        session.begin(endpoint(1, true));
        session.deliver("old");
        session.end(true);

        session.begin(null);
        session.deliver("typed");
        assertEvents("send1:old", "type:typed ");
    }

    @Test public void targetIsFixedForTheWholeRecording() {
        EndpointSender.Endpoint ep = endpoint(1, false);
        session.begin(ep);
        session.deliver("a");
        session.deliver("b");
        for (String e : events) assertTrue(e, e.startsWith("send1:"));
    }
}
