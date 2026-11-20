package com.musaed.analysis.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DsmlParserTest {

    /** إعادة بناء وفية للكتلة في لقطة سليمان 2026-11-20 (بحث premium). */
    private val screenshotDsml = """
        <|DSML| calls |>
        <|DSML| invoke name="mt_apk_search">
        <|DSML| parameter name="arguments" string="true">{"target": "dex_strings", "query": "premium", "queryType": "literal", "caseSensitive": false, "matchMode": "contains", "prefix": "Lcom/hecorat/", "includeMatchOffsets": false, "limit": 60, "snippetMaxChars": 200}</|DSML| parameter>
        </|DSML| invoke>
        </|DSML| calls|>
    """.trimIndent()

    @Test
    fun `parses the screenshot-style dsml search call`() {
        val call = DsmlParser.parse(screenshotDsml)
        assertTrue(call != null)
        assertEquals("mt_apk_search", call!!.tool)
        assertEquals("dex_strings", call.arguments.get("target").asString)
        assertEquals("premium", call.arguments.get("query").asString)
        assertEquals("Lcom/hecorat/", call.arguments.get("prefix").asString)
        assertEquals(60, call.arguments.get("limit").asInt)
        assertEquals(200, call.arguments.get("snippetMaxChars").asInt)
    }

    @Test
    fun `ignores surrounding arabic text`() {
        val reply = "سأبحث الآن عن كلمة premium في السلاسل:\n$screenshotDsml\nأخبرني إن أردت تضييق البحث."
        val call = DsmlParser.parse(reply)
        assertTrue(call != null)
        assertEquals("mt_apk_search", call!!.tool)
    }

    @Test
    fun `braces inside a string value do not break the arguments object`() {
        val reply = """
            <|DSML| invoke name="mt_apk_search">
            <|DSML| parameter name="arguments" string="true">{"target": "dex_strings", "query": "a}b", "limit": 5}</|DSML| parameter>
        """.trimIndent()
        val call = DsmlParser.parse(reply)
        assertTrue(call != null)
        assertEquals("a}b", call!!.arguments.get("query").asString)
        assertEquals(5, call.arguments.get("limit").asInt)
    }

    @Test
    fun `returns null for plain text`() {
        assertNull(DsmlParser.parse("أهلاً، اسألني عن التطبيق."))
        assertNull(DsmlParser.parse("{\"tool\": \"mt_apk_search\", \"arguments\": {}}"))
    }

    @Test
    fun `returns null when invoke has no json after it`() {
        val reply = "<|DSML| calls |>\n<|DSML| invoke name=\"mt_apk_search\">\n</|DSML| invoke>\n</|DSML| calls|>"
        assertNull(DsmlParser.parse(reply))
    }

    @Test
    fun `returns null when the json after invoke is malformed`() {
        val reply = "<|DSML| invoke name=\"mt_apk_search\">\n<|DSML| parameter name=\"arguments\" string=\"true\">{\"target\": \"dex_strings\", \"query\": </|DSML| parameter>"
        assertNull(DsmlParser.parse(reply))
    }

    @Test
    fun `returns the tool name even for a non-mt name, callers reject it`() {
        val reply = "<|DSML| invoke name=\"mt_apk_frobnicate\">\n<|DSML| parameter name=\"arguments\" string=\"true\">{}</|DSML| parameter>"
        val call = DsmlParser.parse(reply)
        assertTrue(call != null)
        assertEquals("mt_apk_frobnicate", call!!.tool)
    }
}
