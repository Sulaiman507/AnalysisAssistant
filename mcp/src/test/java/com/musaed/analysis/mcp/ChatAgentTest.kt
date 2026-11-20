package com.musaed.analysis.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAgentTest {

    private val outline = """{"data":{"fields":[],"methods":[{"name":"a","descriptor":"(Landroid/content/Context;)Z"}]}}"""

    private class FakeModel(private val replies: List<String>) {
        private var index = 0
        val messagesSeen = mutableListOf<List<ChatMessage>>()

        fun chat(messages: List<ChatMessage>): String {
            messagesSeen += messages.toList()
            if (index >= replies.size) return replies.last()
            return replies[index++]
        }
    }

    private fun chatServer() = FakeServer(
        mapOf(
            "mt_apk_list_installed_apps" to FakeMcp.toolResult(
                """{"data":{"items":[{"path":"/data/app/com.musaed.test.clear/base.apk","packageName":"com.musaed.test.clear"}]}}"""
            ),
            "mt_apk_workspace_open" to FakeMcp.toolResult("""{"data":{"workspaceId":"ws-1"}}"""),
            "mt_apk_search" to FakeMcp.toolResult("""{"data":{"hits":[]}}"""),
            "mt_apk_dex_read_class_outline" to FakeMcp.toolResult(outline),
            "mt_apk_patch_bytes" to FakeMcp.toolResult("""{"data":{"ok":true}}"""),
            "mt_apk_session_create" to FakeMcp.toolResult("""{"data":{"editSessionId":"es-9"}}"""),
            "mt_apk_edit_text" to FakeMcp.toolResult("""{"data":{"ok":true}}"""),
            "mt_file_list" to FakeMcp.toolResult("""{"data":{"items":[]}}"""),
        ),
    )

    private fun makeAgent(
        model: FakeModel,
        target: String? = "com.musaed.test.clear",
        maxToolSteps: Int = ChatAgent.DEFAULT_MAX_TOOL_STEPS,
    ): Triple<ChatAgent, SessionLog, FakeServer> {
        val server = chatServer()
        val log = SessionLog()
        val runner = ToolRunner(server::call, log, timeoutMs = 5_000)
        val agent = ChatAgent(model::chat, runner, target, maxToolSteps = maxToolSteps)
        return Triple(agent, log, server)
    }

    private val outlineAction =
        """{"tool": "mt_apk_dex_read_class_outline", "arguments": {"workspaceId": "ws-1", "editSessionId": "", "locator": "dex_class:com/musaed/test/obfuscated/a;"}}"""

    @Test
    fun `plain text reply is the answer, no tool called`() {
        val model = FakeModel(listOf("أهلاً، اسألني عن التطبيق."))
        val (agent, _, server) = makeAgent(model)
        val session = ChatSession()
        val outcome = agent.respond(session, "مرحباً")
        assertTrue(outcome is ChatOutcome.Answer)
        assertEquals("أهلاً، اسألني عن التطبيق.", (outcome as ChatOutcome.Answer).text)
        assertTrue(server.calls.isEmpty())
        assertEquals(2, session.turns().size)
    }

    @Test
    fun `tool call then text answer uses the tool result`() {
        val model = FakeModel(listOf(outlineAction, "الكلاس a فيه دالة a ترجع boolean."))
        val (agent, log, server) = makeAgent(model)
        val session = ChatSession()
        val outcome = agent.respond(session, "وش في الكلاس a؟")
        assertTrue(outcome is ChatOutcome.Answer)
        assertTrue((outcome as ChatOutcome.Answer).text.contains("دالة"))
        assertTrue(server.calls.any { it.first == "mt_apk_dex_read_class_outline" })
        // مساحة العمل فُتحت مرة واحدة وأُعيد استخدامها
        assertEquals(1, server.calls.count { it.first == "mt_apk_workspace_open" })
        assertTrue(log.entries().any { it.tool == "mt_apk_dex_read_class_outline" })
    }

    @Test
    fun `mutating tool is now executed, not rejected`() {
        // بلا حدود بقرار سليمان 2026-11-17: أدوات التعديل مسموحة في المحادثة.
        val patchAction =
            """{"tool": "mt_apk_patch_bytes", "arguments": {"workspaceId": "ws-1", "editSessionId": "", "locator": "zip_entry:classes.dex", "patches": []}}"""
        val model = FakeModel(listOf(patchAction, "تم التعديل."))
        val (agent, _, server) = makeAgent(model)
        val session = ChatSession()
        val outcome = agent.respond(session, "عدّل الدالة")
        assertTrue(outcome is ChatOutcome.Answer)
        assertTrue(server.calls.any { it.first == "mt_apk_patch_bytes" })
    }

    @Test
    fun `unknown tool is rejected locally without a server call`() {
        val fakeAction =
            """{"tool": "mt_apk_frobnicate", "arguments": {}}"""
        val model = FakeModel(listOf(fakeAction, "هذه الأداة غير موجودة."))
        val (agent, _, server) = makeAgent(model)
        val session = ChatSession()
        val outcome = agent.respond(session, "افعل شيئاً")
        assertTrue(outcome is ChatOutcome.Answer)
        assertTrue(server.calls.isEmpty())
    }

    /** صيغة DSML من لقطة سليمان 2026-11-20: تُنفَّذ كاستدعاء JSON عادي، بلا عرض خام. */
    private val dsmlSearch = """
        <|DSML| calls |>
        <|DSML| invoke name="mt_apk_search">
        <|DSML| parameter name="arguments" string="true">{"target": "dex_strings", "query": "premium", "queryType": "literal", "caseSensitive": false, "matchMode": "contains", "prefix": "Lcom/hecorat/", "includeMatchOffsets": false, "limit": 60, "snippetMaxChars": 200}</|DSML| parameter>
        </|DSML| invoke>
        </|DSML| calls|>
    """.trimIndent()

    @Test
    fun `dsml tool call is executed like a json call`() {
        val model = FakeModel(listOf(dsmlSearch, "بحثت عن premium ولم أجد نتائج."))
        val (agent, _, server) = makeAgent(model)
        val session = ChatSession()
        val outcome = agent.respond(session, "ابحث عن premium")
        assertTrue(outcome is ChatOutcome.Answer)
        assertTrue((outcome as ChatOutcome.Answer).text.contains("premium"))
        assertTrue(server.calls.any { it.first == "mt_apk_search" })
        // الرد الخام لا يظهر في المحادثة — الجواب النصي فقط
        assertTrue(session.turns().none { it.content.contains("DSML") })
    }

    @Test
    fun `dsml reply with unknown tool is rejected locally`() {
        val dsmlUnknown = """
            <|DSML| invoke name="mt_apk_frobnicate">
            <|DSML| parameter name="arguments" string="true">{}</|DSML| parameter>
        """.trimIndent()
        val model = FakeModel(listOf(dsmlUnknown, "هذه الأداة غير موجودة."))
        val (agent, _, server) = makeAgent(model)
        val session = ChatSession()
        val outcome = agent.respond(session, "افعل شيئاً")
        assertTrue(outcome is ChatOutcome.Answer)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `requestStop before respond returns Stopped without model calls`() {
        var calls = 0
        val chatFn: (List<ChatMessage>) -> String = {
            calls++
            "أهلاً."
        }
        val server = chatServer()
        val log = SessionLog()
        val runner = ToolRunner(server::call, log, timeoutMs = 5_000)
        val agent = ChatAgent(chatFn, runner, "com.musaed.test.clear")
        agent.requestStop()
        val outcome = agent.respond(ChatSession(), "مرحباً")
        assertTrue(outcome is ChatOutcome.Stopped)
        assertEquals(0, calls)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `stop requested mid conversation ends with Stopped`() {
        var agentBox: ChatAgent? = null
        var calls = 0
        val chatFn: (List<ChatMessage>) -> String = {
            calls++
            if (calls == 2) agentBox?.requestStop()
            outlineAction
        }
        val server = chatServer()
        val log = SessionLog()
        val runner = ToolRunner(server::call, log, timeoutMs = 5_000)
        val agent = ChatAgent(chatFn, runner, "com.musaed.test.clear", maxToolSteps = Int.MAX_VALUE)
        agentBox = agent
        val outcome = agent.respond(ChatSession(), "اشرح كل شيء")
        assertTrue(outcome is ChatOutcome.Stopped)
        assertEquals(2, calls)
    }

    @Test
    fun `session_create result is reused as edit session id`() {
        val createAction =
            """{"tool": "mt_apk_session_create", "arguments": {}}"""
        val editAction =
            """{"tool": "mt_apk_edit_text", "arguments": {"locator": "dex_class:com/musaed/test/obfuscated/a;", "targetVersion": 1, "edits": []}}"""
        val model = FakeModel(listOf(createAction, editAction, "تم التعديل في الجلسة."))
        val (agent, _, server) = makeAgent(model)
        val session = ChatSession()
        val outcome = agent.respond(session, "عدّل الكلاس")
        assertTrue(outcome is ChatOutcome.Answer)
        val edit = server.calls.single { it.first == "mt_apk_edit_text" }
        assertEquals("ws-1", edit.second.get("workspaceId").asString)
        assertEquals("es-9", edit.second.get("editSessionId").asString)
    }

    @Test
    fun `file tool is sent without injected workspace args`() {
        val listAction =
            """{"tool": "mt_file_list", "arguments": {"path": "Download/edit-apps", "recursive": false, "cursor": "", "limit": 50}}"""
        val model = FakeModel(listOf(listAction, "المجلد فارغ."))
        val (agent, _, server) = makeAgent(model)
        val session = ChatSession()
        val outcome = agent.respond(session, "ماذا في المجلد؟")
        assertTrue(outcome is ChatOutcome.Answer)
        val call = server.calls.single { it.first == "mt_file_list" }
        assertTrue(!call.second.has("workspaceId"))
        assertTrue(!call.second.has("editSessionId"))
        assertTrue(server.calls.none { it.first == "mt_apk_workspace_open" })
    }

    @Test
    fun `no target means the model asks for the package`() {
        val model = FakeModel(listOf(outlineAction, "ما اسم حزمة التطبيق؟"))
        val (agent, _, server) = makeAgent(model, target = null)
        val session = ChatSession()
        val outcome = agent.respond(session, "وش في الكلاس a؟")
        assertTrue(outcome is ChatOutcome.Answer)
        assertTrue((outcome as ChatOutcome.Answer).text.contains("حزمة"))
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `tool step cap ends with a cap message`() {
        val model = FakeModel(List(8) { outlineAction })
        val (agent, _, server) = makeAgent(model, maxToolSteps = 5)
        val session = ChatSession()
        val outcome = agent.respond(session, "اشرح كل شيء")
        assertTrue(outcome is ChatOutcome.Answer)
        assertTrue((outcome as ChatOutcome.Answer).text.contains("حد خطوات القراءة"))
        assertEquals(5, server.calls.count { it.first == "mt_apk_dex_read_class_outline" })
    }

    @Test
    fun `model error becomes a readable answer`() {
        val failingChat: (List<ChatMessage>) -> String = { throw ModelException("انقطع الاتصال") }
        val server = chatServer()
        val log = SessionLog()
        val runner = ToolRunner(server::call, log, timeoutMs = 5_000)
        val agent = ChatAgent(failingChat, runner, "com.musaed.test.clear")
        val session = ChatSession()
        val outcome = agent.respond(session, "مرحباً")
        assertTrue(outcome is ChatOutcome.Answer)
        assertTrue((outcome as ChatOutcome.Answer).text.contains("تعذّر الوصول إلى النموذج"))
    }

    @Test
    fun `model list call is sent without injected workspace args`() {
        val listAction =
            """{"tool": "mt_apk_list_installed_apps", "arguments": {"query": "com.musaed.test.clear", "appType": "user", "limit": 50}}"""
        val model = FakeModel(listOf(listAction, "وجدت التطبيق مثبّتاً."))
        val (agent, _, server) = makeAgent(model)
        val session = ChatSession()
        val outcome = agent.respond(session, "هل التطبيق مثبّت؟")
        assertTrue(outcome is ChatOutcome.Answer)
        val call = server.calls.single { it.first == "mt_apk_list_installed_apps" }
        assertTrue(!call.second.has("workspaceId"))
        assertTrue(!call.second.has("editSessionId"))
        // أداة الفتح لا تفتح مساحة عمل داخلية مسبقاً
        assertTrue(server.calls.none { it.first == "mt_apk_workspace_open" })
    }

    @Test
    fun `model workspace_open call is sent as-is and its id is reused`() {
        val openAction =
            """{"tool": "mt_apk_workspace_open", "arguments": {"path": "mt://installed-app/com.musaed.test.clear", "temporary": true}}"""
        val model = FakeModel(listOf(openAction, outlineAction, "الكلاس a فيه دالة a."))
        val (agent, _, server) = makeAgent(model)
        val session = ChatSession()
        val outcome = agent.respond(session, "وش في الكلاس a؟")
        assertTrue(outcome is ChatOutcome.Answer)
        val open = server.calls.single { it.first == "mt_apk_workspace_open" }
        assertTrue(!open.second.has("workspaceId"))
        assertTrue(!open.second.has("editSessionId"))
        // أداة القراءة التالية تستخدم معرّف مساحة العمل المفتوحة، بلا فتح ثانٍ
        val outlineCall = server.calls.single { it.first == "mt_apk_dex_read_class_outline" }
        assertEquals("ws-1", outlineCall.second.get("workspaceId").asString)
        assertTrue(outlineCall.second.has("editSessionId"))
    }

    @Test
    fun `workspace args the model sends to a setup tool are stripped`() {
        val openAction =
            """{"tool": "mt_apk_workspace_open", "arguments": {"workspaceId": "ws-9", "editSessionId": "x", "path": "mt://installed-app/com.musaed.test.clear", "temporary": true}}"""
        val model = FakeModel(listOf(openAction, "فُتحت مساحة العمل."))
        val (agent, _, server) = makeAgent(model)
        val session = ChatSession()
        val outcome = agent.respond(session, "افتح التطبيق")
        assertTrue(outcome is ChatOutcome.Answer)
        val open = server.calls.single { it.first == "mt_apk_workspace_open" }
        assertTrue(!open.second.has("workspaceId"))
        assertTrue(!open.second.has("editSessionId"))
        assertEquals("mt://installed-app/com.musaed.test.clear", open.second.get("path").asString)
    }

    @Test
    fun `unlimited steps let the model call many tools then answer`() {
        val model = FakeModel(List(8) { outlineAction } + "الكلاس a فيه دالة a.")
        val (agent, _, server) = makeAgent(model, maxToolSteps = Int.MAX_VALUE)
        val session = ChatSession()
        val outcome = agent.respond(session, "اشرح كل شيء")
        assertTrue(outcome is ChatOutcome.Answer)
        assertTrue((outcome as ChatOutcome.Answer).text.contains("دالة"))
        assertEquals(8, server.calls.count { it.first == "mt_apk_dex_read_class_outline" })
    }

    @Test
    fun `custom limit of two stops after two tool calls`() {
        val model = FakeModel(List(5) { outlineAction })
        val (agent, _, server) = makeAgent(model, maxToolSteps = 2)
        val session = ChatSession()
        val outcome = agent.respond(session, "اشرح كل شيء")
        assertTrue(outcome is ChatOutcome.Answer)
        assertTrue((outcome as ChatOutcome.Answer).text.contains("حد خطوات القراءة"))
        assertEquals(2, server.calls.count { it.first == "mt_apk_dex_read_class_outline" })
    }
}
