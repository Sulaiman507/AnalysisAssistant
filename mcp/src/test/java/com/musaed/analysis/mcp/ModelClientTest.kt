package com.musaed.analysis.mcp

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelClientTest {

    private val config = ModelClient.ModelConfig(
        baseUrl = "https://api.deepseek.com/v1",
        apiPath = "/chat/completions",
        apiKey = "test-key",
        model = "deepseek-reasoner",
    )

    private fun chatReply(content: String): String {
        val message = com.google.gson.JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", content)
        }
        val choice = com.google.gson.JsonObject().apply { add("message", message) }
        return com.google.gson.JsonObject().apply {
            addProperty("id", "x")
            add("choices", com.google.gson.JsonArray().apply { add(choice) })
        }.toString()
    }

    @Test
    fun `sends model messages and returns content`() {
        var capturedUrl = ""
        var capturedBody = ""
        val client = ModelClient(config) { _, url, body, _ ->
            capturedUrl = url
            capturedBody = body ?: ""
            chatReply("{\"tool\": \"mt_apk_search\"}")
        }
        val reply = client.chat(listOf(ChatMessage("system", "p"), ChatMessage("user", "hi")))
        assertEquals("{\"tool\": \"mt_apk_search\"}", reply)
        assertEquals("https://api.deepseek.com/v1/chat/completions", capturedUrl)
        val body = JsonParser.parseString(capturedBody).asJsonObject
        assertEquals("deepseek-reasoner", body.get("model").asString)
        assertEquals(0.0, body.get("temperature").asDouble, 0.001)
        assertEquals(8000, body.get("max_tokens").asInt)
        assertEquals(2, body.getAsJsonArray("messages").size())
        assertEquals("hi", body.getAsJsonArray("messages")[1].asJsonObject.get("content").asString)
    }

    @Test
    fun `throws on provider error object`() {
        val client = ModelClient(config) { _, _, _, _ ->
            """{"error":{"message":"Incorrect API key","type":"invalid_request"}}"""
        }
        try {
            client.chat(listOf(ChatMessage("user", "hi")))
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertTrue(e.message!!.contains("Incorrect API key"))
        }
    }

    @Test
    fun `throws when reply has no content`() {
        val client = ModelClient(config) { _, _, _, _ ->
            """{"choices":[{"message":{"role":"assistant","content":""}}]}"""
        }
        try {
            client.chat(listOf(ChatMessage("user", "hi")))
            fail("expected ModelException")
        } catch (e: ModelException) {
            assertTrue(e.message!!.contains("بلا محتوى"))
        }
    }

    @Test
    fun `lists models from the models endpoint`() {
        var capturedUrl = ""
        var capturedMethod = ""
        val client = ModelClient(config) { method, url, _, _ ->
            capturedMethod = method
            capturedUrl = url
            """{"data":[{"id":"deepseek-reasoner"},{"id":"deepseek-chat"}]}"""
        }
        val models = client.listModels()
        assertEquals("GET", capturedMethod)
        assertEquals("https://api.deepseek.com/v1/models", capturedUrl)
        assertEquals(listOf("deepseek-reasoner", "deepseek-chat"), models)
    }

    @Test
    fun `requires key and model before chatting`() {
        val noKey = ModelClient(config.copy(apiKey = "")) { _, _, _, _ -> "{}" }
        try {
            noKey.chat(listOf(ChatMessage("user", "hi")))
            fail("expected exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("مفتاح"))
        }
        val noModel = ModelClient(config.copy(model = "")) { _, _, _, _ -> "{}" }
        try {
            noModel.chat(listOf(ChatMessage("user", "hi")))
            fail("expected exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("نموذج"))
        }
    }
}
