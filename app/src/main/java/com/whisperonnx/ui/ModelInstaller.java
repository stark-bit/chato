package com.whisperonnx.ui;

import android.util.Log;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.whisperonnx.R;
import com.whisperonnx.backend.ModelDownloader;
import com.whisperonnx.backend.ParakeetRecognizer;
import com.whisperonnx.backend.WhisperModel;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.OkHttpClient;

public class ModelInstaller implements DefaultLifecycleObserver {
    private final AppCompatActivity activity;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private Thread thread;

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

    public void offerParakeet(Runnable onInstalled, Runnable onAborted) {
        offer(
                activity.getString(R.string.model_parakeet),
                ParakeetRecognizer.FILES,
                ParakeetRecognizer.SOURCE,
                activity.getString(R.string.model_download_parakeet_note),
                ParakeetRecognizer.dir(activity),
                null,
                onInstalled,
                onAborted);
    }

    public void offerWhisper(Runnable onInstalled, Runnable onAborted) {
        File zipDir = WhisperModel.downloadDir(activity);
        offer(
                activity.getString(R.string.model_whisper),
                WhisperModel.FILES,
                WhisperModel.SOURCE,
                null,
                zipDir,
                (bar, label) -> {
                    activity.runOnUiThread(() -> {
                        bar.setIndeterminate(true);
                        label.setText(R.string.model_unpacking);
                    });
                    File zip = new File(zipDir, WhisperModel.ZIP);
                    try (InputStream in = new FileInputStream(zip)) {
                        WhisperModel.extract(
                                in,
                                WhisperModel.dir(activity),
                                name -> activity.runOnUiThread(
                                        () -> label.setText(activity.getString(R.string.setup_unpacking, name))));
                    }
                    if (!zip.delete() || !zipDir.delete()) Log.w("ModelInstaller", "Cannot delete " + zipDir);
                },
                onInstalled,
                onAborted);
    }

    private void offer(
            String name,
            List<ModelDownloader.FileSpec> files,
            String source,
            String note,
            File dir,
            AfterDownload after,
            Runnable onInstalled,
            Runnable onAborted) {
        if (isRunning()) return;
        int mb = (int) Math.round(ModelDownloader.totalSize(files) / 1e6);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.model_download_title, name))
                .setMessage(activity.getString(R.string.model_download_message, name, mb, source)
                        + (note == null ? "" : "\n\n" + note))
                .setPositiveButton(
                        R.string.model_download, (d, w) -> download(name, files, dir, after, onInstalled, onAborted))
                .setNegativeButton(android.R.string.cancel, (d, w) -> onAborted.run())
                .setOnCancelListener(d -> onAborted.run())
                .show();
    }

    private void download(
            String name,
            List<ModelDownloader.FileSpec> files,
            File dir,
            AfterDownload after,
            Runnable onInstalled,
            Runnable onAborted) {
        int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        TextView label = new TextView(activity);
        label.setText(R.string.model_download_starting);
        box.addView(bar);
        box.addView(label);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.model_downloading, name))
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> cancelled.set(true))
                .show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        cancelled.set(false);
        OkHttpClient client =
                new OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build();
        thread = new Thread(
                () -> {
                    String error = fetch(client, name, files, dir, after, dialog, bar, label);
                    activity.runOnUiThread(() -> finish(dialog, name, error, onInstalled, onAborted));
                },
                "model-download");
        thread.start();
    }

    private String fetch(
            OkHttpClient client,
            String name,
            List<ModelDownloader.FileSpec> files,
            File dir,
            AfterDownload after,
            AlertDialog dialog,
            ProgressBar bar,
            TextView label) {
        try {
            ModelDownloader.download(
                    client,
                    files,
                    dir,
                    (done, total) -> activity.runOnUiThread(() -> {
                        bar.setProgress((int) (done * 1000 / total));
                        label.setText(activity.getString(
                                R.string.model_download_progress, (int) (done / 1_000_000), (int) (total / 1_000_000)));
                    }),
                    cancelled);
            if (after == null) return null;
            activity.runOnUiThread(
                    () -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false));
            after.run(bar, label);
            return null;
        } catch (InterruptedIOException e) {
            return activity.getString(R.string.model_download_cancelled);
        } catch (IOException e) {
            Log.e("ModelInstaller", name + " download failed", e);
            return activity.getString(R.string.model_download_failed, e.getMessage());
        }
    }

    private void finish(AlertDialog dialog, String name, String error, Runnable onInstalled, Runnable onAborted) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        dialog.dismiss();
        if (error != null) {
            Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
            onAborted.run();
            return;
        }
        Toast.makeText(activity, activity.getString(R.string.model_installed, name), Toast.LENGTH_SHORT)
                .show();
        onInstalled.run();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        cancelled.set(true);
    }
}
