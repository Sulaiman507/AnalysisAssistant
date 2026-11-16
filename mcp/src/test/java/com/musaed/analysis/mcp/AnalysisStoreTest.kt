package com.musaed.analysis.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AnalysisStoreTest {

    private fun result(arabic: String?) = AnalysisResult(
        success = true,
        reportJson = """{"verdict": "local", "confidence": 0.9}""",
        reportArabic = arabic,
        stepsUsed = 5,
        notes = listOf("ملاحظة اختبار"),
        targetPackage = "com.musaed.test.clear",
        workspaceId = "ws-1",
    )

    @Test
    fun `text file holds the arabic report when present`() {
        val dir = Files.createTempDirectory("analysis-store").toFile()
        val (jsonFile, textFile) = AnalysisStore.save(dir, result("النتيجة: الفحص محلي صرف."))
        assertTrue(jsonFile.readText().contains("\"verdict\""))
        val text = textFile.readText()
        assertTrue(text.contains("النتيجة: الفحص محلي صرف."))
        assertTrue(text.contains("ملاحظة اختبار"))
        // التقرير العربي في ملف النص، والـ JSON الخام في ملف JSON فقط.
        assertFalse(text.contains("\"confidence\""))
    }

    @Test
    fun `text file falls back to json when no arabic report`() {
        val dir = Files.createTempDirectory("analysis-store").toFile()
        val (_, textFile) = AnalysisStore.save(dir, result(null))
        assertTrue(textFile.readText().contains("\"verdict\""))
    }
}
