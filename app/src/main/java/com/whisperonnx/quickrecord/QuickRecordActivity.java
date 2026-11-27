package com.whisperonnx.quickrecord;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import com.whisperonnx.ui.Components;

// External: Android lets the service use the mic from the background only when a visible activity started it
public class QuickRecordActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Components.open(this, Components.TEST_SCREEN);
        } else {
            QuickRecordService.toggle(this, getIntent().getIntExtra(QuickRecordService.EXTRA_ENDPOINT, 0));
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
