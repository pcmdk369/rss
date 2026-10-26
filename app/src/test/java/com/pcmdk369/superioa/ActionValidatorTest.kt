package com.pcmdk369.superioa

import com.pcmdk369.superioa.action.ActionType
import com.pcmdk369.superioa.action.ActionValidator
import com.pcmdk369.superioa.action.PlanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionValidatorTest {
    private fun valid(json: String, w: Int = 1080, h: Int = 2400): PlanResult.Valid {
        val r = ActionValidator.parsePlan(json, w, h)
        assertTrue("expected valid but was $r", r is PlanResult.Valid)
        return r as PlanResult.Valid
    }

    private fun invalid(json: String, w: Int = 1080, h: Int = 2400) {
        val r = ActionValidator.parsePlan(json, w, h)
        assertTrue("expected invalid but was $r", r is PlanResult.Invalid)
    }

    @Test fun validPlanParses() {
        val p = valid("""{"message":"hi","done":false,"actions":[{"type":"OPEN_APP","target":"Settings"},{"type":"TAP","target":"Wi-Fi"},{"type":"VERIFY","target":"On"}]}""")
        assertEquals(3, p.plan.actions.size)
        assertEquals(ActionType.OPEN_APP, p.plan.actions[0].type)
    }

    @Test fun codeFencesAreTolerated() {
        valid("```json\n{\"actions\":[{\"type\":\"BACK\"}]}\n```")
    }

    @Test fun lowercaseTypeIsAccepted() {
        valid("""{"actions":[{"type":"home"}]}""")
    }

    @Test fun unknownTypeRejected() = invalid("""{"actions":[{"type":"FORMAT_DISK"}]}""")
    @Test fun missingTypeRejected() = invalid("""{"actions":[{"target":"x"}]}""")
    @Test fun unknownFieldRejected() = invalid("""{"actions":[{"type":"BACK","shell":"rm -rf /"}]}""")
    @Test fun malformedJsonRejected() = invalid("""{"actions":[{"type":"BACK"}""")
    @Test fun noJsonRejected() = invalid("Sure! I will open settings now.")
    @Test fun emptyNotDoneRejected() = invalid("""{"actions":[]}""")
    @Test fun emptyDoneAccepted() { valid("""{"done":true,"actions":[]}""") }
    @Test fun tapOutOfBoundsRejected() = invalid("""{"actions":[{"type":"TAP","x":5000,"y":10}]}""")
    @Test fun tapNeedsTargetOrCoords() = invalid("""{"actions":[{"type":"TAP"}]}""")
    @Test fun tapCoordsInBoundsAccepted() { valid("""{"actions":[{"type":"TAP","x":100,"y":200}]}""") }
    @Test fun longPressByTargetAccepted() { valid("""{"actions":[{"type":"LONG_PRESS","target":"More options"}]}""") }
    @Test fun longPressByCoordinatesAccepted() { valid("""{"actions":[{"type":"LONG_PRESS","x":100,"y":200}]}""") }
    @Test fun clearTextAccepted() { valid("""{"actions":[{"type":"CLEAR_TEXT"}]}""") }
    @Test fun selectAllAccepted() { valid("""{"actions":[{"type":"SELECT_ALL"}]}""") }
    @Test fun toggleNeedsTarget() = invalid("""{"actions":[{"type":"TOGGLE"}]}""")
    @Test fun toggleByTargetAccepted() { valid("""{"actions":[{"type":"TOGGLE","target":"Wi-Fi"}]}""") }
    @Test fun notificationShadeAccepted() { valid("""{"actions":[{"type":"NOTIFICATIONS"}]}""") }
    @Test fun quickSettingsAccepted() { valid("""{"actions":[{"type":"QUICK_SETTINGS"}]}""") }
    @Test fun waitTooLongRejected() = invalid("""{"actions":[{"type":"WAIT","millis":60000}]}""")
    @Test fun waitZeroRejected() = invalid("""{"actions":[{"type":"WAIT","millis":0}]}""")
    @Test fun typeTooLongRejected() = invalid("""{"actions":[{"type":"TYPE","text":"${"a".repeat(501)}"}]}""")
    @Test fun scrollBadDirectionRejected() = invalid("""{"actions":[{"type":"SCROLL","direction":"sideways"}]}""")
    @Test fun httpUrlRejected() = invalid("""{"actions":[{"type":"OPEN_APP","target":"http://example.com"}]}""")
    @Test fun httpsUrlAccepted() { valid("""{"actions":[{"type":"OPEN_APP","target":"https://example.com"}]}""") }
    @Test fun wrongTypedFieldRejected() = invalid("""{"actions":[{"type":"TAP","x":"abc","y":1}]}""")

    @Test fun tooManyActionsRejected() {
        val many = (1..13).joinToString(",") { """{"type":"BACK"}""" }
        invalid("""{"actions":[$many]}""")
    }

    @Test fun oneBadActionRejectsWholePlan() =
        invalid("""{"actions":[{"type":"BACK"},{"type":"NOPE"}]}""")

    @Test fun describeNeverLeaksTypedText() {
        val a = valid("""{"actions":[{"type":"TYPE","text":"hunter2-secret"}]}""").plan.actions[0]
        assertTrue(!a.describe().contains("hunter2"))
    }
}
