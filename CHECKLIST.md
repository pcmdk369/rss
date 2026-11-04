# Requirements and implementation checklist

## Source basis

The uploaded build command requires `List.md` as the primary source of truth, but `List.md` was not found in the repository or available files. This checklist therefore maps the requirements visible in the uploaded `SuperiOA_Agent_Build_Command.txt`, the available SuperiOA architecture document and the existing repository. It is not a substitute for a complete List.md traceability audit.

Status meanings:
- **Implemented** — code exists in the repository.
- **Partial** — only a limited adapter or scaffold exists.
- **Not implemented** — no working implementation exists.
- **Device test required** — CI cannot verify actual Android/OEM behavior.
- **Blocked** — needs user-supplied source, secret, hardware, account or external approval.

| Requirement | Status | Evidence / notes |
|---|---|---|
| Native Android application and navigable UI | Implemented | Kotlin, Compose, onboarding, Home, History, Settings |
| Central Master Agent / Task Orchestrator | Implemented; device test required | `orchestrator/Orchestrator.kt` |
| Five sequential provider slots | Implemented | `llm/Failover.kt`, `llm/SettingsStore.kt`; five slots are not five parallel agents |
| Per-engine key, HTTPS base URL, model ID, enabled state and timeout | Implemented | Settings UI and encrypted local preferences |
| Provider-specific wire formats | Partial | One OpenAI-compatible chat-completions adapter; other formats require an explicit adapter |
| Strict action schema and allowlist | Implemented; unit tested | `action/Action.kt`; unknown root/action fields, mistyped fields and invalid bounds rejected |
| Generic Universal Action Engine | Implemented; device test required | 22 generic action types in `exec/ActionEngine.kt` |
| Android AccessibilityService | Implemented; device test required | User-enabled service in `exec/SuperiAccessibilityService.kt` |
| Installed-app discovery | Implemented; device test required | PackageManager-based launcher discovery |
| High-impact action confirmation | Implemented; unit tested | Target and visible-screen checks in `security/SecurityGate.kt` |
| Authentication / CAPTCHA / OTP checkpoint | Partial | Text-triggered pause and manual user handoff; OCR/vision fallback is not implemented |
| Stop / cancellation | Implemented; device test required | Home Stop and Admin Voice stop cancel active task |
| Verification before completion | Implemented in validator and planner contract; unit tested | `done=true` requires final VERIFY in the same plan |
| Bounded execution and retry safety | Implemented | 100 rounds, 500 actions, 10-minute task budget, 15-second action timeout; only failed VERIFY may be retried |
| Workflow memory and confidence | Implemented; unit tested | Room, app context, confidence threshold, clear controls |
| Secret redaction | Implemented; unit tested | `security/Redactor.kt`; sensitive input fields also block typing/copy/paste |
| LLM API key storage | Implemented | Android Keystore-backed encrypted preferences; still not a substitute for server-side proxying |
| Home STT | Implemented; device test required | Android SpeechRecognizer |
| Android TTS | Implemented; device test required | Shared TTS engine, initialization queue and playback diagnostics |
| Admin Voice | Partial; device test required | Device authentication + enrolled replayable passphrase + microphone foreground service; not a true offline wake word |
| Browser / Chrome | Partial | Opens HTTPS URLs and operates exposed accessibility UI; no DOM bridge |
| OCR / vision | Not implemented | No working OCR/vision provider |
| Web search | Not implemented | No working search provider |
| App-specific integrations across third-party apps | Partial | Generic UI actions only; each app's UI and policy may limit automation |
| Optional Supabase / remote sync | Not integrated | Local-first; no Supabase client or cloud sync enabled |
| API 36 / Android 16 target | Implemented in build configuration; CI verification pending | `app/build.gradle.kts` |
| Debug APK CI | Implemented | GitHub Actions artifact |
| Release APK build | Implemented as unsigned validation unless signing secrets are configured | `docs/RELEASE.md` |
| Signed release APK + Play AAB | Conditional / blocked | Requires private keystore and four GitHub Actions secrets |
| Physical-device acceptance tests | Blocked | Follow `docs/PRODUCTION_READINESS.md` |
| Full List.md traceability | Blocked | Root `List.md` missing |

## Important capability boundary

SuperiOA exposes 22 generic Android action types. It does not contain thousands of independently implemented app integrations. Actual execution depends on the visible UI, AccessibilityService support, explicit user permissions and any app-specific adapters added later.

## Verification

The authoritative current status is the latest run on the `production-readiness` branch: [GitHub Actions](https://github.com/pcmdk369/rss/actions). Do not infer success from an earlier run. A successful CI run verifies only the tasks that CI executes; it does not replace the physical-device tests.
