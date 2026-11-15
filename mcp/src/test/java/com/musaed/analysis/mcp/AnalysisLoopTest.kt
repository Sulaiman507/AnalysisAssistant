package com.musaed.analysis.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisLoopTest {

    /** برومبت اختبار قصير — ليس برومبت الإنتاج v1.1. */
    private val testPrompt = "TEST PROMPT — not the production System Prompt v1.1."

    private val outline = """
        {"data":{
          "fields":[{"name":"isPremium","descriptor":"Z"}],
          "methods":[
            {"name":"hasPremiumFeatures","descriptor":"(Landroid/content/Context;)Z"}
          ]
        }}
    """.trimIndent()

    private val emptySearch =
        """{"data":{"hits":[],"pagination":{"returnedCount":0}}}"""

    private val classes =
        """{"data":{"items":[
          {"locator":"dex_class:com/musaed/test/clear/PremiumGate;","javaName":"com.musaed.test.clear.PremiumGate"},
          {"locator":"dex_class:com/musaed/test/clear/MainActivity;","javaName":"com.musaed.test.clear.MainActivity"},
          {"locator":"dex_class:com/musaed/test/clear/App;","javaName":"com.musaed.test.clear.App"}
        ]}}"""

    private class FakeModel(private val replies: List<String>) {
        private var index = 0
        val messagesSeen = mutableListOf<List<ChatMessage>>()

        fun chat(messages: List<ChatMessage>): String {
            messagesSeen += messages.toList()
            if (index >= replies.size) return replies.last()
            return replies[index++]
        }
    }

    private fun loopServer() = FakeServer(
        mapOf(
            "mt_apk_list_installed_apps" to FakeMcp.toolResult(
                """{"data":{"items":[{"path":"/data/app/com.musaed.test.clear/base.apk","packageName":"com.musaed.test.clear"}]}}"""
            ),
            "mt_apk_workspace_open" to FakeMcp.toolResult("""{"data":{"workspaceId":"ws-1"}}"""),
            "mt_apk_dex_read_class_outline" to FakeMcp.toolResult(outline),
        ),
        sequential = mapOf(
            "mt_apk_search" to List(14) { FakeMcp.toolResult(emptySearch) },
            "mt_apk_dex_list_classes" to listOf(FakeMcp.toolResult(classes)),
        ),
    )

    private fun makeLoop(model: FakeModel, maxSteps: Int = 40): Pair<AnalysisLoop, SessionLog> {
        val server = loopServer()
        val log = SessionLog()
        val runner = ToolRunner(server::call, log, timeoutMs = 5_000)
        val loop = AnalysisLoop(model::chat, runner, log, testPrompt, maxSteps = maxSteps)
        return loop to log
    }

    private val outlineAction =
        """{"tool": "mt_apk_dex_read_class_outline", "arguments": {"workspaceId": "ws-1", "editSessionId": "", "locator": "dex_class:com/musaed/test/clear/PremiumGate;"}}"""
    private val finalReport =
        """{"final_report": {"verdict": "local", "gate": "PremiumGate"}}"""

    @Test
    fun `runs tool then final report`() {
        val model = FakeModel(listOf(outlineAction, finalReport))
        val (loop, log) = makeLoop(model)
        val result = loop.run("com.musaed.test.clear")
        assertTrue(result.success)
        assertTrue(result.reportJson!!.contains("PremiumGate"))
        assertEquals(2, result.stepsUsed)
        assertEquals("ws-1", result.workspaceId)
        // رسالة المستخدم الأولى تحمل الهدف والفحص السريع
        val firstUser = model.messagesSeen[0][1]
        assertTrue(firstUser.content.contains("com.musaed.test.clear"))
        assertTrue(firstUser.content.contains("الفحص السريع"))
        // الأداة سُجلت في سجل الجلسة
        assertTrue(log.stepsPresent().contains(1))
        assertTrue(log.entries().any { it.tool == "mt_apk_dex_read_class_outline" })
    }

    /** التقرير النهائي بصيغته في البرومبت [٦]: كائن JSON مباشر بحقل verdict ثم التقرير العربي. */
    private val bareReport =
        """{"obfuscation": "none", "verdict": "local", "confidence": 0.9,
            "evidence": [{"id": 1, "tool": "mt_apk_dex_read_class_outline", "identifier": "dex_class:com/musaed/test/clear/PremiumGate;", "excerpt": "hasPremiumFeatures"}],
            "gate": {"class": "PremiumGate", "method": "hasPremiumFeatures", "signature": "(Landroid/content/Context;)Z"},
            "payment_sdk": "none", "signature_check": {"found": false, "evidence_id": 0},
            "patch_plan": [], "open_questions": []}
            التقرير: الفحص محلي في PremiumGate."""

    @Test
    fun `accepts the bare report object from the prompt`() {
        val model = FakeModel(listOf(outlineAction, bareReport))
        val (loop, _) = makeLoop(model)
        val result = loop.run("com.musaed.test.clear")
        assertTrue(result.success)
        val report = com.google.gson.JsonParser.parseString(result.reportJson!!).asJsonObject
        assertEquals("local", report.get("verdict").asString)
        assertEquals("PremiumGate", report.getAsJsonObject("gate").get("class").asString)
        assertEquals(2, result.stepsUsed)
    }

    @Test
    fun `recovers from a malformed reply`() {        val model = FakeModel(listOf("هذا نص بلا JSON", outlineAction, finalReport))
        val (loop, _) = makeLoop(model)
        val result = loop.run("com.musaed.test.clear")
        assertTrue(result.success)
        assertEquals(3, result.stepsUsed)
        assertTrue(result.notes.any { it.contains("JSON") })
    }

    @Test
    fun `stops at the step cap`() {
        val model = FakeModel(listOf(outlineAction))
        val (loop, _) = makeLoop(model, maxSteps = 5)
        val result = loop.run("com.musaed.test.clear")
        assertFalse(result.success)
        assertEquals(5, result.stepsUsed)
        assertTrue(result.notes.any { it.contains("سقف الخطوات") })
    }

    @Test
    fun `parses fenced json and name alias`() {
        val model = FakeModel(emptyList())
        val (loop, _) = makeLoop(model)
        val action = loop.parseAction("```json\n{\"name\": \"mt_apk_search\", \"args\": {\"query\": \"premium\"}}\n```")
        assertEquals("ToolCall", action.javaClass.simpleName)
    }

    @Test
    fun `rejects non-mt tools and garbage`() {
        val model = FakeModel(emptyList())
        val (loop, _) = makeLoop(model)
        assertEquals("Malformed", loop.parseAction("{\"tool\": \"some_other_tool\"}").javaClass.simpleName)
        assertEquals("Malformed", loop.parseAction("لا يوجد JSON هنا").javaClass.simpleName)
        assertEquals("FinalReport", loop.parseAction(finalReport).javaClass.simpleName)
    }

    /**
     * دليل حي 2026-11-15: النموذج طلب mt_apk_dex_search (غير موجودة)
     * فأضاع خطوة كاملة باستدعاء خادم + إعادة محاولة. الآن تُرفض محلياً
     * دون الوصول للخادم، وتُصحَّح برسالة فورية.
     */
    @Test
    fun `rejects an invented tool name without calling the server`() {
        val invented =
            """{"tool": "mt_apk_dex_search", "arguments": {"query": "premium"}}"""
        val model = FakeModel(listOf(invented, outlineAction, finalReport))
        val server = loopServer()
        val log = SessionLog()
        val runner = ToolRunner(server::call, log, timeoutMs = 5_000)
        val loop = AnalysisLoop(model::chat, runner, log, testPrompt, maxSteps = 40)
        val result = loop.run("com.musaed.test.clear")
        assertTrue(result.success)
        assertEquals(3, result.stepsUsed)
        // الخادم لم يُستدعَ بالأداة المخترعة أصلاً
        assertFalse(server.calls.any { it.first == "mt_apk_dex_search" })
        // وسُجلت محلياً كخطأ واحد (بلا إعادة محاولة)
        assertEquals(1, log.entries().count { it.tool == "mt_apk_dex_search" })
        assertTrue(log.entries().any { it.tool == "mt_apk_dex_search" && it.status == "error" })
        // ورسالة التصحيح وصلت النموذج قبل خطوته التالية
        assertTrue(model.messagesSeen[1].last().content.contains("غير موجودة"))
        // ورسالة المستخدم الأولى تحمل دليل الأدوات
        val firstUser = model.messagesSeen[0][1]
        assertTrue(firstUser.content.contains("الأدوات المتاحة"))
        assertTrue(firstUser.content.contains("dex_class:"))
    }

    /**
     * دليل حي 2026-11-15: النموذج استدعى mt_apk_dex_list_classes بمعامل
     * package فرفضها الخادم بـ INVALID_ARGUMENT مع allowedValues وexample —
     * لكن الرسالة المقتطعة لم توصلها له. الآن يصله التفصيل كاملاً فيصحّح.
     */
    @Test
    fun `passes the server error detail back so the model can correct its arguments`() {
        val serverError = FakeMcp.errorResult(
            """{"ok":false,"data":null,"error":{"code":"INVALID_ARGUMENT","message":"Invalid mt_apk_dex_list_classes argument","allowedValues":["workspaceId","editSessionId","prefix","limit"],"example":"{}"}}"""
        )
        val badArgs =
            """{"tool": "mt_apk_dex_list_classes", "arguments": {"package": "com.musaed.test.clear"}}"""
        val goodArgs =
            """{"tool": "mt_apk_dex_list_classes", "arguments": {"workspaceId": "ws-1", "editSessionId": "", "prefix": "", "limit": 200}}"""
        val model = FakeModel(listOf(badArgs, goodArgs, finalReport))
        val server = FakeServer(
            mapOf(
                "mt_apk_list_installed_apps" to FakeMcp.toolResult(
                    """{"data":{"items":[{"path":"/data/app/com.musaed.test.clear/base.apk","packageName":"com.musaed.test.clear"}]}}"""
                ),
                "mt_apk_workspace_open" to FakeMcp.toolResult("""{"data":{"workspaceId":"ws-1"}}"""),
                "mt_apk_dex_read_class_outline" to FakeMcp.toolResult(outline),
            ),
            sequential = mapOf(
                "mt_apk_search" to List(14) { FakeMcp.toolResult(emptySearch) },
                // الفحص السريع (خطوة 0) يستهلك الأول، ثم خطأ الخادم، ثم النجاح
                "mt_apk_dex_list_classes" to listOf(
                    FakeMcp.toolResult(classes), serverError, FakeMcp.toolResult(classes),
                ),
            ),
        )
        val log = SessionLog()
        val runner = ToolRunner(server::call, log, timeoutMs = 5_000)
        val loop = AnalysisLoop(model::chat, runner, log, testPrompt, maxSteps = 40)
        val result = loop.run("com.musaed.test.clear")
        assertTrue(result.success)
        assertEquals(3, result.stepsUsed)
        // رسالة التصحيح تحمل المعاملات المسموحة كاملة (لا رسالة مقتطعة)
        assertTrue(model.messagesSeen[1].last().content.contains("المعاملات المسموحة: workspaceId, editSessionId, prefix, limit"))
        // وخطأ الخادم سُجل مرة واحدة فقط (بلا إعادة محاولة)
        assertEquals(1, log.entries().count { it.tool == "mt_apk_dex_list_classes" && it.status == "error" })
    }

    /** تعليمات المحادثة تصل للحلقة مع رسالة المستخدم الأولى. */
    @Test
    fun `user instructions reach the first user message`() {
        val model = FakeModel(listOf(finalReport))
        val (loop, _) = makeLoop(model)
        val result = loop.run("com.musaed.test.clear", userInstructions = "ركز على فحص الدفع")
        assertTrue(result.success)
        val firstUser = model.messagesSeen[0][1]
        assertTrue(firstUser.content.contains("تعليمات المستخدم من المحادثة"))
        assertTrue(firstUser.content.contains("ركز على فحص الدفع"))
    }
}
