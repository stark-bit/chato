---
date: 2026-06-07T12:00:00+00:00
researcher: stark-bit
git_commit: 426e04b
branch: master
repository: whisperIMEplus
topic: "Files to edit for UI style change from minimal purple to sleek futuristic"
tags: [research, codebase, android, ui, styling, colors, themes, drawables]
status: complete
last_updated: 2026-06-07
last_updated_by: stark-bit
---

# Research: Files to edit for UI style change from minimal purple to sleek futuristic

**Date**: 2026-06-07  
**Researcher**: stark-bit  
**Git Commit**: 426e04b  
**Branch**: master  
**Repository**: whisperIMEplus

## Research Question

Which files must be edited to change the UI style of this whisper APP, currently it's quite minimal purple style, to a sleek futuristic style?

## Summary

The app's UI styling is controlled by a small set of resource files. The current "minimal purple" look uses `#6750A4` as the accent color (Material Design purple) with light/dark backgrounds. To achieve a sleek futuristic style, you'll need to modify:

1. **Color palette** (`colors.xml`, `night/colors.xml`) - core color definitions
2. **Theme** (`themes.xml`) - Material3 theme attributes
3. **Drawable shapes** (5 files) - button backgrounds, frames, recognizer background
4. **Icon drawables** (25+ files) - if you want to change icon colors from white
5. **Launcher icon** (`ic_launcher.xml`) - app icon background
6. **Layout files** (5 files) - only if changing hardcoded color references

## Detailed Findings

### 1. Color Definitions (MUST EDIT)

**`app/src/main/res/values/colors.xml`** - Light mode colors
- `colorAccent`: `#996750A4` (purple with 60% alpha) - primary accent used everywhere
- `colorAccentDark`: `#ff6750A4` (solid purple) - pressed states
- `windowBackground`: `#FAFAFA` (near white) - app background
- `darkGrey`: `#999999` - button backgrounds in recognizer/keyboard
- `lightGrey`: `#DDDDDD` - progress bar background tint

**`app/src/main/res/values-night/colors.xml`** - Night mode colors
- `colorAccent`: `#ff6750A4` (solid purple)
- `colorAccentDark`: `#996750A4` (inverted alpha)
- `windowBackground`: `#111111` (near black)
- `darkGrey`: `#555555` (darker grey for night)

### 2. Theme Definition (MUST EDIT)

**`app/src/main/res/values/themes.xml`** - Three theme variants
- `Theme.Whisper.NoActionBar` - Main app theme (Material3.DayNight.NoActionBar)
- `Theme.Whisper.ActionBar` - Settings theme (Material3.DayNight)
- `Theme.WhisperFloating` - Floating overlay theme (transparent background)

Currently only sets `android:windowBackground`. For futuristic look, add Material3 color attributes:
- `colorPrimary`, `colorPrimaryVariant`, `colorSecondary`
- `colorSurface`, `colorOnSurface`, `colorOnPrimary`
- `android:colorAccent` (for backward compatibility)

### 3. Drawable Shapes (MUST EDIT)

**`app/src/main/res/drawable/rounded_button_background.xml`** - Record button, space, dash, dot, delete, enter buttons
```xml
<solid android:color="@color/colorAccent" />
<corners android:radius="10dp" />
```

**`app/src/main/res/drawable/rounded_button_background_pressed.xml`** - Pressed state
```xml
<solid android:color="@color/colorAccentDark" />
<corners android:radius="10dp" />
```

**`app/src/main/res/drawable/rounded_button_frame.xml`** - Download screen frame
```xml
<stroke android:color="@color/colorAccent" />
<corners android:radius="10dp" />
```

**`app/src/main/res/drawable/rounded_background.xml`** - (unused currently)
```xml
<solid android:color="@color/darkGrey" />
<corners android:radius="10dp" />
```

**`app/src/main/res/drawable/recognizer_background.xml`** - Floating recognizer overlay
```xml
<solid android:color="@color/darkGrey" />
<corners android:topLeftRadius="20dp" android:topRightRadius="20dp" />
```

### 4. Launcher Icon (MUST EDIT)

**`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`**
- Background: `@color/colorAccent` (purple)
- Foreground: `@drawable/ic_mic_foreground` (white mic icon)
- Monochrome: `@drawable/ic_mic_foreground_monochrome`

### 5. Icon Drawables (OPTIONAL - 25+ files)

All icons use hardcoded `#FFFFFF` (white) fill. Files in `app/src/main/res/drawable/`:
- `ic_mic_48dp.xml` - Main mic icon
- `ic_mic_foreground.xml` - Launcher foreground
- `ic_copy.xml` - Copy FAB icon
- `ic_info_48dp.xml` - Info button
- `ic_cancel_36dp.xml` - Cancel button
- `ic_auto_on_36dp.xml` / `ic_auto_off_36dp.xml` - Auto mode icons (on uses `@color/colorAccent`)
- `ic_counter_1_on_36dp.xml` / `ic_counter_1_off_36dp.xml` - Language counter 1
- `ic_counter_2_on_36dp.xml` / `ic_counter_2_off_36dp.xml` - Language counter 2
- `ic_english_on_36dp.xml` / `ic_english_off_36dp.xml` - Translate toggle
- `ic_cloud_on_36dp.xml` / `ic_cloud_off_36dp.xml` - Backend toggle
- `ic_keyboard_36dp.xml` - Keyboard switch
- `ic_keyboard_del_48dp.xml` - Delete key
- `ic_keyboard_return_48dp.xml` - Enter key
- `ic_dash_48dp.xml` - Dash key
- `ic_dot_48dp.xml` - Dot key
- `ic_space_48dp.xml` - Space icon
- `ic_settings.xml` - Settings icon

### 6. Layout Files (ONLY for hardcoded colors)

These layouts reference `@color/colorAccent` directly (will auto-update when colors.xml changes):
- `activity_main.xml` - Main activity (7 references)
- `activity_settings.xml` - Settings (8 references)
- `activity_recognize.xml` - Floating recognizer (2 references)
- `activity_download.xml` - Download screen (1 reference)
- `voice_service.xml` - IME keyboard (2 references)

**Hardcoded colors requiring manual edit:**
- `voice_service.xml:94` - `@android:color/holo_red_dark` (status text)

## Code References

- `app/src/main/res/values/colors.xml:6-8` - Primary color definitions
- `app/src/main/res/values-night/colors.xml:4-6` - Night mode colors
- `app/src/main/res/values/themes.xml:3-5` - Main theme definition
- `app/src/main/res/drawable/rounded_button_background.xml:2` - Button background color
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml:3` - Launcher background

## Architecture Insights

### Color Usage Pattern
The app uses a centralized color system:
1. Define colors in `colors.xml` / `night/colors.xml`
2. Reference via `@color/colorAccent` in XML
3. Shape drawables use color references for fills/strokes
4. Layouts reference colors for text, tints, backgrounds

### Material3 Integration
The app uses `Theme.Material3.DayNight` as parent theme but only overrides `windowBackground`. Material3's color system (`colorPrimary`, `colorSecondary`, etc.) is not utilized. To get a proper futuristic Material3 look, you should:
1. Generate a color scheme using Material Theme Builder
2. Add color attributes to `themes.xml`
3. Consider using `DynamicColors` for modern Android versions

### Day/Night Support
The app has proper day/night support via separate `colors.xml` files. Both must be updated for consistent styling.

## Recommended Futuristic Color Palette

For a sleek futuristic look, consider:

**Light Mode:**
- Accent: Neon cyan `#00E5FF` or electric blue `#2979FF`
- Background: Dark slate `#1A1A2E` or deep navy `#0D1117`
- Surface: Slightly lighter `#16213E`

**Dark Mode:**
- Accent: Bright cyan `#00FFFF` or neon green `#39FF14`
- Background: Pure black `#000000` or very dark `#0A0A0A`
- Surface: Dark grey `#1E1E1E`

## Files to Edit (Priority Order)

### Critical (do these first):
1. `app/src/main/res/values/colors.xml` - New color palette
2. `app/src/main/res/values-night/colors.xml` - Night mode palette
3. `app/src/main/res/values/themes.xml` - Add Material3 color attributes

### High Priority:
4. `app/src/main/res/drawable/rounded_button_background.xml` - Button style
5. `app/src/main/res/drawable/rounded_button_background_pressed.xml` - Pressed state
6. `app/src/main/res/drawable/recognizer_background.xml` - Floating overlay
7. `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` - App icon

### Medium Priority (optional):
8. All icon drawables if changing from white to accent color
9. `voice_service.xml` - Fix hardcoded red color

## Open Questions

1. Should icons remain white or adopt accent color?
2. Should corner radius change (currently 10dp)?
3. Should the floating recognizer have a different style than main app?
4. Want to implement Material3 dynamic colors for modern Android?