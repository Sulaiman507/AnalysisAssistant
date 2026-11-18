package com.musaed.analysis.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSettingsTest {

    @Test
    fun `deepseek is the default`() {
        val s = ProviderSettings.deepSeek()
        assertEquals("DeepSeek", s.name)
        assertEquals("https://api.deepseek.com/v1", s.baseUrl)
        assertEquals("/chat/completions", s.apiPath)
        assertEquals("deepseek-reasoner", s.model)
        assertFalse(s.isConfigured())
        assertTrue(ProviderSettings.deepSeek("sk-x").isConfigured())
    }

    @Test
    fun `gonkaRouter preset matches the official docs`() {
        val s = ProviderSettings.gonkaRouter()
        assertEquals("GonkaRouter", s.name)
        assertEquals("https://api.gonkarouter.io/v1", s.baseUrl)
        assertEquals("/chat/completions", s.apiPath)
        assertEquals("deepseek-ai/DeepSeek-V4-Flash-0731", s.model)
        assertFalse(s.isConfigured())
        assertTrue(ProviderSettings.gonkaRouter("gk-x").isConfigured())
    }

    @Test
    fun `zro preset matches the official docs`() {
        val s = ProviderSettings.zro()
        assertEquals("Zro", s.name)
        assertEquals("https://zro.moonmath.ai/v1", s.baseUrl)
        assertEquals("/chat/completions", s.apiPath)
        assertEquals("deepseek-v4.1-flash", s.model)
        assertFalse(s.isConfigured())
        assertTrue(ProviderSettings.zro("zro-x").isConfigured())
    }

    @Test
    fun `custom provider starts with an empty model to be fetched`() {
        val s = ProviderSettings.custom("sk-x", "https://api.example.com/v1")
        assertEquals("مخصص", s.name)
        assertEquals("https://api.example.com/v1", s.baseUrl)
        assertEquals("/chat/completions", s.apiPath)
        assertEquals("", s.model)
        // بلا نموذج بعد — غير مكتمل حتى يختار النموذج من القائمة المجلوبة.
        assertFalse(s.isConfigured())
    }

    @Test
    fun `custom base url gains v1 when missing`() {
        assertEquals("https://api.example.com/v1", ProviderSettings.custom(baseUrl = "https://api.example.com").baseUrl)
        assertEquals("https://api.example.com/v1", ProviderSettings.custom(baseUrl = "https://api.example.com/").baseUrl)
        assertEquals("https://api.example.com/v1", ProviderSettings.custom(baseUrl = "  https://api.example.com  ").baseUrl)
    }

    @Test
    fun `custom base url keeps v1 and strips a trailing slash`() {
        assertEquals("https://api.example.com/v1", ProviderSettings.custom(baseUrl = "https://api.example.com/v1").baseUrl)
        assertEquals("https://api.example.com/v1", ProviderSettings.custom(baseUrl = "https://api.example.com/v1/").baseUrl)
        assertEquals("", ProviderSettings.custom(baseUrl = "").baseUrl)
    }

    @Test
    fun `json round trip preserves all fields`() {
        val s = ProviderSettings.gonkaRouter("gk-secret")
        val restored = ProviderSettings.fromJson(s.toJson())
        assertEquals(s, restored)
        assertEquals("deepseek-ai/DeepSeek-V4-Flash-0731", restored!!.toModelConfig().model)
        assertEquals("https://api.gonkarouter.io/v1", restored.toModelConfig().baseUrl)
    }

    @Test
    fun `fromJson returns null for invalid input`() {
        assertNull(ProviderSettings.fromJson(null))
        assertNull(ProviderSettings.fromJson(""))
        assertNull(ProviderSettings.fromJson("not json"))
    }
}
