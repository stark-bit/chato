package com.whisperonnx.endpoint;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

public class EndpointSenderTest {

    private static final String JOT_BODY =
            "{\"edits\":[{\"oldText\":\"<!-- voice -->\",\"newText\":\"- {{text}}\\n<!-- voice -->\"}]}";

    private MockWebServer server;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(2, TimeUnit.SECONDS).build();

    @Before public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After public void tearDown() throws Exception {
        server.shutdown();
    }

    private static EndpointSender.Endpoint ep(String url, String headers, String body) {
        return new EndpointSender.Endpoint(1, "Notes", url, headers, body, false);
    }

    private static String bodyOf(Request req) throws Exception {
        Buffer buf = new Buffer();
        req.body().writeTo(buf);
        return buf.readUtf8();
    }

    // ---- jsonEscape / fillTemplate ----

    @Test public void jsonEscape_escapesSpecialCharacters() {
        assertEquals("a\\\"b", EndpointSender.jsonEscape("a\"b"));
        assertEquals("a\\\\b", EndpointSender.jsonEscape("a\\b"));
        assertEquals("a\\nb\\rc\\td", EndpointSender.jsonEscape("a\nb\rc\td"));
        assertEquals("\\u0001\\u001f", EndpointSender.jsonEscape("\u0001\u001f"));
    }

    @Test public void jsonEscape_leavesUnicodeAlone() {
        assertEquals("café 😀 – ok", EndpointSender.jsonEscape("café 😀 – ok"));
    }

    @Test public void fillTemplate_escapedTextRoundTripsThroughJson() throws Exception {
        String tricky = "He said \"hi\"\\ then\nleft\t😀 \u0002";
        String body = EndpointSender.fillTemplate(ep("http://x", "", EndpointSender.DEFAULT_BODY), tricky);
        assertEquals(tricky, new JSONObject(body).getString("text"));
    }

    @Test public void fillTemplate_rawTextIsInsertedUnchanged() {
        String body = EndpointSender.fillTemplate(ep("http://x", "", "<p>{{text_raw}}</p>"), "a \"b\"\nc");
        assertEquals("<p>a \"b\"\nc</p>", body);
    }

    @Test public void fillTemplate_fillsNameAndTimestamp() {
        String body = EndpointSender.fillTemplate(ep("http://x", "", "{{name}}|{{timestamp}}"), "t");
        String[] parts = body.split("\\|");
        assertEquals("Notes", parts[0]);
        assertTrue(parts[1], parts[1].matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d([+-]\\d\\d:\\d\\d|Z)"));
    }

    @Test public void fillTemplate_unnamedEndpointUsesDefaultName() {
        EndpointSender.Endpoint e = new EndpointSender.Endpoint(2, "", "http://x", "", "{{name}}", false);
        assertEquals("Endpoint 2", EndpointSender.fillTemplate(e, "t"));
    }

    @Test public void fillTemplate_emptyTemplateFallsBackToDefault() throws Exception {
        String body = EndpointSender.fillTemplate(ep("http://x", "", ""), "hello");
        assertEquals("hello", new JSONObject(body).getString("text"));
    }

    @Test public void fillTemplate_textContainingPlaceholderIsNotExpandedTwice() throws Exception {
        // Placeholders are filled in one pass, so dictated "{{name}}" must survive as-is
        String body = EndpointSender.fillTemplate(ep("http://x", "", EndpointSender.DEFAULT_BODY), "say {{name}}");
        assertEquals("say {{name}}", new JSONObject(body).getString("text"));
    }

    @Test public void fillTemplate_jotBodyStaysValidJson() throws Exception {
        String text = "Buy \"milk\"\nand <!-- voice --> eggs";
        String body = EndpointSender.fillTemplate(ep("http://x", "", JOT_BODY), text);
        JSONObject edit = new JSONObject(body).getJSONArray("edits").getJSONObject(0);
        assertEquals("<!-- voice -->", edit.getString("oldText"));
        assertEquals("- " + text + "\n<!-- voice -->", edit.getString("newText"));
    }

    // ---- buildRequest ----

    @Test public void buildRequest_parsesHeaders() throws Exception {
        Request req = EndpointSender.buildRequest(ep("https://example.com/api",
                "Authorization: Bearer abc:def\n\nnot a header\n: novalue\n  X-Trim  :  v  ", "{}"), "t");
        assertEquals("POST", req.method());
        assertEquals("Bearer abc:def", req.header("Authorization"));
        assertEquals("v", req.header("X-Trim"));
        assertEquals(2, req.headers().size());
        assertEquals("application/json; charset=utf-8", req.body().contentType().toString());
    }

    @Test public void buildRequest_contentTypeHeaderOverridesDefault() throws Exception {
        Request req = EndpointSender.buildRequest(ep("https://example.com",
                "content-type: text/plain", "{{text_raw}}"), "hello");
        assertTrue(req.body().contentType().toString().startsWith("text/plain"));
        assertNull(req.header("content-type"));  // carried by the body, not duplicated
        assertEquals("hello", bodyOf(req));
    }

    @Test public void buildRequest_handlesWindowsLineEndings() {
        Request req = EndpointSender.buildRequest(ep("https://example.com",
                "A: 1\r\nB: 2\r\n", "{}"), "t");
        assertEquals("1", req.header("A"));
        assertEquals("2", req.header("B"));
    }

    @Test public void buildRequest_badUrlThrowsIllegalArgument() {
        try {
            EndpointSender.buildRequest(ep("not a url", "", "{}"), "t");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void buildRequest_badHeaderThrowsIllegalArgument() {
        try {
            EndpointSender.buildRequest(ep("https://example.com", "X-Bad: caf\u00e9\u0001", "{}"), "t");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { }
    }

    // ---- execute (real HTTP against a local mock server) ----

    @Test public void execute_sendsRequestAndReportsSuccess() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200));
        Request req = EndpointSender.buildRequest(ep(server.url("/api/notes/abc/edit").toString(),
                "Authorization: Bearer token", JOT_BODY), "hello");

        assertNull(EndpointSender.execute(client, req));

        RecordedRequest rec = server.takeRequest(1, TimeUnit.SECONDS);
        assertEquals("POST", rec.getMethod());
        assertEquals("/api/notes/abc/edit", rec.getPath());
        assertEquals("Bearer token", rec.getHeader("Authorization"));
        assertTrue(rec.getHeader("Content-Type").startsWith("application/json"));
        JSONObject edit = new JSONObject(rec.getBody().readUtf8()).getJSONArray("edits").getJSONObject(0);
        assertEquals("- hello\n<!-- voice -->", edit.getString("newText"));
    }

    @Test public void execute_anyTwoHundredIsSuccess() {
        server.enqueue(new MockResponse().setResponseCode(204));
        Request req = EndpointSender.buildRequest(ep(server.url("/").toString(), "", "{}"), "t");
        assertNull(EndpointSender.execute(client, req));
    }

    @Test public void execute_httpErrorIsReported() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("oldText not found"));
        Request req = EndpointSender.buildRequest(ep(server.url("/").toString(), "", "{}"), "t");
        assertEquals("HTTP 400", EndpointSender.execute(client, req));
    }

    @Test public void execute_serverErrorIsReported() {
        server.enqueue(new MockResponse().setResponseCode(503));
        Request req = EndpointSender.buildRequest(ep(server.url("/").toString(), "", "{}"), "t");
        assertEquals("HTTP 503", EndpointSender.execute(client, req));
    }

    @Test public void execute_networkFailureIsReported() throws Exception {
        String url = server.url("/").toString();
        server.shutdown();  // nothing listening any more
        Request req = EndpointSender.buildRequest(ep(url, "", "{}"), "t");
        String error = EndpointSender.execute(client, req);
        assertTrue(error, error.startsWith("Network error"));
    }

    @Test public void quickRecordUsesThePreferredEndpointOrTheFirstWithAUrl() {
        assertEquals(2, EndpointSender.pickQuickRecord(2, new boolean[]{true, true}));
        assertEquals(1, EndpointSender.pickQuickRecord(2, new boolean[]{true, false}));
        assertEquals(2, EndpointSender.pickQuickRecord(1, new boolean[]{false, true}));
        assertEquals(2, EndpointSender.pickQuickRecord(7, new boolean[]{false, true}));
        assertEquals(0, EndpointSender.pickQuickRecord(1, new boolean[]{false, false}));
    }
}
