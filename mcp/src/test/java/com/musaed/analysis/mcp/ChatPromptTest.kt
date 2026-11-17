package com.musaed.analysis.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPromptTest {

    @Test
    fun `known tools cover all 67 live tools`() {
        // بلا حدود بقرار سليمان 2026-11-17: كل أدوات الخدمة الـ 67 معروفة
        // — قراءة وتعديل وبناء وتثبيت وحذف وملفات وZIP.
        assertEquals(67, ChatPrompt.KNOWN_TOOLS.size)
        for (name in listOf(
            "mt_apk_dex_read_pseudocode",
            "mt_apk_edit_text",
            "mt_apk_patch_bytes",
            "mt_apk_build",
            "mt_apk_install",
            "mt_apk_delete_target",
            "mt_file_delete",
            "mt_zip_create",
            "mt_file_get_access_policy",
        )) {
            assertTrue("$name must be known", name in ChatPrompt.KNOWN_TOOLS)
        }
    }

    @Test
    fun `prompt states the run analysis contract and no limits`() {
        assertTrue(ChatPrompt.TEXT.contains("run_analysis"))
        assertTrue(ChatPrompt.TEXT.contains("بلا حدود"))
    }

    @Test
    fun `catalog covers every known tool`() {
        for (name in ChatPrompt.KNOWN_TOOLS) {
            assertTrue("catalog must describe $name", ChatPrompt.TOOL_CATALOG_TEXT.contains(name))
        }
    }
}
