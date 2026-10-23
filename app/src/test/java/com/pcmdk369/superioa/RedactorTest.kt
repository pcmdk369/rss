package com.pcmdk369.superioa

import com.pcmdk369.superioa.security.Redactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactorTest {
    @Test fun redactsKnownSecrets() {
        assertFalse(Redactor.redact("using abcd1234efgh", listOf("abcd1234efgh")).contains("abcd1234efgh"))
    }

    @Test fun redactsBearerAndKeyPatterns() {
        assertFalse(Redactor.redact("Authorization: Bearer abcdef123456").contains("abcdef123456"))
        assertFalse(Redactor.redact("api_key=sk-ABCDEFGH12345678").contains("ABCDEFGH12345678"))
        assertFalse(Redactor.redact("password: hunter2hunter2").contains("hunter2"))
    }

    @Test fun redactsJwt() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop"
        assertFalse(Redactor.redact("t $jwt t").contains("eyJhbGci"))
    }

    @Test fun redactsOtpAndCardNumbers() {
        assertFalse(Redactor.redact("Your code is 482913").contains("482913"))
        assertFalse(Redactor.redact("card 4111 1111 1111 1111").contains("4111 1111"))
    }

    @Test fun leavesNormalTextAlone() {
        assertEquals("Open Settings and tap Wi-Fi", Redactor.redact("Open Settings and tap Wi-Fi"))
    }
}
