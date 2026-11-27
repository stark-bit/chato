package com.whisperonnx.setup;

import com.whisperonnx.R;
import com.whisperonnx.backend.Backends;
import com.whisperonnx.backend.ModelDownloader;
import com.whisperonnx.backend.ParakeetRecognizer;
import com.whisperonnx.backend.WhisperModel;
import com.whisperonnx.ui.Components;
import com.whisperonnx.ui.ModelInstaller;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.io.InputStream;

/**
 * Opens when a local model is needed but missing (first launch). Offers the in-app download of
 * Parakeet or Whisper, a remote server instead, or installing a Whisper zip that's already on
 * the phone. Choosing one saves it as the backend and closes the screen.
 */
public class SetupActivity extends AppCompatActivity {

    private final ModelInstaller installer = new ModelInstaller(this);
    private ActivityResultLauncher<Intent> pickZip;
    private SharedPreferences sp;
    private ProgressBar progressBar;
    private TextView zipSummary;
    private boolean extracting;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_download);
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        progressBar = findViewById(R.id.progress_bar);
        zipSummary = findViewById(R.id.summary_zip);

        ((TextView) findViewById(R.id.summary_parakeet)).setText(summary(
                ParakeetRecognizer.isInstalled(this), R.string.setup_parakeet_summary, ParakeetRecognizer.FILES));
        ((TextView) findViewById(R.id.summary_whisper)).setText(summary(
                WhisperModel.isInstalled(this), R.string.setup_whisper_summary, WhisperModel.FILES));

        findViewById(R.id.row_parakeet).setOnClickListener(v -> {
            if (busy()) return;
            if (ParakeetRecognizer.isInstalled(this)) done(Backends.PARAKEET);
            else installer.offerParakeet(() -> done(Backends.PARAKEET), () -> { });
        });
        findViewById(R.id.row_whisper).setOnClickListener(v -> {
            if (busy()) return;
            if (WhisperModel.isInstalled(this)) done(Backends.WHISPER);
            else installer.offerWhisper(() -> done(Backends.WHISPER), () -> { });
        });
        findViewById(R.id.row_server).setOnClickListener(v -> {
            if (busy()) return;
            Backends.select(sp, Backends.REMOTE);
            Components.open(this, Components.SETTINGS);
            finish();
        });

        pickZip = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getData() != null && result.getData().getData() != null) installZip(result.getData().getData());
        });
        findViewById(R.id.row_zip).setOnClickListener(v -> {
            if (busy()) return;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("application/zip");
            pickZip.launch(intent);
        });
    }

    private String summary(boolean installed, int textRes, java.util.List<ModelDownloader.FileSpec> files) {
        if (installed) return getString(R.string.setup_installed);
        return getString(textRes, Math.round(ModelDownloader.totalSize(files) / 1e6));
    }

    private boolean busy() {
        return extracting || installer.isRunning();
    }

    /** Saves the choice and returns to whatever opened setup (the test screen or the keyboard's app). */
    private void done(int backend) {
        Backends.select(sp, backend);
        finish();
    }

    private void installZip(Uri zip) {
        extracting = true;
        progressBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String error = null;
            try (InputStream in = getContentResolver().openInputStream(zip)) {
                if (in == null) throw new IOException(getString(R.string.setup_zip_unreadable));
                WhisperModel.extract(in, WhisperModel.dir(this),
                        name -> runOnUiThread(() -> zipSummary.setText(getString(R.string.setup_unpacking, name))));
                if (!WhisperModel.isInstalled(this)) throw new IOException(getString(R.string.setup_zip_wrong));
            } catch (IOException e) {
                error = e.getMessage();
            }
            String err = error;
            runOnUiThread(() -> {
                extracting = false;
                progressBar.setVisibility(View.GONE);
                if (isFinishing() || isDestroyed()) return;
                if (err == null) {
                    Toast.makeText(this, getString(R.string.model_installed, getString(R.string.model_whisper)), Toast.LENGTH_SHORT).show();
                    done(Backends.WHISPER);
                } else {
                    zipSummary.setText(R.string.setup_zip_summary);
                    Toast.makeText(this, getString(R.string.model_install_failed, err), Toast.LENGTH_LONG).show();
                }
            });
        }, "whisper-unzip").start();
    }
}
