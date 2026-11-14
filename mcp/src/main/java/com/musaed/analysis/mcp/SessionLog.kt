package com.musaed.analysis.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * سجلّ جلسة (المرحلة ٢): يحفظ كل مخرجات الأدوات حرفياً — مرجع المدقّق
 * وبطاقات الأدلة. كل استدعاء أداة (بما فيه كل محاولة إعادة) يُسجَّل
 * بنتيجته الخام كما رجعت من الخادم، دون تلخيص أو تعديل.
 *
 * status: "ok" عند نجاح الاستدعاء، و"error" عند فشله.
 */
data class SessionEntry(
    val seq: Int,
    val step: Int,
    val attempt: Int,
    val timestamp: String,
    val tool: String,
    val argumentsJson: String,
    val status: String,
    val durationMs: Long,
    val rawResult: String,
    val error: String?,
)

/** قائمة إلحاقية متزامنة من إدخالات الجلسة، مرقّمة تسلسلياً من ١. */
class SessionLog {
    private val lock = Any()
    private val entriesList = mutableListOf<SessionEntry>()
    private var nextSeq = 1

    fun record(
        step: Int,
        attempt: Int,
        tool: String,
        argumentsJson: String,
        status: String,
        durationMs: Long,
        rawResult: String,
        error: String?,
    ): SessionEntry {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = synchronized(lock) {
            val e = SessionEntry(
                seq = nextSeq++,
                step = step,
                attempt = attempt,
                timestamp = now,
                tool = tool,
                argumentsJson = argumentsJson,
                status = status,
                durationMs = durationMs,
                rawResult = rawResult,
                error = error,
            )
            entriesList += e
            e
        }
        return entry
    }

    fun entries(): List<SessionEntry> = synchronized(lock) { entriesList.toList() }

    /** مجموعة أرقام الخطوات الحاضرة في السجل (للبوابة: ١–٢٠ كاملة). */
    fun stepsPresent(): Set<Int> = synchronized(lock) { entriesList.map { it.step }.toSet() }

    /** JSON كامل: كل إدخال بنتيجته الخام الكاملة دون اقتطاع. */
    fun toJson(): JsonObject {
        val root = JsonObject()
        val arr = JsonArray()
        for (e in entries()) {
            arr.add(JsonObject().apply {
                addProperty("seq", e.seq)
                addProperty("step", e.step)
                addProperty("attempt", e.attempt)
                addProperty("timestamp", e.timestamp)
                addProperty("tool", e.tool)
                addProperty("argumentsJson", e.argumentsJson)
                addProperty("status", e.status)
                addProperty("durationMs", e.durationMs)
                addProperty("rawResult", e.rawResult)
                if (e.error != null) addProperty("error", e.error)
            })
        }
        root.add("entries", arr)
        root.addProperty("count", entries().size)
        return root
    }

    fun toJsonString(): String = GsonBuilder().setPrettyPrinting().create().toJson(toJson())

    /**
     * نص مقروء: كل إدخال بسطره مع المعاملات والنتيجة الخام مقتطعة عند
     * 4000 حرف، مع تنبيه أن الكامل في JSON.
     */
    fun toText(): String {
        val sb = StringBuilder()
        sb.appendLine("سجل جلسة — مساعد التحليل")
        sb.appendLine("عدد الإدخالات: ${entries().size}")
        sb.appendLine()
        for (e in entries()) {
            sb.appendLine("[${e.seq}] خطوة ${e.step} — محاولة ${e.attempt} — ${e.timestamp}")
            sb.appendLine("    الأداة: ${e.tool}")
            sb.appendLine("    المعاملات: ${e.argumentsJson}")
            sb.appendLine("    الحالة: ${e.status} — المدة: ${e.durationMs}ms")
            if (e.error != null) sb.appendLine("    الخطأ: ${e.error}")
            val raw = e.rawResult
            if (raw.length > 4000) {
                sb.appendLine("    النتيجة الخام (مقتطعة عند 4000 حرف — الكامل في JSON):")
                sb.appendLine(raw.take(4000))
                sb.appendLine("    … (اقتُطعت — النتيجة الكاملة في session_log.json)")
            } else {
                sb.appendLine("    النتيجة الخام:")
                sb.appendLine(raw)
            }
            sb.appendLine()
        }
        return sb.toString()
    }
}
