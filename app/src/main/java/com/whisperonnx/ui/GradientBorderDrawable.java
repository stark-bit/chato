package com.whisperonnx.ui;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import java.lang.ref.WeakReference;

// External: the glow draws outside the bounds, so the parent View needs clipChildren=false
public class GradientBorderDrawable extends Drawable {
    private static final int[] GRADIENT_COLORS = {
        0xFF6366F1, 0xFF6366F1, 0xFFE11D48, 0xFFEAB308, 0xFF22C55E, 0xFF6366F1, 0xFF22C55E, 0xFF6366F1
    };

    private static final float[] GRADIENT_POSITIONS = {0f, 0.27f, 0.33f, 0.41f, 0.49f, 0.65f, 0.93f, 1f};

    private static final int GLOW_STEPS = 10;
    private static final float GLOW_MAX_ALPHA = 0.18f;

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

    private static final long FRAME_DELAY = 16;
    private static final float DEGREES_PER_FRAME = 360f / (5000f / FRAME_DELAY);

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

        glowPaint.setStyle(Paint.Style.FILL);

        gradientPaint.setStyle(Paint.Style.FILL);

        centerPaint.setStyle(Paint.Style.FILL);
        centerPaint.setColor(0xFF475569);
    }

    public void setCenterColor(int color) {
        centerPaint.setColor(color);
        invalidateSelf();
    }

    public void setHostView(View view) {
        this.hostViewRef = new WeakReference<>(view);
    }

    public static void enableGlowClipping(View button) {
        ViewParent vp = button.getParent();
        if (vp instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) vp;
            parent.setClipChildren(false);
            parent.setClipToPadding(false);
            ViewParent gvp = parent.getParent();
            if (gvp instanceof ViewGroup) {
                ViewGroup grandparent = (ViewGroup) gvp;
                grandparent.setClipChildren(false);
                grandparent.setClipToPadding(false);
            }
        }
    }

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

        float stepSize = glowBlur / GLOW_STEPS;
        for (int i = 1; i <= GLOW_STEPS; i++) {
            float expand = i * stepSize;
            float t = (float) i / GLOW_STEPS;
            int alpha = (int) (255 * GLOW_MAX_ALPHA * (1f - t) * (1f - t));
            if (alpha <= 0) continue;

            glowPaint.setColor(Color.argb(alpha, 255, 255, 255));
            glowPaint.setShader(gradient);
            tempGlow.set(bounds.left - expand, bounds.top - expand, bounds.right + expand, bounds.bottom + expand);
            canvas.drawRoundRect(tempGlow, cornerRadius + expand, cornerRadius + expand, glowPaint);
        }

        gradientPaint.setShader(gradient);
        tempGlow.set(bounds);
        canvas.drawRoundRect(tempGlow, cornerRadius, cornerRadius, gradientPaint);

        float innerInset = borderWidth;
        tempInner.set(
                bounds.left + innerInset,
                bounds.top + innerInset,
                bounds.right - innerInset,
                bounds.bottom - innerInset);
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
