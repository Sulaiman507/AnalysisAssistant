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
            "mt_apk_dex_read_class_outline" to FakeMcp.toolResult(outline),
        ),
    )

    private fun makeAgent(
        model: FakeModel,
        target: String? = "com.musaed.test.clear",
    ): Triple<ChatAgent, SessionLog, FakeServer> {
        val server = chatServer()
        val log = SessionLog()
        val runner = ToolRunner(server::call, log, timeoutMs = 5_000)
        val agent = ChatAgent(model::chat, runner, target, null)
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
    fun `mutating tool is rejected locally without a server call`() {
        val patchAction =
            """{"tool": "mt_apk_patch_bytes", "arguments": {"workspaceId": "ws-1", "editSessionId": ""}}"""
        val model = FakeModel(listOf(patchAction, "عذراً، لا أستطيع التعديل من المحادثة."))
        val (agent, _, server) = makeAgent(model)
        val session = ChatSession()
        val outcome = agent.respond(session, "عدّل الدالة")
        assertTrue(outcome is ChatOutcome.Answer)
        assertTrue(server.calls.none { it.first == "mt_apk_patch_bytes" })
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `run analysis action returns instructions`() {
        val model = FakeModel(listOf("""{"action": "run_analysis"}"""))
        val (agent, _, _) = makeAgent(model)
        val session = ChatSession()
        val outcome = agent.respond(session, "نفّذ")
        assertTrue(outcome is ChatOutcome.RunAnalysis)
        assertTrue((outcome as ChatOutcome.RunAnalysis).instructions.contains("نفّذ"))
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
        val (agent, _, server) = makeAgent(model)
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
        val agent = ChatAgent(failingChat, runner, "com.musaed.test.clear", null)
        val session = ChatSession()
        val outcome = agent.respond(session, "مرحباً")
        assertTrue(outcome is ChatOutcome.Answer)
        assertTrue((outcome as ChatOutcome.Answer).text.contains("تعذّر الوصول إلى النموذج"))
    }
}
