# Gemini Gradient Mic Button (Pressed State) Implementation Plan

## Overview

Add a rotating conic gradient border with glow effect to the microphone button when pressed and held, matching the Gemini CSS aesthetic. Uses Android's `SweepGradient` + `ValueAnimator` for rotation, and `BlurMaskFilter` for glow.

## Current State Analysis

The mic button uses simple solid-color XML drawables:
- **Normal**: `rounded_button_background.xml` → `@color/colorAccent` (#64748B slate-500)
- **Pressed**: `rounded_button_background_pressed.xml` → `@color/colorAccentDark` (#475569 slate-600)

Background is swapped programmatically via `setBackgroundResource()` on `ACTION_DOWN`/`ACTION_UP` touch events in 3 Java files:
- `WhisperInputMethodService.java` (IME keyboard)
- `MainActivity.java` (main app)
- `WhisperRecognizeActivity.java` (recognizer overlay)

The Recorder sends `MSG_RECORDING` on start and `MSG_RECORDING_DONE`/`MSG_RECORDING_ERROR` on end. The touch listener handles the visual state swap.

### Key Constraints:
- minSdk 28 (Android 9) — `SweepGradient`, `ValueAnimator`, `BlurMaskFilter` all available
- Hardware accelerated by default — `BlurMaskFilter` requires `LAYER_TYPE_SOFTWARE` for the glow layer
- The button is an `ImageButton` with `src` (mic icon) and `background` (shape drawable)

## Desired End State

When the mic button is pressed and held:
1. A **rainbow sweep gradient** rotates continuously around the button border (5s per revolution)
2. A **blurred glow** (blur 15px) appears behind the button, following the same gradient
3. The button has a **solid dark inner fill** so the mic icon remains visible
4. On release, the effect stops and reverts to the normal slate background

### Verification:
- Press and hold mic button → rotating rainbow gradient border + glow visible
- Button icon remains visible on solid inner fill
- Release button → effect stops, reverts to normal slate background
- Works in all 3 contexts: main activity, IME keyboard, recognizer overlay

## What We're NOT Doing

- No effect on other buttons (space, dash, dot, delete, enter)
- No effect in normal (non-pressed) state
- No layout structural changes
- No color theme changes
- No Compose dependencies

## Color Palette (Gemini CSS → Android Hex)

From the CSS `conic-gradient`:
```css
oklch(0.63 0.2 251.22)  → #6366f1 (indigo/blue-purple)
oklch(0.67 0.21 25.81)  → #e11d48 (rose/red-pink)
oklch(0.9 0.19 93.93)   → #eab308 (yellow)
oklch(0.79 0.25 150.49) → #22c55e (green)
```

These 4 colors rotate around the border. The sweep gradient positions:
- 0%: #6366f1 (indigo)
- 27%: #6366f1 (indigo) — hold
- 33%: #e11d48 (rose)
- 41%: #eab308 (yellow)
- 49%: #22c55e (green)
- 65%: #6366f1 (indigo)
- 93%: #22c55e (green)
- 100%: #6366f1 (indigo)

## Implementation Approach

Create a single custom `Drawable` class (`GeminiGradientDrawable.java`) that handles everything internally. The Java code swaps between this drawable (pressed) and the existing XML drawable (normal) on touch events.

---

## Phase 1: Create GeminiGradientDrawable

### Overview
New custom `Drawable` class that draws the rotating gradient border with glow.

### Changes Required:

#### 1. New file: `GeminiGradientDrawable.java`
**File**: `app/src/main/java/com/whisperonnx/utils/GeminiGradientDrawable.java`
**Purpose**: Custom drawable with sweep gradient rotation + glow

```java
package com.whisperonnx.utils;

import android.animation.ValueAnimator;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;

public class GeminiGradientDrawable extends Drawable {

    private static final int[] GRADIENT_COLORS = {
        0xFF6366F1, // indigo
        0xFF6366F1, // indigo (hold at 27%)
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

    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix gradientMatrix = new Matrix();
    private float rotationDegrees = 0f;
    private ValueAnimator rotationAnimator;
    private float cornerRadius;
    private float borderWidth;
    private float glowBlur;

    public GeminiGradientDrawable(float cornerRadiusPx, float borderWidthPx, float glowBlurPx) {
        this.cornerRadius = cornerRadiusPx;
        this.borderWidth = borderWidthPx;
        this.glowBlur = glowBlurPx;

        // Inner fill — dark background matching app theme
        innerPaint.setStyle(Paint.Style.FILL);
        innerPaint.setColor(0xFF0F172A); // slate-900

        // Border paint — will use sweep gradient
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(borderWidth);

        // Glow paint — blurred copy of gradient
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(borderWidth + glowBlur);
        glowPaint.setMaskFilter(new BlurMaskFilter(glowBlur, BlurMaskFilter.Blur.NORMAL));
    }

    public void startAnimation() {
        if (rotationAnimator != null && rotationAnimator.isRunning()) return;
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f);
        rotationAnimator.setDuration(5000); // 5s per rotation
        rotationAnimator.setRepeatCount(ValueAnimator.INFINITE);
        rotationAnimator.setInterpolator(new LinearInterpolator());
        rotationAnimator.addUpdateListener(a -> {
            rotationDegrees = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        rotationAnimator.start();
    }

    public void stopAnimation() {
        if (rotationAnimator != null) {
            rotationAnimator.cancel();
            rotationAnimator = null;
        }
    }

    @Override
    public void draw(Canvas canvas) {
        RectF bounds = new RectF(getBounds());
        float inset = glowBlur; // leave room for glow
        RectF drawBounds = new RectF(
            bounds.left + inset,
            bounds.top + inset,
            bounds.right - inset,
            bounds.bottom - inset
        );

        // Create sweep gradient centered on drawable
        float cx = drawBounds.centerX();
        float cy = drawBounds.centerY();
        SweepGradient gradient = new SweepGradient(cx, cy, GRADIENT_COLORS, GRADIENT_POSITIONS);

        // Rotate
        gradientMatrix.setRotate(rotationDegrees, cx, cy);
        gradient.setLocalMatrix(gradientMatrix);

        // Draw glow (behind everything)
        glowPaint.setShader(gradient);
        canvas.drawRoundRect(drawBounds, cornerRadius, cornerRadius, glowPaint);

        // Draw border
        borderPaint.setShader(gradient);
        canvas.drawRoundRect(drawBounds, cornerRadius, cornerRadius, borderPaint);

        // Draw inner fill (covers center, leaving only border visible)
        float innerInset = borderWidth / 2f;
        RectF innerBounds = new RectF(
            drawBounds.left + innerInset,
            drawBounds.top + innerInset,
            drawBounds.right - innerInset,
            drawBounds.bottom - innerInset
        );
        canvas.drawRoundRect(innerBounds, cornerRadius, cornerRadius, innerPaint);
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
```

### Key Design Decisions:
- **`SweepGradient`** is Android's equivalent of CSS `conic-gradient`
- **`BlurMaskFilter`** for glow — requires `LAYER_TYPE_SOFTWARE` on the View for proper rendering
- **Inner fill** covers the gradient center so only the border ring shows
- **5000ms duration** with `INFINITE` repeat matches CSS `animation: rotate 5s infinite linear`
- **`LinearInterpolator`** matches CSS `linear` timing function

### Success Criteria:

#### Automated Verification:
- [ ] File compiles: `./gradlew assembleDebug`
- [ ] No import errors

#### Manual Verification:
- [ ] Class exists at correct path

**Implementation Note**: This phase is file creation only. No UI changes yet.

---

## Phase 2: Integrate into Touch Listeners

### Overview
Modify the 3 Java files that handle mic button touch events to use `GeminiGradientDrawable` on press and revert on release.

### Changes Required:

#### 1. WhisperInputMethodService.java (IME keyboard)
**File**: `app/src/main/java/com/whisperonnx/WhisperInputMethodService.java`
**Changes**: Add `GeminiGradientDrawable` field, create on demand, swap on touch

In the class fields section, add:
```java
private GeminiGradientDrawable geminiDrawable;
```

In the touch listener setup (around line 243), replace the `ACTION_DOWN` and `ACTION_UP` background swaps:

```java
// ACTION_DOWN — pressed
if (geminiDrawable == null) {
    float density = getResources().getDisplayMetrics().density;
    geminiDrawable = new GeminiGradientDrawable(
        10 * density,   // cornerRadius (matches XML 10dp)
        4 * density,    // borderWidth
        15 * density    // glowBlur (matches CSS blur(15px))
    );
}
btnRecord.setBackground(geminiDrawable);
btnRecord.setLayerType(View.LAYER_TYPE_SOFTWARE, null); // required for BlurMaskFilter
geminiDrawable.startAnimation();

// ACTION_UP — released
geminiDrawable.stopAnimation();
btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
btnRecord.setLayerType(View.LAYER_TYPE_HARDWARE, null); // restore HW accel
```

Apply the same pattern to the `Recorder.RecorderListener` callbacks that also swap backgrounds:
- `MSG_RECORDING` (line 149) — already pressed, no change needed (touch listener handles it)
- `MSG_RECORDING_DONE` (line 152) — stop animation, revert background
- `MSG_RECORDING_ERROR` (line 158 area) — stop animation, revert background

#### 2. MainActivity.java (main app)
**File**: `app/src/main/java/com/whisperonnx/MainActivity.java`
**Changes**: Same pattern — add field, modify touch listener (line 168 area)

Same `geminiDrawable` field. Same touch listener modification. Same RecorderListener callback updates.

#### 3. WhisperRecognizeActivity.java (recognizer overlay)
**File**: `app/src/main/java/com/whisperonnx/WhisperRecognizeActivity.java`
**Changes**: Same pattern — add field, modify touch listener (line 93 area)

Same `geminiDrawable` field. Same touch listener modification. Same RecorderListener callback updates.

### Success Criteria:

#### Automated Verification:
- [ ] Project builds: `./gradlew assembleDebug`

#### Manual Verification:
- [ ] Main app: press & hold mic → rotating gradient border + glow visible, release → reverts to slate
- [ ] IME keyboard: press & hold mic → rotating gradient border + glow visible, release → reverts to slate
- [ ] Recognizer overlay: press & hold mic → rotating gradient border + glow visible, release → reverts to slate
- [ ] Mic icon remains visible on solid dark inner fill during effect
- [ ] Glow is visible behind button (not clipped)
- [ ] No performance issues (smooth 60fps rotation)
- [ ] Effect stops cleanly on release (no lingering animation)

---

## Phase 3: Polish & Edge Cases

### Overview
Handle edge cases: VAD auto-stop, timer expiry, error states.

### Changes Required:

#### 1. VAD mode auto-stop
When VAD detects silence and auto-stops recording, `MSG_RECORDING_DONE` fires. The RecorderListener callback must stop the animation and revert background. Already handled in Phase 2's RecorderListener changes.

#### 2. 30-second timer expiry
When the `CountDownTimer` finishes (onFinish), recording stops via `mRecorder.stop()`, which triggers `MSG_RECORDING_DONE`. Animation reverts via RecorderListener. No additional changes needed.

#### 3. Error state
`MSG_RECORDING_ERROR` already reverts background in RecorderListener. Add animation stop here too (covered in Phase 2).

#### 4. Glow clipping
If glow appears clipped by parent layout, add padding to the button's parent `LinearLayout` or set `clipChildren="false"` on parent views. Check each layout:
- `voice_service.xml` — button inside `LinearLayout` with `layout_buttons` id
- `activity_main.xml` — button inside `RelativeLayout`
- `activity_recognize.xml` — button inside `LinearLayout`

If clipping occurs, add `android:clipChildren="false"` and `android:clipToPadding="false"` to parent views.

### Success Criteria:

#### Automated Verification:
- [ ] Project builds: `./gradlew assembleDebug`

#### Manual Verification:
- [ ] VAD auto-stop: button reverts cleanly when silence detected
- [ ] 30s timeout: button reverts cleanly when timer expires
- [ ] Error state: button reverts cleanly on recording error
- [ ] Glow not clipped by parent layouts in any of the 3 contexts
- [ ] No memory leaks (drawable/animator properly cleaned up)

---

## Testing Strategy

### Manual Testing Steps:
1. Build and install debug APK
2. **Main Activity**: Press & hold mic → verify rotating gradient + glow, release → verify revert
3. **IME Keyboard**: Enable in Settings → open any text field → long-press mic → verify effect
4. **Recognizer Overlay**: Trigger floating recognizer → press & hold mic → verify effect
5. **VAD mode**: Enable VAD, press mic, speak, stop speaking → verify effect stops when VAD detects silence
6. **30s timeout**: Hold mic for 30 seconds → verify effect stops when timer expires
7. **Error state**: Try recording with mic permission denied → verify effect stops on error
8. **Performance**: Verify smooth rotation, no jank, no battery drain during hold

## Performance Considerations

- `BlurMaskFilter` with `LAYER_TYPE_SOFTWARE` disables hardware acceleration for the button View during the effect. This is acceptable since it's only active during recording (seconds, not continuous).
- `ValueAnimator` at 60fps calling `invalidateSelf()` is lightweight — just redraws the single drawable.
- No bitmap allocation per frame — gradient + blur are computed on the GPU/CPU canvas.

## References

- CSS reference: Gemini gradient effect (user-provided)
- Current drawable: `app/src/main/res/drawable/rounded_button_background.xml`
- Current pressed drawable: `app/src/main/res/drawable/rounded_button_background_pressed.xml`
- Touch listener (IME): `WhisperInputMethodService.java:243`
- Touch listener (Main): `MainActivity.java:168`
- Touch listener (Recognizer): `WhisperRecognizeActivity.java:93`
- Recorder callbacks: `Recorder.java` — `MSG_RECORDING`, `MSG_RECORDING_DONE`, `MSG_RECORDING_ERROR`
- Color mapping: oklch → hex conversion from CSS conic-gradient
