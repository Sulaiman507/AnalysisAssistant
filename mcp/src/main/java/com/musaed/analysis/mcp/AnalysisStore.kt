package com.musaed.analysis.mcp

import java.io.File

/** يحفظ نتيجة التحليل: JSON الخام + تقرير مقروء — على نمط مخازن المرحلة ٢. */
object AnalysisStore {
    const val JSON_FILE = "analysis.json"
    const val TEXT_FILE = "analysis_report.txt"

    fun save(dir: File, result: AnalysisResult): Pair<File, File> {
        dir.mkdirs()
        OutputArchive.preserve(dir, JSON_FILE)
        OutputArchive.preserve(dir, TEXT_FILE)
        val jsonFile = File(dir, JSON_FILE)
        jsonFile.writeText(result.reportJson ?: "{}")
        val text = buildString {
            appendLine("نتيجة التحليل — مساعد التحليل (المرحلة ٥)")
            appendLine("التطبيق الهدف: ${result.targetPackage}")
            appendLine("مساحة العمل: ${result.workspaceId ?: "—"}")
            appendLine("الخطوات المستخدمة: ${result.stepsUsed}")
            appendLine("النتيجة: " + if (result.success) "ناجح ✓" else "غير مكتمل ✗")
            result.notes.forEach { appendLine("ملاحظة: $it") }
            appendLine()
            if (result.reportArabic != null) {
                appendLine("التقرير العربي:")
                appendLine(result.reportArabic)
            } else {
                appendLine("التقرير JSON:")
                appendLine(result.reportJson ?: "(لا يوجد تقرير)")
            }
        }
        val textFile = File(dir, TEXT_FILE)
        textFile.writeText(text)
        return jsonFile to textFile
    }
}
