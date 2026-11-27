package com.whisperonnx.quickrecord;

import com.whisperonnx.ui.Components;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * Invisible screen that toggles quick record and closes. The quick record widget opens it, and
 * so does the digital assistant gesture (it handles {@link Intent#ACTION_ASSIST}). Being a
 * visible activity for a moment is what lets the service use the mic from the background.
 */
public class QuickRecordActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Components.open(this, Components.TEST_SCREEN);  // asks for the mic
        } else {
            QuickRecordService.toggle(this, getIntent().getIntExtra(QuickRecordService.EXTRA_ENDPOINT, 0));
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
