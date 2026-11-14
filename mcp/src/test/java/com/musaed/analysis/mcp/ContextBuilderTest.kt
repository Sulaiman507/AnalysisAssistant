package com.musaed.analysis.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderTest {

    private val outline = """
        {"data":{
          "fields":[{"name":"isPremium","descriptor":"Z"}],
          "methods":[
            {"name":"hasPremiumFeatures","descriptor":"(Landroid/content/Context;)Z"},
            {"name":"isPremium","descriptor":"()Z"}
          ]
        }}
    """.trimIndent()

    private val pseudocode = """
        {"data":{"textWindow":{"text":"class PremiumGate {\n  boolean hasPremiumFeatures(Context c) { return isPremium(); }\n}"}}}
    """.trimIndent()

    private val smali = """
        {"data":{"textWindow":{"text":".method public hasPremiumFeatures(Landroid/content/Context;)Z\n  .locals 1\n  return v0\n.end method"}}}
    """.trimIndent()

    private val xrefs = """
        {"data":{"refs":[
          {"sourceLocator":"dex_method:com/example/Other;->call()V"},
          {"sourceLocator":"dex_method:com/example/Main;->onCreate(Landroid/os/Bundle;)V"}
        ]}}
    """.trimIndent()

    private fun fullServer() = FakeServer(
        mapOf(
            "mt_apk_dex_read_class_outline" to FakeMcp.toolResult(outline),
            "mt_apk_dex_read_pseudocode" to FakeMcp.toolResult(pseudocode),
            "mt_apk_read_text" to FakeMcp.toolResult(smali),
            "mt_apk_dex_xref" to FakeMcp.toolResult(xrefs),
        )
    )

    @Test
    fun `builds a complete package with pseudocode and xrefs`() {
        val server = fullServer()
        val pkg = ContextBuilder(server::call)
            .build("ws-1", "dex_class:com/musaed/test/clear/PremiumGate;")
        assertEquals("dex_class:com/musaed/test/clear/PremiumGate;", pkg.classLocator)
        assertEquals(listOf("isPremium"), pkg.fields)
        assertEquals(2, pkg.methods.size)
        assertEquals(2, pkg.bodies.size)
        assertTrue(pkg.bodies.all { it.source == "pseudocode" })
        assertTrue(pkg.bodies[0].pseudocode!!.contains("hasPremiumFeatures"))
        assertEquals(2, pkg.xrefs.size)
        assertTrue(pkg.xrefs[0].contains("Other"))
        assertFalse(pkg.truncated)
        val text = pkg.toText()
        assertTrue(text.contains("PremiumGate"))
        assertTrue(text.contains("hasPremiumFeatures"))
        // الأدوات المستدعاة: مخطط + كود شبهي لكل دالة + مرجعان
        assertEquals(
            listOf(
                "mt_apk_dex_read_class_outline",
                "mt_apk_dex_read_pseudocode",
                "mt_apk_dex_read_pseudocode",
                "mt_apk_dex_xref",
                "mt_apk_dex_xref",
            ),
            server.calls.map { it.first },
        )
    }

    @Test
    fun `falls back to Smali when pseudocode fails`() {
        val server = FakeServer(
            mapOf(
                "mt_apk_dex_read_class_outline" to FakeMcp.toolResult(outline),
                "mt_apk_read_text" to FakeMcp.toolResult(smali),
                "mt_apk_dex_xref" to FakeMcp.toolResult(xrefs),
            ),
            failNames = setOf("mt_apk_dex_read_pseudocode"),
        )
        val pkg = ContextBuilder(server::call)
            .build("ws-1", "dex_class:com/musaed/test/clear/PremiumGate;")
        assertEquals(2, pkg.bodies.size)
        assertTrue(pkg.bodies.all { it.source == "smali" })
        assertTrue(pkg.bodies[0].smali!!.contains(".method"))
        assertFalse(pkg.truncated)
    }

    @Test
    fun `marks truncated when size cap is exceeded`() {
        val huge = "x".repeat(50_000)
        val hugePseudo = """{"data":{"textWindow":{"text":"$huge"}}}"""
        val server = FakeServer(
            mapOf(
                "mt_apk_dex_read_class_outline" to FakeMcp.toolResult(outline),
                "mt_apk_dex_read_pseudocode" to FakeMcp.toolResult(hugePseudo),
                "mt_apk_read_text" to FakeMcp.toolResult(smali),
                "mt_apk_dex_xref" to FakeMcp.toolResult(xrefs),
            )
        )
        val pkg = ContextBuilder(server::call)
            .build("ws-1", "dex_class:com/musaed/test/clear/PremiumGate;")
        assertTrue(pkg.truncated)
        assertTrue(pkg.bodies.isEmpty())
        assertTrue(pkg.notes.any { it.contains("48000") || it.contains("حُذف") })
    }

    @Test
    fun `fails gracefully when outline is missing`() {
        val server = FakeServer(emptyMap(), failNames = setOf("mt_apk_dex_read_class_outline"))
        val pkg = ContextBuilder(server::call)
            .build("ws-1", "dex_class:com/musaed/test/clear/PremiumGate;")
        assertTrue(pkg.fields.isEmpty())
        assertTrue(pkg.methods.isEmpty())
        assertTrue(pkg.bodies.isEmpty())
        assertTrue(pkg.notes.isNotEmpty())
        assertFalse(pkg.truncated)
    }
}
