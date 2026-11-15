package com.wangzan101.mika.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiKeyTextTest {
    @Test fun stripsNewlinesAndKeepsTheRest() {
        val n = ApiKeyText.accept("sk-ab\ncd")
        assertEquals("sk-abcd", n.text)
        assertEquals(ApiKeyText.Notice.NewlinesRemoved, n.notice)
        val crlf = ApiKeyText.accept("sk-ab\r\ncd")
        assertEquals("sk-abcd", crlf.text)
        assertEquals(ApiKeyText.Notice.NewlinesRemoved, crlf.notice)
        assertTrue(ApiKeyText.isHeaderLegal(crlf.text))
    }

    @Test fun stripsZeroWidthAndBomWithoutTouchingTheKey() {
        val zw = ApiKeyText.accept("sk-\u200Babc")
        assertEquals("sk-abc", zw.text)
        assertEquals(ApiKeyText.Notice.InvisibleRemoved, zw.notice)
        val bom = ApiKeyText.accept("\uFEFFsk-abc")
        assertEquals("sk-abc", bom.text)
        assertEquals(ApiKeyText.Notice.InvisibleRemoved, bom.notice)
    }

    @Test fun newlineHintWinsWhenInvisibleCharactersWereAlsoRemoved() {
        val both = ApiKeyText.accept("sk-\u200B\nabc")
        assertEquals("sk-abc", both.text)
        assertEquals(ApiKeyText.Notice.NewlinesRemoved, both.notice)
    }

    @Test fun leavesIllegalCharactersInPlace() {
        val chinese = ApiKeyText.accept("密钥：sk-abc123")
        assertEquals("密钥：sk-abc123", chinese.text)
        assertEquals(ApiKeyText.Notice.IllegalCharacters, chinese.notice)
        assertFalse(ApiKeyText.isHeaderLegal(chinese.text))
        val control = ApiKeyText.accept("sk-\u0001abc")
        assertEquals("sk-\u0001abc", control.text)
        assertEquals(ApiKeyText.Notice.IllegalCharacters, control.notice)
        val emoji = ApiKeyText.accept("sk-abc😀")
        assertEquals("sk-abc😀", emoji.text)
        assertEquals(ApiKeyText.Notice.IllegalCharacters, emoji.notice)
    }

    @Test fun illegalCharactersWinOverAStrippedNewline() {
        val mixed = ApiKeyText.accept("密钥：sk-\nabc")
        assertEquals("密钥：sk-abc", mixed.text)
        assertEquals(ApiKeyText.Notice.IllegalCharacters, mixed.notice)
    }

    @Test fun spacesAndTabsStayAndCanGoInAHeader() {
        assertEquals(ApiKeyText.Notice.None, ApiKeyText.accept("sk-1 2").notice)
        assertEquals("sk-1 2", ApiKeyText.accept("sk-1 2").text)
        assertEquals(ApiKeyText.Notice.None, ApiKeyText.accept("sk-\tabc").notice)
        assertTrue(ApiKeyText.isHeaderLegal("Bearer sk-1 2"))
        assertTrue(ApiKeyText.isHeaderLegal(""))
        assertFalse(ApiKeyText.isHeaderLegal("Bearer sk-\nabc"))
    }
}
