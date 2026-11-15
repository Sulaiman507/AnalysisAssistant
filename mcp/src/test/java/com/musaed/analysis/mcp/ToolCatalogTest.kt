package com.musaed.analysis.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogTest {

    /** الأسماء المخترعة التي طلبها النموذج في التشغيل الحي 2026-11-15 — يجب ألا تظهر أبداً. */
    private val inventedNames = listOf(
        "mt_apk_dex_search",
        "mt_apk_dex_search_string",
        "mt_apk_list",
        "mt_apk_list_tools",
        "mt_apk_read_smali",
    )

    @Test
    fun `catalog lists thirteen tools with their argument shapes`() {
        assertEquals(13, ToolCatalog.NAMES.size)
        for (name in ToolCatalog.NAMES) {
            assertTrue("الدليل لا يذكر $name", ToolCatalog.TEXT.contains(name))
        }
        // المعاملات الأساسية وصيغ المعرفات التي خمّنها النموذج خطأً حياً
        assertTrue(ToolCatalog.TEXT.contains("workspaceId"))
        assertTrue(ToolCatalog.TEXT.contains("editSessionId"))
        assertTrue(ToolCatalog.TEXT.contains("locator"))
        assertTrue(ToolCatalog.TEXT.contains("dex_class:"))
        assertTrue(ToolCatalog.TEXT.contains("dex_method:"))
        assertTrue(ToolCatalog.TEXT.contains("axml:AndroidManifest.xml"))
        assertTrue(ToolCatalog.TEXT.contains("style"))
        assertTrue(ToolCatalog.TEXT.contains("readable"))
    }

    @Test
    fun `catalog contains no invented tool name`() {
        for (name in inventedNames) {
            assertFalse("الدليل يحتوي اسماً مخترعاً: $name", name in ToolCatalog.NAMES)
            // مطابقة كاسم كامل — "mt_apk_list" مثلاً بادئة لاسمين حقيقيين
            // (list_installed_apps وlist_entries) فلا تُحسب ظهوراً.
            val wholeName = Regex("""\b${Regex.escape(name)}(?![a-z_])""")
            assertFalse(
                "نص الدليل يذكر اسماً مخترعاً: $name",
                wholeName.containsMatchIn(ToolCatalog.TEXT),
            )
        }
    }

    @Test
    fun `catalog names are exactly the protocol tools`() {
        // أسماء الأدوات التي يستخدمها الكود العامل فعلياً (GateTester و
        // ContextBuilder وStressTest وQuickScan) — لا أكثر ولا أقل.
        val expected = setOf(
            "mt_apk_list_installed_apps",
            "mt_apk_workspace_open",
            "mt_apk_search",
            "mt_apk_dex_list_classes",
            "mt_apk_dex_read_class_outline",
            "mt_apk_dex_read_pseudocode",
            "mt_apk_read_text",
            "mt_apk_dex_xref",
            "mt_apk_list_entries",
            "mt_apk_resource_list",
            "mt_apk_read_signature",
            "mt_apk_continue",
            "mt_apk_workspace_delete",
        )
        assertEquals(expected, ToolCatalog.NAMES)
        // ملاحظة: وجود كل اسم في القائمة الحية (67 أداة) يتحقق منه
        // verify-local.sh خطوة 1 (grep كل mt_ في الكود ضد tools_list).
    }
}
