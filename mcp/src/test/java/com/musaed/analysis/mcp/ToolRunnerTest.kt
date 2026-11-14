package com.musaed.analysis.mcp

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ToolRunnerTest {

    private fun okResult(): JsonObject = FakeMcp.toolResult("""{"data":{"ok":true}}""")

    @Test
    fun `retries once on transient failure then succeeds`() {
        val calls = AtomicInteger(0)
        val log = SessionLog()
        val runner = ToolRunner({ _, _ ->
            if (calls.incrementAndGet() == 1) {
                throw McpException("فشل عابر في النقل")
            }
            okResult()
        }, log, 5_000)
        val result = runner.call("mt_apk_read_signature", JsonObject(), step = 3)
        assertTrue(McpResults.dataOf(result).get("ok").asBoolean)
        assertEquals(2, calls.get())
        val entries = log.entries()
        assertEquals(2, entries.size)
        assertEquals("error", entries[0].status)
        assertEquals("ok", entries[1].status)
        assertEquals(1, entries[0].attempt)
        assertEquals(2, entries[1].attempt)
        assertEquals(3, entries[0].step)
        assertEquals(3, entries[1].step)
        runner.shutdown()
    }

    @Test
    fun `does not retry when server returns isError`() {
        val calls = AtomicInteger(0)
        val log = SessionLog()
        val runner = ToolRunner({ _, _ ->
            calls.incrementAndGet()
            FakeMcp.errorResult("خطأ من الخادم")
        }, log, 5_000)
        try {
            runner.call("mt_apk_read_signature", JsonObject(), step = 4)
            throw AssertionError("expected McpException")
        } catch (e: McpException) {
            assertFalse(e.retryable)
            assertTrue(e.message!!.contains("أعادت خطأ"))
        }
        assertEquals(1, calls.get())
        // محاولة واحدة فقط في السجل — بلا إعادة
        val entries = log.entries()
        assertEquals(1, entries.size)
        assertEquals("error", entries[0].status)
        assertEquals(4, entries[0].step)
        runner.shutdown()
    }

    @Test
    fun `times out after 300ms with sleeping call and logs two attempts`() {
        val calls = AtomicInteger(0)
        val log = SessionLog()
        val runner = ToolRunner({ _, _ ->
            calls.incrementAndGet()
            Thread.sleep(2_000)
            okResult()
        }, log, 300)
        try {
            runner.call("mt_apk_read_signature", JsonObject(), step = 5)
            throw AssertionError("expected McpException")
        } catch (e: McpException) {
            assertTrue(e.message!!.contains("مهلة"))
        }
        assertEquals(2, calls.get())
        // محاولتان مسجلتان (الأولى + الإعادة) قبل الفشل النهائي
        val entries = log.entries()
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.status == "error" })
        assertEquals(5, entries[0].step)
        runner.shutdown()
    }
}
