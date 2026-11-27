package com.whisperonnx.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class WhisperModelTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] zip(String... names) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (String n : names) {
                z.putNextEntry(new ZipEntry(n));
                if (!n.endsWith("/")) z.write(("data of " + n).getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test public void extractsTopLevelFilesAndSkipsFolders() throws IOException {
        File dir = tmp.newFolder("model");
        List<String> seen = new ArrayList<>();
        int n = WhisperModel.extract(new ByteArrayInputStream(zip("a.onnx", "empty/", "b.onnx")), dir, seen::add);
        assertEquals(2, n);
        assertEquals(Arrays.asList("a.onnx", "b.onnx"), seen);
        assertEquals("data of b.onnx", new String(Files.readAllBytes(new File(dir, "b.onnx").toPath()), StandardCharsets.UTF_8));
    }

    @Test public void rejectsEntriesOutsideTheFolder() throws IOException {
        File dir = tmp.newFolder("model");
        for (String bad : new String[]{"../evil.onnx", "sub/nested.onnx"}) {
            try {
                WhisperModel.extract(new ByteArrayInputStream(zip(bad)), dir, null);
                fail("accepted " + bad);
            } catch (IOException expected) {
            }
        }
        assertFalse(new File(tmp.getRoot(), "evil.onnx").exists());
    }

    @Test public void installedMeansSixFilesFoldersDontCount() throws IOException {
        File dir = tmp.newFolder("model");
        assertFalse(WhisperModel.isInstalled(dir));
        WhisperModel.extract(new ByteArrayInputStream(zip("1", "2", "3", "4", "5")), dir, null);
        assertTrue(new File(dir, ParakeetRecognizer.DIR).mkdir());
        assertTrue(new File(dir, "whisper-download").mkdir());
        assertFalse(WhisperModel.isInstalled(dir));
        WhisperModel.extract(new ByteArrayInputStream(zip("6")), dir, null);
        assertTrue(WhisperModel.isInstalled(dir));
        assertFalse(WhisperModel.isInstalled(new File(dir, "missing")));
    }
}
