package com.musaed.analysis.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * تحليل دفاعي لنتائج tools/call — مشترك بين اختبار البوابة وبنّاء السياق
 * واختبار الإجهاد.
 *
 * الشكل الدقيق لردود الخادم لم يُرصد حياً بالكامل بعد، فالتحليل يقبل
 * structuredContent أو نص content بصيغة JSON أو النتيجة نفسها، ويفك تغليف
 * data المتداخل دفاعياً. أي شكل غير متوقع يُحفظ خاماً لتشخيصه ولا يُخترع
 * له بديل.
 */
internal object McpResults {

    /**
     * يستخرج كائن data من نتيجة tools/call: structuredContent أولاً، ثم نص
     * content بصيغة JSON، ثم النتيجة نفسها إن لم يوجد أي منهما.
     */
    fun dataOf(result: JsonObject): JsonObject {
        var data: JsonObject? = null
        if (result.has("structuredContent") && result.get("structuredContent").isJsonObject) {
            val sc = result.getAsJsonObject("structuredContent")
            data = if (sc.has("data") && sc.get("data").isJsonObject) sc.getAsJsonObject("data") else sc
        } else if (result.has("content") && result.get("content").isJsonArray) {
            for (el in result.getAsJsonArray("content")) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                if (obj.has("text") && obj.get("text").isJsonPrimitive) {
                    val text = obj.get("text").asString.trim()
                    if (text.startsWith("{")) {
                        try {
                            val parsed = JsonParser.parseString(text).asJsonObject
                            data = if (parsed.has("data") && parsed.get("data").isJsonObject) {
                                parsed.getAsJsonObject("data")
                            } else {
                                parsed
                            }
                            break
                        } catch (_: Exception) {
                            // النص ليس JSON — تابع للعنصر التالي
                        }
                    }
                }
            }
        }
        var out = data ?: result
        // فك تغليف data المتداخل إن وُجد (بعض الخوادم تغلّف الحمولة مرتين):
        // يُفك فقط إن لم توجد items في المستوى الحالي.
        while (out.has("data") && out.get("data").isJsonObject && !out.has("items")) {
            out = out.getAsJsonObject("data")
        }
        return out
    }

    fun itemsOf(data: JsonObject): List<JsonObject> {
        if (data.has("items") && data.get("items").isJsonArray) {
            return data.getAsJsonArray("items")
                .mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
        }
        return emptyList()
    }

    fun firstString(obj: JsonObject, vararg keys: String): String? {
        for (k in keys) {
            if (obj.has(k) && obj.get(k).isJsonPrimitive) {
                val v = obj.get(k).asString
                if (v.isNotBlank()) return v
            }
        }
        return null
    }

    /**
     * يستخرج نص كود من data: textWindow.text أولاً (كما في وصف أدوات القراءة
     * في القائمة الحية)، ثم حقل text المباشر. يعيد null إن لم يوجد نص.
     */
    fun codeTextOf(data: JsonObject): String? {
        if (data.has("textWindow") && data.get("textWindow").isJsonObject) {
            val tw = data.getAsJsonObject("textWindow")
            val t = firstString(tw, "text")
            if (!t.isNullOrBlank()) return t
        }
        val direct = firstString(data, "text")
        if (!direct.isNullOrBlank()) return direct
        return null
    }

    /** يستخرج مؤشر ترقيم الصفحات التالي من data.pagination إن وُجد. */
    fun nextCursorOf(data: JsonObject): String? {
        if (data.has("pagination") && data.get("pagination").isJsonObject) {
            val pg = data.getAsJsonObject("pagination")
            val hasMore = pg.has("hasMore") && pg.get("hasMore").isJsonPrimitive &&
                pg.get("hasMore").asBoolean
            if (!hasMore) return null
            return firstString(pg, "nextCursor")
        }
        return null
    }
}
