package com.whisperonnx.asr;

import android.content.Context;

import com.whisperonnx.utils.ModelDownloader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Where the Whisper small (int8 ONNX) model comes from and where it lives: six files directly in
 * the app's external files dir. It ships as one zip, which is downloaded into {@link #downloadDir}
 * (a subfolder, so it doesn't count as a model file) and then extracted.
 */
public final class WhisperModel {

    public static final int FILE_COUNT = 6;
    public static final String ZIP = "whisper_small_int8.zip";

    /** Pinned to one commit so the checksum stays valid. */
    private static final String REPO =
            "https://huggingface.co/DocWolle/whisperOnnx/resolve/cb523d0fba0f8d9a28a3f7432668a8ea4e8be6e4/";
    public static final String SOURCE = "huggingface.co/DocWolle/whisperOnnx";

    public static final List<ModelDownloader.FileSpec> FILES = Collections.singletonList(
            new ModelDownloader.FileSpec(ZIP, REPO + ZIP, 242722488L,
                    "97ac121610693e8c9adaef9c6db9066ef0cbebba53ca6e47f6d1541068ca19e3"));

    private WhisperModel() {}

    public static File dir(Context context) {
        return context.getExternalFilesDir(null);
    }

    public static File downloadDir(Context context) {
        return new File(dir(context), "whisper-download");
    }

    public static boolean isInstalled(Context context) {
        return isInstalled(dir(context));
    }

    static boolean isInstalled(File dir) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return false;
        int count = 0;
        for (File f : files) if (f.isFile()) count++;
        return count == FILE_COUNT;
    }

    public interface OnEntry {
        void onEntry(String name);
    }

    /**
     * Extracts the model zip into {@code target}. The model files sit at the top of the zip;
     * folder entries are skipped, and any file that would land elsewhere is rejected.
     *
     * @return the number of files written
     */
    public static int extract(InputStream zip, File target, OnEntry onEntry) throws IOException {
        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Can't create " + target);
        String root = target.getCanonicalPath();
        byte[] buf = new byte[64 * 1024];
        int written = 0;
        try (ZipInputStream in = new ZipInputStream(zip)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                File out = new File(target, e.getName());
                if (!out.getParentFile().getCanonicalPath().equals(root)) {  // nested or "../" entries
                    throw new IOException("Unexpected entry in model zip: " + e.getName());
                }
                if (onEntry != null) onEntry.onEntry(out.getName());
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
                }
                written++;
            }
        }
        return written;
    }
}
