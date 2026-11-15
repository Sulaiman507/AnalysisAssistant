package com.musaed.analysis.mcp

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** يحفظ نتيجة اختبار البوابة في ملف نصي واحد لمشاركته مع القائمة. */
object GateTestStore {
    const val GATE_FILE = "gate_test.txt"

    fun save(dir: File, result: GateTestResult): File {
        dir.mkdirs()
        OutputArchive.preserve(dir, GATE_FILE)
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            appendLine("نتيجة اختبار البوابة — حُفظت $now")
            appendLine()
            append(result.report)
        }
        return File(dir, GATE_FILE).also { it.writeText(text) }
    }
}
