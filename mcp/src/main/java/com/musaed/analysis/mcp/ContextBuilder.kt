package com.musaed.analysis.mcp

import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * جسم دالة واحدة داخل حزمة الكلاس: الكود الشبهي أولاً، وSmali بديلاً عند
 * فشل الكود الشبهي، و"failed" عند فشل الاثنين (مع حفظ الخطأ في الملاحظات).
 */
data class MethodBody(
    val locator: String,
    val pseudocode: String?,
    val smali: String?,
    val source: String, // pseudocode | smali | failed
)

/**
 * حزمة كلاس واحدة: مخططه + أجسام دواله + مراجعه — وحدة سياق صغيرة جاهزة
 * للتحليل، ضمن سقف 48_000 حرف.
 */
data class ClassPackage(
    val classLocator: String,
    val fields: List<String>,
    val methods: List<String>,
    val bodies: List<MethodBody>,
    val xrefs: List<String>,
    val truncated: Boolean,
    val notes: List<String>,
) {
    fun toText(): String = buildString {
        appendLine("حزمة الكلاس: $classLocator")
        appendLine("الحقول (${fields.size}):")
        fields.forEach { appendLine("  • $it") }
        appendLine("الدوال (${methods.size}):")
        methods.forEach { appendLine("  • $it") }
        appendLine()
        for (body in bodies) {
            appendLine("--- دالة: ${body.locator} (المصدر: ${body.source}) ---")
            val text = body.pseudocode ?: body.smali
            if (text != null) appendLine(text) else appendLine("(تعذّرت قراءة جسم الدالة)")
            appendLine()
        }
        appendLine("المراجع المتقاطعة (${xrefs.size}):")
        xrefs.forEach { appendLine("  • $it") }
        if (truncated) appendLine("ملاحظة: حُذف جزء من الحزمة لتجاوز سقف الحجم.")
        notes.forEach { appendLine("ملاحظة: $it") }
    }
}

/**
 * بنّاء سياق (المرحلة ٢): يجمع مخطط الكلاس + أجسام دواله + مراجعه في
 * حزمة واحدة صغيرة.
 *
 * الترتيب: مخطط أولاً، ثم لكل دالة (بحد أقصى 8) كود شبهي بأسلوب مقروء،
 * وعند فشله بديل Smali بنفس المحدد، وعند فشل الاثنين تُسجَّل الدالة
 * كمصدر failed. ثم مرجعان متقاطعان: للكلاس ولأول دالة. الفشل هنا لطيف
 * دائماً: لا يرمي، بل يعيد حزمة بملاحظات توثق ما تعذّر.
 */
class ContextBuilder(
    private val callTool: (String, JsonObject) -> JsonObject,
) {
    companion object {
        const val MAX_METHODS = 8
        const val MAX_CHARS = 48_000
        const val MAX_XREFS = 20

        private const val TOOL_READ_OUTLINE = "mt_apk_dex_read_class_outline"
        private const val TOOL_READ_PSEUDOCODE = "mt_apk_dex_read_pseudocode"
        private const val TOOL_READ_TEXT = "mt_apk_read_text"
        private const val TOOL_DEX_XREF = "mt_apk_dex_xref"
    }

    fun build(workspaceId: String, classLocator: String): ClassPackage {
        val notes = mutableListOf<String>()
        // ١ — المخطط
        val outlineData: JsonObject = try {
            val result = callTool(TOOL_READ_OUTLINE, JsonObject().apply {
                addProperty("workspaceId", workspaceId)
                addProperty("editSessionId", "")
                addProperty("locator", classLocator)
                addProperty("limit", 200)
            })
            McpResults.dataOf(result)
        } catch (e: Exception) {
            notes += "تعذّرت قراءة مخطط الكلاس: ${e.message ?: e.toString()}"
            return ClassPackage(classLocator, emptyList(), emptyList(), emptyList(), emptyList(), false, notes)
        }
        val fields = memberNames(outlineData, "fields")
        val methodEntries = memberEntries(outlineData, "methods")
        if (methodEntries.isEmpty() && fields.isEmpty()) {
            notes += "مخطط الكلاس فارغ أو شكله غير متوقع — الرد الخام محفوظ في سجل الجلسة."
            return ClassPackage(classLocator, emptyList(), emptyList(), emptyList(), emptyList(), false, notes)
        }
        val classDesc = classLocator.removePrefix("dex_class:")
        val methodLocators = methodEntries.mapNotNull { entry ->
            methodLocatorOf(entry, classDesc)
        }.distinct()
        val methods = methodLocators.map { it.substringAfter("->") }

        // ٢ — أجسام الدوال (بحد أقصى 8 وضمن سقف 48_000 حرف)
        val bodies = mutableListOf<MethodBody>()
        var totalChars = 0
        var truncated = false
        for (locator in methodLocators.take(MAX_METHODS)) {
            if (totalChars >= MAX_CHARS) {
                truncated = true
                notes += "حُذفت أجسام الدوال المتبقية لتجاوز سقف $MAX_CHARS حرفاً."
                break
            }
            val body = readMethodBody(workspaceId, locator, notes)
            val text = body.pseudocode ?: body.smali
            if (text != null && totalChars + text.length > MAX_CHARS) {
                truncated = true
                notes += "حُذف جسم الدالة $locator والأجسام التالية لتجاوز سقف $MAX_CHARS حرفاً."
                break
            }
            if (text != null) totalChars += text.length
            bodies += body
        }
        if (methodLocators.size > MAX_METHODS) {
            truncated = true
            notes += "اقتصر البناء على أول $MAX_METHODS دوال من ${methodLocators.size}."
        }

        // ٣ — مرجعان متقاطعان: للكلاس ولأول دالة
        val xrefs = mutableListOf<String>()
        collectXrefs(workspaceId, classLocator, "not_applicable", xrefs, notes)
        val firstMethod = methodLocators.firstOrNull()
        if (firstMethod != null) {
            collectXrefs(workspaceId, firstMethod, "dispatch", xrefs, notes)
        }

        return ClassPackage(
            classLocator = classLocator,
            fields = fields,
            methods = methods,
            bodies = bodies,
            xrefs = xrefs.distinct().take(MAX_XREFS),
            truncated = truncated,
            notes = notes,
        )
    }

    /** يقرأ جسم دالة: كود شبهي أولاً، وSmali بديلاً، وfailed عند فشل الاثنين. */
    private fun readMethodBody(workspaceId: String, locator: String, notes: MutableList<String>): MethodBody {
        val pseudoArgs = JsonObject().apply {
            addProperty("workspaceId", workspaceId)
            addProperty("editSessionId", "")
            addProperty("locator", locator)
            addProperty("limit", 500)
            addProperty("maxChars", 49152)
            addProperty("startLine", 0)
            addProperty("startColumn", 0)
            addProperty("style", "readable")
        }
        try {
            val data = McpResults.dataOf(callTool(TOOL_READ_PSEUDOCODE, pseudoArgs))
            val text = McpResults.codeTextOf(data)
            if (!text.isNullOrBlank()) {
                return MethodBody(locator, pseudocode = text, smali = null, source = "pseudocode")
            }
            notes += "الكود الشبهي للدالة $locator رجع فارغاً — جرّبنا Smali بديلاً."
        } catch (e: Exception) {
            notes += "فشل الكود الشبهي للدالة $locator (${e.message ?: e.toString()}) — جرّبنا Smali بديلاً."
        }
        try {
            val data = McpResults.dataOf(callTool(TOOL_READ_TEXT, pseudoArgs))
            val text = McpResults.codeTextOf(data)
            if (!text.isNullOrBlank()) {
                return MethodBody(locator, pseudocode = null, smali = text, source = "smali")
            }
            notes += "قراءة Smali للدالة $locator رجعت فارغة."
        } catch (e: Exception) {
            notes += "فشلت قراءة Smali للدالة $locator: ${e.message ?: e.toString()}"
        }
        return MethodBody(locator, pseudocode = null, smali = null, source = "failed")
    }

    /** يجمع مراجع sourceLocator من نتيجة xref (بحد 20) أو الخام مقتطعاً. */
    private fun collectXrefs(
        workspaceId: String,
        locator: String,
        methodResolution: String,
        out: MutableList<String>,
        notes: MutableList<String>,
    ) {
        try {
            val result = callTool(TOOL_DEX_XREF, JsonObject().apply {
                addProperty("workspaceId", workspaceId)
                addProperty("editSessionId", "")
                addProperty("locator", locator)
                addProperty("methodResolution", methodResolution)
                addProperty("limit", 50)
            })
            val data = McpResults.dataOf(result)
            val refs = if (data.has("refs") && data.get("refs").isJsonArray) {
                data.getAsJsonArray("refs")
                    .mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
            } else {
                McpResults.itemsOf(data)
            }
            if (refs.isEmpty()) {
                // قد تكون المراجع تحت مفتاح آخر — احفظ الخام مقتطعاً
                val raw = Gson().toJson(data)
                if (raw.length > 200) {
                    notes += "مراجع $locator بشكل غير متوقع — الخام مقتطع: ${raw.take(500)}"
                }
                return
            }
            for (ref in refs) {
                val src = McpResults.firstString(ref, "sourceLocator")
                if (src != null) out += src
            }
        } catch (e: Exception) {
            notes += "تعذّر جلب مراجع $locator: ${e.message ?: e.toString()}"
        }
    }

    private fun memberNames(data: JsonObject, key: String): List<String> {
        return memberEntries(data, key).mapNotNull { entry ->
            McpResults.firstString(entry, "name", "descriptor", "locator")
        }
    }

    private fun memberEntries(data: JsonObject, key: String): List<JsonObject> {
        if (!data.has(key) || !data.get(key).isJsonArray) return emptyList()
        return data.getAsJsonArray(key)
            .mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
    }

    /**
     * يشتق محدد الدالة من واصف الكلاس: من dex_class:com/foo/Bar; إلى
     * dex_method:com/foo/Bar;->name(descriptor). إن حمل الإدخال محدداً
     * صريحاً يُستخدم كما هو.
     */
    internal fun methodLocatorOf(entry: JsonObject, classDesc: String): String? {
        val explicit = McpResults.firstString(entry, "locator")
        if (explicit != null && explicit.startsWith("dex_method:")) return explicit
        val name = McpResults.firstString(entry, "name") ?: return null
        val descriptor = McpResults.firstString(entry, "descriptor") ?: return null
        return "dex_method:$classDesc->$name$descriptor"
    }
}
