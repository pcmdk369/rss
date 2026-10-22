# SuperiOA (MVP)

Android task-orchestration assistant by PCMDK369: an LLM **plans** (strict JSON actions), the app **validates, gates and executes** locally through Android AccessibilityService, then **verifies**.

Kotlin + Jetpack Compose, Room, EncryptedSharedPreferences, OkHttp. minSdk 26, target 34.

> Status: source written and statically reviewed only. It has **not** been compiled or run on a device by the author's tooling. See `CHECKLIST.md`.

## Get the APK (no Android Studio needed)
1. Create a GitHub repository and push this folder to the `main` branch.
2. Open the repo's **Actions** tab, **Build APK**, and wait for it to finish (or press *Run workflow*).
3. Download the artifact **SuperiOA-debug-apk**, unzip, copy `app-debug.apk` to your phone and install it (allow "install unknown apps").
The workflow also runs the unit tests; failures are uploaded as `test-reports`.

## Build locally
Install Android Studio (JDK 17 + SDK 34), open this folder, let it sync (it creates the Gradle wrapper), then run **Build > Build APK(s)**.
Command line with Gradle 8.9 installed: `gradle :app:testDebugUnitTest :app:assembleDebug`
Output: `app/build/outputs/apk/debug/app-debug.apk`.

## First run
1. Open the app, read the disclosure, tap **Open Accessibility settings**, enable **SuperiOA**.
2. **Settings > LLM engines**: enable at least one engine and fill in key, `https://` base URL and model ID (OpenAI-style `/chat/completions`). Engines 2-5 are fallbacks tried in order.
3. **Home**: type or dictate a task, press **Run**. **Stop** cancels immediately. Approval dialogs appear for CAPTCHA/OTP/password/payment screens and high-impact taps.

## Architecture
```
Home UI -> Orchestrator -> FailoverManager (engines 1..5, sequential) -> LLM returns JSON plan
        -> ActionValidator (strict schema) -> SecurityGate -> ActionEngine -> AccessibilityService
        -> VERIFY / replan (max 8 rounds, 60 actions, 2 retries) -> Workflow memory (Room) + TTS
```
- Actions: OPEN_APP, BACK, HOME, RECENTS, TAP, TYPE, SCROLL, SWIPE, COPY, PASTE, READ_UI, WAIT, VERIFY, RETRY, ABORT, REQUEST_USER.
- Failover happens only on planning calls (no side effects), so a timeout never repeats a tap or send.
- Workflows are saved only after a verified success with no security checkpoint, reused only when the app context matches and confidence >= 0.70 (0.40-0.70 asks first).

## Configuration names
See `.env.example` (`LLM_ENGINE_1..5_API_KEY/BASE_URL/MODEL_ID`). They map to the Settings fields; nothing is hard-coded and no keys are committed.

## Tests
`app/src/test`: action validation, failover order/retries/cancellation, secret redaction, workflow confidence.
