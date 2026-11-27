package com.whisperonnx.ui;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build;
import android.view.WindowInsetsController;

public class ThemeUtils {
    public static void setStatusBarAppearance(Activity activity) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return;
        WindowInsetsController insetsController = activity.getWindow().getInsetsController();
        if (insetsController == null) return;
        int nightModeFlags = activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isDarkMode = nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
        // External: APPEARANCE_LIGHT_STATUS_BARS means dark status bar icons
        int lightStatusBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
        insetsController.setSystemBarsAppearance(isDarkMode ? 0 : lightStatusBars, lightStatusBars);
    }
}
