package com.musaed.analysis.mcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateTesterTest {

    // أشكال ردود مفترضة حسب وصف الأدوات في القائمة الحية (data.items[].path،
    // وworkspaceId، ومحدد dex_class:...) — ليست ردوداً مرصودة من الخادم؛
    // الشكل الحقيقي يُتحقق منه على الهاتف عند تشغيل الاختبار، والتقرير
    // يحفظ الرد الخام عند عدم تطابق الشكل لتشخيصه.

    /** يبني نتيجة tools/call بشكل MCP القياسي: content نصي يحمل JSON. */
    private fun toolResult(dataJson: String): JsonObject {
        val data = JsonParser.parseString(dataJson).asJsonObject
        val textItem = JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", Gson().toJson(data))
        }
        return JsonObject().apply {
            add("content", JsonArray().apply { add(textItem) })
            addProperty("isError", false)
        }
    }

    /** نتيجة بصيغة structuredContent: المحتوى المنظم هو الحمولة نفسها. */
    private fun structuredResult(dataJson: String): JsonObject {
        val payload = JsonParser.parseString(dataJson).asJsonObject
        return JsonObject().apply {
            add("structuredContent", payload)
        }
    }

    private class FakeServer(private val responses: Map<String, JsonObject>) {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        fun call(name: String, args: JsonObject): JsonObject {
            calls += name to args
            return responses[name]
                ?: throw IllegalStateException("unexpected tool call: $name")
        }
    }

    private val installedClear = """
        {"data":{"items":[
          {"path":"mt://installed-app/com.musaed.test.clear",
           "packageName":"com.musaed.test.clear","name":"Clear Test"}
        ]}}
    """.trimIndent()

    private val installedEmpty = """{"data":{"items":[]}}""".trimIndent()

    private val availableClear = """
        {"data":{"items":[
          {"path":"clear.apk"},
          {"path":"Downloads/other.apk"}
        ]}}
    """.trimIndent()

    private val opened = """{"data":{"workspaceId":"ws-123"}}""".trimIndent()

    private val classesWithGate = """
        {"data":{"items":[
          {"locator":"dex_class:com/musaed/test/clear/MainActivity;",
           "name":"com.musaed.test.clear.MainActivity"},
          {"locator":"dex_class:com/musaed/test/clear/PremiumGate;",
           "name":"com.musaed.test.clear.PremiumGate"}
        ]}}
    """.trimIndent()

    private val classesNoGate = """
        {"data":{"items":[
          {"locator":"dex_class:com/musaed/test/clear/MainActivity;",
           "name":"com.musaed.test.clear.MainActivity"}
        ]}}
    """.trimIndent()

    private val outline = """
        {"data":{
          "fields":[{"name":"isPremium","descriptor":"Z"}],
          "methods":[
            {"name":"hasPremiumFeatures","descriptor":"(Landroid/content/Context;)Z"},
            {"name":"<init>","descriptor":"()V"}
          ]
        }}
    """.trimIndent()

    private val outlineUnknownShape = """
        {"data":{"summary":"class PremiumGate { boolean hasPremiumFeatures(Context c) }"}}
    """.trimIndent()

    private val expectedToolOrder = listOf(
        "mt_apk_list_installed_apps",
        "mt_apk_workspace_open",
        "mt_apk_dex_list_classes",
        "mt_apk_dex_read_class_outline",
    )

    private fun happyServer() = FakeServer(
        mapOf(
            "mt_apk_list_installed_apps" to toolResult(installedClear),
            "mt_apk_workspace_open" to toolResult(opened),
            "mt_apk_dex_list_classes" to toolResult(classesWithGate),
            "mt_apk_dex_read_class_outline" to toolResult(outline),
        )
    )

    @Test
    fun `happy path opens installed app and reads PremiumGate outline`() {
        val server = happyServer()
        val result = GateTester(server::call).run()
        assertTrue(result.success)
        assertEquals("ws-123", result.workspaceId)
        assertEquals("dex_class:com/musaed/test/clear/PremiumGate;", result.classLocator)
        assertTrue(result.report.contains("hasPremiumFeatures"))
        assertTrue(result.report.contains("isPremium"))
        assertEquals(expectedToolOrder, server.calls.map { it.first })
        // معامل فتح مساحة العمل: المسار من القائمة + مؤقتة
        val openArgs = server.calls[1].second
        assertEquals("mt://installed-app/com.musaed.test.clear", openArgs.get("path").asString)
        assertTrue(openArgs.get("temporary").asBoolean)
        // معامل قراءة المخطط: المحدد حرفياً كما رجع من سرد الكلاسات
        val outlineArgs = server.calls[3].second
        assertEquals("dex_class:com/musaed/test/clear/PremiumGate;", outlineArgs.get("locator").asString)
        assertEquals("", outlineArgs.get("editSessionId").asString)
    }

    @Test
    fun `falls back to available APK file when app is not installed`() {
        val server = FakeServer(
            mapOf(
                "mt_apk_list_installed_apps" to toolResult(installedEmpty),
                "mt_apk_list_available_apks" to structuredResult(availableClear),
                "mt_apk_workspace_open" to toolResult(opened),
                "mt_apk_dex_list_classes" to toolResult(classesWithGate),
                "mt_apk_dex_read_class_outline" to toolResult(outline),
            )
        )
        val result = GateTester(server::call).run()
        assertTrue(result.success)
        assertEquals(
            listOf(
                "mt_apk_list_installed_apps",
                "mt_apk_list_available_apks",
                "mt_apk_workspace_open",
                "mt_apk_dex_list_classes",
                "mt_apk_dex_read_class_outline",
            ),
            server.calls.map { it.first },
        )
        assertEquals("clear.apk", server.calls[2].second.get("path").asString)
    }

    @Test
    fun `fails with install message when app is nowhere`() {
        val server = FakeServer(
            mapOf(
                "mt_apk_list_installed_apps" to toolResult(installedEmpty),
                "mt_apk_list_available_apks" to toolResult("""{"data":{"items":[]}}"""),
            )
        )
        val result = GateTester(server::call).run()
        assertFalse(result.success)
        assertTrue(result.summary.contains("ثبّت"))
        assertTrue(result.summary.contains("clear.apk"))
        assertEquals(
            listOf("mt_apk_list_installed_apps", "mt_apk_list_available_apks"),
            server.calls.map { it.first },
        )
    }

    @Test
    fun `fails when PremiumGate is not among listed classes`() {
        val server = FakeServer(
            mapOf(
                "mt_apk_list_installed_apps" to toolResult(installedClear),
                "mt_apk_workspace_open" to toolResult(opened),
                "mt_apk_dex_list_classes" to toolResult(classesNoGate),
            )
        )
        val result = GateTester(server::call).run()
        assertFalse(result.success)
        assertTrue(result.summary.contains("PremiumGate"))
    }

    @Test
    fun `unknown outline shape still succeeds and keeps raw text for diagnosis`() {
        val server = FakeServer(
            mapOf(
                "mt_apk_list_installed_apps" to toolResult(installedClear),
                "mt_apk_workspace_open" to toolResult(opened),
                "mt_apk_dex_list_classes" to toolResult(classesWithGate),
                "mt_apk_dex_read_class_outline" to toolResult(outlineUnknownShape),
            )
        )
        val result = GateTester(server::call).run()
        assertTrue(result.success)
        assertTrue(result.report.contains("hasPremiumFeatures"))
    }
}
