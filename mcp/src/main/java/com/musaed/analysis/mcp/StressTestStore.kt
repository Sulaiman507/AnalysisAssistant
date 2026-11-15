package com.musaed.analysis.mcp

import java.io.File

/** يحفظ نتيجة اختبار الإجهاد في ملف نصي واحد لمشاركته مع سجل الجلسة. */
object StressTestStore {
    const val STRESS_FILE = "stress_test.txt"

    fun save(dir: File, result: StressTestResult): File {
        dir.mkdirs()
        OutputArchive.preserve(dir, STRESS_FILE)
        return File(dir, STRESS_FILE).also { it.writeText(result.report) }
    }
}
