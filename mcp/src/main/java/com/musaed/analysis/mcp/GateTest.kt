package com.musaed.analysis.mcp

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * اختبار بوابة قبول المرحلة ١: فتح APK حقيقي عبر MCP وقراءة مخطط كلاس واحد
 * على الأقل من الجهاز.
 *
 * الخطوات بالترتيب:
 *  ١ — البحث عن تطبيق الاختبار بين التطبيقات المثبّتة، وإن لم يكن مثبّتاً
 *      فالبحث عن ملف الـ APK في Home كمحاولة بديلة.
 *  ٢ — فتح مساحة عمل مؤقتة للهدف.
 *  ٣ — سرد كلاسات DEX للعثور على كلاس البوابة.
 *  ٤ — قراءة مخطط الكلاس (الحقول والدوال).
 *
 * أسماء الأدوات المستخدمة هنا كلها أسماء حقيقية من القائمة الحية التي جُلبت
 * من جهاز المستخدم (67 أداة، الخادم MT MCP 0.2.0، جُلبت 2026-11-14 19:14:59)
 * — لا يوجد اسم مخترع. سكربت tools/verify-local.sh يتحقق من ذلك ضد ملف
 * القائمة الحية قبل كل بناء.
 *
 * ملاحظة عن شكل الردود: مخططات الإدخال مأخوذة حرفياً من القائمة الحية، أما
 * شكل حقل result الراجع فمُحلَّل بتسامح (structuredContent أو نص content
 * بصيغة JSON أو النتيجة نفسها) لأن شكله الدقيق لم يُرصد حياً بعد — أول
 * رصد حي له يكون على الهاتف عند تشغيل هذا الاختبار، والتقرير يحفظ الرد
 * الخام (مقتطعاً) عند عدم تطابق الشكل لتشخيصه.
 */
class GateTester(
    private val callTool: (String, JsonObject) -> JsonObject,
) {
    companion object {
        const val TEST_PACKAGE = "com.musaed.test.clear"
        const val GATE_CLASS_SIMPLE_NAME = "PremiumGate"
        const val GATE_APK_FILE = "clear.apk"

        private const val TOOL_LIST_INSTALLED = "mt_apk_list_installed_apps"
        private const val TOOL_LIST_AVAILABLE = "mt_apk_list_available_apks"
        private const val TOOL_WORKSPACE_OPEN = "mt_apk_workspace_open"
        private const val TOOL_DEX_LIST_CLASSES = "mt_apk_dex_list_classes"
        private const val TOOL_DEX_READ_OUTLINE = "mt_apk_dex_read_class_outline"
    }

    /** ينفذ الاختبار كاملاً ولا يرمي — أي فشل يرجع كنتيجة فاشلة موثقة. */
    fun run(onStep: (String) -> Unit = {}): GateTestResult {
        val steps = mutableListOf<String>()
        var workspaceId: String? = null
        var locator: String? = null
        var targetPath: String? = null
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        fun log(s: String) {
            steps += s
            onStep(s)
        }
        try {
            // الخطوة ١: البحث عن تطبيق الاختبار المثبّت
            log("١ — البحث عن تطبيق الاختبار $TEST_PACKAGE بين التطبيقات المثبّتة…")
            val installed = checkedCall(TOOL_LIST_INSTALLED, JsonObject().apply {
                addProperty("query", TEST_PACKAGE)
                addProperty("appType", "user")
                addProperty("limit", 50)
            })
            targetPath = findInstalledPath(installed)
            if (targetPath != null) {
                log("   وُجد مثبّتاً: $targetPath")
            } else {
                log("   غير مثبّت — البحث عن ملف $GATE_APK_FILE في Home…")
                val available = checkedCall(TOOL_LIST_AVAILABLE, JsonObject().apply {
                    addProperty("prefix", "")
                    addProperty("limit", 50)
                })
                targetPath = findAvailableApkPath(available)
                if (targetPath != null) log("   وُجد الملف: $targetPath")
            }
            if (targetPath == null) {
                val msg = "تطبيق الاختبار $TEST_PACKAGE غير مثبّت على الهاتف، ولم يُعثر على ملف $GATE_APK_FILE في Home. ثبّت ملف $GATE_APK_FILE أولاً ثم أعد الاختبار."
                log("✗ $msg")
                return fail(now, targetPath, workspaceId, locator, steps, msg)
            }

            // الخطوة ٢: فتح مساحة عمل مؤقتة
            log("٢ — فتح مساحة عمل مؤقتة للهدف…")
            val opened = checkedCall(TOOL_WORKSPACE_OPEN, JsonObject().apply {
                addProperty("path", targetPath)
                addProperty("temporary", true)
            })
            workspaceId = workspaceIdOf(opened)
                ?: return fail(
                    now, targetPath, workspaceId, locator, steps,
                    "فُتحت مساحة العمل لكن الخادم لم يرجع معرّف مساحة عمل. الرد: ${Gson().toJson(opened).take(300)}",
                )
            log("   مساحة العمل: $workspaceId")

            // الخطوة ٣: سرد كلاسات DEX والعثور على كلاس البوابة
            log("٣ — سرد كلاسات DEX للعثور على كلاس البوابة $GATE_CLASS_SIMPLE_NAME…")
            val classes = checkedCall(TOOL_DEX_LIST_CLASSES, JsonObject().apply {
                addProperty("workspaceId", workspaceId)
                addProperty("editSessionId", "")
                addProperty("prefix", "")
                addProperty("limit", 200)
            })
            locator = findGateLocator(classes)
                ?: return fail(
                    now, targetPath, workspaceId, locator, steps,
                    "لم يُعثر على كلاس $GATE_CLASS_SIMPLE_NAME بين كلاسات DEX (أول 200 كلاس).",
                )
            log("   الكلاس: $locator")

            // الخطوة ٤: قراءة مخطط الكلاس
            log("٤ — قراءة مخطط الكلاس…")
            val outline = checkedCall(TOOL_DEX_READ_OUTLINE, JsonObject().apply {
                addProperty("workspaceId", workspaceId)
                addProperty("editSessionId", "")
                addProperty("locator", locator)
                addProperty("limit", 200)
            })
            val outlineText = formatOutline(outline)
            log("✓ تم فتح $TEST_PACKAGE وقراءة مخطط $GATE_CLASS_SIMPLE_NAME")
            val report = buildReport(
                now, targetPath, workspaceId, locator, outlineText, steps,
                success = true, message = null,
            )
            return GateTestResult(
                success = true,
                summary = "نجح اختبار البوابة ✓ — فُتح $TEST_PACKAGE وقُرئ مخطط $GATE_CLASS_SIMPLE_NAME",
                report = report,
                workspaceId = workspaceId,
                classLocator = locator,
            )
        } catch (e: McpException) {
            val msg = e.message ?: e.toString()
            log("✗ $msg")
            return fail(now, targetPath, workspaceId, locator, steps, msg)
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            log("✗ $msg")
            return fail(now, targetPath, workspaceId, locator, steps, msg)
        }
    }

    private fun fail(
        now: String,
        targetPath: String?,
        workspaceId: String?,
        locator: String?,
        steps: List<String>,
        message: String,
    ): GateTestResult {
        val report = buildReport(
            now, targetPath, workspaceId, locator, outlineText = "", steps,
            success = false, message = message,
        )
        return GateTestResult(
            success = false,
            summary = message,
            report = report,
            workspaceId = workspaceId,
            classLocator = locator,
        )
    }

    /** يستدعي الأداة ويرمي McpException إن أعادت isError=true. */
    private fun checkedCall(name: String, args: JsonObject): JsonObject {
        val result = callTool(name, args)
        if (result.has("isError") && result.get("isError").isJsonPrimitive &&
            result.get("isError").asBoolean
        ) {
            val text = runCatching { Gson().toJson(McpResults.dataOf(result)) }.getOrNull() ?: ""
            throw McpException("الأداة $name أعادت خطأ: ${text.take(300)}")
        }
        return result
    }


    /** يبحث في قائمة التطبيقات المثبّتة عن حزمة الاختبار ويعيد مسارها. */
    internal fun findInstalledPath(result: JsonObject): String? {
        for (item in McpResults.itemsOf(McpResults.dataOf(result))) {
            val path = McpResults.firstString(item, "path")
            val pkg = McpResults.firstString(item, "packageName", "package", "name")
            if (path != null && (path.contains(TEST_PACKAGE) || pkg == TEST_PACKAGE)) return path
        }
        return null
    }

    /** يبحث في ملفات APK المتاحة في Home عن ملف تطبيق الاختبار. */
    internal fun findAvailableApkPath(result: JsonObject): String? {
        val want = GATE_APK_FILE.lowercase(Locale.US)
        for (item in McpResults.itemsOf(McpResults.dataOf(result))) {
            val path = McpResults.firstString(item, "path") ?: continue
            val lower = path.lowercase(Locale.US)
            if (lower == want || lower.endsWith("/$want")) return path
        }
        return null
    }

    internal fun workspaceIdOf(result: JsonObject): String? {
        return McpResults.firstString(McpResults.dataOf(result), "workspaceId", "workspace_id", "id")
            ?: McpResults.firstString(result, "workspaceId", "workspace_id", "id")
    }

    /**
     * يعثر على كلاس البوابة بين الكلاسات ويعيد محدده حرفياً كما رجع من الخادم.
     * إن لم يوجد محدد صريح، يُشتق من الواصف (L<com/.../PremiumGate;) كبديل.
     */
    internal fun findGateLocator(result: JsonObject): String? {
        for (item in McpResults.itemsOf(McpResults.dataOf(result))) {
            val locator = McpResults.firstString(item, "locator")
            val name = McpResults.firstString(item, "name", "className", "descriptor", "class")
            val haystack = listOfNotNull(locator, name).joinToString(" ")
            if (!haystack.contains(GATE_CLASS_SIMPLE_NAME)) continue
            if (locator != null) return locator
            val desc = McpResults.firstString(item, "descriptor")
            if (desc != null && desc.startsWith("L") && desc.endsWith(";")) {
                return "dex_class:" + desc.removePrefix("L")
            }
            if (name != null) {
                val slashName = name.replace('.', '/')
                return "dex_class:$slashName;"
            }
        }
        return null
    }

    /** ينسّق مخطط الكلاس: الحقول ثم الدوال؛ وإن لم يُعرف الشكل يعرض الرد الخام. */
    internal fun formatOutline(result: JsonObject): String {
        val data = McpResults.dataOf(result)
        val sb = StringBuilder()
        var found = 0
        for ((label, key) in listOf("الحقول" to "fields", "الدوال" to "methods")) {
            if (data.has(key) && data.get(key).isJsonArray) {
                val arr = data.getAsJsonArray(key)
                sb.appendLine("$label (${arr.size()}):")
                for (el in arr) {
                    sb.appendLine("  • " + entryLine(el))
                    found++
                }
            }
        }
        if (found == 0) {
            sb.appendLine("المخطط الخام (شكله غير متوقع — للتشخيص):")
            sb.appendLine(Gson().toJson(data).take(2000))
        }
        return sb.toString().trimEnd()
    }

    private fun entryLine(el: JsonElement): String {
        if (!el.isJsonObject) return el.toString().take(200)
        val obj = el.asJsonObject
        val name = McpResults.firstString(obj, "name", "descriptor", "locator", "signature", "text")
        val extra = McpResults.firstString(obj, "type", "returnType")
        return when {
            name != null && extra != null && extra != name -> "$name : $extra"
            name != null -> name
            else -> Gson().toJson(obj).take(200)
        }
    }

    private fun buildReport(
        now: String,
        targetPath: String?,
        workspaceId: String?,
        locator: String?,
        outlineText: String,
        steps: List<String>,
        success: Boolean,
        message: String?,
    ): String {
        return buildString {
            appendLine("اختبار بوابة قبول المرحلة ١ — مساعد التحليل")
            appendLine("التاريخ: $now")
            appendLine("التطبيق الهدف: $TEST_PACKAGE")
            appendLine("المسار: ${targetPath ?: "—"}")
            appendLine("مساحة العمل: ${workspaceId ?: "—"}")
            appendLine("الكلاس: ${locator ?: "—"}")
            if (success) {
                appendLine("النتيجة: ناجح ✓ — فُتح APK حقيقي عبر MCP وقُرئ مخطط كلاس واحد على الأقل")
            } else {
                appendLine("النتيجة: فاشل ✗ — ${message ?: ""}")
            }
            appendLine()
            appendLine("الخطوات:")
            steps.forEach { appendLine("  $it") }
            if (outlineText.isNotBlank()) {
                appendLine()
                appendLine("مخطط الكلاس:")
                appendLine(outlineText)
            }
        }
    }
}

/** نتيجة اختبار البوابة: نجاح/فشل + ملخص عربي + تقرير نصي كامل. */
data class GateTestResult(
    val success: Boolean,
    val summary: String,
    val report: String,
    val workspaceId: String?,
    val classLocator: String?,
)
