package com.duophone.app.stage

import org.junit.Assert.assertEquals
import org.junit.Test

class TopScreenPhoneMaskerTest {
    @Test
    fun masksFourthThroughSeventhDigitsOfStandardMobile() {
        assertEquals("138****5678", TopScreenPhoneMasker.mask("13812345678"))
    }

    @Test
    fun masksLiveDialTypingProgressively() {
        assertEquals("", TopScreenPhoneMasker.mask(""))
        assertEquals("138", TopScreenPhoneMasker.mask("138"))
        assertEquals("138*", TopScreenPhoneMasker.mask("1381"))
        assertEquals("138****", TopScreenPhoneMasker.mask("1381234"))
        assertEquals("138****5", TopScreenPhoneMasker.mask("13812345"))
    }

    @Test
    fun preservesChinaCountryCodeAndPunctuation() {
        assertEquals("+86138****5678", TopScreenPhoneMasker.mask("+8613812345678"))
        assertEquals("+86 138 **** 5678", TopScreenPhoneMasker.mask("+86 138 1234 5678"))
        assertEquals("138-****-5678", TopScreenPhoneMasker.mask("138-1234-5678"))
    }

    @Test
    fun leavesShortCodesUssdAndTextIntact() {
        assertEquals("110", TopScreenPhoneMasker.mask("110"))
        assertEquals("*#06#", TopScreenPhoneMasker.mask("*#06#"))
        assertEquals("New Message", TopScreenPhoneMasker.mask("New Message"))
    }

    @Test
    fun preservesExactCharacterLengthForVisualTransformation() {
        val samples = listOf("", "1", "138", "1381", "1381234", "13812345678", "+8613812345678", "+86 138 1234 5678", "138-1234-5678")
        samples.forEach { sample ->
            assertEquals(sample.length, TopScreenPhoneMasker.mask(sample).length)
        }
    }
}
