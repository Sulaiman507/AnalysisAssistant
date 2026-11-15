package com.musaed.analysis.mcp

import com.google.gson.JsonObject

/** مستوى التشويش من الفحص السريع. */
enum class ObfuscationLevel { NONE, MEDIUM, HIGH }

/**
 * نتيجة الفحص السريع (خطوة 0 من المرحلة 3): بحث بالكلمات + كشف مكتبة
 * دفع + قياس تشويش — تغذي النموذج قبل بدء الحلقة، ولا تحكم وحدها.
 *
 * الفحص لطيف دائماً: أي بحث يفشل يُسجَّل في الملاحظات ولا يرمي؛ وإن تعذّر
 * قياس التشويش يُعتبر متوسطاً بحذر مع ملاحظة بذلك.
 */
data class QuickScanResult(
    val obfuscation: ObfuscationLevel,
    val obfuscationMeasured: Boolean,
    val shortNameRatio: Double,
    val classesSampled: Int,
    val keywordsFound: List<String>,
    val keywordsChecked: List<String>,
    val billingSdkFound: Boolean,
    val billingSdkName: String?,
    val billingSdksFound: List<String> = emptyList(),
    val notes: List<String>,
) {
    fun toText(): String = buildString {
        appendLine("الفحص السريع (خطوة 0):")
        val levelText = when (obfuscation) {
            ObfuscationLevel.NONE -> "منعدمة"
            ObfuscationLevel.MEDIUM -> "متوسطة"
            ObfuscationLevel.HIGH -> "عالية"
        }
        appendLine("  التشويش: $levelText" + if (obfuscationMeasured) {
            " (نسبة الأسماء القصيرة ${"%.0f".format(shortNameRatio * 100)}٪ من $classesSampled كلاساً)"
        } else {
            " (لم تُقَس — اعتُبرت متوسطة بحذر)"
        })
        appendLine("  الكلمات الموجودة: " + if (keywordsFound.isEmpty()) "لا شيء" else keywordsFound.joinToString(", "))
        appendLine("  مكتبة الدفع: " + if (billingSdksFound.isEmpty()) "لا شيء" else billingSdksFound.joinToString(", "))
        notes.forEach { appendLine("  ملاحظة: $it") }
    }
}

/**
 * الفحص السريع (المرحلة 3، خطوة 0): يبحث بالكلمات عن آثار الفحص المدفوع،
 * ويكشف مكتبة الدفع، ويقيس التشويش من نسبة أسماء الكلاسات القصيرة.
 *
 * الكلمات مأخوذة من أنماط الفحص المعروفة (premium/isPremium/isPro/
 * hasPremium/vip/subscri/billing/purchase) ومن الكود الحالي الذي يبحث
 * أصلاً عن premium وisPremium وBillingClient. عتبات التشويش تقديرية:
 * أقل من 10٪ أسماء قصيرة = منعدمة، وأقل من 50٪ = متوسطة، وفوقها = عالية.
 */
class QuickScan(
    private val callTool: (String, JsonObject) -> JsonObject,
) {
    companion object {
        /**
         * كلمات الخطوة 1 من البرومبت + isPremium/hasPremium (من الكود الحالي):
         * premium, isPremium, isPro, hasPremium, vip, subscri, billing,
         * purchase, entitlement.
         */
        val KEYWORDS = listOf(
            "premium", "isPremium", "isPro", "hasPremium",
            "vip", "subscri", "billing", "purchase", "entitlement",
        )

        /** مكتبات الدفع من الخطوة 1 في البرومبت. */
        val BILLING_SDKS = listOf(
            "BillingClient", "RevenueCat", "Adapty", "Qonversion", "Superwall",
        )

        private const val TOOL_SEARCH = "mt_apk_search"
        private const val TOOL_DEX_LIST_CLASSES = "mt_apk_dex_list_classes"
    }

    fun scan(workspaceId: String): QuickScanResult {
        val notes = mutableListOf<String>()
        val found = mutableListOf<String>()
        for (keyword in KEYWORDS) {
            try {
                val data = McpResults.dataOf(
                    callTool(TOOL_SEARCH, searchArgs(workspaceId, "overview", keyword))
                )
                if (McpResults.searchHitsCount(data) > 0) found += keyword
            } catch (e: Exception) {
                notes += "تعذّر البحث عن الكلمة $keyword: ${e.message ?: e.toString()}"
            }
        }

        val sdksFound = mutableListOf<String>()
        for (sdk in BILLING_SDKS) {
            try {
                val data = McpResults.dataOf(
                    callTool(TOOL_SEARCH, searchArgs(workspaceId, "dex_names", sdk))
                )
                if (McpResults.searchHitsCount(data) > 0) sdksFound += sdk
            } catch (e: Exception) {
                notes += "تعذّر البحث عن مكتبة الدفع $sdk: ${e.message ?: e.toString()}"
            }
        }

        var sampled = 0
        var short = 0
        var measured = false
        try {
            val data = McpResults.dataOf(
                callTool(TOOL_DEX_LIST_CLASSES, JsonObject().apply {
                    addProperty("workspaceId", workspaceId)
                    addProperty("editSessionId", "")
                    addProperty("prefix", "")
                    addProperty("limit", 200)
                })
            )
            val names = McpResults.itemsOf(data).mapNotNull { simpleNameOf(it) }
            sampled = names.size
            short = names.count { it.length <= 2 }
            measured = true
        } catch (e: Exception) {
            notes += "تعذّر قياس التشويش (سرد الكلاسات): ${e.message ?: e.toString()}"
        }
        val ratio = if (sampled > 0) short.toDouble() / sampled else 0.0
        val level = when {
            !measured -> ObfuscationLevel.MEDIUM
            ratio < 0.10 -> ObfuscationLevel.NONE
            ratio < 0.50 -> ObfuscationLevel.MEDIUM
            else -> ObfuscationLevel.HIGH
        }
        if (!measured) notes += "لم تُقَس نسبة التشويش — اعتُبرت متوسطة بحذر."

        return QuickScanResult(
            obfuscation = level,
            obfuscationMeasured = measured,
            shortNameRatio = ratio,
            classesSampled = sampled,
            keywordsFound = found,
            keywordsChecked = KEYWORDS,
            billingSdkFound = sdksFound.isNotEmpty(),
            billingSdkName = sdksFound.firstOrNull(),
            billingSdksFound = sdksFound,
            notes = notes,
        )
    }

    private fun searchArgs(workspaceId: String, target: String, query: String): JsonObject {
        return JsonObject().apply {
            addProperty("workspaceId", workspaceId)
            addProperty("editSessionId", "")
            addProperty("target", target)
            addProperty("query", query)
            addProperty("queryType", "literal")
            addProperty("caseSensitive", false)
            addProperty("matchMode", "contains")
            addProperty("prefix", "")
            addProperty("includeMatchOffsets", false)
            addProperty("limit", 50)
            addProperty("snippetMaxChars", 240)
        }
    }

    /**
     * الاسم البسيط للكلاس: من javaName أولاً (كما يرجعها السرد حياً)،
     * وإن غاب فمن المحدد. يُهمل الإدخال إن لم يوجد فيه اسم.
     */
    internal fun simpleNameOf(item: JsonObject): String? {
        val javaName = McpResults.firstString(item, "javaName")
        if (javaName != null) {
            val simple = javaName.substringAfterLast('.').trim()
            if (simple.isNotEmpty()) return simple
        }
        val locator = McpResults.firstString(item, "locator") ?: return null
        val body = locator.removePrefix("dex_class:").trimEnd(';').removePrefix("L")
        val simple = body.substringAfterLast('/').substringAfterLast('.').trim()
        return simple.ifEmpty { null }
    }
}
