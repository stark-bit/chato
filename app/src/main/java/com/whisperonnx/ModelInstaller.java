package com.whisperonnx;

import android.util.Log;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.whisperonnx.asr.ParakeetRecognizer;
import com.whisperonnx.asr.WhisperModel;
import com.whisperonnx.utils.ModelDownloader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;

/**
 * Asks to download a model and shows progress. Used by setup, Settings and the test screen.
 * Leaving the screen cancels the download; the partial files are resumed next time.
 */
public class ModelInstaller implements DefaultLifecycleObserver {

    private final AppCompatActivity activity;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private Thread thread;

    /** Runs on the download thread after the files are verified, e.g. to unzip them. */
    private interface AfterDownload {
        void run(ProgressBar bar, TextView label) throws IOException;
    }

    public ModelInstaller(AppCompatActivity activity) {
        this.activity = activity;
        activity.getLifecycle().addObserver(this);
    }

    public boolean isRunning() {
        return thread != null && thread.isAlive();
    }

    /**
     * @param onInstalled runs on the main thread once the model is ready
     * @param onAborted   runs on the main thread if the user declines, cancels, or it fails
     */
    public void offerParakeet(Runnable onInstalled, Runnable onAborted) {
        offer(activity.getString(R.string.model_parakeet), ParakeetRecognizer.FILES, ParakeetRecognizer.SOURCE,
                "Until it is installed, Whisper is used.",
                ParakeetRecognizer.dir(activity), null, onInstalled, onAborted);
    }

    /** Downloads the Whisper zip, extracts it into place and deletes the zip. */
    public void offerWhisper(Runnable onInstalled, Runnable onAborted) {
        File zipDir = WhisperModel.downloadDir(activity);
        offer(activity.getString(R.string.model_whisper), WhisperModel.FILES, WhisperModel.SOURCE, null, zipDir,
                (bar, label) -> {
                    activity.runOnUiThread(() -> {
                        bar.setIndeterminate(true);
                        label.setText("Unpacking…");
                    });
                    File zip = new File(zipDir, WhisperModel.ZIP);
                    try (InputStream in = new FileInputStream(zip)) {
                        WhisperModel.extract(in, WhisperModel.dir(activity),
                                name -> activity.runOnUiThread(() -> label.setText("Unpacking " + name)));
                    }
                    //noinspection ResultOfMethodCallIgnored
                    zip.delete();
                    //noinspection ResultOfMethodCallIgnored
                    zipDir.delete();
                }, onInstalled, onAborted);
    }

    private void offer(String name, List<ModelDownloader.FileSpec> files, String source, String note,
                       File dir, AfterDownload after, Runnable onInstalled, Runnable onAborted) {
        if (isRunning()) return;
        long mb = Math.round(ModelDownloader.totalSize(files) / 1e6);
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Download " + name + "?")
                .setMessage("The " + name + " model (" + mb + " MB) is downloaded once from "
                        + source + ". Wi-Fi recommended." + (note == null ? "" : "\n\n" + note))
                .setPositiveButton("Download", (d, w) -> download(name, files, dir, after, onInstalled, onAborted))
                .setNegativeButton(android.R.string.cancel, (d, w) -> onAborted.run())
                .setOnCancelListener(d -> onAborted.run())
                .show();
    }

    private void download(String name, List<ModelDownloader.FileSpec> files, File dir, AfterDownload after,
                          Runnable onInstalled, Runnable onAborted) {
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        TextView label = new TextView(activity);
        label.setText("Starting…");
        box.addView(bar);
        box.addView(label);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("Downloading " + name)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> cancelled.set(true))
                .show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        cancelled.set(false);
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build();
        thread = new Thread(() -> {
            String error = null;
            try {
                ModelDownloader.download(client, files, dir, (done, total) -> activity.runOnUiThread(() -> {
                    bar.setProgress((int) (done * 1000 / total));
                    label.setText((done / 1_000_000) + " / " + (total / 1_000_000) + " MB");
                }), cancelled);
                if (after != null) {
                    activity.runOnUiThread(() -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false));
                    after.run(bar, label);
                }
            } catch (InterruptedIOException e) {
                error = "Download cancelled. Try again to resume.";
            } catch (IOException e) {
                Log.e("ModelInstaller", name + " download failed", e);
                error = "Download failed: " + e.getMessage() + ". Try again to resume.";
            }
            String err = error;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                dialog.dismiss();
                if (err == null) {
                    Toast.makeText(activity, name + " installed", Toast.LENGTH_SHORT).show();
                    onInstalled.run();
                } else {
                    Toast.makeText(activity, err, Toast.LENGTH_LONG).show();
                    onAborted.run();
                }
            });
        }, "model-download");
        thread.start();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        cancelled.set(true);  // partial files are kept and resumed next time
    }
}
