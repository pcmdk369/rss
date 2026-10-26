# OpenJarvis → SuperiOA Integration Plan

Reference upstream: https://github.com/open-jarvis/OpenJarvis
Reviewed upstream architecture: https://github.com/open-jarvis/OpenJarvis/blob/main/docs/architecture/overview.md
Target app: https://github.com/pcmdk369/rss

## Integration rule

Do **not** replace the Android project with a wholesale copy of OpenJarvis. OpenJarvis is a Python-first local AI framework with a Rust extension, frontend, and desktop components; SuperiOA is a Kotlin Android app whose local Accessibility service executes device actions. Copying all upstream files into the Android repo would not make them run on Android and could break the APK build.

Port compatible design ideas into SuperiOA, preserving existing Android UI, Accessibility execution, approval/security gates, admin voice flow, and LLM failover. Keep upstream license and attribution with any source code actually copied. Prefer clean interfaces and independently testable adapters.

## Architecture mapping

| OpenJarvis primitive | SuperiOA integration target | Required behavior |
|---|---|---|
| Intelligence / model catalog | Existing LLM settings and failover manager | Model ID, provider, context limits, capability metadata; no embedded API keys |
| Engine interface | llm/ provider adapters | Common health, model listing where supported, request/stream API; fail over only before device-side effects |
| Agents / orchestrator | Existing orchestrator/Orchestrator.kt | Tool/action loop that emits strict validated Action objects, never direct UI side effects from the model |
| Tools / skills | Existing skills/ plus action/ | Registered, allowlisted tools with explicit input schemas and permission checks |
| Memory | Existing Room database under memory/ | Searchable task/workflow history; redact secrets; only reuse verified workflows under existing confidence/security rules |
| Learning / traces | New local trace records | Record model choice, duration, tool/action outcomes, verification and failure reason; never record passwords, OTPs, raw passphrases or API keys |
| Telemetry | Local diagnostics screen/log export | Latency, token/cost metadata when provided, fallback count, success/failure; opt-in for any remote upload |
| Scheduler / continuous agents | Android WorkManager for deferrable work; foreground service only for active microphone sessions | Respect Android background limits; no promise of guaranteed 24/7 execution |
| Security guardrails / sandbox | Existing SecurityGate, ActionValidator, Redactor | Keep approvals for sensitive/high-impact actions; do not add arbitrary shell/code execution to the Android action path |
| OpenAI-compatible API server | Optional separate backend project, not bundled into APK | Server-side credentials only; authenticated HTTPS; rate limits and audit logs |

## Implementation sequence

1. **Baseline and build gate**
   - Keep current main intact.
   - Run :app:testDebugUnitTest and :app:assembleDebug on a real Android-capable runner.
   - Fix compilation/test failures before adding new layers.
2. **Engine contracts**
   - Define a small Kotlin engine interface and adapters around existing provider caller/failover code.
   - Add deterministic unit tests for health/fallback/error normalization.
3. **Tool registry**
   - Register only existing, allowlisted SuperiOA skills/actions.
   - Validate tool arguments and route every device action through the current validator/security gate/executor.
4. **Trace and memory**
   - Add Room trace entities/migrations and redaction tests.
   - Record outcomes only after verification; keep secrets and audio out of traces.
5. **Local-first routing**
   - Add optional local inference adapter only where a compatible Android runtime/device model is available.
   - Prefer local inference when configured; use cloud providers only when explicitly enabled and reachable.
6. **Telemetry and scheduled tasks**
   - Add local metrics and opt-in export.
   - Use WorkManager for deferrable scheduled tasks; keep microphone listening in its explicit foreground service with user-visible notification.
7. **Validation**
   - Unit tests, APK build, emulator/device smoke test, permissions/background behavior test, and manual security review.
   - Do not call this production-ready until these checks pass.

## Acceptance criteria

- Existing SuperiOA Android app and device-action flow remain intact.
- Model/engine adapters are replaceable and unit-tested.
- LLM output cannot bypass action validation, approval gates, or Accessibility execution.
- Failover never replays a previously executed device action.
- Trace/memory data is redacted and stored locally by default.
- No credentials are committed to source control.
- APK build and tests are green, with evidence linked from the commit/PR.
- Android background restrictions and microphone permission disclosures remain explicit.

## Current status

This file documents the integration boundary and sequence only. It is **not** evidence that OpenJarvis code has been copied, that the Kotlin port has been implemented, or that the APK has built. Upstream source tree is large and Python/Rust/desktop-oriented; only compatible components should be ported deliberately.
