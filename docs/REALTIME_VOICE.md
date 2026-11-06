# SuperiOA real-time voice architecture

## What changes

The Home screen uses a persistent full-duplex audio session instead of Android's one-shot SpeechRecognizer and native TextToSpeech chain.

- Microphone: Native WebRTC audio capture with hardware acoustic echo cancellation and noise suppression.
- Transport: WebRTC peer connection to the Realtime API.
- Input turn detection: server-side VAD.
- Output playback: Native WebRTC audio playout with platform-managed jitter buffering.
- Barge-in: server VAD interrupts the response; local playback is flushed when speech-start is received.
- Conversation: provider-managed session context during the active session.
- Device execution: Realtime function call execute_android_task hands a natural-language goal to the existing validated Android orchestrator.
- Result delivery: task result is returned as a function output so the voice model can speak the result.

## Credential flow

1. The app calls the Render gateway using its app-to-gateway token.
2. The gateway calls POST /v1/realtime/client_secrets using the server-side OPENAI_API_KEY.
3. The gateway returns only the short-lived Realtime client secret.
4. The app connects to the Realtime WebSocket with that short-lived credential.

The standard OpenAI API key is never stored in Android preferences or returned to the app.

## Deploy the gateway

The root render.yaml adds a separate Node web service named superioa-realtime-gateway in Singapore on Render Free. The Free plan can spin down while idle; expect a cold start before the first voice session.

1. Create/sync the Render Blueprint from the repository branch that contains render.yaml.
2. Add OPENAI_API_KEY and SUPERIOA_CLIENT_TOKEN as secret environment variables in Render. Generate the latter with openssl rand -hex 32.
3. Deploy the gateway and verify GET /health returns ok: true and configured: true.
4. In the app Settings, set the gateway's actual HTTPS URL and the same device access token.
5. Save and start a session.

Do not add secret values to GitHub, source code, or screenshots.

## Operational boundaries

- The Android app must remain in the foreground during this first implementation. A dedicated microphone foreground service is a separate lifecycle feature and is not claimed here.
- Realtime provider use is billable and depends on API access, model availability, and usage limits.
- The shared gateway bearer token is suitable only for a private owner-operated test. A public release needs user authentication, per-user quotas, token rotation, persistent rate limiting, and abuse monitoring.
- Audio is sent to the configured Realtime provider. Microphone permission and visible live-session state must remain clear.
- Android Accessibility actions still require the service to be enabled. The model cannot bypass the local validator or security gate.
- This code must pass Android CI and physical-device tests before it is treated as production-ready.

## Validation checklist

- [ ] Render health check is green and configured is true.
- [ ] App obtains a temporary client secret without exposing the provider key.
- [ ] WebRTC SDP negotiation succeeds, the data channel opens, and microphone capture starts only after permission and peer setup.
- [ ] User speech triggers server VAD and a spoken response.
- [ ] Assistant audio streams without waiting for a complete audio file.
- [ ] Speaking over the assistant triggers server VAD barge-in; WebRTC manages the audio track and playout.
- [ ] Device-task requests call the orchestrator and honor approvals.
- [ ] Stop ends microphone capture and releases AudioRecord/AudioTrack.
- [ ] Airplane mode, gateway 401/429/5xx, permission denial, and audio initialization failures show recoverable errors.
- [ ] Physical-device test on the target Itel A80 confirms 24 kHz recording and playback.