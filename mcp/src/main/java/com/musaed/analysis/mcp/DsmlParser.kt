package com.musaed.analysis.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * يفهم صيغة DSML لاستدعاء الأدوات (إصلاح 2026-11-20، بطلب سليمان):
 * بعض النماذج والمزوّدين يردّون باستدعاء أداة مغلفاً بوسوم DSML بدل
 * كائن JSON المباشر، مثل:
 *   <|DSML| calls |>
 *   <|DSML| invoke name="mt_apk_search">
 *   <|DSML| parameter name="arguments" string="true">{"target": "dex_strings", "query": "premium", ...}</|DSML| parameter>
 *   </|DSML| invoke>
 *   </|DSML| calls|>
 * قبل هذا الإصلاح كان المفسّر يجد كائن المعاملات الداخلي وحده (بلا
 * مفتاح tool) فيصنّف الرد «نصاً عادياً» في المحادثة فيظهر خاماً في
 * الفقاعة، و«رداً غير صالح» في حلقة التحليل فتتوقف بعد ثلاثة متتالية.
 * الآن يُستخرج اسم الأداة من invoke ومعاملاتها من أول كائن JSON متوازن
 * بعدها، وتُعامل كأي استدعاء JSON عادي — والتحقق من أن الاسم أداة
 * حقيقية يبقى على المتصل (القائمة الحية)، لا على هذا المفسّر.
 */
object DsmlParser {

    data class ToolCall(val tool: String, val arguments: JsonObject)

    private val INVOKE_NAME = Regex("""invoke\s+name\s*=\s*"([^"]+)"""")

    /** يرجع استدعاء الأداة، أو null إن لم يكن الرد استدعاء DSML صالحاً. */
    fun parse(reply: String): ToolCall? {
        if (!reply.contains("DSML")) return null
        val nameMatch = INVOKE_NAME.find(reply) ?: return null
        val tool = nameMatch.groupValues[1].trim()
        if (tool.isBlank()) return null
        // أول كائن JSON متوازن بعد وسام invoke هو كائن المعاملات.
        val after = reply.substring(nameMatch.range.last + 1)
        val jsonText = ReportSplit.split(after)?.first ?: return null
        val args = try {
            JsonParser.parseString(jsonText).asJsonObject
        } catch (_: Exception) {
            return null
        }
        return ToolCall(tool, args)
    }
}
