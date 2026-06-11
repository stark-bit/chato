package com.whisperonnx.utils;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.ref.WeakReference;

/**
 * Rotating sweep gradient border with glow that extends OUTSIDE the button bounds.
 * Requires: clipChildren=false on the button's parent View.
 *
 * Draw order:
 * 1. Glow layers — semi-transparent gradient copies extending beyond bounds
 * 2. Sharp gradient fill — at button bounds (border ring)
 * 3. Solid center fill — covers inner area, matches original button color
 */
public class GradientBorderDrawable extends Drawable {

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

    private static final int GLOW_STEPS = 10;
    private static final float GLOW_MAX_ALPHA = 0.18f; // 18% peak opacity

    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix gradientMatrix = new Matrix();
    private final RectF tempGlow = new RectF();
    private final RectF tempInner = new RectF();
    private final float cornerRadius;
    private final float borderWidth;
    private final float glowBlur;
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

    public GradientBorderDrawable(float cornerRadiusPx, float borderWidthPx, float blurRadiusPx) {
        this.cornerRadius = cornerRadiusPx;
        this.borderWidth = borderWidthPx;
        this.glowBlur = blurRadiusPx;

        // Glow paint — used for semi-transparent gradient copies
        glowPaint.setStyle(Paint.Style.FILL);

        // Sharp gradient paint
        gradientPaint.setStyle(Paint.Style.FILL);

        // Center paint — solid fill, slightly darker than normal button (slate-600)
        centerPaint.setStyle(Paint.Style.FILL);
        centerPaint.setColor(0xFF475569);
    }

    public void setHostView(View view) {
        this.hostViewRef = new WeakReference<>(view);
    }

    /**
     * Enable drawing outside bounds. Call on the button's parent ViewGroup.
     */
    public static void enableGlowClipping(View button) {
        ViewParent vp = button.getParent();
        if (vp instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) vp;
            parent.setClipChildren(false);
            parent.setClipToPadding(false);
            // Also check grandparent for nested clipping
            ViewParent gvp = parent.getParent();
            if (gvp instanceof ViewGroup) {
                ViewGroup grandparent = (ViewGroup) gvp;
                grandparent.setClipChildren(false);
                grandparent.setClipToPadding(false);
            }
        }
    }

    /**
     * Restore default clipping. Call when reverting.
     */
    public static void restoreGlowClipping(View button) {
        ViewParent vp = button.getParent();
        if (vp instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) vp;
            parent.setClipChildren(true);
            parent.setClipToPadding(true);
            ViewParent gvp = parent.getParent();
            if (gvp instanceof ViewGroup) {
                ViewGroup grandparent = (ViewGroup) gvp;
                grandparent.setClipChildren(true);
                grandparent.setClipToPadding(true);
            }
        }
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

        // Layer 1: Glow — semi-transparent gradient copies extending BEYOND bounds
        // Each step is larger and more transparent, creating a soft glow effect
        float stepSize = glowBlur / GLOW_STEPS;
        for (int i = 1; i <= GLOW_STEPS; i++) {
            float expand = i * stepSize;
            float t = (float) i / GLOW_STEPS; // 0 at inner edge, 1 at outer edge
            // Quadratic falloff: bright near button edge, fading outward
            int alpha = (int) (255 * GLOW_MAX_ALPHA * (1f - t) * (1f - t));
            if (alpha <= 0) continue;

            glowPaint.setColor(Color.argb(alpha, 255, 255, 255));
            glowPaint.setShader(gradient);
            tempGlow.set(
                bounds.left - expand, bounds.top - expand,
                bounds.right + expand, bounds.bottom + expand
            );
            canvas.drawRoundRect(tempGlow, cornerRadius + expand, cornerRadius + expand, glowPaint);
        }

        // Layer 2: Sharp gradient fill at button bounds
        gradientPaint.setShader(gradient);
        tempGlow.set(bounds);
        canvas.drawRoundRect(tempGlow, cornerRadius, cornerRadius, gradientPaint);

        // Layer 3: Solid center fill (covers inner area, leaves border ring visible)
        float innerInset = borderWidth;
        tempInner.set(
            bounds.left + innerInset, bounds.top + innerInset,
            bounds.right - innerInset, bounds.bottom - innerInset
        );
        float innerRadius = Math.max(0, cornerRadius - borderWidth);
        if (tempInner.width() > 0 && tempInner.height() > 0) {
            canvas.drawRoundRect(tempInner, innerRadius, innerRadius, centerPaint);
        }
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
