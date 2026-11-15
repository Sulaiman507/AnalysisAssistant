package com.musaed.analysis.mcp

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** نتيجة رسالة محادثة واحدة: جواب نصي، أو طلب بدء التحليل الكامل. */
sealed interface ChatOutcome {
    data class Answer(val text: String) : ChatOutcome
    data class RunAnalysis(val instructions: String) : ChatOutcome
}

/**
 * وكيل المحادثة (ميزة المحادثة): رسالة مستخدم ← (قراءة كود عند الحاجة)
 * ← جواب نصي؛ أو طلب «نفّذ» فيُعاد للتحليل الكامل.
 *
 * عقد الردود مع النموذج (مطابق لبرومبت المحادثة):
 *  - نص عادي = الجواب النهائي لهذه الرسالة.
 *  - {"tool": "<أداة قراءة>", "arguments": {...}} = خطوة قراءة واحدة.
 *  - {"action": "run_analysis"} = المستخدم طلب بدء التحليل الكامل.
 * ويُقبل «name» بدلاً من «tool» و«args»/«params» بدلاً من «arguments»،
 * وتُستخرج كتلة JSON الأولى إن لفّ النموذج ردّه بنص أو بسياج ```json.
 *
 * ضمانات:
 *  - القائمة البيضاء فقط: أي أداة خارج ChatPrompt.WHITELIST تُرفض
 *    محلياً دون الوصول للخادم — المحادثة قراءة فقط، لا تعديل أبداً.
 *  - مساحة العمل تُفتح عند أول أداة تحتاجها فقط، وللهدف نفسه فقط —
 *    بلا تجاوز لتطبيق آخر.
 *  - عدد استدعاءات النموذج لكل رسالة محدود (maxToolSteps + 1) حتى لا
 *    تدور المحادثة بلا نهاية على حساب رصيد المستخدم.
 *  - نتائج الأدوات تُقتطع عند 4000 حرف في رسائل النموذج (الكامل يبقى
 *    في سجل المحادثة) حتى لا يمتلئ سياقه.
 */
class ChatAgent(
    private val chat: (List<ChatMessage>) -> String,
    private val runner: ToolRunner,
    private val targetPackage: String?,
    private val lastReportSummary: String?,
    private val systemPrompt: String = ChatPrompt.TEXT,
    private val maxToolSteps: Int = 5,
) {
    companion object {
        const val DEFAULT_MAX_TOOL_STEPS = 5
        const val MAX_TOOL_RESULT_CHARS = 4000
        const val TOOL_LIST_INSTALLED = "mt_apk_list_installed_apps"
        const val TOOL_WORKSPACE_OPEN = "mt_apk_workspace_open"
    }

    private var workspaceId: String? = null

    fun respond(session: ChatSession, userText: String, onStep: (String) -> Unit = {}): ChatOutcome {
        session.addUser(userText)
        val messages = mutableListOf(ChatMessage("system", systemMessage()))
        messages += session.historyForModel()

        var toolSteps = 0
        for (attempt in 1..maxToolSteps + 1) {
            onStep("النموذج يفكّر…")
            val reply = try {
                chat(messages)
            } catch (e: Exception) {
                val text = "تعذّر الوصول إلى النموذج: ${e.message ?: e.toString()}"
                session.addModel(text)
                return ChatOutcome.Answer(text)
            }
            messages += ChatMessage("assistant", reply)

            when (val parsed = parseReply(reply)) {
                is Parsed.Text -> {
                    session.addModel(reply)
                    return ChatOutcome.Answer(reply)
                }
                is Parsed.RunAnalysis -> {
                    val confirm = "حاضر — أبدأ التحليل الكامل بتعليمات المحادثة."
                    session.addModel(confirm)
                    return ChatOutcome.RunAnalysis(session.instructionsForAnalysis())
                }
                is Parsed.ToolCall -> {
                    if (parsed.tool !in ChatPrompt.WHITELIST) {
                        messages += ChatMessage(
                            "user",
                            "الأداة ${parsed.tool} ليست من أدوات القراءة المتاحة في المحادثة. " +
                                "الأدوات المتاحة هي: " + ChatPrompt.WHITELIST.joinToString(", ") +
                                ". استخدم واحدة منها، أو أجب بنص عادي.",
                        )
                        continue
                    }
                    if (toolSteps >= maxToolSteps) {
                        messages += ChatMessage(
                            "user",
                            "وصلت لحد خطوات القراءة في هذه الرسالة — أجب بما لديك بنص عادي، " +
                                "ولا تستدعِ أداة أخرى.",
                        )
                        continue
                    }
                    toolSteps++
                    onStep("قراءة الكود: ${parsed.tool}…")
                    val workspace = ensureWorkspace()
                    if (workspace == null) {
                        messages += ChatMessage(
                            "user",
                            "لم يُحدد تطبيق هدف بعد — اسأل المستخدم عن اسم حزمة التطبيق " +
                                "ليكتبه في حقل الهدف أولاً، ثم أعد المحاولة.",
                        )
                        continue
                    }
                    val args = parsed.arguments.deepCopy()
                    if (!args.has("workspaceId")) args.addProperty("workspaceId", workspace)
                    if (!args.has("editSessionId")) args.addProperty("editSessionId", "")
                    val result = try {
                        runner.call(parsed.tool, args, step = 0)
                    } catch (e: McpException) {
                        val detail = McpResults.serverErrorText(e.rawResult)
                            ?: (e.message ?: e.toString())
                        messages += ChatMessage(
                            "user",
                            "الأداة ${parsed.tool} فشلت: $detail — صحّح المعاملات " +
                                "حسب الدليل وأعد المحاولة، أو أجب بما لديك.",
                        )
                        continue
                    }
                    val raw = McpResults.dataOf(result).toString()
                    val shown = if (raw.length > MAX_TOOL_RESULT_CHARS) {
                        raw.take(MAX_TOOL_RESULT_CHARS) + "\n… (اقتُطعت النتيجة — الكامل في سجل المحادثة)"
                    } else {
                        raw
                    }
                    messages += ChatMessage("user", "نتيجة الأداة ${parsed.tool}:\n$shown")
                }
            }
        }
        val text = "وصلت لحد خطوات القراءة في هذه الرسالة — اسأل سؤالاً أضيق، " +
            "أو قل «نفّذ» للتحليل الكامل."
        session.addModel(text)
        return ChatOutcome.Answer(text)
    }

    internal sealed interface Parsed {
        data object Text : Parsed
        data object RunAnalysis : Parsed
        data class ToolCall(val tool: String, val arguments: JsonObject) : Parsed
    }

    /** يفسّر رد النموذج: نص عادي، أو أداة قراءة، أو طلب تحليل كامل. */
    internal fun parseReply(reply: String): Parsed {
        val jsonText = extractJson(reply) ?: return Parsed.Text
        val obj = try {
            JsonParser.parseString(jsonText).asJsonObject
        } catch (_: Exception) {
            return Parsed.Text
        }
        if (obj.has("action") && obj.get("action").isJsonPrimitive &&
            obj.get("action").asString == "run_analysis"
        ) {
            return Parsed.RunAnalysis
        }
        val tool = firstString(obj, "tool", "name") ?: return Parsed.Text
        if (!tool.startsWith("mt_")) return Parsed.Text
        val args = when {
            obj.has("arguments") && obj.get("arguments").isJsonObject -> obj.getAsJsonObject("arguments")
            obj.has("args") && obj.get("args").isJsonObject -> obj.getAsJsonObject("args")
            obj.has("params") && obj.get("params").isJsonObject -> obj.getAsJsonObject("params")
            else -> JsonObject()
        }
        return Parsed.ToolCall(tool, args)
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

    /** سياق المحادثة: البرومبت + الهدف الحالي + ملخص آخر تقرير (إن وجد). */
    private fun systemMessage(): String {
        return buildString {
            append(systemPrompt)
            appendLine()
            appendLine()
            appendLine("التطبيق الهدف الحالي: ${targetPackage ?: "لم يُحدد بعد — اسأل المستخدم عن اسم الحزمة إن احتجت قراءة الكود."}")
            if (!lastReportSummary.isNullOrBlank()) {
                appendLine("آخر تقرير تحليل لهذا التطبيق (للشرح منه عند السؤال عنه):")
                appendLine(lastReportSummary)
            } else {
                appendLine("لا يوجد تقرير تحليل بعد لهذا التطبيق.")
            }
        }
    }

    /**
     * يفتح مساحة عمل مؤقتة للهدف عند أول حاجة؛ يعيد null إن لم يُحدد
     * هدف — فيسأل النموذج المستخدم عنه بدل الفشل الصامت. يرمي برسالة
     * عربية عند فشل الفتح لسبب آخر.
     */
    private fun ensureWorkspace(): String? {
        workspaceId?.let { return it }
        val target = targetPackage?.trim().orEmpty()
        if (target.isBlank()) return null
        val listed = runner.call(TOOL_LIST_INSTALLED, JsonObject().apply {
            addProperty("query", target)
            addProperty("appType", "user")
            addProperty("limit", 50)
        }, step = 0)
        var targetPath: String? = null
        for (item in McpResults.itemsOf(McpResults.dataOf(listed))) {
            val path = McpResults.firstString(item, "path") ?: continue
            val pkg = McpResults.firstString(item, "packageName", "package", "name") ?: ""
            if (pkg == target || path.contains(target)) {
                targetPath = path
                break
            }
        }
        val path = targetPath
            ?: throw McpException("لم يُعثر على التطبيق $target بين التطبيقات المثبّتة.")
        val opened = runner.call(TOOL_WORKSPACE_OPEN, JsonObject().apply {
            addProperty("path", path)
            addProperty("temporary", true)
        }, step = 0)
        val id = McpResults.firstString(
            McpResults.dataOf(opened), "workspaceId", "workspace_id", "id",
        ) ?: throw McpException("فُتحت مساحة العمل لكن الخادم لم يرجع معرّفها.")
        workspaceId = id
        return id
    }
}
