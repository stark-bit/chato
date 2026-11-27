package com.whisperonnx.backend;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class ModelDownloader {
    public static final class FileSpec {
        public final String name, url, sha256;
        public final long size;

        public FileSpec(String name, String url, long size, String sha256) {
            this.name = name;
            this.url = url;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    public interface Progress {
        void onProgress(long done, long total);
    }

    private ModelDownloader() {}

    public static long totalSize(List<FileSpec> files) {
        long t = 0;
        for (FileSpec f : files) t += f.size;
        return t;
    }

    public static boolean isComplete(List<FileSpec> files, File dir) {
        for (FileSpec f : files) {
            File file = new File(dir, f.name);
            if (!file.isFile() || file.length() != f.size) return false;
        }
        return true;
    }

    public static void download(OkHttpClient client, List<FileSpec> files, File dir,
                                Progress progress, AtomicBoolean cancelled) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        long total = totalSize(files);
        long doneBefore = 0;
        for (FileSpec f : files) {
            File target = new File(dir, f.name);
            if (target.isFile() && target.length() == f.size) {
                doneBefore += f.size;
                progress.onProgress(doneBefore, total);
                continue;
            }
            File part = new File(dir, f.name + ".part");
            fetch(client, f, part, doneBefore, total, progress, cancelled);

            String actual = sha256(part);
            if (!actual.equalsIgnoreCase(f.sha256)) {
                if (!part.delete()) throw new IOException(f.name + ": checksum mismatch, and cannot delete " + part);
                throw new IOException(f.name + ": checksum mismatch");
            }
            if ((target.exists() && !target.delete()) || !part.renameTo(target)) {
                throw new IOException("Cannot save " + target);
            }
            doneBefore += f.size;
        }
        progress.onProgress(total, total);
    }

    private static void fetch(OkHttpClient client, FileSpec f, File part, long doneBefore, long total,
                              Progress progress, AtomicBoolean cancelled) throws IOException {
        long have = part.isFile() ? part.length() : 0;
        if (have > f.size) {
            if (!part.delete()) throw new IOException("Cannot delete " + part);
            have = 0;
        }
        if (have == f.size) return;

        Request.Builder rb = new Request.Builder().url(f.url);
        if (have > 0) rb.header("Range", "bytes=" + have + "-");
        try (Response resp = client.newCall(rb.build()).execute()) {
            boolean append;
            if (resp.code() == 206 && have > 0) {
                append = true;
            } else if (resp.isSuccessful()) {
                append = false;  // External: the server ignored the range, so start over
                have = 0;
            } else {
                throw new IOException(f.name + ": HTTP " + resp.code());
            }
            ResponseBody body = resp.body();
            if (body == null) throw new IOException(f.name + ": empty response");

            byte[] buf = new byte[64 * 1024];
            long lastReport = 0;
            try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(part, append)) {
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancelled.get()) throw new InterruptedIOException("Cancelled");
                    out.write(buf, 0, n);
                    have += n;
                    if (have > f.size) throw new IOException(f.name + ": larger than expected");
                    if (have - lastReport >= 512 * 1024) {
                        lastReport = have;
                        progress.onProgress(doneBefore + have, total);
                    }
                }
            }
        }
        if (have != f.size) throw new IOException(f.name + ": incomplete download, try again");
    }

    static String sha256(File file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        byte[] buf = new byte[256 * 1024];
        try (InputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
