package com.musaed.analysis.mcp

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpClientTest {

    // قائمة وهمية للاختبار فقط — ليست أسماء أدوات MT الحقيقية
    private val sampleListResult = """
        {"tools":[
          {"name":"test.alpha","description":"First fake tool","inputSchema":{"type":"object","properties":{}}},
          {"name":"test.beta","description":"Second fake tool","inputSchema":{"type":"object","properties":{"apkPath":{"type":"string"}}}},
          {"name":"test.gamma"}
        ]}
    """.trimIndent()

    @Test
    fun `toolsFromResult parses names and descriptions`() {
        val client = McpClient("http://127.0.0.1:9/mcp")
        val result = JsonParser.parseString(sampleListResult).asJsonObject
        val tools = client.toolsFromResult(result)
        assertEquals(3, tools.size)
        assertEquals("test.alpha", tools[0].name)
        assertEquals("First fake tool", tools[0].description)
        assertEquals("test.beta", tools[1].name)
        assertTrue(tools[1].inputSchema!!.has("properties"))
        assertEquals("test.gamma", tools[2].name)
        assertNull(tools[2].description)
    }

    // نسخة مضغوطة (سطر واحد) من نفس القائمة — هكذا ترسلها خوادم SSE فعلياً
    private val sampleListCompact = """{"tools":[{"name":"test.alpha","description":"First fake tool","inputSchema":{"type":"object","properties":{}}},{"name":"test.beta","description":"Second fake tool","inputSchema":{"type":"object","properties":{"apkPath":{"type":"string"}}}},{"name":"test.gamma"}]}"""

    @Test
    fun `parseResponseBody handles plain JSON`() {
        val client = McpClient("http://127.0.0.1:9/mcp")
        val body = """{"jsonrpc":"2.0","id":1,"result":$sampleListResult}"""
        val msg = client.parseResponseBody(body, "application/json")
        assertEquals(1, msg.get("id").asInt)
        assertTrue(msg.has("result"))
    }

    @Test
    fun `parseResponseBody handles SSE stream`() {
        val client = McpClient("http://127.0.0.1:9/mcp")
        val body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":$sampleListCompact}\n\n"
        val msg = client.parseResponseBody(body, "text/event-stream")
        assertEquals(2, msg.get("id").asInt)
        assertTrue(msg.has("result"))
    }

    @Test
    fun `parseResponseBody joins multi-line SSE data`() {
        val client = McpClient("http://127.0.0.1:9/mcp")
        // JSON متعدد الأسطر حسب مواصفة SSE: كل سطر ببادئة data: خاصة به
        val lines = sampleListResult.lines()
        val body = buildString {
            appendLine("event: message")
            append("data: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":")
            appendLine(lines[0])
            lines.drop(1).forEach { appendLine("data: $it") }
            appendLine("data: }") // إغلاق كائن JSON-RPC الخارجي
            appendLine()
        }
        val msg = client.parseResponseBody(body, "text/event-stream")
        assertEquals(3, msg.get("id").asInt)
        assertTrue(msg.has("result"))
        val tools = client.toolsFromResult(msg.getAsJsonObject("result"))
        assertEquals(3, tools.size)
    }

    // ملاحظة: لا يوجد هنا اختبار تبادل حي (end-to-end) ضد خادم MCP — التبادل الحي
    // لا يعمل من JVM في هذه الحاوية تحديداً: الـ sandbox يمنع اتصالات TCP
    // المباشرة من عمليات JVM (رسالة الحظر الحرفية تظهر عند محاولة HttpClient:
    // "Other TCP connections is turned off for this assistant... switch other_tcp
    // from Deny to Ask")، وهذا نفس سبب فشل Gradle daemon IPC هنا. التحقق الحي
    // يتم على الهاتف نفسه (بوابة قبول المرحلة ١)، حيث يعمل HttpURLConnection
    // مع localhost بشكل طبيعي. منطق التحليل (JSON-RPC/SSE) مغطى بالاختبارات أعلاه.

    @Test
    fun `rpcError is not retryable`() {
        // خطأ JSON-RPC من الخادم (مثل -32602 أداة غير معروفة) قرار من
        // الخادم لا فشل نقل — إعادة المحاولة مضيعة (دليل حي 2026-11-15:
        // كل أداة مخترعة أُعيدت مرتين بلا فائدة).
        val client = McpClient("http://127.0.0.1:9/mcp")
        val err = client.rpcError("tools/call", -32602, "Unknown tool")
        assertEquals("MCP error -32602 for tools/call: Unknown tool", err.message)
        assertFalse(err.retryable)
    }
}
