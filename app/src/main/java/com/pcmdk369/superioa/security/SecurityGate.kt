package com.pcmdk369.superioa.security

import com.pcmdk369.superioa.action.ActionType
import com.pcmdk369.superioa.action.AgentAction

/** Decides when SuperiOA must stop and hand control to the human. It never tries to bypass anything. */
object SecurityGate {
    private val screenTriggers = listOf(
        "captcha", "i'm not a robot", "i am not a robot", "verification code", "one-time",
        "one time password", "otp", "enter password", "passcode", "biometric", "fingerprint",
        "face id", "two-factor", "2-step", "2fa", "security code", "cvv", "card number",
        "confirm your identity", "confirm it's you"
    )

    private val actionTrigger = Regex(
        "\\b(pay|payment|purchase|buy|checkout|place order|send|delete|remove|post|publish|transfer|submit|uninstall|confirm)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Returns a reason when the visible screen needs the user (CAPTCHA, OTP, password, biometrics, payment...). */
    fun screenNeedsUser(screenText: String): String? {
        val low = screenText.lowercase()
        val hit = screenTriggers.firstOrNull { low.contains(it) } ?: return null
        return "This screen appears to need you ($hit)."
    }

    /** Returns a reason when an action is high-impact and needs explicit approval first. */
    fun actionNeedsApproval(a: AgentAction): String? {
        if (a.type !in setOf(ActionType.TAP, ActionType.LONG_PRESS, ActionType.TOGGLE)) return null
        val t = a.target ?: return null
        val m = actionTrigger.find(t) ?: return null
        return "High-impact action: ${a.type.name.lowercase()} \"${t.take(60)}\" (${m.value.lowercase()}). Allow?"
    }
}
