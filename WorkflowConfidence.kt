package com.pcmdk369.superioa.memory

enum class ReuseDecision { REUSE, CONFIRM, DISABLED }

/** Laplace-smoothed success rate. Reuse needs >= 0.70; 0.40..0.70 asks the user; below that the workflow is ignored. */
object WorkflowConfidence {
    const val REUSE_THRESHOLD = 0.70
    const val CONFIRM_THRESHOLD = 0.40

    fun score(success: Int, failure: Int): Double = (success + 1.0) / (success + failure + 2.0)

    fun decide(success: Int, failure: Int): ReuseDecision {
        val s = score(success, failure)
        return when {
            s >= REUSE_THRESHOLD -> ReuseDecision.REUSE
            s >= CONFIRM_THRESHOLD -> ReuseDecision.CONFIRM
            else -> ReuseDecision.DISABLED
        }
    }

    fun normalizeGoal(goal: String): String =
        goal.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
}
