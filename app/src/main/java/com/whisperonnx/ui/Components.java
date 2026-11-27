package com.whisperonnx.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

public final class Components {
    public static final String TEST_SCREEN = "com.whisperonnx.testscreen.TestScreenActivity";
    public static final String SETUP_SCREEN = "com.whisperonnx.setup.SetupActivity";
    public static final String SETTINGS = "com.whisperonnx.settings.SettingsActivity";
    public static final String KEYBOARD = "com.whisperonnx.WhisperInputMethodService";
    public static final String QUICK_RECORD_WIDGET = "com.whisperonnx.QuickRecordWidget";

    private Components() { }

    public static Intent intent(Context context, String name) {
        return new Intent().setComponent(new ComponentName(context.getPackageName(), name));
    }

    public static void open(Context context, String name) {
        context.startActivity(intent(context, name));
    }

    public static void openSetup(Context context) {
        context.startActivity(intent(context, SETUP_SCREEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    public static String keyboardId(Context context) {
        return context.getPackageName() + "/" + KEYBOARD;
    }
}
