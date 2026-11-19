package com.whisperonnx;

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

import com.whisperonnx.asr.ParakeetRecognizer;
import com.whisperonnx.utils.ModelDownloader;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;

/**
 * Asks to download the Parakeet model and shows progress. Used by Settings and the test screen.
 * Leaving the screen cancels the download; the partial files are resumed next time.
 */
public class ParakeetInstaller implements DefaultLifecycleObserver {

    private final AppCompatActivity activity;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private Thread thread;

    public ParakeetInstaller(AppCompatActivity activity) {
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
    public void offer(Runnable onInstalled, Runnable onAborted) {
        if (isRunning()) return;
        long mb = ModelDownloader.totalSize(ParakeetRecognizer.FILES) / 1_000_000;
        new AlertDialog.Builder(activity)
                .setTitle("Download Parakeet?")
                .setMessage("The Parakeet model (" + mb + " MB) is downloaded once from "
                        + ParakeetRecognizer.SOURCE + ". Wi-Fi recommended.\n\n"
                        + "Until it is installed, Whisper is used.")
                .setPositiveButton("Download", (d, w) -> download(onInstalled, onAborted))
                .setNegativeButton(android.R.string.cancel, (d, w) -> onAborted.run())
                .setOnCancelListener(d -> onAborted.run())
                .show();
    }

    private void download(Runnable onInstalled, Runnable onAborted) {
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

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Downloading Parakeet")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> cancelled.set(true))
                .show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        cancelled.set(false);
        OkHttpClient client = new OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build();
        File dir = ParakeetRecognizer.dir(activity);
        thread = new Thread(() -> {
            String error = null;
            try {
                ModelDownloader.download(client, ParakeetRecognizer.FILES, dir, (done, total) -> activity.runOnUiThread(() -> {
                    bar.setProgress((int) (done * 1000 / total));
                    label.setText((done / 1_000_000) + " / " + (total / 1_000_000) + " MB");
                }), cancelled);
            } catch (InterruptedIOException e) {
                error = "Download cancelled. Choose Parakeet again to resume.";
            } catch (IOException e) {
                Log.e("ParakeetInstaller", "Download failed", e);
                error = "Download failed: " + e.getMessage() + ". Choose Parakeet again to retry.";
            }
            String err = error;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                dialog.dismiss();
                if (err == null) {
                    Toast.makeText(activity, "Parakeet installed", Toast.LENGTH_SHORT).show();
                    onInstalled.run();
                } else {
                    Toast.makeText(activity, err, Toast.LENGTH_LONG).show();
                    onAborted.run();
                }
            });
        }, "parakeet-download");
        thread.start();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        cancelled.set(true);  // partial files are kept and resumed next time
    }
}
