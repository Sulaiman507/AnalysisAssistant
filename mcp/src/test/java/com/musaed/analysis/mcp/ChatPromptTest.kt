package com.musaed.analysis.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPromptTest {

    @Test
    fun `whitelist is read only`() {
        // القائمة البيضاء محددة بالاسم حرفياً — أي أداة تعديل/بناء/تثبيت/
        // حذف خارجها تُرفض. أسماء القراءة وحدها مسموحة (ومنها
        // list_installed_apps — سرد التطبيقات المثبتة قراءة، لا تثبيت).
        val expected = setOf(
            "mt_apk_list_installed_apps",
            "mt_apk_list_available_apks",
            "mt_apk_workspace_open",
            "mt_apk_list_entries",
            "mt_apk_search",
            "mt_apk_dex_list_classes",
            "mt_apk_dex_read_class_outline",
            "mt_apk_dex_read_pseudocode",
            "mt_apk_read_text",
            "mt_apk_dex_xref",
            "mt_apk_resource_list",
            "mt_apk_resource_read",
            "mt_apk_read_signature",
            "mt_apk_continue",
        )
        assertEquals(expected, ChatPrompt.WHITELIST)
    }

    @Test
    fun `prompt states the run analysis contract`() {
        assertTrue(ChatPrompt.TEXT.contains("run_analysis"))
        assertTrue(ChatPrompt.TEXT.contains("قراءة فقط"))
    }

    @Test
    fun `catalog covers every whitelisted tool`() {
        for (name in ChatPrompt.WHITELIST) {
            assertTrue("catalog must describe $name", ChatPrompt.TOOL_CATALOG_TEXT.contains(name))
        }
    }
}
