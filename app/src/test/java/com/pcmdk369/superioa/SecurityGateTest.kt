package com.pcmdk369.superioa

import com.pcmdk369.superioa.action.ActionType
import com.pcmdk369.superioa.action.AgentAction
import com.pcmdk369.superioa.security.SecurityGate
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SecurityGateTest {
    @Test fun coordinateTapOnSendScreenRequiresApproval() {
        val result = SecurityGate.actionNeedsApproval(
            AgentAction(ActionType.TAP, x = 400, y = 900),
            "package:com.example.chat\nSend"
        )
        assertNotNull(result)
    }

    @Test fun opaqueTargetOnDeleteScreenRequiresApproval() {
        val result = SecurityGate.actionNeedsApproval(
            AgentAction(ActionType.TAP, target = "com.example:id/action"),
            "Delete account"
        )
        assertNotNull(result)
    }

    @Test fun explicitHighImpactTargetRequiresApproval() {
        assertNotNull(SecurityGate.actionNeedsApproval(AgentAction(ActionType.TAP, target = "Publish")))
    }

    @Test fun harmlessTapDoesNotRequireApproval() {
        assertNull(SecurityGate.actionNeedsApproval(
            AgentAction(ActionType.TAP, target = "Open settings"),
            "package:com.example.settings\nDisplay"
        ))
    }

    @Test fun authenticationAndPaymentScreensPauseForUser() {
        assertNotNull(SecurityGate.screenNeedsUser("Enter verification code"))
        assertNotNull(SecurityGate.screenNeedsUser("Confirm payment"))
    }
}
