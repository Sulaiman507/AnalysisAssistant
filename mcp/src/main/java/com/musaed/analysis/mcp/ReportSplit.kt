package com.musaed.analysis.mcp

/**
 * يفصل ردّ التقرير النهائي (المرحلة 5): كائن JSON الأول + التقرير العربي
 * الذي يليه — حسب صيغة الإخراج [٦] في البرومبت (JSON أولاً ثم التقرير).
 *
 * يمسح الأقواس بأسلوب متوازن يحترم السلاسل النصية (مع `\` للهروب)، فلا
 * يُخطئه قوس داخل مقتطف نصي في التقرير. يُزال سياج ```json إن وُجد.
 * يرجع null إن لم يوجد كائن JSON مكتمل.
 */
object ReportSplit {

    /** يرجع (JSON، التقرير العربي بعده أو null إن لم يوجد نص بعده). */
    fun split(reply: String): Pair<String, String?>? {
        var text = reply.trim()
        text = text.replace("```json", "").replace("```", "").trim()
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val json = text.substring(start, i + 1)
                            val rest = text.substring(i + 1).trim().ifBlank { null }
                            return json to rest
                        }
                    }
                }
            }
            i++
        }
        return null
    }
}
