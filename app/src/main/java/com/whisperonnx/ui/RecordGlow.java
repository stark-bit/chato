package com.whisperonnx.ui;

import android.view.View;

import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;

import java.util.concurrent.Executor;

public final class RecordGlow {
    private final View button;
    private final float borderDp;
    private final Integer centerColor;
    private final int idleBackground;
    private final Executor uiThread;
    private GradientBorderDrawable drawable;

    public RecordGlow(View button, float borderDp, @ColorInt Integer centerColor, @DrawableRes int idleBackground,
                      Executor uiThread) {
        this.button = button;
        this.borderDp = borderDp;
        this.centerColor = centerColor;
        this.idleBackground = idleBackground;
        this.uiThread = uiThread;
    }

    public void start() {
        uiThread.execute(() -> {
            GradientBorderDrawable d = drawable();
            d.setHostView(button);
            GradientBorderDrawable.enableGlowClipping(button);
            button.setBackground(d);
            d.startAnimation();
        });
    }

    public void stop() {
        uiThread.execute(() -> {
            if (drawable != null) drawable.stopAnimation();
            GradientBorderDrawable.restoreGlowClipping(button);
            button.setBackgroundResource(idleBackground);
        });
    }

    private GradientBorderDrawable drawable() {
        if (drawable != null) return drawable;
        float density = button.getResources().getDisplayMetrics().density;
        drawable = new GradientBorderDrawable(borderDp * density, 0.5f * density, 15 * density);
        if (centerColor != null) drawable.setCenterColor(centerColor);
        return drawable;
    }
}
