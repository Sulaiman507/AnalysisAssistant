package com.musaed.analysis.mcp

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** نتيجة حلقة التحليل: التقرير JSON الخام (كما ردّ به النموذج) أو الفشل مع الملاحظات. */
data class AnalysisResult(
    val success: Boolean,
    val reportJson: String?,
    val stepsUsed: Int,
    val notes: List<String>,
    val targetPackage: String,
    val workspaceId: String?,
)

/**
 * حلقة الوكيل (المرحلة 3): الفحص السريع (خطوة 0) ثم حلقة نموذج ← أداة
 * حتى التقرير النهائي أو شرط توقف أو سقف الخطوات.
 *
 * عقد الإجراءات مع النموذج (يُشرح له في رسالة المستخدم الأولى):
 *  - خطوة أداة: {"tool": "<اسم أداة MT MCP>", "arguments": {...}}
 *  - التقرير النهائي: كائن JSON مباشر بحقل verdict كما في البرومبت [٦]
 *    (ثم التقرير العربي) — وتُقبل صيغة {"final_report": {...}} أيضاً كبديل.
 * ويُقبل «name» بدلاً من «tool» و«args»/«params» بدلاً من «arguments»،
 * وتُستخرج كتلة JSON الأولى إن لفّ النموذج ردّه بنص أو بسياج ```json.
 * نتائج الأدوات تُقتطع عند 6000 حرف في رسائل النموذج (الكامل يبقى في
 * سجل الجلسة) حتى لا يمتلئ سياقه.
 *
 * البرومبت يُمرَّر كمعامل — نص System Prompt v1.1 يُوضع في SystemPrompt
 * عند توفره حرفياً، ولا يُكتب هنا نص بديل عنه.
 */
class AnalysisLoop(
    private val chat: (List<ChatMessage>) -> String,
    private val runner: ToolRunner,
    private val log: SessionLog,
    private val systemPrompt: String,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
    /** دليل الأدوات المرسل للنموذج — الأسماء والمعاملات الحرفية (ToolCatalog). */
    private val toolCatalog: String = ToolCatalog.TEXT,
) {
    companion object {
        const val DEFAULT_MAX_STEPS = 80
        const val MAX_TOOL_RESULT_CHARS = 6000
        private const val MAX_CONSECUTIVE_ERRORS = 3

        private const val TOOL_LIST_INSTALLED = "mt_apk_list_installed_apps"
        private const val TOOL_WORKSPACE_OPEN = "mt_apk_workspace_open"
    }

    /** نص الخطوة المعروض: بلا حد يُعرض «بلا حد» بدل الرقم الكبير. */
    private fun stepLabel(stepsUsed: Int): String =
        if (maxSteps == Int.MAX_VALUE) "خطوة $stepsUsed (بلا حد)" else "خطوة $stepsUsed/$maxSteps"

    fun run(
        targetPackage: String,
        userInstructions: String? = null,
        onStep: (String) -> Unit = {},
    ): AnalysisResult {
        val notes = mutableListOf<String>()

        // فتح مساحة عمل للهدف — بلا تجاوز لتطبيق آخر: التحليل يجب أن يكون
        // للتطبيق المطلوب نفسه، فإن حُظر فشل واضح برسالة صريحة.
        onStep("فتح مساحة عمل لـ $targetPackage…")
        val workspaceId = try {
            openWorkspace(targetPackage)
        } catch (e: Exception) {
            notes += e.message ?: e.toString()
            return AnalysisResult(
                success = false, reportJson = null, stepsUsed = 0,
                notes = notes, targetPackage = targetPackage, workspaceId = null,
            )
        }

        // الخطوة 0 — الفحص السريع يغذّي النموذج
        onStep("الفحص السريع (خطوة 0)…")
        val scanText = try {
            val scan = QuickScan { n, a -> runner.call(n, a, step = 0) }.scan(workspaceId)
            scan.toText()
        } catch (e: Exception) {
            notes += "تعذّر الفحص السريع: ${e.message ?: e.toString()}"
            "الفحص السريع: تعذّر — ابدأ من الصفر."
        }

        val messages = mutableListOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", userRequest(targetPackage, workspaceId, scanText, userInstructions)),
        )

        var stepsUsed = 0
        var consecutiveErrors = 0
        var consecutiveMalformed = 0
        while (stepsUsed < maxSteps) {
            stepsUsed++
            onStep("${stepLabel(stepsUsed)}: استدعاء النموذج…")
            val reply = try {
                chat(messages)
            } catch (e: Exception) {
                consecutiveErrors++
                notes += "فشل استدعاء النموذج (خطوة $stepsUsed): ${e.message ?: e.toString()}"
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    notes += "توقف بعد $MAX_CONSECUTIVE_ERRORS أخطاء متتالية من النموذج."
                    return AnalysisResult(
                        success = false, reportJson = null, stepsUsed = stepsUsed,
                        notes = notes, targetPackage = targetPackage, workspaceId = workspaceId,
                    )
                }
                messages += ChatMessage(
                    "user",
                    "تعذّر استدعاء النموذج: ${e.message ?: e.toString()} — أعد المحاولة.",
                )
                continue
            }
            consecutiveErrors = 0
            messages += ChatMessage("assistant", reply)

            when (val action = parseAction(reply)) {
                is Action.FinalReport -> {
                    return AnalysisResult(
                        success = true, reportJson = action.json, stepsUsed = stepsUsed,
                        notes = notes, targetPackage = targetPackage, workspaceId = workspaceId,
                    )
                }
                is Action.ToolCall -> {
                    consecutiveMalformed = 0
                    // تحقق محلي من الاسم قبل الوصول للخادم: الأداة المخترعة
                    // لا تُستدعى أصلاً — تُسجَّل وتُصحَّح برسالة فورية.
                    // (دليل حي 2026-11-15: النموذج طلب أدوات غير موجودة
                    // أصلاً فأضاع خطوات كاملة باستدعاء خادم + إعادة محاولة.)
                    if (action.tool !in ToolCatalog.NAMES) {
                        notes += "النموذج طلب أداة غير موجودة: ${action.tool} (خطوة $stepsUsed)."
                        log.record(
                            step = stepsUsed, attempt = 1, tool = action.tool,
                            argumentsJson = Gson().toJson(action.arguments),
                            status = "error", durationMs = 0, rawResult = "",
                            error = "الأداة ${action.tool} غير موجودة في قائمة أدوات MT MCP.",
                        )
                        messages += ChatMessage(
                            "user",
                            "الأداة ${action.tool} غير موجودة. الأدوات المتاحة هي: " +
                                ToolCatalog.NAMES.joinToString(", ") +
                                ". استخدم واحدة منها بالمعاملات الموضحة في الدليل، ولا تخترع أداة.",
                        )
                        continue
                    }
                    onStep("${stepLabel(stepsUsed)}: ${action.tool}…")
                    val result = try {
                        runner.call(action.tool, action.arguments, step = stepsUsed)
                    } catch (e: McpException) {
                        // تفصيل خطأ الخادم الكامل (المسموح + مثال) بدل الرسالة
                        // المقتطعة — به يصحّح النموذج معاملاته من محاولة واحدة.
                        val detail = McpResults.serverErrorText(e.rawResult)
                            ?: (e.message ?: e.toString())
                        messages += ChatMessage(
                            "user",
                            "الأداة ${action.tool} فشلت: $detail — صحّح المعاملات " +
                                "حسب الدليل وأعد المحاولة، أو انتقل لخطوة أخرى.",
                        )
                        continue
                    }
                    val raw = McpResults.dataOf(result).toString()
                    val shown = if (raw.length > MAX_TOOL_RESULT_CHARS) {
                        raw.take(MAX_TOOL_RESULT_CHARS) + "\n… (اقتُطعت النتيجة — الكامل في سجل الجلسة)"
                    } else {
                        raw
                    }
                    messages += ChatMessage("user", "نتيجة الأداة ${action.tool}:\n$shown")
                }
                is Action.Malformed -> {
                    consecutiveMalformed++
                    notes += "رد النموذج (خطوة $stepsUsed) لم يكن JSON صالحاً بالصيغة المطلوبة."
                    if (consecutiveMalformed >= MAX_CONSECUTIVE_ERRORS) {
                        notes += "توقف بعد $MAX_CONSECUTIVE_ERRORS ردود غير صالحة متتالية من النموذج."
                        return AnalysisResult(
                            success = false, reportJson = null, stepsUsed = stepsUsed,
                            notes = notes, targetPackage = targetPackage, workspaceId = workspaceId,
                        )
                    }
                    messages += ChatMessage(
                        "user",
                        "ردك لم يكن JSON صالحاً بالصيغة المطلوبة. أعد الإجابة بكائن JSON " +
                            "واحد فقط: {\"tool\": \"<اسم الأداة>\", \"arguments\": {...}} " +
                            "لأداة، أو كائن التقرير النهائي كما في البرومبت — بلا نص خارج JSON.",
                    )
                }
            }
        }
        notes += "انتهى سقف الخطوات ($maxSteps) دون تقرير نهائي."
        return AnalysisResult(
            success = false, reportJson = null, stepsUsed = stepsUsed,
            notes = notes, targetPackage = targetPackage, workspaceId = workspaceId,
        )
    }

    internal sealed interface Action {
        data class ToolCall(val tool: String, val arguments: JsonObject) : Action
        data class FinalReport(val json: String) : Action
        data object Malformed : Action
    }

    /** يستخرج إجراء النموذج من ردّه: أداة أو تقرير نهائي أو غير صالح. */
    internal fun parseAction(reply: String): Action {
        val jsonText = extractJson(reply) ?: return Action.Malformed
        val obj = try {
            JsonParser.parseString(jsonText).asJsonObject
        } catch (_: Exception) {
            return Action.Malformed
        }
        if (obj.has("final_report")) {
            return Action.FinalReport(obj.get("final_report").toString())
        }
        // التقرير النهائي بصيغته في البرومبت [٦]: كائن JSON مباشر بحقل
        // verdict (ثم التقرير العربي بعده) — هذه هي الصيغة المتوقعة عادة.
        if (obj.has("verdict")) {
            return Action.FinalReport(obj.toString())
        }
        val tool = firstString(obj, "tool", "name") ?: return Action.Malformed
        if (!tool.startsWith("mt_")) return Action.Malformed
        val args = when {
            obj.has("arguments") && obj.get("arguments").isJsonObject -> obj.getAsJsonObject("arguments")
            obj.has("args") && obj.get("args").isJsonObject -> obj.getAsJsonObject("args")
            obj.has("params") && obj.get("params").isJsonObject -> obj.getAsJsonObject("params")
            else -> JsonObject()
        }
        return Action.ToolCall(tool, args)
    }

    /** يستخرج أول كتلة JSON من الرد (يزيل سياج ```json والنص المحيط). */
    internal fun extractJson(reply: String): String? {
        var text = reply.trim()
        text = text.replace("```json", "").replace("```", "").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String? {
        for (k in keys) {
            if (obj.has(k) && obj.get(k).isJsonPrimitive) {
                val v = obj.get(k).asString
                if (v.isNotBlank()) return v
            }
        }
        return null
    }

    private fun userRequest(
        targetPackage: String,
        workspaceId: String,
        scanText: String,
        userInstructions: String? = null,
    ): String {
        return buildString {
            appendLine("حلّل التطبيق $targetPackage (مساحة العمل $workspaceId).")
            appendLine()
            if (!userInstructions.isNullOrBlank()) {
                appendLine("تعليمات المستخدم من المحادثة — نفّذ التحليل وفقها:")
                appendLine(userInstructions)
                appendLine()
            }
            appendLine(scanText)
            appendLine()
            appendLine(toolCatalog)
            appendLine()
            appendLine("في كل خطوة أجب بكائن JSON واحد فقط:")
            appendLine("  لتنفيذ أداة: {\"tool\": \"<اسم أداة MT MCP>\", \"arguments\": {...}}")
            appendLine("  وعند اكتمال البروتوكول: أرسل كائن JSON للتقرير كما هو معرّف في")
            appendLine("  البرومبت (الحقول: obfuscation وverdict وconfidence وevidence وgate")
            appendLine("  وpayment_sdk وsignature_check وpatch_plan وopen_questions)، ثم التقرير العربي.")
            appendLine("لا نص خارج JSON قبل كائن التقرير.")
        }
    }

    /** يفتح مساحة عمل مؤقتة للهدف؛ يرمي برسالة عربية عند الفشل. */
    private fun openWorkspace(targetPackage: String): String {
        val listed = runner.call(TOOL_LIST_INSTALLED, JsonObject().apply {
            addProperty("query", targetPackage)
            addProperty("appType", "user")
            addProperty("limit", 50)
        }, step = 0)
        var targetPath: String? = null
        for (item in McpResults.itemsOf(McpResults.dataOf(listed))) {
            val path = McpResults.firstString(item, "path") ?: continue
            val pkg = McpResults.firstString(item, "packageName", "package", "name") ?: ""
            if (pkg == targetPackage || path.contains(targetPackage)) {
                targetPath = path
                break
            }
        }
        val path = targetPath
            ?: throw McpException("لم يُعثر على التطبيق $targetPackage بين التطبيقات المثبّتة.")
        val opened = runner.call(TOOL_WORKSPACE_OPEN, JsonObject().apply {
            addProperty("path", path)
            addProperty("temporary", true)
        }, step = 0)
        return McpResults.firstString(
            McpResults.dataOf(opened), "workspaceId", "workspace_id", "id",
        ) ?: throw McpException("فُتحت مساحة العمل لكن الخادم لم يرجع معرّفها.")
    }
}
