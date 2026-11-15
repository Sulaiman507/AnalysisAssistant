package com.musaed.analysis.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickScanTest {

    private fun hitResult(vararg locators: String): String {
        val hits = locators.joinToString(",") {
            """{"locator":"$it","searchScope":"dex_class","hit":{"kind":"dex_class_name","matchKind":"class"}}"""
        }
        return """{"data":{"hits":[$hits],"pagination":{"returnedCount":${locators.size}}}}"""
    }

    private val emptySearch =
        """{"data":{"hits":[],"pagination":{"returnedCount":0}}}"""

    private fun classesResult(vararg javaNames: String): String {
        val items = javaNames.joinToString(",") { name ->
            val locator = "dex_class:" + name.replace('.', '/') + ";"
            """{"locator":"$locator","javaName":"$name"}"""
        }
        return """{"data":{"items":[$items]}}"""
    }

    private fun clearServer() = FakeServer(
        emptyMap(),
        sequential = mapOf(
            "mt_apk_search" to listOf(
                FakeMcp.toolResult(hitResult("dex_field:com/example/PremiumGate;->isPremium:Z")),
                FakeMcp.toolResult(hitResult("dex_method:com/example/PremiumGate;->isPremium()Z")),
                FakeMcp.toolResult(emptySearch),
                FakeMcp.toolResult(emptySearch),
                FakeMcp.toolResult(emptySearch),
                FakeMcp.toolResult(emptySearch),
                FakeMcp.toolResult(emptySearch),
                FakeMcp.toolResult(emptySearch),
                FakeMcp.toolResult(emptySearch),
                FakeMcp.toolResult(emptySearch),
                FakeMcp.toolResult(emptySearch),
                FakeMcp.toolResult(emptySearch),
                FakeMcp.toolResult(emptySearch),
                FakeMcp.toolResult(emptySearch),
            ),
            "mt_apk_dex_list_classes" to listOf(
                FakeMcp.toolResult(
                    classesResult(
                        "com.musaed.test.clear.PremiumGate",
                        "com.musaed.test.clear.MainActivity",
                        "com.musaed.test.clear.App",
                    )
                ),
            ),
        ),
    )

    @Test
    fun `finds keywords and reports no obfuscation on a clear app`() {
        val server = clearServer()
        val result = QuickScan(server::call).scan("ws-1")
        assertEquals(listOf("premium", "isPremium"), result.keywordsFound)
        assertFalse(result.billingSdkFound)
        assertEquals(ObfuscationLevel.NONE, result.obfuscation)
        assertTrue(result.obfuscationMeasured)
        assertEquals(3, result.classesSampled)
        assertEquals(0.0, result.shortNameRatio, 0.001)
        assertTrue(result.notes.isEmpty())
        val text = result.toText()
        assertTrue(text.contains("منعدمة"))
        assertTrue(text.contains("isPremium"))
        // ١٤ عملية بحث بالترتيب: ٩ كلمات ثم ٥ مكتبات دفع
        val searches = server.calls.filter { it.first == "mt_apk_search" }
        assertEquals(14, searches.size)
        assertEquals(
            listOf(
                "premium", "isPremium", "isPro", "hasPremium",
                "vip", "subscri", "billing", "purchase", "entitlement",
                "BillingClient", "RevenueCat", "Adapty", "Qonversion", "Superwall",
            ),
            searches.map { it.second.get("query").asString },
        )
    }

    @Test
    fun `reports high obfuscation when most class names are short`() {
        val server = FakeServer(
            emptyMap(),
            sequential = mapOf(
                "mt_apk_search" to List(14) { FakeMcp.toolResult(emptySearch) },
                "mt_apk_dex_list_classes" to listOf(
                    FakeMcp.toolResult(
                        classesResult(
                            "a.a", "a.b", "a.c", "a.d",
                            "a.e", "a.f", "a.g", "a.h",
                            "com.example.Real", "com.example.Main",
                        )
                    ),
                ),
            ),
        )
        val result = QuickScan(server::call).scan("ws-1")
        assertTrue(result.keywordsFound.isEmpty())
        assertEquals(ObfuscationLevel.HIGH, result.obfuscation)
        assertEquals(0.8, result.shortNameRatio, 0.001)
        assertEquals(10, result.classesSampled)
        assertTrue(result.toText().contains("عالية"))
    }

    @Test
    fun `detects the billing SDK`() {
        val server = FakeServer(
            emptyMap(),
            sequential = mapOf(
                "mt_apk_search" to List(9) { FakeMcp.toolResult(emptySearch) } +
                    listOf(
                        FakeMcp.toolResult(
                            hitResult("dex_class:com/android/billingclient/api/BillingClient;")
                        )
                    ) + List(4) { FakeMcp.toolResult(emptySearch) },
                "mt_apk_dex_list_classes" to listOf(
                    FakeMcp.toolResult(
                        classesResult(
                            "com.example.Main", "com.example.Store",
                            "com.example.Settings", "com.example.App",
                            "com.example.Utils",
                        )
                    ),
                ),
            ),
        )
        val result = QuickScan(server::call).scan("ws-1")
        assertTrue(result.billingSdkFound)
        assertEquals("BillingClient", result.billingSdkName)
        assertEquals(ObfuscationLevel.NONE, result.obfuscation)
    }

    @Test
    fun `never throws when every tool call fails`() {
        val server = FakeServer(
            emptyMap(),
            failNames = setOf("mt_apk_search", "mt_apk_dex_list_classes"),
        )
        val result = QuickScan(server::call).scan("ws-1")
        assertTrue(result.keywordsFound.isEmpty())
        assertFalse(result.billingSdkFound)
        assertFalse(result.obfuscationMeasured)
        assertEquals(ObfuscationLevel.MEDIUM, result.obfuscation)
        assertEquals(0, result.classesSampled)
        assertTrue(result.notes.isNotEmpty())
        assertTrue(result.toText().contains("لم تُقَس"))
    }

    @Test
    fun `counts search hits from the live hits shape`() {
        val data = com.google.gson.JsonParser.parseString(
            """{"hits":[{"locator":"dex_class:com/example/Foo;"}],
                "pagination":{"returnedCount":1}}"""
        ).asJsonObject
        assertEquals(1, McpResults.searchHitsCount(data))
        val empty = com.google.gson.JsonParser.parseString(
            """{"hits":[],"pagination":{"returnedCount":0}}"""
        ).asJsonObject
        assertEquals(0, McpResults.searchHitsCount(empty))
    }
}
