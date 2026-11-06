import express from "express";
import helmet from "helmet";
import crypto from "node:crypto";

const app = express();
app.disable("x-powered-by");
app.use(helmet());
app.use(express.json({ limit: "16kb" }));

const PORT = Number(process.env.PORT || 10000);
const OPENAI_API_KEY = process.env.OPENAI_API_KEY || "";
const CLIENT_TOKEN = process.env.SUPERIOA_CLIENT_TOKEN || "";
const MODEL = process.env.REALTIME_MODEL || "gpt-realtime";
const VOICE = process.env.REALTIME_VOICE || "marin";
const WINDOW_MS = 60_000;
const MAX_REQUESTS = 12;
const buckets = new Map();

function safeEqual(a, b) {
  const left = Buffer.from(a || "");
  const right = Buffer.from(b || "");
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}

function rateLimit(req, res, next) {
  const now = Date.now();
  const key = req.ip || req.socket.remoteAddress || "unknown";
  const current = buckets.get(key);
  if (!current || now - current.startedAt >= WINDOW_MS) {
    buckets.set(key, { startedAt: now, count: 1 });
    return next();
  }
  current.count += 1;
  if (current.count > MAX_REQUESTS) {
    return res.status(429).json({ error: "Too many session requests. Try again shortly." });
  }
  next();
}

app.get("/health", (_req, res) => {
  res.json({ ok: true, service: "superioa-realtime-gateway", configured: Boolean(OPENAI_API_KEY && CLIENT_TOKEN) });
});

app.post("/v1/realtime/client-secret", rateLimit, async (req, res) => {
  const auth = req.get("authorization") || "";
  const supplied = auth.startsWith("Bearer ") ? auth.slice(7) : "";
  if (!CLIENT_TOKEN || !safeEqual(supplied, CLIENT_TOKEN)) {
    return res.status(401).json({ error: "Unauthorized." });
  }
  if (!OPENAI_API_KEY) {
    return res.status(503).json({ error: "Realtime provider is not configured on the gateway." });
  }

  try {
    const response = await fetch("https://api.openai.com/v1/realtime/client_secrets", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${OPENAI_API_KEY}`,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        expires_after: { anchor: "created_at", seconds: 600 },
        session: {
          type: "realtime",
          model: MODEL,
          instructions: [
            "You are SuperiOA, a voice-first Android personal task agent.",
            "Speak naturally, warmly and concisely. Keep answers suitable for spoken conversation.",
            "When the user asks you to operate their Android device or complete a device task, call execute_android_task with a clear, concise goal.",
            "Never claim a device action succeeded until the Android executor returns a verified result.",
            "Never request, store, or type passwords, OTPs, payment credentials, or authentication secrets.",
            "Ask the user before sensitive, destructive, financial, or externally visible actions."
          ].join(" "),
          audio: {
            input: {
              format: { type: "audio/pcm", rate: 24000 },
              noise_reduction: { type: "near_field" },
              turn_detection: { type: "server_vad", threshold: 0.5, prefix_padding_ms: 300, silence_duration_ms: 550 }
            },
            output: {
              format: { type: "audio/pcm", rate: 24000 },
              voice: VOICE,
              speed: 1.0
            }
          },
          tools: [{
            type: "function",
            name: "execute_android_task",
            description: "Ask the SuperiOA Android execution agent to perform a user-authorized device task. Use only for actions on the user's device, not for answering general questions.",
            parameters: {
              type: "object",
              properties: { goal: { type: "string", description: "A concise natural-language description of the Android task." } },
              required: ["goal"],
              additionalProperties: false
            }
          }],
          tool_choice: "auto",
          max_output_tokens: 1200
        }
      })
    });

    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      return res.status(response.status === 429 ? 429 : 502).json({
        error: data?.error?.message || "Realtime provider could not create a session."
      });
    }
    if (!data?.value) return res.status(502).json({ error: "Provider returned no temporary client secret." });
    res.set("Cache-Control", "no-store");
    return res.json({ value: data.value, expires_at: data.expires_at, model: MODEL, voice: VOICE });
  } catch {
    return res.status(502).json({ error: "Unable to reach the Realtime provider." });
  }
});

app.listen(PORT, "0.0.0.0", () => {
  console.log(`SuperiOA Realtime gateway listening on ${PORT}`);
});
