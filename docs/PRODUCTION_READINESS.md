# Production readiness and acceptance checklist

## Release decision

**Status: NOT YET APPROVED FOR PRODUCTION DISTRIBUTION.**

The project has a native Android application and a real task-execution path, but a passing CI run is not proof of safe operation on a physical device. Production approval remains blocked on release signing setup, target-device acceptance tests, external provider configuration and review of the missing `List.md` requirements source.

## Implemented in the repository

| Area | Current state |
|---|---|
| Native application | Kotlin + Jetpack Compose, onboarding, Home, History and Settings screens |
| Android platform | min SDK 26, compile/target SDK 36; Android Gradle Plugin 8.13.2 and Gradle 8.13 |
| LLM configuration | Five sequential slots with independent key, HTTPS URL, model ID, enabled state and timeout |
| Provider protocol | One OpenAI-compatible chat-completions adapter; other wire protocols need dedicated adapters |
| Action planning | Strict JSON parser, action allowlist, type/field validation, bounded coordinates and payloads |
| Execution | Local AccessibilityService, installed-app discovery, Android global actions, gestures and text operations |
| Orchestration | Planning, validation, approval, execution, verification, bounded replanning and cancellation |
| Safety | Sensitive screen checkpoints, high-impact action confirmation, sensitive-field typing/copy/paste restrictions |
| Voice | Android SpeechRecognizer, native TTS, Admin Voice device-auth gate and passphrase gate |
| Memory | Room task logs and verified local workflow reuse with confidence and app context |
| CI | Unit tests, lint, debug build and release build; signed release/AAB steps are conditional on private keystore secrets |
| Secrets | No real provider keys or release keystore are committed; provider values are entered in encrypted device settings |

## Explicitly not complete

- The repository does not contain the `List.md` file named as the primary requirements source in the uploaded build command. The current checklist is therefore based on the uploaded build command, existing repository code and the available SuperiOA architecture document, not on a nonexistent List.md.
- No physical-device acceptance test has been completed in this work session.
- No release keystore or GitHub signing secrets were supplied/configured.
- No OCR/vision provider, web-search provider, browser DOM bridge or full third-party app skill catalogue is implemented.
- Admin Voice is not an offline wake-word engine and is not guaranteed to run continuously 24/7.
- The app has 22 generic Android action types; it does not implement thousands of individual app integrations.
- No Supabase client, remote workflow sync or cloud account backend is enabled.
- Crash analytics, production monitoring, formal privacy-policy/store assets and Play Console review are still outstanding.

## Required physical-device acceptance tests

Run these on the actual supported phone model(s), including the target Android/OEM version. Record device model, Android version, build SHA, test date and pass/fail evidence without including secrets or private screen content.

| Test | Expected result |
|---|---|
| Fresh install and first launch | No crash; onboarding disclosure is readable; app does not silently enable Accessibility |
| Accessibility enable/disable | User can enable/disable in Android Settings; app reports unavailable cleanly when disabled |
| Configure one valid LLM engine | Settings save; request succeeds; correct model and endpoint are used |
| Invalid key / unavailable provider | Failure is actionable; failover moves to the next configured engine; no secret appears in logs |
| Malformed model JSON | Entire plan is rejected; no partial action executes |
| Unknown action / field / out-of-bounds coordinates | Plan is rejected safely |
| Completion without final VERIFY | Plan is rejected; task cannot be marked complete |
| Typed task | Supported action runs locally and final result is verified |
| Voice task | Speech is transcribed and starts only when local execution and an LLM engine are ready |
| Stop during planning/action/approval | Task is cancelled; no later actions continue |
| Send/delete/publish/payment control | User approval appears before the side effect |
| OTP/password/biometric/CAPTCHA | App pauses; it does not attempt to bypass the checkpoint or type/copy/paste sensitive input |
| Workflow reuse | Only matching, sufficiently confident workflows are replayed; verification still runs |
| Clear workflow memory/history | Local records are deleted and UI reflects the result |
| Admin Voice wrong phrase | No command is executed |
| Admin Voice start/stop | Device authentication required; ongoing notification visible; Stop cancels voice listening and active task |
| TTS startup and task result | Startup and completion speech audible; disabled setting remains silent |
| Process death / battery optimization | Service stops or recovers safely; no false claim of guaranteed always-on operation |
| Android 16 edge-to-edge | Content is visible and controls are not obscured by status/navigation bars |
| Release upgrade | Signed build updates prior signed build with same app ID and upload key without losing intended local data |

## Production release gates

1. Supply or add `List.md` to the repository and map every requirement to an implemented file, test or explicit blocker.
2. Configure private release keystore secrets using [RELEASE.md](RELEASE.md).
3. Wait for the latest branch CI to pass tests, lint, debug APK and release APK builds.
4. Install the signed release build on the target phone and complete the acceptance table above.
5. Review the privacy and data-transfer model, accessibility-service disclosure, store declarations and applicable Play policies.
6. Review dependency vulnerabilities and update deprecated dependencies in a separately tested change.
7. Publish only after a human release review. CI does not publish to Play Store.

## Bounded execution policy

- Maximum 100 planning rounds.
- Maximum 500 actions per task.
- Maximum task duration 10 minutes.
- Maximum 15 seconds per Android action.
- Maximum two retries, and only for a failed read-only VERIFY action.
- Failed taps, typing, sending, deleting and other side effects are not blindly retried.
