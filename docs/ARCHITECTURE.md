# Architecture

The code in `app/src/main/java/com/whisperonnx` is split into one package per feature, plus
shared packages that the features build on. Package names use the terms in the
[glossary](GLOSSARY.md). `make check` enforces the rules below; nothing here relies on review.

## Packages

| Package | Holds | Glossary term |
|---|---|---|
| `keyboard` | `KeyboardService`, `CursorDrag`, `TextEdits` | Keyboard, cursor drag, bullet key |
| `quickrecord` | `QuickRecordService`, `QuickRecordActivity`, `QuickRecordWidgetProvider`, `QuickRecordWidgetConfigActivity` | Quick record, quick record widget, digital assistant |
| `voiceinput` | `VoiceInputService`, `VoiceInputActivity` | System voice input |
| `testscreen` | `TestScreenActivity`, `GithubStar` | Test screen |
| `setup` | `SetupActivity` | Setup screen |
| `settings` | `SettingsActivity` | Settings |
| `ui` | `Components`, `ModelInstaller`, `HapticFeedback`, `GradientBorderDrawable`, `ThemeUtils` | Shared code with UI |
| `backend` | `Whisper`, `Backends`, `ParakeetRecognizer`, `TdtDecoder`, `OpenAITranscriber`, `WhisperModel`, `ModelDownloader` | Backend, model download |
| `recording` | `Recorder`, `RecordBuffer`, `WavUtils`, `OtherAudioPauser` | Hold mode, auto mode, chunk, pause other audio key |
| `endpoint` | `EndpointSender`, `SendSession` | Endpoint, send target, body template |
| `thirdparty.rtranslator` | Whisper ONNX code vendored from RTranslator | |
| root (`com.whisperonnx`) | Only the generated `R` and `BuildConfig`, and the registered names below | |

## Rules

1. **Features don't depend on each other.** To open another feature's screen, use
   `ui.Components` (`Components.open(this, Components.SETTINGS)`). Code that two features need
   moves down into a shared package.
2. **Shared code doesn't depend on features**, or on the classes in the root package.
3. **`backend`, `recording` and `endpoint` have no UI.** They don't use `ui`, `R`,
   `android.widget`, `android.view`, `android.app`, AppCompat or Material. They report state
   through listeners and constants (`Recorder.MSG_NO_PERMISSION`, `Whisper.isModelMissing()`),
   and the feature that called them shows the label or opens the screen.
4. **Only `backend` uses `thirdparty`**, and `thirdparty` uses nothing of ours.
5. **No cycles between packages.**
6. **No catch-all packages** (`utils`, `common`, `helpers`, `misc`). Name a package after what it does.
7. **Plain logic stays free of Android** (`CursorDrag`, `SendSession`, `TdtDecoder`,
   `RecordBuffer`, `WavUtils`), so its unit tests run on the JVM.
8. **User-visible text lives in `res/values/strings.xml`**, never as a literal in Java or a layout.

## Registered names

Android stores some of the app's component names on the phone. Renaming one would silently turn
the feature off after an update, so these names never change, even though the code moved:

| Stored name | Stored as | Kept by |
|---|---|---|
| `com.whisperonnx.MainActivity` | launcher icon | `<activity-alias>` → `testscreen.TestScreenActivity` |
| `com.whisperonnx.WhisperRecognizeActivity` | default speech recognizer activity | `<activity-alias>` → `voiceinput.VoiceInputActivity` |
| `com.whisperonnx.QuickRecordActivity` | digital assistant | `<activity-alias>` → `quickrecord.QuickRecordActivity` |
| `com.whisperonnx.WhisperInputMethodService` | enabled keyboard | empty subclass of `keyboard.KeyboardService` |
| `com.whisperonnx.WhisperRecognitionService` | system voice input | empty subclass of `voiceinput.VoiceInputService` |
| `com.whisperonnx.QuickRecordWidget` | placed widgets | empty subclass of `quickrecord.QuickRecordWidgetProvider` |

Services and receivers can't have an alias, so they keep an empty class in the root package.
Code that needs one of these names (the keyboard ID, the widget provider) gets it from
`ui.Components`.

## The checks

`make check` runs them fastest first. None needs a phone.

| Check | Where | Enforces |
|---|---|---|
| Checkstyle `ImportControl` | `config/checkstyle/import-control.xml` | Rules 1 to 4, on imports |
| Checkstyle `hardcodedUiText` | `config/checkstyle/checkstyle.xml` | Rule 8 in Java |
| Android Lint `HardcodedText`, `SetTextI18n` as errors | `app/build.gradle` | Rule 8 in layouts and `setText` |
| `ArchitectureTest` (ArchUnit) | `app/src/test` | Rules 1 to 7, on compiled classes, so fully qualified names can't get round them |
| `ComponentsTest` | `app/src/test` | Registered names are still declared; every class named in the manifest, `res/xml` and `Components` exists |

When a check fails, fix the cause rather than suppressing it. If a rule is wrong, change the rule
here and in the check in the same change.
