package com.whisperonnx.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

public class ModelDownloaderTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private MockWebServer server;
    private final OkHttpClient client = new OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private long lastDone = -1, lastTotal = -1;
    private final ModelDownloader.Progress progress = (done, total) -> { lastDone = done; lastTotal = total; };

    private static final byte[] A = bytes(3000, 'a');
    private static final byte[] B = bytes(700_000, 'b');  // big enough for mid-file progress

    @Before public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After public void tearDown() throws Exception {
        server.shutdown();
    }

    private static byte[] bytes(int n, char c) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (c + i % 7);
        return b;
    }

    private static String sha(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(data)) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private ModelDownloader.FileSpec spec(String name, byte[] data) throws Exception {
        return new ModelDownloader.FileSpec(name, server.url("/" + name).toString(), data.length, sha(data));
    }

    private static MockResponse ok(byte[] data) {
        return new MockResponse().setBody(new Buffer().write(data));
    }

    private File dir() {
        return new File(tmp.getRoot(), "models");
    }

    @Test public void downloadsAndVerifiesAllFiles() throws Exception {
        List<ModelDownloader.FileSpec> files = Arrays.asList(spec("a.bin", A), spec("b.bin", B));
        server.enqueue(ok(A));
        server.enqueue(ok(B));

        ModelDownloader.download(client, files, dir(), progress, cancelled);

        assertArrayEquals(A, Files.readAllBytes(new File(dir(), "a.bin").toPath()));
        assertArrayEquals(B, Files.readAllBytes(new File(dir(), "b.bin").toPath()));
        assertFalse(new File(dir(), "b.bin.part").exists());
        assertTrue(ModelDownloader.isComplete(files, dir()));
        assertEquals(A.length + B.length, lastDone);
        assertEquals(A.length + B.length, lastTotal);
    }

    @Test public void skipsFilesAlreadyPresent() throws Exception {
        List<ModelDownloader.FileSpec> files = Arrays.asList(spec("a.bin", A), spec("b.bin", B));
        assertTrue(dir().mkdirs());
        Files.write(new File(dir(), "a.bin").toPath(), A);
        server.enqueue(ok(B));

        ModelDownloader.download(client, files, dir(), progress, cancelled);

        assertEquals(1, server.getRequestCount());
        assertEquals("/b.bin", server.takeRequest().getPath());
    }

    @Test public void resumesFromPartialFile() throws Exception {
        List<ModelDownloader.FileSpec> files = Collections.singletonList(spec("b.bin", B));
        assertTrue(dir().mkdirs());
        int have = 250_000;
        Files.write(new File(dir(), "b.bin.part").toPath(), Arrays.copyOf(B, have));
        server.enqueue(new MockResponse().setResponseCode(206)
                .setBody(new Buffer().write(Arrays.copyOfRange(B, have, B.length))));

        ModelDownloader.download(client, files, dir(), progress, cancelled);

        RecordedRequest req = server.takeRequest();
        assertEquals("bytes=" + have + "-", req.getHeader("Range"));
        assertArrayEquals(B, Files.readAllBytes(new File(dir(), "b.bin").toPath()));
    }

    @Test public void restartsWhenServerIgnoresRange() throws Exception {
        List<ModelDownloader.FileSpec> files = Collections.singletonList(spec("b.bin", B));
        assertTrue(dir().mkdirs());
        Files.write(new File(dir(), "b.bin.part").toPath(), "garbage".getBytes(StandardCharsets.UTF_8));
        server.enqueue(ok(B));  // 200 with the whole file

        ModelDownloader.download(client, files, dir(), progress, cancelled);

        assertArrayEquals(B, Files.readAllBytes(new File(dir(), "b.bin").toPath()));
    }

    @Test public void checksumMismatchDeletesFileAndFails() throws Exception {
        ModelDownloader.FileSpec good = spec("a.bin", A);
        byte[] corrupt = A.clone();
        corrupt[10] ^= 1;
        server.enqueue(ok(corrupt));
        try {
            ModelDownloader.download(client, Collections.singletonList(good), dir(), progress, cancelled);
            fail("expected checksum failure");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("checksum"));
        }
        assertFalse(new File(dir(), "a.bin").exists());
        assertFalse(new File(dir(), "a.bin.part").exists());
    }

    @Test public void httpErrorFails() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404));
        try {
            ModelDownloader.download(client, Collections.singletonList(spec("a.bin", A)), dir(), progress, cancelled);
            fail("expected HTTP error");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("HTTP 404"));
        }
        assertFalse(new File(dir(), "a.bin").exists());
    }

    @Test public void cancelKeepsPartialDataForResume() throws Exception {
        List<ModelDownloader.FileSpec> files = Collections.singletonList(spec("b.bin", B));
        server.enqueue(ok(B).throttleBody(64 * 1024, 20, TimeUnit.MILLISECONDS));
        ModelDownloader.Progress cancelMidway = (done, total) -> { if (done > 0 && done < total) cancelled.set(true); };
        try {
            ModelDownloader.download(client, files, dir(), cancelMidway, cancelled);
            fail("expected cancellation");
        } catch (InterruptedIOException expected) { }

        File part = new File(dir(), "b.bin.part");
        assertTrue(part.exists());
        assertTrue(part.length() > 0 && part.length() < B.length);
        assertFalse(new File(dir(), "b.bin").exists());
        assertFalse(ModelDownloader.isComplete(files, dir()));
    }

    @Test public void isCompleteChecksSize() throws Exception {
        List<ModelDownloader.FileSpec> files = Collections.singletonList(spec("a.bin", A));
        assertFalse(ModelDownloader.isComplete(files, dir()));
        assertTrue(dir().mkdirs());
        Files.write(new File(dir(), "a.bin").toPath(), Arrays.copyOf(A, 10));
        assertFalse(ModelDownloader.isComplete(files, dir()));
        Files.write(new File(dir(), "a.bin").toPath(), A);
        assertTrue(ModelDownloader.isComplete(files, dir()));
    }
}
