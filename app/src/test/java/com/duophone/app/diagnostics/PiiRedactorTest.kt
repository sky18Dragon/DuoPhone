package com.duophone.app.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiiRedactorTest {

    @Test
    fun redactsPhoneNumbersImeiAndOtpCodes() {
        val sample = "ATD+8613800138000; imei=861234567890123 您的验证码为 482910 请勿泄露"
        val redacted = PiiRedactor.redact(sample)

        assertFalse(redacted.contains("13800138000"))
        assertFalse(redacted.contains("861234567890123"))
        assertFalse(redacted.contains("482910"))
        assertTrue(redacted.contains("[OTP:6]"))
    }
}
