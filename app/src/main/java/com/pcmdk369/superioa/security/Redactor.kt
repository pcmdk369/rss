package com.pcmdk369.superioa.security

/** Removes API keys, tokens, passwords, OTP-like codes and card-like numbers from any text before it is logged or sent. */
object Redactor {
    private const val MASK = "[REDACTED]"

    private val patterns = listOf(
        Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}"),
        Regex("(?i)(api[_-]?key|apikey|token|secret|password|passwd|authorization)\"?\\s*[:=]\\s*\"?[^\\s\",;]+"),
        Regex("\\bsk-[A-Za-z0-9_-]{8,}"),
        Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"),
        Regex("(?i)(otp|code|pin)\\D{0,12}\\d{4,8}\\b"),
        Regex("\\b(?:\\d[ -]?){13,19}\\b"),
    )

    fun redact(text: String, knownSecrets: Collection<String> = emptyList()): String {
        var out = text
        for (s in knownSecrets) {
            if (s.length >= 4) out = out.replace(s, MASK)
        }
        for (p in patterns) out = p.replace(out, MASK)
        return out
    }
}
