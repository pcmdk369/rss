# SuperiOA Realtime Gateway

Small HTTPS gateway that keeps the long-lived OpenAI API key on Render and issues short-lived Realtime client secrets to the Android app.

## Deploy

1. In Render, create a Blueprint from this repository and select `render.yaml`.
2. Configure the two secret environment variables on the `superioa-realtime-gateway` service:
   - `OPENAI_API_KEY`: a server-side OpenAI API key with Realtime API access.
   - `SUPERIOA_CLIENT_TOKEN`: a long random secret (at least 32 random bytes, hex encoded).
3. Deploy and check `/health`. The response should show `configured: true`.
4. In SuperiOA → Settings → Realtime Voice Gateway, set:
   - Gateway URL: `https://superioa-realtime-gateway.onrender.com` (or the actual Render URL shown on the service).
   - Device access token: the exact `SUPERIOA_CLIENT_TOKEN` value.
5. Save and start a voice session.

Generate the device token locally, for example:

```bash
openssl rand -hex 32
```

Do not commit either secret or paste them into chat, issues, screenshots, or source files.

## API

`POST /v1/realtime/client-secret`

Headers:
- `Authorization: Bearer <SUPERIOA_CLIENT_TOKEN>`
- `Content-Type: application/json`

Returns a short-lived provider client secret with `Cache-Control: no-store`. The gateway does not persist the client secret or the provider key. Requests are rate limited per client IP.

`GET /health` returns service health and whether the required environment variables are present. It never returns secret values.

## Security notes

- The Android app receives only a temporary Realtime token; the long-lived OpenAI key remains in Render environment variables.
- The static app-to-gateway token is a lightweight single-owner gate, not full user authentication. Treat it as a secret and rotate it if exposed.
- The gateway currently has in-memory rate limiting. For public multi-user distribution, add account authentication, per-user quotas, persistent rate limiting, and abuse monitoring before launch.
- Render Free instances may sleep when idle, adding cold-start delay. A paid always-on instance is optional if low-latency startup is important.
- Realtime audio is processed by the configured provider. Provide a clear microphone indicator and obtain the user's permission before recording.
