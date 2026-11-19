package com.whisperonnx;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;


import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.slider.RangeSlider;
import com.whisperonnx.asr.Backends;
import com.whisperonnx.asr.ParakeetRecognizer;
import com.whisperonnx.utils.EndpointSender;
import com.whisperonnx.utils.ThemeUtils;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "SettingsActivity";

    private SharedPreferences sp = null;
    private final ParakeetInstaller parakeetInstaller = new ParakeetInstaller(this);
    private CompoundButton modeBluetooth;
    private RangeSlider minSilence;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        ThemeUtils.setStatusBarAppearance(this);
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);

        sp = PreferenceManager.getDefaultSharedPreferences(this);
        modeBluetooth = findViewById(R.id.mode_bluetooth);
        modeBluetooth.setChecked(sp.getBoolean("bluetooth",false));
        modeBluetooth.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean("bluetooth", isChecked);
            editor.apply();
            if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},111);
            }
        });

        findViewById(R.id.row_test_transcription).setOnClickListener(v ->
                startActivity(new Intent(this, MainActivity.class)));

        // ===== OPENAI REMOTE BACKEND SETTINGS =====
        Spinner spinnerBackendType = findViewById(R.id.spinner_backend_type);
        LinearLayout layoutOpenaiSettings = findViewById(R.id.layout_openai_settings);
        EditText editEndpoint = findViewById(R.id.edit_openai_endpoint);
        EditText editApiKey = findViewById(R.id.edit_openai_api_key);
        EditText editModel = findViewById(R.id.edit_openai_model);

        // "local" + an engine, or the remote API. The keyboard's cloud key switches local <-> remote.
        ArrayAdapter<String> backendAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, Backends.LABELS);
        backendAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerBackendType.setAdapter(backendAdapter);

        String backendType = sp.getString("backend_type", "local");
        spinnerBackendType.setSelection(Backends.current(sp));

        editEndpoint.setText(sp.getString("openai_endpoint", "https://api.openai.com/v1/audio/transcriptions"));
        editApiKey.setText(sp.getString("openai_api_key", ""));
        editModel.setText(sp.getString("openai_model", "whisper-1"));

        layoutOpenaiSettings.setVisibility(backendType.equals("openai") ? View.VISIBLE : View.GONE);

        spinnerBackendType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Backends.select(sp, position);
                if (position == Backends.PARAKEET && !ParakeetRecognizer.isInstalled(SettingsActivity.this)) {
                    parakeetInstaller.offer(() -> { }, () -> spinnerBackendType.setSelection(Backends.WHISPER));
                }
                layoutOpenaiSettings.setVisibility(position == Backends.REMOTE ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        TextWatcher openaiSaveWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { saveOpenaiConfig(); }
        };

        editEndpoint.addTextChangedListener(openaiSaveWatcher);
        editApiKey.addTextChangedListener(openaiSaveWatcher);
        editModel.addTextChangedListener(openaiSaveWatcher);

        minSilence = findViewById(R.id.settings_min_silence);
        float silence = sp.getInt("silenceDurationMs", 800);
        minSilence.setValues(silence);
        minSilence.addOnChangeListener(new RangeSlider.OnChangeListener() {
            @Override
            public void onValueChange(@NonNull RangeSlider slider, float value, boolean fromUser) {
                SharedPreferences.Editor editor = sp.edit();
                editor.putInt("silenceDurationMs", (int) value);
                editor.apply();
            }
        });

        setupEndpointSettings();

        // Tapping anywhere on a switch row toggles it, as in FUTO settings
        findViewById(R.id.layout_mode_bluetooth).setOnClickListener(v -> modeBluetooth.toggle());

        checkPermissions();

    }

    private void setupEndpointSettings() {
        LinearLayout container = findViewById(R.id.layout_endpoints);
        for (int i = 1; i <= EndpointSender.COUNT; i++) {
            final int index = i;
            View item = getLayoutInflater().inflate(R.layout.item_endpoint_settings, container, false);
            ((TextView) item.findViewById(R.id.endpoint_title)).setText(getString(R.string.endpoint_title, index));
            EndpointSender.Endpoint ep = EndpointSender.load(sp, index);
            bindText(item.findViewById(R.id.edit_endpoint_name), EndpointSender.key(index, "name"), ep.name);
            bindText(item.findViewById(R.id.edit_endpoint_url), EndpointSender.key(index, "url"), ep.url);
            bindText(item.findViewById(R.id.edit_endpoint_headers), EndpointSender.key(index, "headers"), ep.headers);
            bindText(item.findViewById(R.id.edit_endpoint_body), EndpointSender.key(index, "body"), ep.body);
            CompoundButton combine = item.findViewById(R.id.check_endpoint_combine);
            item.findViewById(R.id.row_endpoint_combine).setOnClickListener(v -> combine.toggle());
            combine.setChecked(ep.combine);
            combine.setOnCheckedChangeListener((b, checked) ->
                    sp.edit().putBoolean(EndpointSender.key(index, "combine"), checked).apply());
            container.addView(item);
        }
    }

    /** Shows the current value and saves every edit to the given preference key. */
    private void bindText(EditText edit, String prefKey, String value) {
        edit.setText(value);
        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { sp.edit().putString(prefKey, s.toString()).apply(); }
        });
    }

    private void checkPermissions() {
        List<String> perms = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO);
            Toast.makeText(this, getString(R.string.need_record_audio_permission), Toast.LENGTH_SHORT).show();
        }
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) && (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)){
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!perms.isEmpty()) {
            requestPermissions(perms.toArray(new String[] {}), 0);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Record permission is granted");
        } else {
            Log.d(TAG, "Record permission is not granted");
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) { //handle "back click" on action bar
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void saveOpenaiConfig() {
        EditText editEndpoint = findViewById(R.id.edit_openai_endpoint);
        EditText editApiKey = findViewById(R.id.edit_openai_api_key);
        EditText editModel = findViewById(R.id.edit_openai_model);

        sp.edit()
            .putString("openai_endpoint", editEndpoint.getText().toString().trim())
            .putString("openai_api_key", editApiKey.getText().toString().trim())
            .putString("openai_model", editModel.getText().toString().trim())
            .apply();
    }
}
