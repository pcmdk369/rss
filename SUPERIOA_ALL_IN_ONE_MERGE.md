# SuperiOA All-in-One Repository Integration

This repository is the canonical combined project for the Android app, planner core, web dashboard, Node service, and Supabase Edge Function.

## Build Android APK
GitHub Actions -> **Build SuperiOA APK and checks** -> download **SuperiOA-debug-apk** from a successful run. APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Project layout
- `app/`: Android application, local action engine, accessibility service, orchestration, settings and voice UI.
- `core/`: reusable Kotlin action validation, LLM coordination and workflow memory with unit tests.
- `server/`, `web/`: optional web console/service and tests.
- `supabase/functions/agent-execute/`: server-side planner and request validation.
- `supabase/migrations/`: database audit and rate-limit migrations.

## Secrets
Never commit real keys, passwords, session secrets, service-role keys or signing keys. Keep provider API keys in Supabase Edge Function secrets (or the trusted backend's deployment environment). Android clients must call the authenticated server endpoint; do not bake provider keys into APK BuildConfig, resources, or source. `.env.example` contains placeholders only.

## Safety / device execution
The LLM proposes structured plans only. Android validates actions and executes locally through explicit Android permissions. Keep approval gates for sensitive actions and let users stop an active workflow. Accessibility permission must be enabled by the device owner.

## CI validation
The workflow runs Kotlin core tests, Android unit tests, APK build, Node syntax/tests/web build, and Supabase function checks. A successful workflow proves CI build/test completion, not that every device/OEM interaction has been manually tested.
