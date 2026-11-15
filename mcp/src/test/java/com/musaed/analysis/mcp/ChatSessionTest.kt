package com.musaed.analysis.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionTest {

    @Test
    fun `turns keep order and roles`() {
        val session = ChatSession()
        session.addUser("اشرح لي التطبيق")
        session.addModel("التطبيق يفحص Premium محلياً.")
        val turns = session.turns()
        assertEquals(2, turns.size)
        assertEquals("user", turns[0].role)
        assertEquals("model", turns[1].role)
        assertTrue(turns[1].content.contains("Premium"))
    }

    @Test
    fun `history for model trims oldest turns`() {
        val session = ChatSession()
        repeat(15) { i ->
            session.addUser("سؤال $i")
            session.addModel("جواب $i")
        }
        val history = session.historyForModel()
        assertTrue(history.size <= ChatSession.MAX_TURNS_SENT)
        // الأحدث يبقى — آخر رسالة هي جواب 14
        assertEquals("جواب 14", history.last().content)
        assertEquals("assistant", history.last().role)
    }

    @Test
    fun `instructions contain user turns only`() {
        val session = ChatSession()
        session.addUser("ركز على فحص الدفع")
        session.addModel("تمام، سأركز عليه.")
        session.addUser("وتجاهل الإعلانات")
        val instructions = session.instructionsForAnalysis()
        assertTrue(instructions.contains("ركز على فحص الدفع"))
        assertTrue(instructions.contains("وتجاهل الإعلانات"))
        assertTrue(!instructions.contains("تمام، سأركز عليه."))
    }

    @Test
    fun `json round trip preserves turns`() {
        val session = ChatSession()
        session.addUser("ما البوابة؟")
        session.addModel("البوابة a.a.")
        val restored = ChatSession.fromJson(session.toJson())
        assertEquals(session.turns(), restored.turns())
    }

    @Test
    fun `corrupt json starts an empty session`() {
        assertTrue(ChatSession.fromJson(null).isEmpty())
        assertTrue(ChatSession.fromJson("not json").isEmpty())
        assertTrue(ChatSession.fromJson("").isEmpty())
    }
}
