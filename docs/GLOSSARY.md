# Glossary

The names used for Chato's features, keys and screens. UI text, the README, code comments and
commit messages use these terms. When a name changes, change it here first, then in
`app/src/main/res/values/strings.xml`.

- **UI label**: what the user sees, from `strings.xml`. "a11y" marks the label TalkBack reads out for an icon key.
- **Code**: the class, view ID or constant.
- **Avoid**: names that have been used before and shouldn't come back.

## Backends and models

| Term | Meaning | UI label | Code | Stored as | Avoid |
|---|---|---|---|---|---|
| **Backend** | Where speech is turned into text: one of the three below. | "Transcription backend" | `Backends` | `backend_type` + `local_engine` | engine, mode |
| **Parakeet** | NVIDIA Parakeet TDT 0.6B v2, int8 ONNX, runs on the phone. The recommended backend. | "Parakeet (recommended)" | `Backends.PARAKEET`, `ParakeetRecognizer` | `backend_type=local`, `local_engine=parakeet` | experimental |
| **Whisper small** | OpenAI Whisper small, int8 ONNX, runs on the phone. | "Whisper small" | `Backends.WHISPER`, `WhisperModel` | `backend_type=local`, `local_engine=whisper` | Whisper+ (the upstream app) |
| **Server** | Any OpenAI-compatible `/v1/audio/transcriptions` server, including self-hosted ones. | "OpenAI-compatible server" | `Backends.REMOTE`, `OpenAITranscriber` | `backend_type=openai`, `openai_endpoint`, `openai_api_key`, `openai_model` | cloud, remote, OpenAI API |
| **On the phone** | Parakeet or Whisper small, as opposed to the server. | "On this phone" | `BACKEND_LOCAL` | `backend_type=local` | local (in UI text), on device, offline |
| **Model download** | The in-app download of Parakeet or Whisper small, checked against SHA-256 and resumable. | "Download Parakeet?" | `ModelInstaller`, `ModelDownloader` | | install (except for the zip option) |

Short model names inside sentences, e.g. "Loading Parakeet…": `model_parakeet` ("Parakeet") and
`model_whisper` ("Whisper small").

## Keyboard

The keyboard (`KeyboardService`) has a **top row** and a **bottom row**.

| Term | Meaning | UI label | Code | Stored as | Avoid |
|---|---|---|---|---|---|
| **Top row** | Punctuation, status text, pause other audio key, cloud key, settings. Can be collapsed. | a11y "Show or hide top row" (collapse key) | `layout_toolbar`, `btnToolbarToggle` | `imeToolbarExpanded` | toolbar (in UI text) |
| **Status text** | Messages shown in the keyboard (target, "Loading Parakeet…", errors) without changing its height. | | `tv_status_inline`, `tv_status` | | |
| **Pause other audio key** | When on, other apps' audio (music, videos) pauses while recording so the mic doesn't pick it up; most apps resume afterwards. Lit while on. Left of the cloud key. | a11y "Pause other audio" | `btnPauseAudio`, `OtherAudioPauser` | `imePauseOtherAudio` | mute key |
| **Cloud key** | Switches between the server and the backend on the phone. Lit while the server is in use. | a11y "Switch backend" | `btnBackend` | `backend_type` | backend toggle, remote toggle |
| **Mic** | Hold to record (hold mode), or tap to start and stop (auto mode). | a11y "Record" | `btnRecord` | | record button |
| **Send target key** | Cycles the send target: cursor → endpoint 1 → endpoint 2. Lit, with a 1/2 badge, while an endpoint is selected. | a11y "Send target" | `btnSendTarget`, `tvSendTargetBadge` | `imeSendTarget` (0 = cursor) | send button, POST button |
| **Auto key** | Turns auto mode on and off. Shows `A`. | a11y "Auto mode" | `btnModeAuto` | `imeModeAuto` | |
| **Switch keyboard key** | Returns to your other keyboard. | a11y "Switch keyboard" | `btnKeyboard` | | exit |
| **Cursor drag** | Drag on the space key to move the cursor: sideways by characters, up and down by lines. A tap still types a space. | a11y "Space" | `btnSpace`, `CursorDrag` | | trackpad, swipe space |
| **Bullet key** | Starts a `- ` line. | a11y "New bullet point" | `btnBullet` | | |

## Recording and sending

| Term | Meaning | UI label | Code | Stored as | Avoid |
|---|---|---|---|---|---|
| **Hold mode** | Records while the mic is held; sends audio in 30 s chunks. The default. | | `modeAuto == false` | | normal mode, push-to-talk |
| **Auto mode** | Tap to start; a pause ends the recording. Sends audio in 8 s chunks. Keeps the screen on while recording. | "Auto mode" | `modeAuto` | `imeModeAuto`, `silenceDurationMs` | automatic mode |
| **Chunk** | A piece of a recording that is transcribed on its own, while you keep talking. | | `Recorder.setChunkMs` | | segment |
| **Send target** | Where dictated text goes: the cursor, or an endpoint. | "Typing at cursor", "Sending to *name*" | `sendTarget`, `SendSession` | `imeSendTarget` | |
| **Endpoint** | A URL that dictated text is POSTed to. There are two: endpoint 1 and endpoint 2. | "Endpoint 1", "Send to endpoint" (Settings section) | `EndpointSender` | `endpoint1_*`, `endpoint2_*` | webhook, server (that's the backend) |
| **Body template** | The POST body, with `{{text}}`, `{{text_raw}}`, `{{timestamp}}` and `{{name}}` placeholders. | "Body template" | `EndpointSender` | `endpointN_body` | payload |
| **Quick record** | Records up to 30 s without the keyboard, then sends the text to an endpoint: the widget's own, or the one chosen in Settings for the assistant gesture. Started and stopped by the quick record widget or the digital assistant gesture. A notification shows while it runs. | "Quick record" | `QuickRecordService`, `QuickRecordActivity` | `quickRecordEndpoint` (1 or 2) | voice memo, background recording |
| **Quick record widget** | A home screen widget with one mic button that starts and stops quick record for one endpoint, chosen when it's placed. Shows the endpoint's number and name; lit while recording for it. Place one per endpoint. | "Quick record" | `QuickRecordWidgetProvider`, `QuickRecordWidgetConfigActivity` | `quickRecordWidget_<id>` | |
| **Digital assistant** | Chato chosen as Android's default digital assistant app, so the assistant gesture (e.g. holding the power button) starts and stops quick record. | "Use as digital assistant" | `QuickRecordActivity` (`ACTION_ASSIST`) | | assistant mode |

"Server" always means the transcription backend. "Endpoint" always means a send target URL.
The server's address is the **Server URL**, never "endpoint URL", even though its pref key is
`openai_endpoint`.

## Screens

| Term | Meaning | UI label | Code | Avoid |
|---|---|---|---|---|
| **Test screen** | Pick a backend, record, see the text and the time taken. Opens from the launcher and from Settings. | "Test transcription" (Settings row) | `TestScreenActivity`, `activity_main.xml` | main screen |
| **Setup screen** | Opens when a model on the phone is needed but missing. Offers the model download or the server. | "Set up Chato" | `SetupActivity`, `activity_download.xml` | download screen |
| **Settings** | Backend, server, endpoints, quick record, auto mode silence, Bluetooth. | "Settings" | `SettingsActivity` | preferences |
| **System voice input** | Chato as Android's speech recognizer for other apps. | | `VoiceInputService`, `VoiceInputActivity` | |

## Code names that don't match the terms

Kept to avoid churn, and so stored settings keep working:

- The `Whisper` class runs whichever backend is selected, including Parakeet and the server.
- The Java package is `com.whisperonnx`; the app ID is `dev.starkbit.chato`. Each feature has its own
  package.
- Android stores some old class names, so they stay registered as aliases or empty classes in
  `com.whisperonnx`. `ComponentsTest` lists them.
- `backend_type=openai` and the `openai_*` keys mean the server, whatever software runs it.
- `activity_main.xml` is the test screen; `activity_download.xml` is the setup screen.
