package com.musaed.analysis.mcp

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportSplitTest {

    @Test
    fun `splits json and trailing arabic report`() {
        val reply = """{"verdict": "local", "confidence": 0.9}
            النتيجة: الفحص محلي صرف في PremiumGate."""
        val (json, arabic) = ReportSplit.split(reply)!!
        assertEquals("local", JsonParser.parseString(json).asJsonObject.get("verdict").asString)
        assertEquals("النتيجة: الفحص محلي صرف في PremiumGate.", arabic)
    }

    @Test
    fun `braces inside string values do not end the json early`() {
        val reply = """{"verdict": "local", "evidence": [{"excerpt": "if (x == null) { return false; }"}]}
            التقرير العربي بعد الأقواس."""
        val (json, arabic) = ReportSplit.split(reply)!!
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals("local", obj.get("verdict").asString)
        assertTrue(obj.getAsJsonArray("evidence")[0].asJsonObject.get("excerpt").asString.contains("{ return false; }"))
        assertEquals("التقرير العربي بعد الأقواس.", arabic)
    }

    @Test
    fun `strips fenced json blocks`() {
        val reply = "```json\n{\"verdict\": \"server\"}\n```\nالتقرير: خادم صرف."
        val (json, arabic) = ReportSplit.split(reply)!!
        assertEquals("server", JsonParser.parseString(json).asJsonObject.get("verdict").asString)
        assertEquals("التقرير: خادم صرف.", arabic)
    }

    @Test
    fun `json only leaves arabic null`() {
        val (_, arabic) = ReportSplit.split("""{"verdict": "none"}""")!!
        assertNull(arabic)
    }

    @Test
    fun `no json returns null`() {
        assertNull(ReportSplit.split("هذا نص بلا JSON إطلاقاً."))
    }

    @Test
    fun `unbalanced braces return null`() {
        assertNull(ReportSplit.split("""{"verdict": "local" """))
    }
}
