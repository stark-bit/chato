package com.whisperonnx.settings;

import com.whisperonnx.prefs.Prefs;
import com.whisperonnx.R;
import com.whisperonnx.backend.Backends;
import com.whisperonnx.backend.ParakeetRecognizer;
import com.whisperonnx.backend.WhisperModel;
import com.whisperonnx.endpoint.EndpointSender;
import com.whisperonnx.ui.Permissions;
import com.whisperonnx.ui.Components;
import com.whisperonnx.ui.ModelInstaller;
import com.whisperonnx.ui.ThemeUtils;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.slider.RangeSlider;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "SettingsActivity";

    private Prefs prefs = null;
    private final ModelInstaller modelInstaller = new ModelInstaller(this);
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

        prefs = Prefs.of(this);
        modeBluetooth = findViewById(R.id.mode_bluetooth);
        modeBluetooth.setChecked(prefs.bluetooth());
        modeBluetooth.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            prefs.setBluetooth(isChecked);
            if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},111);
            }
        });

        findViewById(R.id.row_test_transcription).setOnClickListener(v ->
                Components.open(this, Components.TEST_SCREEN));

        Spinner spinnerBackendType = findViewById(R.id.spinner_backend_type);
        LinearLayout layoutOpenaiSettings = findViewById(R.id.layout_openai_settings);
        EditText editEndpoint = findViewById(R.id.edit_openai_endpoint);
        EditText editApiKey = findViewById(R.id.edit_openai_api_key);
        EditText editModel = findViewById(R.id.edit_openai_model);

        ArrayAdapter<String> backendAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, getResources().getStringArray(R.array.backend_labels));
        backendAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerBackendType.setAdapter(backendAdapter);

        spinnerBackendType.setSelection(Backends.current(prefs));

        editEndpoint.setText(prefs.serverUrl());
        editApiKey.setText(prefs.serverApiKey());
        editModel.setText(prefs.serverModel());

        layoutOpenaiSettings.setVisibility(prefs.useServer() ? View.VISIBLE : View.GONE);

        spinnerBackendType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Backends.select(prefs, position);
                if (position == Backends.PARAKEET && !ParakeetRecognizer.isInstalled(SettingsActivity.this)) {
                    modelInstaller.offerParakeet(() -> { }, () -> spinnerBackendType.setSelection(Backends.WHISPER));
                } else if (position == Backends.WHISPER && !WhisperModel.isInstalled(SettingsActivity.this)) {
                    modelInstaller.offerWhisper(() -> { }, () -> { });
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
        minSilence.setValues((float) prefs.silenceMs());
        minSilence.addOnChangeListener(new RangeSlider.OnChangeListener() {
            @Override
            public void onValueChange(@NonNull RangeSlider slider, float value, boolean fromUser) {
                prefs.setSilenceMs((int) value);
            }
        });

        setupEndpointSettings();
        setupQuickRecordSettings();

        findViewById(R.id.layout_mode_bluetooth).setOnClickListener(v -> modeBluetooth.toggle());

        checkPermissions();

    }

    private void setupEndpointSettings() {
        LinearLayout container = findViewById(R.id.layout_endpoints);
        for (int i = 1; i <= EndpointSender.COUNT; i++) {
            final int index = i;
            View item = getLayoutInflater().inflate(R.layout.item_endpoint_settings, container, false);
            ((TextView) item.findViewById(R.id.endpoint_title)).setText(getString(R.string.endpoint_title, index));
            EndpointSender.Endpoint ep = EndpointSender.load(prefs, index);
            bindText(item.findViewById(R.id.edit_endpoint_name), index, Prefs.EndpointField.NAME, ep.name);
            bindText(item.findViewById(R.id.edit_endpoint_url), index, Prefs.EndpointField.URL, ep.url);
            bindText(item.findViewById(R.id.edit_endpoint_headers), index, Prefs.EndpointField.HEADERS, ep.headers);
            bindText(item.findViewById(R.id.edit_endpoint_body), index, Prefs.EndpointField.BODY, ep.body);
            CompoundButton combine = item.findViewById(R.id.check_endpoint_combine);
            item.findViewById(R.id.row_endpoint_combine).setOnClickListener(v -> combine.toggle());
            combine.setChecked(ep.combine);
            combine.setOnCheckedChangeListener((b, checked) ->
                    prefs.setEndpointCombine(index, checked));
            container.addView(item);
        }
    }

    private void setupQuickRecordSettings() {
        Spinner spinner = findViewById(R.id.spinner_quick_record_endpoint);
        List<String> labels = new ArrayList<>();
        for (int i = 1; i <= EndpointSender.COUNT; i++) labels.add(getString(R.string.endpoint_title, i));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(EndpointSender.COUNT, prefs.quickRecordEndpoint()) - 1));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.setQuickRecordEndpoint(position + 1);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // External: phones differ in which settings page lists the assistant
        findViewById(R.id.row_assistant).setOnClickListener(v -> {
            for (String action : new String[]{Settings.ACTION_VOICE_INPUT_SETTINGS,
                    Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS}) {
                try {
                    startActivity(new Intent(action));
                    return;
                } catch (android.content.ActivityNotFoundException ignored) {
                }
            }
        });
    }

    private void bindText(EditText edit, int index, Prefs.EndpointField field, String value) {
        edit.setText(value);
        edit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { prefs.setEndpointText(index, field, s.toString()); }
        });
    }

    private void checkPermissions() {
        Permissions.requestMissing(this, false);
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
    public boolean onOptionsItemSelected(MenuItem item) {
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

        prefs.setServer(editEndpoint.getText().toString().trim(),
                editApiKey.getText().toString().trim(),
                editModel.getText().toString().trim());
    }
}
