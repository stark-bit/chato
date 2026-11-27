package com.whisperonnx.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * Names of components that features reach in another feature. Features don't import each other,
 * so opening another feature's screen goes through here. ComponentsTest checks each name.
 */
public final class Components {

    public static final String TEST_SCREEN = "com.whisperonnx.testscreen.TestScreenActivity";
    public static final String SETUP_SCREEN = "com.whisperonnx.setup.SetupActivity";
    public static final String SETTINGS = "com.whisperonnx.settings.SettingsActivity";
    /** Name registered before the move to keyboard/, kept so the enabled keyboard survives updates. */
    public static final String KEYBOARD = "com.whisperonnx.WhisperInputMethodService";
    /** Name registered before the move to quickrecord/, kept so placed widgets survive updates. */
    public static final String QUICK_RECORD_WIDGET = "com.whisperonnx.QuickRecordWidget";

    private Components() { }

    public static Intent intent(Context context, String name) {
        return new Intent().setComponent(new ComponentName(context.getPackageName(), name));
    }

    public static void open(Context context, String name) {
        context.startActivity(intent(context, name));
    }

    /** Opens the setup screen from anywhere, including a service. */
    public static void openSetup(Context context) {
        context.startActivity(intent(context, SETUP_SCREEN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** The ID Android uses for Chato's keyboard, as in Settings.Secure.DEFAULT_INPUT_METHOD. */
    public static String keyboardId(Context context) {
        return context.getPackageName() + "/" + KEYBOARD;
    }
}
