package com.musaed.analysis.mcp

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * عميل MCP صغير (Phase 1) — يتكلم مع خدمة APK MCP في MT Manager عبر HTTP.
 * البروتوكول: JSON-RPC 2.0 فوق HTTP (Streamable HTTP transport).
 *
 * لا يحتوي أي اسم أداة مكتوباً مسبقاً: قائمة الأدوات تُجلب حياً بـ tools/list
 * وتُعرض/تُحفظ كما رجعت من الخدمة.
 */
class McpClient(
    private val baseUrl: String = DEFAULT_URL,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 60_000,
) {
    companion object {
        const val DEFAULT_URL = "http://127.0.0.1:8787/mcp"
        const val PROTOCOL_VERSION = "2025-03-26"
        const val CLIENT_NAME = "musaed-analysis-probe"
        const val CLIENT_VERSION = "0.1.13-prompt-en"
    }

    private var sessionId: String? = null
    private var requestId = 0
    private var connected = false

    var lastServerName: String = "unknown"
        private set
    var lastServerVersion: String = "unknown"
        private set

    fun isConnected(): Boolean = connected

    /** initialize + notifications/initialized. يرمي McpException عند أي فشل. */
    fun connect() {
        val params = JsonObject().apply {
            addProperty("protocolVersion", PROTOCOL_VERSION)
            add("capabilities", JsonObject())
            add("clientInfo", JsonObject().apply {
                addProperty("name", CLIENT_NAME)
                addProperty("version", CLIENT_VERSION)
            })
        }
        val result = callRaw("initialize", params)
        val serverInfo = if (result.has("serverInfo")) result.getAsJsonObject("serverInfo") else null
        lastServerName = serverInfo?.get("name")?.asString ?: "unknown"
        lastServerVersion = serverInfo?.get("version")?.asString ?: "unknown"
        // notifications/initialized — إشعار بلا رد متوقع؛ فشله لا يلغي الاتصال
        try {
            sendNotification("notifications/initialized", JsonObject())
        } catch (e: Exception) {
            // تجاهل — بعض الخوادم لا ترد على الإشعارات
        }
        connected = true
    }

    /** يجلب قائمة الأدوات الفعلية من الخدمة (مع ترقيم الصفحات إن وُجد). */
    fun listTools(): List<McpTool> {
        requireConnected()
        val tools = mutableListOf<McpTool>()
        var cursor: String? = null
        var pages = 0
        do {
            val params = JsonObject()
            if (cursor != null) params.addProperty("cursor", cursor)
            val result = callRaw("tools/list", params)
            tools += toolsFromResult(result)
            cursor = if (result.has("nextCursor") && !result.get("nextCursor").isJsonNull) {
                result.get("nextCursor").asString
            } else {
                null
            }
            pages++
        } while (cursor != null && pages < 10)
        return tools
    }

    /** استدعاء عام لأي أداة باسمها (يُستخدم بعد معرفة الأسماء الحية من tools/list). */
    fun callTool(name: String, arguments: JsonObject? = null): JsonObject {
        requireConnected()
        val params = JsonObject().apply {
            addProperty("name", name)
            if (arguments != null) add("arguments", arguments)
        }
        return callRaw("tools/call", params)
    }

    private fun requireConnected() {
        check(connected) { "MCP client is not connected — call connect() first" }
    }

    /** يرسل طلب JSON-RPC واحداً ويعيد حقل result. يرمي McpException عند الخطأ. */
    private fun callRaw(method: String, params: JsonObject): JsonObject {
        val id = ++requestId
        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", id)
            addProperty("method", method)
            add("params", params)
        }
        val (status, contentType, body) = postJson(request.toString())
        if (status !in 200..299) {
            throw McpException("MCP HTTP $status for $method: ${body.take(500)}")
        }
        val message = parseResponseBody(body, contentType)
        if (message.has("error")) {
            val err = message.getAsJsonObject("error")
            val code = if (err.has("code")) err.get("code").asInt else -1
            val msg = if (err.has("message")) err.get("message").asString else "unknown error"
            throw rpcError(method, code, msg)
        }
        if (!message.has("result")) {
            throw McpException(
                "MCP response for $method has no result: ${body.take(500)}",
                retryable = false,
            )
        }
        return message.getAsJsonObject("result")
    }

    /**
     * خطأ JSON-RPC من الخادم (مثل -32602 أداة غير معروفة): قرار من الخادم
     * لا فشل نقل — لا يستحق إعادة محاولة. (دليل حي 2026-11-15: النموذج
     * طلب أدوات مخترعة فأعيد كل طلب مرتين بلا فائدة.)
     */
    internal fun rpcError(method: String, code: Int, msg: String): McpException =
        McpException("MCP error $code for $method: $msg", retryable = false)

    private fun sendNotification(method: String, params: JsonObject) {
        val request = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            add("params", params)
        }
        val (status, _, _) = postJson(request.toString())
        // 202 Accepted متوقع للإشعارات؛ 200 أيضاً مقبول
        if (status !in 200..299) throw McpException("MCP notification $method failed: HTTP $status")
    }

    private data class HttpResult(val status: Int, val contentType: String?, val body: String)

    private fun postJson(json: String): HttpResult {
        val url = URI.create(baseUrl).toURL()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
        }
        try {
            conn.outputStream.use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
            val status = conn.responseCode
            conn.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use(BufferedReader::readText) ?: ""
            return HttpResult(status, conn.getHeaderField("Content-Type"), body)
        } catch (e: java.net.ConnectException) {
            throw McpException(
                "تعذّر الاتصال بخدمة MCP على $baseUrl — هل خدمة APK MCP مشغّلة في MT Manager؟", e
            )
        } catch (e: java.net.SocketTimeoutException) {
            throw McpException("انتهت مهلة الاتصال بخدمة MCP على $baseUrl", e)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * يحلل جسم الرد: إما JSON عادي أو تدفق SSE (text/event-stream) يحتوي أسطر data:.
     * أسطر data: المتتالية في حدث واحد تُجمع بـ "\n" حسب مواصفة SSE (JSON متعدد
     * الأسطر يُرسل هكذا). يعيد آخر رسالة JSON-RPC كاملة.
     */
    internal fun parseResponseBody(body: String, contentType: String?): JsonObject {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) throw McpException("Empty MCP response body")
        if (contentType?.startsWith("text/event-stream") == true ||
            trimmed.startsWith("event:") || trimmed.startsWith("data:")
        ) {
            val messages = mutableListOf<JsonObject>()
            val currentData = mutableListOf<String>()
            fun flushEvent() {
                if (currentData.isNotEmpty()) {
                    val joined = currentData.joinToString("\n")
                    if (joined.isNotBlank() && joined != "[DONE]") {
                        messages += JsonParser.parseString(joined).asJsonObject
                    }
                    currentData.clear()
                }
            }
            trimmed.lineSequence().forEach { rawLine ->
                val line = rawLine.trim()
                when {
                    line.isEmpty() -> flushEvent()
                    line.startsWith("data:") -> currentData += line.removePrefix("data:").trimStart()
                    // أسطر event: و id: و :تعليق تُتجاهل
                }
            }
            flushEvent()
            return messages.lastOrNull()
                ?: throw McpException("SSE response has no data lines: ${body.take(500)}")
        }
        return JsonParser.parseString(trimmed).asJsonObject
    }

    /** يحوّل حقل result الخاص بـ tools/list إلى قائمة أدوات. */
    internal fun toolsFromResult(result: JsonObject): List<McpTool> {
        if (!result.has("tools")) return emptyList()
        return result.getAsJsonArray("tools").map { element ->
            val obj = element.asJsonObject
            McpTool(
                name = obj.get("name").asString,
                description = if (obj.has("description") && !obj.get("description").isJsonNull) {
                    obj.get("description").asString
                } else {
                    null
                },
                inputSchema = if (obj.has("inputSchema") && !obj.get("inputSchema").isJsonNull) {
                    obj.getAsJsonObject("inputSchema")
                } else {
                    null
                },
            )
        }
    }
}
