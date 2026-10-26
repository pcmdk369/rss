# Requirements checklist (mapped to List.md)

Legend: **Implemented** = code written; **Untested** = not compiled or run in the authoring environment (no Android SDK, no network). Run `gradle :app:testDebugUnitTest` to verify.

| # | List.md requirement | Status | Where |
|---|---|---|---|
| 2 | 5-engine sequential failover, per-engine key/URL/model/enabled/timeout | Implemented, untested | `llm/Failover.kt`, `llm/SettingsStore.kt`, Settings screen |
| 2 | Bounded retries + backoff; config errors mark engine unhealthy; no failover on cancel / security denial | Implemented, unit tests written | `FailoverManager`, `FailoverTest` |
| 2 | Shared OpenAI-compatible adapter; other wire formats need their own `EngineCaller` | Implemented (one adapter only) | `llm/OpenAiCompatibleCaller.kt` |
| 2 | Record which engine succeeded and why earlier ones failed | Implemented | `LlmResult.attempts`, shown in task log |
| 5 | Android AccessibilityService | Implemented, untested on device | `exec/SuperiAccessibilityService.kt` |
| 6 | Universal Action Engine, 18 actions, strict schema validation | Implemented, unit tests written; bounded task ceiling raised to 50,000 actions across up to 5,000 planning rounds, with 12 actions per model reply | `action/Action.kt`, `exec/ActionEngine.kt`, `orchestrator/Orchestrator.kt` |
| 7 | Master agent / Task Orchestrator, verification, replanning | Implemented, untested | `orchestrator/Orchestrator.kt` |
| 8 | Local workflow memory (version, context, confidence, last-used, clear) | Implemented, unit tests for confidence | `memory/*`, Settings screen |
| 3 | TTS (Android native) | Implemented | `voice/Speaker.kt` |
| 9 | STT | Implemented via system `RecognizerIntent` (Voice button) | `ui/Screens.kt` |
| 4 | Chrome / browser skill | Minimal: opens https URLs in Chrome and drives visible controls through accessibility; no DOM bridge or OCR fallback | `skills/Skills.kt`, `ActionEngine.openApp` |
| 12 | Secret management | Encrypted on-device storage; redaction in logs; no secrets in source | `SettingsStore`, `Redactor` |
| 13 | Logging | Task history (goal, status, engine, summary). Cost/usage estimates NOT implemented | `memory/Db.kt` |
| - | Security checkpoints (CAPTCHA/OTP/password/biometric/payment, high-impact taps), Stop button | Implemented, untested | `security/SecurityGate.kt`, Home screen |
| 1 | Supabase | **Not integrated.** Only an optional RLS schema is provided | `supabase/migrations/001_superioa_sync.sql` |
| 10 | OCR / vision | **Not implemented** (interface stub reports "not configured") | `skills/Providers.kt` |
| 11 | Web search API | **Not implemented** (interface stub) | `skills/Providers.kt` |
| 14-18 | Image generation, official service APIs, crash reporting, push, backend worker | Not implemented (optional in List.md) | - |
| - | `Human_Android_5000_Plus_Actions_Master_List.docx` | Not imported. The source catalogue contains 15,766 human-level actions; the APK currently exposes 18 generic executor actions, not 15,766 independently implemented integrations. Full coverage requires dynamic UI execution plus app/domain skills and capability-specific APIs where available | - |

## Known limitations
- Android 10+ restricts launching activities from the background; OPEN_APP works best while SuperiOA or the target flow is in the foreground.
- Apps that hide UI from accessibility (games, some banking apps, `FLAG_SECURE`) cannot be read.
- Screen text goes to the configured LLM provider (passwords excluded, obvious secrets redacted).
- Keys stored in the app are only as safe as the device. Use a backend proxy for long-lived keys.
- Release signing is not configured; the CI produces a **debug** APK (installable by sideloading).
