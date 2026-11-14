package com.musaed.analysis.mcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * مساعد مشترك لاختبارات وحدة MCP: يبني نتائج tools/call الوهمية بأشكالها
 * القياسية، بدل تكرار بنائها في كل ملف اختبار.
 *
 * الأشكال مفترضة حسب وصف الأدوات في القائمة الحية — ليست ردوداً مرصودة
 * من الخادم؛ الشكل الحقيقي يُتحقق منه على الهاتف.
 */
internal object FakeMcp {

    /** نتيجة tools/call بشكل MCP القياسي: content نصي يحمل JSON. */
    fun toolResult(dataJson: String): JsonObject {
        val data = JsonParser.parseString(dataJson).asJsonObject
        val textItem = JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", Gson().toJson(data))
        }
        return JsonObject().apply {
            add("content", JsonArray().apply { add(textItem) })
            addProperty("isError", false)
        }
    }

    /** نتيجة بصيغة structuredContent: المحتوى المنظم هو الحمولة نفسها. */
    fun structuredResult(dataJson: String): JsonObject {
        val payload = JsonParser.parseString(dataJson).asJsonObject
        return JsonObject().apply {
            add("structuredContent", payload)
        }
    }

    /** نتيجة خادم خاطئة: isError=true مع رسالة نصية. */
    fun errorResult(message: String): JsonObject {
        val textItem = JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", message)
        }
        return JsonObject().apply {
            add("content", JsonArray().apply { add(textItem) })
            addProperty("isError", true)
        }
    }
}

/**
 * خادم وهمي يجيب من خريطة اسم الأداة ← النتيجة، ويرمي عند الاستدعاء غير
 * المتوقع. الأدوات المذكورة في failNames ترمي خطأ نقل (قابل لإعادة
 * المحاولة) — لمحاكاة الفشل العابر.
 */
internal class FakeServer(
    private val responses: Map<String, JsonObject>,
    private val failNames: Set<String> = emptySet(),
) {
    val calls = mutableListOf<Pair<String, JsonObject>>()

    fun call(name: String, args: JsonObject): JsonObject {
        calls += name to args
        if (name in failNames) throw McpException("فشل وهمي للأداة $name")
        return responses[name] ?: throw IllegalStateException("unexpected tool call: $name")
    }
}
