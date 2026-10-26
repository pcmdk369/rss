# SuperiOA (MVP)

Android task-orchestration assistant by PCMDK369: an LLM **plans** (strict JSON actions), the app **validates, gates and executes** locally through Android AccessibilityService, then **verifies**.

Kotlin + Jetpack Compose, Room, EncryptedSharedPreferences, OkHttp. minSdk 26, target 34.

> Status: source written and statically reviewed only. It has **not** been compiled or run on a device by the author's tooling. See `CHECKLIST.md`.

## Get the APK (no Android Studio needed)
1. Create a GitHub repository and push this folder to the `main` branch.
2. Open the repo's **Actions** tab, **Build APK**, and wait for it to finish (or press *Run workflow*).
3. Download the artifact **SuperiOA-debug-apk** from the newest successful run, unzip it, and install `app-debug.apk` (allow "install unknown apps").

### Android install, Accessibility and LLM troubleshooting
- **“Update failed” / “App not installed”**: GitHub-hosted runners may generate a different debug signing key for each clean runner. Android will reject an APK update if its signing certificate differs. If you do not need the old app's local data, uninstall the old SuperiOA first, then install the APK from the newest successful run. Uninstalling deletes local app data.
- **Use the newest artifact only**: open the latest successful run under Actions, download `SuperiOA-debug-apk`, extract the ZIP, and install `app-debug.apk`. Do not install an APK left over from an earlier run.
- **“Restricted setting” for Accessibility**: this is enforced by Android for some sideloaded apps. Open SuperiOA App info and look for Android's “Allow restricted settings” option, sometimes in the overflow menu. If your device does not show it, use ADB from a trusted computer: enable Developer options and USB debugging, connect the phone, verify it with `adb devices`, then run `adb install -r app-debug.apk` from the folder containing the APK. This installs the app via ADB but does not itself grant Accessibility; enable SuperiOA manually in Android Accessibility settings and accept the system consent. Never enable USB debugging on a computer you do not trust.
- **LLM is configured on the phone**: open SuperiOA → Settings and enable an engine. Supply its API key, HTTPS base URL, and exact model ID. The endpoint must support OpenAI-compatible `POST {base URL}/chat/completions`. A server-side environment variable is not automatically available to an already-installed Android app. Do not paste API keys into issues, screenshots, or public repository files.
- **Stable updates for future builds**: configure a private, persistent signing keystore via GitHub Actions secrets and sign all distributed builds with that same key. Do not commit a keystore or its password to the repository. Until that is configured, treat each CI debug APK as a fresh install rather than a guaranteed in-place update.
The workflow also runs the unit tests; failures are uploaded as `test-reports`.

## Build locally
Install Android Studio (JDK 17 + SDK 34), open this folder, let it sync (it creates the Gradle wrapper), then run **Build > Build APK(s)**.
Command line with Gradle 8.9 installed: `gradle :app:testDebugUnitTest :app:assembleDebug`
Output: `app/build/outputs/apk/debug/app-debug.apk`.

## First run
1. Open the app, read the disclosure, tap **Open Accessibility settings**, enable **SuperiOA**.
2. **Settings > LLM engines**: enable at least one engine and fill in key, `https://` base URL and model ID (OpenAI-style `/chat/completions`). Engines 2-5 are fallbacks tried in order.
3. **Home**: type or dictate a task, press **Run**. **Stop** cancels immediately. Approval dialogs appear for CAPTCHA/OTP/password/payment screens and high-impact UI controls.

## Admin-only background voice mode
- Main screen includes **Admin Voice Mode**. Enroll a private multi-word passphrase after Android device authentication; the passphrase hash is stored in Keystore-backed encrypted preferences.
- To start listening, grant microphone and notification permissions and authenticate with Android biometric/device credential. The app starts a microphone foreground service with an ongoing notification and an explicit Stop action.
- Each spoken instruction must begin with the enrolled passphrase; the service strips the passphrase before passing the command to the existing Orchestrator. The result is spoken through Android TTS.
- A partial wake lock keeps the CPU available while the service is active and can increase battery use.
- **Limits:** Android may stop the service, the user can force-stop the app, reboot requires a fresh manual authentication/start, and recognition availability/network depends on the configured Android speech service. This is not a guaranteed 24/7 SLA.
- A spoken passphrase can be recorded/replayed and is not voice biometrics. Keep the phone secured; do not use a banking PIN, account password, or other existing secret as the phrase. Audio may be processed by Android's configured speech-recognition provider. Sensitive tasks still require the app's approval/security flow.

## Architecture
```
Home UI -> Orchestrator -> FailoverManager (engines 1..5, sequential) -> LLM returns JSON plan
        -> ActionValidator (strict schema) -> SecurityGate -> ActionEngine -> AccessibilityService
        -> VERIFY / replan (max 5,000 rounds, 50,000 actions per task, 12 actions per plan, 2 retries per action) -> Workflow memory (Room) + TTS
```
- Actions: OPEN_APP, BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, TAP, LONG_PRESS, TOGGLE, TYPE, CLEAR_TEXT, SELECT_ALL, SCROLL, SWIPE, COPY, PASTE, READ_UI, WAIT, VERIFY, RETRY, ABORT, REQUEST_USER.
- Failover happens only on planning calls (no side effects), so a timeout never repeats a tap or send.
- Workflows are saved only after a verified success with no security checkpoint, reused only when the app context matches and confidence >= 0.70 (0.40-0.70 asks first).

## Configuration names
See `.env.example` (`LLM_ENGINE_1..5_API_KEY/BASE_URL/MODEL_ID`). They map to the Settings fields; nothing is hard-coded and no keys are committed.

## Tests
`app/src/test`: action validation, failover order/retries/cancellation, secret redaction, workflow confidence.
