package com.duophone.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactPhoneMatcherTest {

    @Test
    fun normalizeAndMatchKeyHandleInternationalAndPunctuation() {
        assertEquals("+8613800138000", ContactPhoneMatcher.normalize(" +86 138-0013-8000 "))
        assertEquals("13800138000", ContactPhoneMatcher.matchKey("+86 138 0013 8000"))
        assertEquals("13800138000", ContactPhoneMatcher.matchKey("0086-138-0013-8000"))
        assertEquals("13800138000", ContactPhoneMatcher.matchKey("13800138000"))
        assertEquals("10086", ContactPhoneMatcher.matchKey("10086"))
    }

    @Test
    fun resolveDisplayNamePrefersExactThenMatchKeyAndGuardsShortNumbers() {
        val contacts = listOf(
            ContactRecord(1L, "张三", "13800138000", "13800138000", source = ContactSource.LOCAL),
            ContactRecord(2L, "中国移动", "10086", "10086", source = ContactSource.LOCAL),
            ContactRecord(-5L, "短号测试", "12", "12", source = ContactSource.ANDROID_SYSTEM),
        )

        assertEquals("张三", ContactPhoneMatcher.resolveDisplayName("+86 138-0013-8000", contacts))
        assertEquals("中国移动", ContactPhoneMatcher.resolveDisplayName("10086", contacts))
        assertNull(ContactPhoneMatcher.resolveDisplayName("912", contacts))
        assertEquals("张三 (+8613800138000)", ContactPhoneMatcher.formatCallerLabel("+8613800138000", contacts))
        assertEquals("未知号码", ContactPhoneMatcher.formatCallerLabel("   ", contacts))
    }

    @Test
    fun mergeContactsPrioritizesLocalAndDeduplicatesSystemDuplicates() {
        val local = listOf(
            ContactRecord(1L, "家（本地优先）", "13800138000", "13800138000", source = ContactSource.LOCAL),
        )
        val system = listOf(
            ContactRecord(-10L, "家（系统重复）", "+86 138 0013 8000", "+8613800138000", source = ContactSource.ANDROID_SYSTEM),
            ContactRecord(-11L, "李四", "13900139000", "13900139000", source = ContactSource.ANDROID_SYSTEM),
            ContactRecord(-12L, "李四重复", "+8613900139000", "+8613900139000", source = ContactSource.ANDROID_SYSTEM),
        )

        val merged = ContactPhoneMatcher.mergeContacts(local, system)
        assertEquals(2, merged.size)
        assertEquals("家（本地优先）", merged.first { it.normalizedPhone == "13800138000" }.name)
        assertEquals("李四", merged.first { it.normalizedPhone == "13900139000" }.name)
    }
}
