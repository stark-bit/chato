package com.whisperonnx.utils;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.ref.WeakReference;

/**
 * Custom Drawable that draws a rotating sweep gradient with blur glow.
 * Designed to be set as background on a FrameLayout wrapper around a button.
 * The button sits on top; the gradient border ring and glow peek out around it.
 *
 * Structure (back to front):
 * 1. Glow layer: blurred gradient fill → soft edges act as glow
 * 2. Sharp gradient fill → clean gradient surface
 * The button on top covers the center — no inner fill needed.
 */
public class GeminiGradientDrawable extends Drawable {

    private static final int[] GRADIENT_COLORS = {
        0xFF6366F1, // indigo
        0xFF6366F1, // indigo (hold to 27%)
        0xFFE11D48, // rose
        0xFFEAB308, // yellow
        0xFF22C55E, // green
        0xFF6366F1, // indigo
        0xFF22C55E, // green
        0xFF6366F1  // indigo (wrap)
    };

    private static final float[] GRADIENT_POSITIONS = {
        0f, 0.27f, 0.33f, 0.41f, 0.49f, 0.65f, 0.93f, 1f
    };

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix gradientMatrix = new Matrix();
    private final float cornerRadius;
    private float rotationDegrees = 0f;
    private WeakReference<View> hostViewRef;
    private SweepGradient cachedGradient;
    private float cachedCx = Float.NaN;
    private float cachedCy = Float.NaN;
    private boolean animating = false;
    private static final long FRAME_DELAY = 16; // ~60fps
    private static final float DEGREES_PER_FRAME = 360f / (5000f / FRAME_DELAY); // 360° over 5s

    private final Runnable animationRunnable = new Runnable() {
        @Override
        public void run() {
            if (!animating) return;
            rotationDegrees = (rotationDegrees + DEGREES_PER_FRAME) % 360f;
            invalidateSelf();
            View host = hostViewRef != null ? hostViewRef.get() : null;
            if (host != null) {
                host.invalidate();
                host.postOnAnimationDelayed(this, FRAME_DELAY);
            }
        }
    };

    public GeminiGradientDrawable(float cornerRadiusPx, float blurRadiusPx) {
        this.cornerRadius = cornerRadiusPx;

        // Glow paint — FILLED, blurred edges create the glow effect
        glowPaint.setStyle(Paint.Style.FILL);
        glowPaint.setMaskFilter(new BlurMaskFilter(blurRadiusPx, BlurMaskFilter.Blur.NORMAL));

        // Gradient paint — FILLED, sharp gradient surface
        gradientPaint.setStyle(Paint.Style.FILL);
    }

    public void setHostView(View view) {
        this.hostViewRef = new WeakReference<>(view);
    }

    public void startAnimation() {
        if (animating) return;
        animating = true;
        View host = hostViewRef != null ? hostViewRef.get() : null;
        if (host != null) {
            host.postOnAnimation(animationRunnable);
        }
    }

    public void stopAnimation() {
        animating = false;
        View host = hostViewRef != null ? hostViewRef.get() : null;
        if (host != null) {
            host.removeCallbacks(animationRunnable);
        }
    }

    private SweepGradient getOrCreateGradient(float cx, float cy) {
        if (cachedGradient == null || cachedCx != cx || cachedCy != cy) {
            cachedGradient = new SweepGradient(cx, cy, GRADIENT_COLORS, GRADIENT_POSITIONS);
            cachedCx = cx;
            cachedCy = cy;
        }
        return cachedGradient;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;

        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();

        SweepGradient gradient = getOrCreateGradient(cx, cy);
        gradientMatrix.setRotate(rotationDegrees, cx, cy);
        gradient.setLocalMatrix(gradientMatrix);

        RectF area = new RectF(bounds);

        // Layer 1: Glow — blurred gradient fill, soft edges glow outward
        glowPaint.setShader(gradient);
        canvas.drawRoundRect(area, cornerRadius, cornerRadius, glowPaint);

        // Layer 2: Sharp gradient fill
        gradientPaint.setShader(gradient);
        canvas.drawRoundRect(area, cornerRadius, cornerRadius, gradientPaint);
    }

    @Override
    public void setAlpha(int alpha) {}

    @Override
    public void setColorFilter(ColorFilter cf) {}

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
