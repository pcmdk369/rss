# List.md requirement traceability

This checklist maps the original root List.md requirements to the current RSS implementation. Status is based on repository inspection, not assumptions in the source document.

Status meanings:
- **Implemented** — code exists.
- **Partial** — limited implementation or interface only.
- **Deferred** — intentionally not enabled in the current product direction.
- **Not implemented** — no working integration.
- **Device test required** — CI cannot verify real Android/OEM behavior.

## 1. Integration inventory (all 18 List.md items)

| # | List.md component | Current implementation | Status / gap |
|---:|---|---|---|
| 1 | Supabase | No Supabase client or remote sync is wired. | **Deferred**. List.md calls this core/existing-stated; current project direction remains local-first. No project credentials, schema deployment or RLS verification was performed. |
| 2 | Five-engine LLM manager | Five independent slots; sequential fallback in configured numeric order; bounded retry/backoff; network/timeout/429/5xx retry; configuration errors mark an engine unhealthy. | **Implemented**. One OpenAI-compatible adapter only; non-compatible providers need adapters. |
| 3 | TTS | Shared Android TextToSpeech engine; async initialization queue; error diagnostics; setting to disable speech. | **Implemented**. Physical speaker/voice testing required. |
| 4 | Chrome / Browser Skill | Opens HTTPS URLs in Chrome and uses Android accessibility-visible UI; Chrome skill hints exist. | **Partial**. No browser DOM bridge, tab manager or stable cross-site automation layer. |
| 5 | Android AccessibilityService | User-enabled service reads accessible UI and executes supported actions. | **Implemented**. Physical-device permission and target-app tests required. |
| 6 | Universal Action Engine | 22 typed action kinds, strict schema, action-specific fields, bounds and timeouts. | **Implemented**. UI compatibility varies by app. |
| 7 | Master Agent / Task Orchestrator | One central orchestrator, agent profiles, skill hints, planning, validation, safety, execution, verification and recovery. | **Partial-to-implemented**. Dynamic hierarchical Sub-Agent / Pre-Sub-Agent task decomposition is not yet a full feature. |
| 8 | Local workflow memory | Room stores app context, action steps, success/failure counts, version and last-used time; confidence gates replay. | **Implemented**. Device migration/recovery tests still required. |
| 9 | STT | Android SpeechRecognizer for Home voice entry and Admin Voice. | **Implemented**. Android speech provider/language availability and real-device tests required. |
| 10 | OCR / screen perception | NotConfiguredOcr interface only. | **Not implemented**. |
| 11 | Web Search API | NotConfiguredWebSearch interface only. | **Not implemented**. Chrome browsing and web search remain separate. |
| 12 | Secure secret/config management | EncryptedSharedPreferences backed by Android Keystore; no real credentials committed; provider fields are entered by the user. | **Implemented with limitation**. Keys in a client APK/device can be extracted; use a trusted HTTPS proxy for long-lived keys. |
| 13 | Logging and usage/cost monitoring | Local task history, engine index, per-attempt error and latency in memory, redaction and task duration. | **Partial**. No persisted full attempt history, token-usage/cost calculation or analytics dashboard. |
| 14 | Vision / image understanding | No configured vision provider. | **Not implemented**. |
| 15 | Image generation | No image-generation integration in this Android agent. | **Out of current MVP scope**. |
| 16 | Official app/service APIs | No app-specific official service adapters. | **Not implemented**. Generic accessibility actions only. |
| 17 | Crash/error reporting | Local errors and task logs only. | **Not implemented** as a remote crash-reporting service. |
| 18 | Push notifications / background jobs | Admin Voice uses a user-started microphone foreground service and ongoing notification. | **Partial**. No remote job queue or guaranteed background task worker. |

## 2. Five-engine failover requirements

| List.md rule | Implementation |
|---|---|
| One logical LLM service, not five parallel agents | One FailoverManager; one provider call at a time |
| Engine 1 through Engine 5 in configured order | Orchestrator now passes no trace-based priority; FailoverManager defaults to engine index order |
| Retry network errors, timeouts, rate limits and selected server errors | OpenAI-compatible caller maps network errors, 408, 429 and 5xx to retryable errors |
| Invalid key/model/request treated as configuration failure | Non-retryable HTTP responses become EngineError.Config and the engine is skipped |
| Bounded retry and backoff | One retry per engine by default, 500 ms exponential backoff |
| Record success/failure and latency | Attempt objects contain engine, success, safe error and latency; task UI logs failures and records winning engine |
| Never leak keys | Redactor plus known-key redaction in failover diagnostics |
| Do not repeat device side effects after LLM timeout | LLM calls occur only in the planning phase; failed side-effect actions are replanned, not blindly replayed |
| Validate model response before execution | Strict ActionValidator before ActionEngine |

Provider compatibility is explicit: the current caller supports OpenAI-compatible POST {base URL}/chat/completions. Five slots do not imply five different vendor protocols are implemented.

## 3. Supabase decision

List.md labels Supabase as core/existing-stated and describes possible Auth, Database, Storage and RLS responsibilities. The current RSS repository does not contain an active Supabase client or deployed schema. The current project direction is local-first with no Supabase integration. This is a documented product decision/deviation, not a completed Supabase requirement. If the owner later re-enables cloud sync, implement it separately with user-scoped authentication, RLS and server-side privileged secrets; never put a service-role key in the APK.

## 4. Security, privacy and execution

- Screen text needed to plan a task is sent to the LLM endpoint selected by the user. Onboarding discloses this and requires the user to agree before continuing. No screenshot is uploaded by the current planner.
- Obvious secrets are redacted; password and sensitive verification input fields are masked from screen context and blocked from typing/copy/paste.
- CAPTCHA, OTP, password, biometric and sensitive-screen checkpoints pause for the user.
- Send/delete/publish/payment and other high-impact taps require confirmation based on target text and visible screen text, including coordinate-based taps.
- A completed plan must end with a VERIFY action in the same reply.
- RETRY can repeat only a previously failed read-only VERIFY; taps, typing and other side effects are not blindly repeated.
- Stop cancels the active task; Admin Voice stop also cancels a voice-started task.
- Execution is bounded to 100 planning rounds, 500 actions, 10 minutes per task and 15 seconds per Android action.
- Workflow memory is local and can be cleared by the user.

## 5. Tests and release readiness

Automated tests exist for action validation, five-engine ordering/retry/failover, secret redaction, workflow confidence and high-impact security gates. GitHub Actions is configured for unit tests, Android lint, debug APK and release APK validation. Signed release APK/AAB requires a private user-owned keystore configured as GitHub Actions secrets.

Physical-device acceptance tests, release signing setup, Play Console declarations and privacy/store review remain release gates. See [PRODUCTION_READINESS.md](docs/PRODUCTION_READINESS.md) and [RELEASE.md](docs/RELEASE.md).

## 6. Source and capability boundary

The source List.md and original build command are retained in the repository/library. The app exposes 22 generic Android action types; it does not implement thousands of app-specific operations. Real capabilities depend on the target app's accessibility tree, Android/OEM behavior and future tested skill/API adapters.
