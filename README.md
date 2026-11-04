# SuperiOA — Android Intelligent Operation Agent

SuperiOA by PCMDK369 is a native Android task-orchestration assistant. The LLM plans; the Android app validates and gates every action; the local AccessibilityService performs supported UI operations; the orchestrator verifies outcomes and stops safely when it cannot confirm success.

## Platform and build baseline

- Kotlin + Jetpack Compose
- Android min SDK 26, compile SDK 36, target SDK 36
- JDK 17, Android Gradle Plugin 8.13.2, Gradle 8.13
- Room for local task/workflow history
- Android Keystore-backed encrypted preferences for provider settings
- OkHttp + HTTPS for configured LLM requests

Google Play requires new apps and app updates to target Android 16 / API 36 from August 31, 2026. See the official [target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878).

## Get a test APK

1. Open [GitHub Actions](https://github.com/pcmdk369/rss/actions).
2. Open the latest successful **Android CI and Release Validation** run.
3. Download **SuperiOA-debug-apk** and install `superioa-debug.apk`.
4. Debug builds use application ID `com.pcmdk369.superioa.debug`; release builds use `com.pcmdk369.superioa`.

The workflow runs unit tests, Android lint, a debug build and a release build. When a private release keystore is configured in GitHub Actions, it also signs the release APK and creates a Play Store AAB. Without those secrets, the release APK is intentionally unsigned and is only a build-validation artifact.

## Build locally

Install JDK 17, Android SDK Platform 36 and Build Tools 36.0.0. Install Gradle 8.13, then run:

```sh
gradle --version
gradle --no-daemon :app:testDebugUnitTest
gradle --no-daemon :app:lintDebug
gradle --no-daemon :app:assembleDebug
gradle --no-daemon :app:assembleRelease
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

Unsigned release validation APK (unless signing variables are supplied): `app/build/outputs/apk/release/app-release-unsigned.apk`

See [release signing instructions](docs/RELEASE.md) before distributing a production build.

## First run and permissions

1. Open SuperiOA and read the onboarding disclosure.
2. Open Android Accessibility settings and explicitly enable SuperiOA. Android controls this permission; the app cannot enable it itself.
3. Open **Settings → LLM engines**.
4. Configure at least one of the five engine slots with an API key, HTTPS base URL, exact model ID and timeout. All provider fields start empty; the app does not silently assume a provider or model.
5. Enter a task on Home and press **Run**. Use **Stop** to cancel the active task. Sensitive actions and authentication checkpoints pause for user approval.

The configured endpoint must support OpenAI-compatible `POST {base URL}/chat/completions`. Providers using another request/response protocol need a dedicated adapter; the app does not pretend every API is compatible.

## Voice

### Home voice input
Home voice input uses Android's installed speech-recognition service to transcribe a command. When Accessibility and an LLM engine are ready, it starts the task automatically.

### Admin Voice
Admin Voice is an explicitly started microphone foreground service:

- Enroll a private multi-word passphrase after Android device authentication.
- Grant microphone and notification permissions.
- Authenticate with the device credential or supported biometric before starting.
- Keep the ongoing notification visible; use its **Stop listening** action or the Admin Voice screen to stop.
- Each recognized utterance must begin with the enrolled phrase. The phrase is a replayable passphrase, not voice biometrics.
- The service is not a guaranteed 24/7 wake-word service. Android, the speech-recognition provider, battery controls and force-stop behavior can interrupt it.
- Audio is processed by Android's configured speech-recognition provider. Keep the phone secured and never use an account password, PIN or banking secret as the phrase.

Admin Voice, Home tasks and completion announcements share one Android TTS engine. TTS requests made during asynchronous initialization are queued. Diagnostic errors use the Android log tag `SuperiOA-TTS`; inspect with `adb logcat -s SuperiOA-TTS`.

## Architecture

```
User text / voice
       ↓
Master Agent / LLM Coordinator
       ↓
Five sequential configured provider slots (not five parallel agents)
       ↓
Strict JSON action plan
       ↓
Action schema validation
       ↓
Security gate + explicit user approval where needed
       ↓
Universal Action Engine
       ↓
Android AccessibilityService / supported Android intents
       ↓
Visible UI verification
       ↓
Bounded re-planning / fail-stop
       ↓
Room workflow memory + task history + optional TTS
```

### Supported generic actions

`OPEN_APP`, `BACK`, `HOME`, `RECENTS`, `NOTIFICATIONS`, `QUICK_SETTINGS`, `TAP`, `LONG_PRESS`, `TOGGLE`, `TYPE`, `CLEAR_TEXT`, `SELECT_ALL`, `SCROLL`, `SWIPE`, `COPY`, `PASTE`, `READ_UI`, `WAIT`, `VERIFY`, `RETRY`, `ABORT`, `REQUEST_USER`.

The validator rejects unknown action types, unknown or mistyped fields, out-of-screen coordinates, oversized text, invalid URLs and plans that claim completion without ending in a successful `VERIFY`. The executor never runs model-generated code.

Task limits: 100 planning rounds, 500 executed actions and 10 minutes per task; each Android action has a 15-second timeout. Only a failed read-only `VERIFY` may be retried by the `RETRY` action. Failed taps, typing and other side effects are replanned rather than blindly repeated.

### Workflow memory

Only verified, non-sensitive successful workflows are eligible for reuse. Reuse is app-context-aware and confidence-based. The app stores normalized task goals, app context, action steps, success/failure counts and last-used time. It does not intentionally store passwords, OTPs, access tokens or screen dumps. Users can clear workflow memory and task history in Settings.

## Privacy and security boundaries

- Accessibility is disclosed and must be enabled by the user in Android Settings.
- Visible screen text needed to plan a user-started task is sent to the LLM endpoint the user configured. Obvious secrets are redacted first; password fields are masked and sensitive input fields are blocked from typing, copy and paste. Do not use the app on private screens unless you understand this data flow and trust the configured provider.
- API keys are stored in Android Keystore-backed encrypted preferences, but a key stored on a user-controlled device is not equivalent to a server-side secret. For long-lived credentials, use a trusted HTTPS proxy you operate.
- OTP, CAPTCHA, password, biometric and account-security checkpoints pause for the user. Payment, send, delete, publish and other high-impact controls require explicit confirmation.
- Stop cancels the active task. The Admin Voice stop action also cancels an active voice-started task.
- No Supabase client or remote workflow sync is enabled. The app remains local-first.
- No screenshot upload, OCR, arbitrary code execution or hidden background automation is implemented.

## Implemented versus not yet implemented

Implemented in the current codebase: Compose onboarding/Home/History/Settings UI, five sequential engine slots, one OpenAI-compatible LLM adapter, typed action validation, bounded orchestration, AccessibilityService execution, user approval checkpoints, local Room workflow memory, Android STT/TTS and authenticated Admin Voice startup.

Not yet implemented or not yet verified on a physical phone: OCR/vision, web-search provider, full browser DOM automation, app-specific integrations for every third-party app, a dedicated offline wake-word detector, external account sync, production crash analytics, and real-device compatibility testing across Android/OEM versions.

The app exposes 22 generic Android actions; it does not contain 15,000 separately implemented app integrations. Actual reach depends on what each target app exposes through Android accessibility and on any app-specific skill/API adapters that are added and tested.

## Quality checks

```sh
gradle --no-daemon :app:testDebugUnitTest
gradle --no-daemon :app:lintDebug
gradle --no-daemon :app:assembleDebug
gradle --no-daemon :app:assembleRelease
```

CI results and downloadable artifacts are published under [GitHub Actions](https://github.com/pcmdk369/rss/actions). A successful CI build proves compilation and automated checks only; it does not replace the physical-device acceptance tests listed in [docs/PRODUCTION_READINESS.md](docs/PRODUCTION_READINESS.md).
