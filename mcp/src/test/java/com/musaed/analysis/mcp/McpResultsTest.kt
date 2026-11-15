package com.musaed.analysis.mcp

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpResultsTest {

    /** خطأ خادم حقيقي من سجل الجلسة الحي 2026-11-15 (معاملات خاطئة من النموذج). */
    private val serverErrorJson = """
        {"ok":false,"data":null,"error":{"code":"INVALID_ARGUMENT",
        "message":"Invalid mt_apk_dex_list_classes argument","severity":"warning",
        "recoverable":true,"retrySameArguments":false,"argument":"package",
        "badValue":"com.musaed.test.clear","badValueType":"string",
        "allowedValues":["workspaceId","editSessionId","prefix","limit"],
        "example":"{}","diagnostics":null},"nextActions":[]}
    """.trimIndent()

    private fun mcpResultWithText(text: String): String {
        val item = JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", text)
        }
        val result = JsonObject().apply {
            add("content", com.google.gson.JsonArray().apply { add(item) })
            addProperty("isError", true)
        }
        return Gson().toJson(result)
    }

    @Test
    fun `serverErrorOf extracts the server error object`() {
        val err = McpResults.serverErrorOf(mcpResultWithText(serverErrorJson))
        assertTrue(err != null)
        assertEquals("INVALID_ARGUMENT", err!!.get("code").asString)
        assertEquals(
            "Invalid mt_apk_dex_list_classes argument",
            err.get("message").asString,
        )
        assertEquals(4, err.getAsJsonArray("allowedValues").size())
        assertEquals("workspaceId", err.getAsJsonArray("allowedValues")[0].asString)
        assertEquals("{}", err.get("example").asString)
    }

    @Test
    fun `serverErrorText formats code message allowed values and example`() {
        val text = McpResults.serverErrorText(mcpResultWithText(serverErrorJson))
        assertTrue(text != null)
        assertTrue(text!!.contains("INVALID_ARGUMENT"))
        assertTrue(text.contains("المعاملات المسموحة: workspaceId, editSessionId, prefix, limit"))
        assertTrue(text.contains("مثال: {}"))
    }

    @Test
    fun `serverErrorOf returns null without a structured error`() {
        assertNull(McpResults.serverErrorOf(null))
        assertNull(McpResults.serverErrorOf(""))
        assertNull(McpResults.serverErrorOf("not json"))
        assertNull(McpResults.serverErrorOf(mcpResultWithText("""{"ok":true,"data":{}}""")))
        assertNull(McpResults.serverErrorOf(mcpResultWithText("plain text, not JSON")))
    }
}
