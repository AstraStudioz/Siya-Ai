# Siya AI - Release Notes & Changelog

## Phase 3: Gemini Multimodal Live Streaming & Camera Vision Hardening
- **Gemini Live Multimodal Video Chunk Payload (`network/GeminiLiveWebSocket.kt:1066-1068`)**:
  - **Root Cause**: `sendMediaChunk` formatted outbound video frames as `{"realtimeInput":{"video":{"mimeType":"image/jpeg","data":"..."}}}`, which violates the Gemini Multimodal Live API specification. The server requires `mediaChunks` array (`{"realtimeInput":{"mediaChunks":[{"mimeType":"image/jpeg","data":"..."}]}}`), causing all camera and screen sharing frames to be discarded.
  - **Fix**: Replaced invalid `"video"` object wrapper with standard `"mediaChunks"` array containing `image/jpeg` payload.
  - Added `buildMediaChunkJson` and `buildAudioChunkJson` companion methods for deterministic payload serialization and JVM unit testing.
- **WebSocket Setup Transcription Synchronization (`network/GeminiLiveWebSocket.kt:294-295`)**:
  - Mirrored `inputAudioTranscription` and `outputAudioTranscription` inside `generation_config` in the WebSocket setup message, guaranteeing compatibility across all Gemini 3.8 and 3.1 Live preview model endpoints.
- **Camera Vision UI & Audio Synchronization (`ui/camera/CameraVisionActivity.kt:218, 502-506`)**:
  - Ensured `ensureAudioCapture()` executes automatically when unmuting or starting session in Camera Vision mode.
  - Dynamically bound `imageTintList` on `btnMicToggle` using `?attr/themeAccent` (unmuted) and `?attr/themeTextMuted` (muted) to match the visual system.
- **Unit Testing**:
  - Added `testMediaChunkJsonFormatCompliesWithMultimodalLiveApi` and `testAudioChunkJsonFormatCompliesWithMultimodalLiveApi` to `GeminiLiveSessionTest.kt`.
  - 135 unit tests passing (`gradle :app:testDebugUnitTest`).

## Phase 4: Persistence Hardening & CameraX 1.5 Modernization
- **Room Persistence Hardening (`data/memory/MemoryDatabase.kt`)**:
  - Enabled schema export (`exportSchema = true`) to prevent accidental schema divergence.
  - Removed broad destructive migration fallback on upgrade to permanently safeguard learned memory facts.
  - Added migration array scaffold and restricted destructive resets strictly to downgrade scenarios.
- **CameraX 1.5 Modernization (`ui/camera/CameraVisionActivity.kt`)**:
  - Replaced deprecated `setTargetResolution` with modern `ResolutionSelector` and `ResolutionStrategy`.
- **Validation**:
  - Added `MemoryDatabaseMigrationTest` ensuring persistence guarantees across future releases.

## Phase 3: Termux & Sandboxed Terminal Engine
- **CommandGuard Safety Engine (`terminal/CommandGuard.kt`)**:
  - Intercepts shell commands before execution; classifies into `Safe`, `RequiresConfirmation`, and `Blocked`.
  - Blocks destructive commands (root/system wiping, device block modification, fork bombs, internet pipe executions).
  - Sanitizes command logging, redacting API keys, passwords, and tokens.
- **Sandboxed Local Shell & Termux Bridge (`terminal/TerminalSession.kt`)**:
  - Runs commands in sandboxed `/system/bin/sh` with working directory set to application files.
  - Streams stdout/stderr with ANSI escape code stripping and timeout handling.
  - Bridges to Termux application via `com.termux.RUN_COMMAND` intent service if installed.
- **Terminal UI (`ui/terminal/TermuxActivity.kt`, `activity_termux.xml`)**:
  - Monospace scrollable console output adhering to Siya's design system.
  - Quick action chips (`ls -la`, `uptime`, `df -h`, `uname -a`, `whoami`, `env`).
  - "Ask Siya" AI terminal helper generating suggested shell commands using Gemini Rest Client.
- **Gemini Live Integration**:
  - Added `run_terminal` tool to Gemini Live WebSocket schema and conversation service dispatcher.
- **Navigation Entry Points**:
  - Added `btnChatTerminal` in Chat header and "Terminal & Shell Engine" row in Settings Section 2.

## Phase 2: Realistic Voice Biometrics (Honest, Robust, Measurable)

### Root Cause Analysis
1. **Model Readiness (`assets/model-spk` & `VoskModelManager.kt`)**:
   - The neural net weights `final.ext.raw` were missing from assets, causing `SpeakerModel` to fail loading. Previously, missing vector extraction was mapped directly to `NOT_OWNER` / rejection, locking out the owner.
   - **Fix**: Added `SpeakerModelStatus` (`READY`, `MISSING`, `CORRUPT`). If not `READY`, voice enrollment is cleanly disabled with an informative banner, and voice verification operates fail-open (`UNCERTAIN`) without blocking the owner.
2. **Permissive Scoring & Fixed Constants (`OwnerVoiceManager.kt`)**:
   - Previous logic used a single fixed threshold (0.70) across all users and scored using the minimum distance over enrolled vectors (the most permissive possible metric). False marketing claimed "100% blocks strangers".
   - **Fix**: Removed "100% blocks strangers" claim everywhere and replaced with a realistic disclaimer. Implemented combined scoring: $d = 0.5 \times d_{\text{centroid}} + 0.5 \times d_{\text{top3}}$. Calibrated threshold $T = \text{clamp}(\mu + 3\sigma, 0.50, 0.75)$ calculated from each user's enrolled samples.
3. **Premature Verification (< 300ms) & Long Lease (`JarvisConversationService.kt`)**:
   - Previous code attempted extraction on 300ms audio (which produces noisy x-vectors) and had an active lease of 120 seconds that skipped verification completely for anyone speaking during that time.
   - **Fix**: Required $\ge 1.5$s of voiced audio before taking an early decision. Short utterances (< 1.5s) yield `UNCERTAIN`. Reduced lease to $\le 10$ seconds, permitted strictly for short utterances (< 1.5s) with NO tool calls. Sensitive actions never use the lease.
4. **Gemini Live Stranger Audio Leak (`JarvisConversationService.kt`)**:
   - Live microphone chunks were streamed immediately to `liveWebSocket?.sendAudioChunk()` before the speaker verification verdict, leaking stranger voices to Gemini.
   - **Fix**: Unverified audio chunks are held in `preVerificationAudioQueue` until speaker verdict. If `OWNER` or `UNCERTAIN`, audio is flushed to Gemini; if `NOT_OWNER`, the buffer is dropped immediately.
5. **Biometric Security & Backups (`VoiceSecurityStorage.kt`, `AndroidManifest.xml`)**:
   - Voiceprints previously resided in plain SharedPreferences (`jarvis_prefs`) and `allowBackup="true"` allowed extraction into cloud backups.
   - **Fix**: Migrated voiceprints to `EncryptedSharedPreferences` (`siya_voice_secure`) backed by Android Keystore (AES-256-GCM / AES-256-SIV). Set `android:allowBackup="false"` and excluded voice storage files in `backup_rules.xml` and `data_extraction_rules.xml`.

### Key Implementation Details
- **2A. Model Readiness**:
  - `SpeakerModelStatus` enum (`READY`, `MISSING`, `CORRUPT`) exposed via `StateFlow`.
  - `verify()` returns 3 states: `OWNER`, `NOT_OWNER`, `UNCERTAIN`. Missing vector maps to `UNCERTAIN`.
- **2B. Enrollment v2 (`VoiceEnrollmentActivity.kt`, `activity_voice_enrollment.xml`)**:
  - 5 natural speech prompts mixing Hindi/Hinglish, English, and numbers.
  - Quality gate per sample: $\ge 3.0$s voiced speech, estimated SNR $\ge 15$ dB (voiced frames vs first 300ms noise floor), clipping $< 1\%$ (samples $\ge 32000$). Plain error feedback ("too quiet", "too noisy", "too short", "audio clipped").
  - Consistency check from sample 3: pairwise cosine distance check rejects outliers ($> \mu + 2.5\sigma$ or $> 0.55$) and displays "Voice profile quality: Good / Fair / Weak".
  - Personal calibration on 5 samples: computes genuine distance mean and standard deviation, sets $T = \text{clamp}(\mu + 3\sigma, 0.50, 0.75)$. Strict = $T - 0.06$, Relaxed = $T + 0.06$.
  - Explicit biometric consent dialog before first sample: explains numeric vectors only, audio not saved, never leaves phone, optional; "I Agree" / "Not Now" with local timestamp recorded.
- **2C. Verification v2 (`JarvisConversationService.kt`, `OwnerVoiceManager.kt`)**:
  - Accumulated voiced audio: decision requires $\ge 1.5$s voiced speech (75 frames).
  - Scoring: $d = 0.5 \times d_{\text{centroid}} + 0.5 \times d_{\text{top3}}$.
  - Decision: $d \le T \implies$ `OWNER`; $d \ge T + 0.08 \implies$ `NOT_OWNER`; otherwise $\implies$ `UNCERTAIN`.
  - Policy: `OWNER` proceeds normally; `NOT_OWNER` ignored (no reply, audio flushed, tools blocked); `UNCERTAIN` allows chat answer but NO tool action.
  - Lease: $\le 10$s after verified `OWNER`, and only for utterances $< 1.5$s with NO tool; sensitive actions never use lease.
  - Sensitive action protection: Calling, SMS, WhatsApp, deletion, payments, app installation, system settings require current turn `OWNER` verdict ($\ge 1.5$s speech).
  - Adaptive profile: on `OWNER` with $d \le T - 0.10$, vector added to ring buffer of max 20 samples, centroid recomputed; switch "Improve voice profile automatically" and "Reset adaptation" button.
  - Non-owner speech: switch "Save guest speech" (default OFF); stranger speech is suppressed and not saved to chat history unless switch is explicitly enabled.
- **2D. Test My Voice**:
  - Live audio test evaluates scoring v2; displays verdict (Owner, Not Owner, Uncertain) and match strength bar ($d \le T - 0.15 \to 100\%$, $d = T \to 60\%$, $d = T + 0.08 \to 0\%$).
  - Displays hint: "Ask someone else to try too".
  - Stores last 20 test results in encrypted storage with a "Clear History" button.
  - Suggests "Relaxed" sensitivity if rejected $> 2$ times in last 5 tests.
- **2E. Storage & Security**:
  - Encrypted storage `siya_voice_secure`.
  - One-time migration moves plain keys and clears unencrypted preferences.
  - "Delete voice profile" permanently wipes vectors, adaptive samples, test history, and consent record.
- **2F. Unit Tests (`OwnerVoiceManagerTest.kt`)**:
  - 11 comprehensive unit tests covering cosine distance, centroid averaging, combined $0.5 \times \text{centroid} + 0.5 \times \text{top3}$ scoring, boundary decisions (`OWNER`, `NOT_OWNER`, `UNCERTAIN`), piecewise match strength mapping, threshold calibration clamps ($[0.50, 0.75]$), consistency outlier detection, quality gate on synthetic PCM (short, clipped, noisy, clear speech), and backward compatibility.
  - 87 out of 87 unit tests passing.

---

## Phase 1: Code Always Comes As A ZIP, One Tap Download
- Download ZIP button next to Copy in assistant message action row for code blocks.
- Real pure-Kotlin `CodeZipBuilder` streaming UTF-8 zip archive to `Downloads/Siya Ai/<name>.zip`.
- Inline action row: Open (`ACTION_VIEW`), Share (FileProvider).
- Live tool `generate_code` returns `{success: true, files: [...], zip_ready: true}` with feminine grammar voice announcement.

---

## Feature: Android Native Text-to-Speech (TTS) System

### Overview
Integrated complete on-device Android `TextToSpeech` vocal system adhering strictly to Siya's female vocal identity, Hindi/English/Hinglish multilingual support, and non-intrusive markdown/code block speech sanitization.

### Architecture & Implementation Details
1. **`AndroidTtsManager` (`audio/AndroidTtsManager.kt`)**:
   - Singleton manager wrapping Android `TextToSpeech` with thread-safe lifecycle and error recovery.
   - **Vocal Identity Enforcement**: Filters available TTS engines and system voices for female voices (`female`, `fem`, `gender=female`, `hi-in-x-hie`, `en-in-x-end`, `en-us-x-sfg`). Automatically sets feminine pitch (default `1.15f`) and speech rate (`1.0f`), preventing unnatural male or deep robot voices.
   - **Audio Sanitization (`sanitizeTextForSpeech`)**: Strips fenced code blocks (` ```...``` `) and announces `"(Code included in chat)"`, cleans inline backticks without breaking snake_case variables, replaces URLs with `"(link)"`, and strips markdown headers/bold/lists while preserving Hindi and Hinglish script.
   - **Speech Control**: Reactive `isSpeaking` and `currentUtteranceId` StateFlows; `speak()`, `stop()`, and `testVoice()` methods.

2. **Preferences & Persistence (`AppPreferences.kt`)**:
   - Added `KEY_TTS_AUTO_SPEAK`, `KEY_TTS_SPEECH_RATE`, `KEY_TTS_PITCH`, `KEY_TTS_VOICE_NAME`.
   - Accessors: `isTtsAutoSpeakEnabled`, `ttsSpeechRate`, `ttsPitch`, `ttsVoiceName`.

3. **Chat Message Integration (`JarvisChatAdapter.kt` & `ChatFragment.kt`)**:
   - Replaced placeholder toast on assistant message play button (`btnAssistantPlay`) with real TTS playback, dynamic play/stop icon swapping (`ic_tts_play` / `ic_tts_stop`), and completion listener resets.
   - Added header toggle button (`btnChatTtsToggle`) in `fragment_chat.xml` for instant 1-tap toggling of auto-speak.
   - Added automated readout hook in `observeChatTurns()`: when auto-speak is enabled, newly completed assistant turns are read aloud automatically.
   - Clean interruption: sending a new message, leaving chat, or manual conversation interruption immediately calls `AndroidTtsManager.stop()`.

4. **Settings & Customization (`SettingsFragment.kt` & `TtsSettingsBottomSheet.kt`)**:
   - Added dedicated `Text-to-Speech (TTS)` row in `fragment_settings.xml` under Voice & Neural Intelligence with quick toggle switch.
   - Custom `TtsSettingsBottomSheet`:
     - Auto-speak chat toggle switch.
     - Speed rate selector: Slow (0.8x), Normal (1.0x), Fast (1.2x), Quick (1.5x).
     - Pitch tuning: Warm (0.95x), Feminine Default (1.15x), Bright (1.30x).
     - Engine & voice diagnostics display.
     - "Test Siya Voice" and "Stop" buttons.

5. **Unit Tests & Verification (`AndroidTtsManagerTest.kt`)**:
   - 8 unit tests covering code block removal, inline code handling, URL stripping, markdown list/header removal, snake_case preservation, Hindi/Hinglish text handling, feminine pitch bounds, and default speech rates.
   - All 95 unit tests in the project pass cleanly (`gradle :app:testDebugUnitTest`).

