# Shadcn Modern Slate Dark Theme Implementation Plan

## Overview

Replace the current minimal purple UI color scheme with a shadcn modern slate dark palette. Dark theme only — no light mode. Changes are color-only: buttons, borders, backgrounds, text labels, progress bars. No icon changes, no focus rings, no structural layout changes.

## Current State Analysis

The app uses a centralized Android color system:
- 5 named colors in `values/colors.xml` (light) and `values-night/colors.xml` (dark)
- `colorAccent` (#6750A4 purple) used ubiquitously: button fills, text labels, progress tints, icon "on" states
- `windowBackground` (#FAFAFA light / #111111 dark) for app background
- `darkGrey` (#999999 / #555555) for secondary button backgrounds
- `lightGrey` (#DDDDDD) for progress bar track tint
- Material3 DayNight theme (`themes.xml`) that only overrides `windowBackground`
- Night mode set to `MODE_NIGHT_FOLLOW_SYSTEM` in `MainActivity` and `SetupActivity`
- 5 shape drawables reference these colors
- 5 layout files reference `@color/colorAccent` (auto-updates)
- 4 icon drawables use `@color/colorAccent` for "on" state circles (auto-updates)

### Key Discoveries:
- `app/src/main/java/com/whisperonnx/MainActivity.java:118` — `AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)` — must change to `MODE_NIGHT_YES`
- `app/src/main/java/com/whisperonnx/SetupActivity.java:42` — same night mode call — must change
- `app/src/main/res/layout/voice_service.xml:94` — hardcoded `@android:color/holo_red_dark` for status text — no change needed (error state color, not part of theme)
- All icon "on" states (`ic_auto_on_36dp.xml`, `ic_counter_1_on_36dp.xml`, `ic_counter_2_on_36dp.xml`, `ic_english_on_36dp.xml`, `ic_cloud_on_36dp.xml`) use `@color/colorAccent` for circle fill — auto-updates with new palette

## Desired End State

App renders entirely in shadcn modern slate dark palette. Background is deep slate blue-black. Buttons are mid-tone slate. Labels and accents are cool gray. Progress bars use slate tones. The app feels like a modern dark IDE or terminal — cool, muted, professional.

### Verification:
- Open every screen (Main, Settings, Download, Recognizer overlay, IME keyboard)
- Confirm no purple remains anywhere
- Confirm all text is readable against dark background
- Confirm button fills, borders, and labels use slate tones
- Confirm day/night system toggle does NOT affect the app (always dark)

## What We're NOT Doing

- No light mode — dark only, forced via `MODE_NIGHT_YES`
- No icon redesign — icons stay white (#FFFFFF)
- No focus ring implementation
- No layout structural changes
- No corner radius changes
- No new Material3 color attributes in themes.xml (keeping it simple — just swap hex values)
- No gradient or glow effects
- No new fonts
- No animation changes

## Color Mapping

Shadcn modern slate maps to Tailwind CSS slate palette. Dark theme only.

### Tailwind Slate Reference (hex):
| Step | Hex |
|---|---|
| slate-50 | `#f8fafc` |
| slate-100 | `#f1f5f9` |
| slate-200 | `#e2e8f0` |
| slate-300 | `#cbd5e1` |
| slate-400 | `#94a3b8` |
| slate-500 | `#64748b` |
| slate-600 | `#475569` |
| slate-700 | `#334155` |
| slate-800 | `#1e293b` |
| slate-900 | `#0f172a` |
| slate-950 | `#020617` |

### Mapping to Android Colors:

| Android Name | Current (Night) | New Value | Tailwind | Role |
|---|---|---|---|---|
| `windowBackground` | `#111111` | `#0f172a` | slate-900 | App background — deep slate |
| `colorAccent` | `#ff6750A4` | `#64748b` | slate-500 | Button fills, labels, tints, icon "on" circles |
| `colorAccentDark` | `#996750A4` | `#475569` | slate-600 | Pressed state (darker than accent) |
| `darkGrey` | `#555555` | `#1e293b` | slate-800 | Secondary button bg, keyboard bg, recognizer overlay |
| `lightGrey` | `#DDDDDD` | `#334155` | slate-700 | Progress bar track tint |
| `black` | `#FF000000` | `#020617` | slate-950 | Deepest dark (if needed) |

### Rationale:
- **slate-500** for `colorAccent`: visible on slate-900 background (~5:1 contrast), not too bright for button fills, works for text labels
- **slate-800** for `darkGrey`: subtle elevation from slate-900 background, good for secondary surfaces
- **slate-700** for `lightGrey`: slightly lighter than slate-800, visible as progress bar track
- **slate-600** for `colorAccentDark`: clearly darker than slate-500 for press feedback
- **slate-950** for `black`: deepest dark for edge cases

## Implementation Approach

Simple hex value swap. The app's centralized color system means most changes auto-propagate through `@color/` references. Only the two color definition files need new hex values. Night mode forcing requires one line change in two Activity files.

---

## Phase 1: Update Color Definitions

### Overview
Replace all color hex values in both `colors.xml` files with shadcn modern slate dark palette. Since we're dark-only, both light and night files get identical dark values (belt and suspenders).

### Changes Required:

#### 1. Light mode colors (fallback)
**File**: `app/src/main/res/values/colors.xml`
**Changes**: Replace all hex values with slate dark palette

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="black">#FF020617</color>
    <color name="lightGrey">#FF334155</color>
    <color name="darkGrey">#FF1E293B</color>
    <color name="colorAccent">#FF64748B</color>
    <color name="colorAccentDark">#FF475569</color>
    <color name="windowBackground">#FF0F172A</color>
</resources>
```

#### 2. Night mode colors
**File**: `app/src/main/res/values-night/colors.xml`
**Changes**: Replace all hex values with identical slate dark palette

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="darkGrey">#FF1E293B</color>
    <color name="colorAccent">#FF64748B</color>
    <color name="colorAccentDark">#FF475569</color>
    <color name="windowBackground">#FF0F172A</color>
</resources>
```

### Success Criteria:

#### Automated Verification:
- [ ] Project builds cleanly: `./gradlew assembleDebug`
- [ ] No resource reference errors

#### Manual Verification:
- [ ] Open Main activity — background is deep slate blue-black (#0f172a), not purple or pure black
- [ ] Record button is slate-500 (#64748b), not purple
- [ ] Status/append/translate labels are slate-500, not purple
- [ ] Progress bar tint is slate-500

**Implementation Note**: Pause here after this phase. Confirm the color swap looks correct before proceeding. At this point the visual change should already be dramatic — the entire app shifts from purple to slate.

---

## Phase 2: Force Dark Mode

### Overview
Change night mode from `MODE_NIGHT_FOLLOW_SYSTEM` to `MODE_NIGHT_YES` so the app is always dark regardless of system theme setting. Also update `values/colors.xml` (done in Phase 1) as fallback.

### Changes Required:

#### 1. MainActivity
**File**: `app/src/main/java/com/whisperonnx/MainActivity.java`
**Line**: 118
**Changes**: Change `MODE_NIGHT_FOLLOW_SYSTEM` to `MODE_NIGHT_YES`

```java
// Before:
AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
// After:
AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
```

#### 2. SetupActivity
**File**: `app/src/main/java/com/whisperonnx/SetupActivity.java`
**Line**: 42
**Changes**: Change `MODE_NIGHT_FOLLOW_SYSTEM` to `MODE_NIGHT_YES`

```java
// Before:
AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
// After:
AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
```

### Success Criteria:

#### Automated Verification:
- [ ] Project builds cleanly: `./gradlew assembleDebug`

#### Manual Verification:
- [ ] Set system theme to Light mode
- [ ] Open app — app still renders dark (slate colors from Phase 1 apply)
- [ ] Set system theme to Dark mode
- [ ] Open app — identical appearance
- [ ] Status bar icons are light (ThemeUtils already handles this for dark mode)

**Implementation Note**: Pause here. Confirm forced dark mode works correctly and Material3 components (Material switch, slider, TextInputLayout) render properly in dark mode.

---

## Phase 3: Verify All Screens (No Code Changes)

### Overview
Walk through every screen and component to confirm slate palette applies everywhere. Document any issues. The color references should auto-propagate — this phase is verification only.

### Screens to Check:

| Screen | File | Key Color Usages |
|---|---|---|
| Main | `activity_main.xml` | bg via theme, labels (colorAccent), progress bar (colorAccent), record btn (colorAccent via drawable), FAB (Material default) |
| Settings | `activity_settings.xml` | labels (colorAccent), TextInputLayout (Material default), RangeSlider (Material default) |
| Download | `activity_download.xml` | frame (colorAccent via drawable), progress bar (colorAccent), Material buttons |
| Recognizer | `activity_recognize.xml` | overlay bg (darkGrey via drawable), progress bar (colorAccent), btn bg (darkGrey), record btn (colorAccent via drawable) |
| IME Keyboard | `voice_service.xml` | bg (darkGrey), toolbar btns (darkGrey), record/space/dot/dash/del/enter btns (colorAccent via drawable), progress bar (colorAccent) |

### Potential Issues to Watch For:
- Material3 FAB color (uses Material theme, not our custom colors) — may still show purple. If so, needs `colorSecondary` or `colorTertiary` override in `themes.xml`
- Material3 TextInputLayout (Settings) — may need Material color overrides
- Material3 RangeSlider (Settings) — may need Material color overrides
- Material3 Button (Download screen) — may need Material color overrides
- Checkbox color (Main, Settings) — may need Material color overrides

### If Material3 Components Show Wrong Colors:
**File**: `app/src/main/res/values/themes.xml`
**Changes**: Add Material3 color attributes to `Theme.Whisper.NoActionBar` and `Theme.Whisper.ActionBar`:

```xml
<style name="Theme.Whisper.NoActionBar" parent="Theme.Material3.DayNight.NoActionBar">
    <item name="android:windowBackground">@color/windowBackground</item>
    <item name="colorPrimary">@color/colorAccent</item>
    <item name="colorOnPrimary">#FF020617</item>
    <item name="colorSecondary">@color/darkGrey</item>
    <item name="colorOnSecondary">#FFF8FAFC</item>
    <item name="colorSurface">@color/darkGrey</item>
    <item name="colorOnSurface">#FFF8FAFC</item>
    <item name="colorOutline">@color/lightGrey</item>
</style>
```

### Success Criteria:

#### Automated Verification:
- [ ] Project builds cleanly: `./gradlew assembleDebug`

#### Manual Verification:
- [ ] Main: FAB icon visible, record button slate, labels slate, progress bar slate
- [ ] Settings: all labels slate, TextInputLayout borders/fills slate, slider thumb/track slate, checkboxes slate
- [ ] Download: frame border slate, progress bar slate, buttons slate
- [ ] Recognizer overlay: dark slate bg, record button slate, secondary buttons blend into bg (expected)
- [ ] IME keyboard: dark slate bg, all key buttons slate-500, progress bar slate
- [ ] Icon "on" states (auto, counter, english, cloud): circle bg is slate-500, icon is white
- [ ] No purple visible anywhere in the app
- [ ] Toggle system light/dark — app stays dark

---

## Testing Strategy

### Unit Tests:
- None needed (resource-only change, no logic)

### Manual Testing Steps:
1. Build and install debug APK
2. Set system to Light mode
3. Open app from launcher — confirm dark slate everywhere
4. Navigate to Settings — confirm all components slate
5. Go back, tap record button — confirm slate-500 fill, slate-600 on press
6. Set system to Dark mode — confirm identical appearance
7. Open IME keyboard (enable in Settings > Language & Input) — confirm slate dark keyboard
8. Trigger recognizer overlay — confirm dark slate overlay
9. Check app icon on launcher — purple bg (icon not in scope, but note it)

## Performance Considerations

None. This is a static color resource change with zero runtime impact.

## References

- Research: `thoughts/shared/research/2026-06-07-ui-style-change-files.md`
- Tailwind CSS slate palette: https://tailwindcss.com/docs/customizing-colors
- Shadcn dark theme reference: https://ui.shadcn.com/themes
- Current colors: `app/src/main/res/values/colors.xml:6-8`
- Current night colors: `app/src/main/res/values-night/colors.xml:4-6`
- Current theme: `app/src/main/res/values/themes.xml:3-5`
- Night mode (Main): `app/src/main/java/com/whisperonnx/MainActivity.java:118`
- Night mode (Setup): `app/src/main/java/com/whisperonnx/SetupActivity.java:42`
