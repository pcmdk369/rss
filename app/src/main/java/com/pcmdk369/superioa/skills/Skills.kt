package com.pcmdk369.superioa.skills

/** Skills are reusable hint packs for the central planner, NOT separate AI agents. */
interface AppSkill {
    val id: String
    val hints: String
    fun matches(goal: String): Boolean
}

object ChromeSkill : AppSkill {
    override val id = "chrome"
    override val hints = "To visit a site use OPEN_APP with an https:// URL (opens in Chrome). " +
        "Read the page with READ_UI, interact with visible text, and VERIFY the expected text afterwards. " +
        "Never submit forms with passwords or payment data; use REQUEST_USER."
    private val words = listOf("chrome", "browser", "website", "web site", "http", "google", "search for", "open the site")
    override fun matches(goal: String) = goal.lowercase().let { g -> words.any { g.contains(it) } }
}

object SettingsSkill : AppSkill {
    override val id = "android-settings"
    override val hints = "Use OPEN_APP target \"Settings\", then TAP visible item names. Use SCROLL down when an item is not visible. " +
        "VERIFY the final state text."
    private val words = listOf("settings", "wifi", "wi-fi", "bluetooth", "brightness", "volume", "airplane")
    override fun matches(goal: String) = goal.lowercase().let { g -> words.any { g.contains(it) } }
}

object SkillRegistry {
    private val skills = linkedMapOf<String, AppSkill>(
        ChromeSkill.id to ChromeSkill,
        SettingsSkill.id to SettingsSkill,
    )

    @Synchronized
    fun register(skill: AppSkill) {
        require(skill.id.isNotBlank()) { "skill id must not be blank" }
        skills[skill.id] = skill
    }

    @Synchronized
    fun unregister(id: String): Boolean =
        id != ChromeSkill.id && id != SettingsSkill.id && skills.remove(id) != null

    @Synchronized
    fun all(): List<AppSkill> = skills.values.toList()

    fun hintsFor(goal: String): String {
        val matches = synchronized(this) {
            skills.values.filter { skill -> runCatching { skill.matches(goal) }.getOrDefault(false) }
        }
        return matches.joinToString("\n") { "- [${it.id}] ${it.hints}" }
            .ifBlank { "- No specific skill matched; use generic UI actions." }
    }
}
