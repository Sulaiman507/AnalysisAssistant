package com.musaed.analysis.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StressTestTest {

    private val installedMt = """
        {"data":{"items":[
          {"path":"mt://installed-app/bin.mt.plus",
           "packageName":"bin.mt.plus","name":"MT Manager"}
        ]}}
    """.trimIndent()

    private val opened = """{"data":{"workspaceId":"ws-stress"}}""".trimIndent()

    private val entries = """
        {"data":{"items":[
          {"path":"classes.dex"},
          {"path":"classes2.dex"},
          {"path":"AndroidManifest.xml"}
        ]}}
    """.trimIndent()

    private val signature = """{"data":{"certificate":"fake-cert"}}""".trimIndent()

    private val emptyHits = """{"data":{"items":[]}}""".trimIndent()

    private val classesPage1 = """
        {"data":{
          "items":[
            {"locator":"dex_class:com/example/Alpha;","name":"com.example.Alpha"},
            {"locator":"dex_class:com/example/Beta;","name":"com.example.Beta"}
          ],
          "pagination":{"hasMore":true,"nextCursor":"cursor-1"}
        }}
    """.trimIndent()

    private val outline = """
        {"data":{
          "fields":[{"name":"flag","descriptor":"Z"}],
          "methods":[
            {"name":"foo","descriptor":"()V"},
            {"name":"bar","descriptor":"(I)V"}
          ]
        }}
    """.trimIndent()

    private val pseudocode = """
        {"data":{"textWindow":{"text":"class Alpha {\n  void foo() { return; }\n}"}}}
    """.trimIndent()

    private val xrefs = """
        {"data":{"refs":[
          {"sourceLocator":"dex_method:com/example/Other;->call()V"}
        ]}}
    """.trimIndent()

    private val smali = """
        {"data":{"textWindow":{"text":".method public foo()V\n  return-void\n.end method"}}}
    """.trimIndent()

    private fun fullServer() = FakeServer(
        mapOf(
            "mt_apk_list_installed_apps" to FakeMcp.toolResult(installedMt),
            "mt_apk_workspace_open" to FakeMcp.toolResult(opened),
            "mt_apk_list_entries" to FakeMcp.toolResult(entries),
            "mt_apk_read_signature" to FakeMcp.toolResult(signature),
            "mt_apk_search" to FakeMcp.toolResult(emptyHits),
            "mt_apk_dex_list_classes" to FakeMcp.toolResult(classesPage1),
            "mt_apk_continue" to FakeMcp.toolResult(emptyHits),
            "mt_apk_dex_read_class_outline" to FakeMcp.toolResult(outline),
            "mt_apk_dex_read_pseudocode" to FakeMcp.toolResult(pseudocode),
            "mt_apk_dex_xref" to FakeMcp.toolResult(xrefs),
            "mt_apk_resource_list" to FakeMcp.toolResult(emptyHits),
            "mt_apk_read_text" to FakeMcp.toolResult(smali),
        )
    )

    @Test
    fun `twenty calls succeed with complete log and package evidence`() {
        val server = fullServer()
        val log = SessionLog()
        val runner = ToolRunner(server::call, log, 5_000)
        val result = StressTester(runner, log, "bin.mt.plus").run()
        assertTrue(result.success)
        assertEquals(20, result.callsMade)
        assertEquals(20, result.callsOk)
        assertEquals(2, result.dexCount)
        assertTrue(result.logComplete)
        assertEquals("ws-stress", result.workspaceId)
        assertEquals("bin.mt.plus", result.targetPackage)
        // الخطوات ١–٢٠ كلها حاضرة في السجل (وقد تحوي الخطوة 0 أيضاً:
        // استدعاءات بناء حزمة الكلاس الإضافية خارج العدّ)
        assertTrue((1..20).all { it in log.stepsPresent() })
        // الدليل الإضافي: حزمة الكلاس الأول بُنيت خارج العدّ
        assertTrue(result.report.contains("حزمة الكلاس الأول"))
        assertTrue(result.report.contains("com/example/Alpha"))
        runner.shutdown()
    }

    @Test
    fun `a failing search step is documented without crashing`() {
        val server = FakeServer(
            mapOf(
                "mt_apk_list_installed_apps" to FakeMcp.toolResult(installedMt),
                "mt_apk_workspace_open" to FakeMcp.toolResult(opened),
                "mt_apk_list_entries" to FakeMcp.toolResult(entries),
                "mt_apk_read_signature" to FakeMcp.toolResult(signature),
                "mt_apk_dex_list_classes" to FakeMcp.toolResult(classesPage1),
                "mt_apk_continue" to FakeMcp.toolResult(emptyHits),
                "mt_apk_dex_read_class_outline" to FakeMcp.toolResult(outline),
                "mt_apk_dex_read_pseudocode" to FakeMcp.toolResult(pseudocode),
                "mt_apk_dex_xref" to FakeMcp.toolResult(xrefs),
                "mt_apk_resource_list" to FakeMcp.toolResult(emptyHits),
                "mt_apk_read_text" to FakeMcp.toolResult(smali),
            ),
            failNames = setOf("mt_apk_search"),
        )
        val log = SessionLog()
        val runner = ToolRunner(server::call, log, 5_000)
        // لا يرمي — ينتهي بتقرير يوثق الخطوات الفاشلة
        val result = StressTester(runner, log, "bin.mt.plus").run()
        assertFalse(result.success)
        assertTrue(result.callsOk < 20)
        assertTrue(result.report.contains("فاشلة"))
        assertTrue(result.report.contains("mt_apk_search"))
        runner.shutdown()
    }

    @Test
    fun `counts dex files from zip_entry locators when path is absent`() {
        val liveEntries = """
            {"data":{"items":[
              {"locator":"zip_entry:AndroidManifest.xml","size":1},
              {"locator":"zip_entry:classes.dex","size":10},
              {"locator":"zip_entry:classes2.dex","size":10},
              {"locator":"zip_entry:assets/audience_network/classes.dex","size":10}
            ]}}
        """.trimIndent()
        val server = FakeServer(
            mapOf(
                "mt_apk_list_installed_apps" to FakeMcp.toolResult(installedMt),
                "mt_apk_workspace_open" to FakeMcp.toolResult(opened),
                "mt_apk_list_entries" to FakeMcp.toolResult(liveEntries),
                "mt_apk_read_signature" to FakeMcp.toolResult(signature),
                "mt_apk_search" to FakeMcp.toolResult(emptyHits),
                "mt_apk_dex_list_classes" to FakeMcp.toolResult(classesPage1),
                "mt_apk_continue" to FakeMcp.toolResult(emptyHits),
                "mt_apk_dex_read_class_outline" to FakeMcp.toolResult(outline),
                "mt_apk_dex_read_pseudocode" to FakeMcp.toolResult(pseudocode),
                "mt_apk_dex_xref" to FakeMcp.toolResult(xrefs),
                "mt_apk_resource_list" to FakeMcp.toolResult(emptyHits),
                "mt_apk_read_text" to FakeMcp.toolResult(smali),
            )
        )
        val log = SessionLog()
        val runner = ToolRunner(server::call, log, 5_000)
        val result = StressTester(runner, log, "bin.mt.plus").run()
        assertTrue(result.success)
        assertEquals(3, result.dexCount)
        runner.shutdown()
    }

    @Test
    fun `uses workspace dexEntries when zip listing finds fewer dex files`() {
        val openedWithCounts = """
            {"data":{"workspaceId":"ws-stress","counts":{"dexEntries":7,"zipEntries":200}}}
        """.trimIndent()
        val server = FakeServer(
            mapOf(
                "mt_apk_list_installed_apps" to FakeMcp.toolResult(installedMt),
                "mt_apk_workspace_open" to FakeMcp.toolResult(openedWithCounts),
                "mt_apk_list_entries" to FakeMcp.toolResult(entries),
                "mt_apk_read_signature" to FakeMcp.toolResult(signature),
                "mt_apk_search" to FakeMcp.toolResult(emptyHits),
                "mt_apk_dex_list_classes" to FakeMcp.toolResult(classesPage1),
                "mt_apk_continue" to FakeMcp.toolResult(emptyHits),
                "mt_apk_dex_read_class_outline" to FakeMcp.toolResult(outline),
                "mt_apk_dex_read_pseudocode" to FakeMcp.toolResult(pseudocode),
                "mt_apk_dex_xref" to FakeMcp.toolResult(xrefs),
                "mt_apk_resource_list" to FakeMcp.toolResult(emptyHits),
                "mt_apk_read_text" to FakeMcp.toolResult(smali),
            )
        )
        val log = SessionLog()
        val runner = ToolRunner(server::call, log, 5_000)
        val result = StressTester(runner, log, "bin.mt.plus").run()
        assertTrue(result.success)
        assertEquals(7, result.dexCount)
        runner.shutdown()
    }

    @Test
    fun `falls back to another user app when default target is blocked`() {
        val blocked = FakeMcp.errorResult(
            """{"ok":false,"error":{"code":"APK_OPEN_BLOCKED","message":"This APK package is not supported by mt_apk_workspace_open."}}"""
        )
        val altInstalled = """
            {"data":{"items":[
              {"path":"mt://installed-app/bin.mt.plus",
               "packageName":"bin.mt.plus","name":"MT Manager"},
              {"path":"mt://installed-app/com.hecorat.screenrecorder.free",
               "packageName":"com.hecorat.screenrecorder.free","name":"AZ Screen Recorder"}
            ]}}
        """.trimIndent()
        val openedAlt = """
            {"data":{"workspaceId":"ws-alt","counts":{"dexEntries":5}}}
        """.trimIndent()
        val server = FakeServer(
            mapOf(
                "mt_apk_list_installed_apps" to FakeMcp.toolResult(installedMt),
                "mt_apk_workspace_open" to FakeMcp.toolResult(openedAlt),
                "mt_apk_list_entries" to FakeMcp.toolResult(entries),
                "mt_apk_read_signature" to FakeMcp.toolResult(signature),
                "mt_apk_search" to FakeMcp.toolResult(emptyHits),
                "mt_apk_dex_list_classes" to FakeMcp.toolResult(classesPage1),
                "mt_apk_continue" to FakeMcp.toolResult(emptyHits),
                "mt_apk_dex_read_class_outline" to FakeMcp.toolResult(outline),
                "mt_apk_dex_read_pseudocode" to FakeMcp.toolResult(pseudocode),
                "mt_apk_dex_xref" to FakeMcp.toolResult(xrefs),
                "mt_apk_resource_list" to FakeMcp.toolResult(emptyHits),
                "mt_apk_read_text" to FakeMcp.toolResult(smali),
            ),
            sequential = mapOf(
                "mt_apk_list_installed_apps" to listOf(
                    FakeMcp.toolResult(installedMt),
                    FakeMcp.toolResult(altInstalled),
                ),
                "mt_apk_workspace_open" to listOf(blocked, FakeMcp.toolResult(openedAlt)),
            ),
        )
        val log = SessionLog()
        val runner = ToolRunner(server::call, log, 5_000)
        val result = StressTester(runner, log, "bin.mt.plus").run()
        assertTrue(result.success)
        assertEquals("com.hecorat.screenrecorder.free", result.targetPackage)
        assertEquals("ws-alt", result.workspaceId)
        assertEquals(5, result.dexCount)
        assertTrue(result.report.contains("APK_OPEN_BLOCKED") || result.report.contains("محظور"))
        runner.shutdown()
    }

    @Test
    fun `package prefix from live L-prefixed class locator is not doubled`() {
        val liveClasses = """
            {"data":{
              "items":[
                {"locator":"dex_class:La/a;","javaName":"a.a"},
                {"locator":"dex_class:La/b;","javaName":"a.b"}
              ],
              "pagination":{"hasMore":true,"nextCursor":"cursor-1"}
            }}
        """.trimIndent()
        val server = FakeServer(
            mapOf(
                "mt_apk_list_installed_apps" to FakeMcp.toolResult(installedMt),
                "mt_apk_workspace_open" to FakeMcp.toolResult(opened),
                "mt_apk_list_entries" to FakeMcp.toolResult(entries),
                "mt_apk_read_signature" to FakeMcp.toolResult(signature),
                "mt_apk_search" to FakeMcp.toolResult(emptyHits),
                "mt_apk_dex_list_classes" to FakeMcp.toolResult(liveClasses),
                "mt_apk_continue" to FakeMcp.toolResult(emptyHits),
                "mt_apk_dex_read_class_outline" to FakeMcp.toolResult(outline),
                "mt_apk_dex_read_pseudocode" to FakeMcp.toolResult(pseudocode),
                "mt_apk_dex_xref" to FakeMcp.toolResult(xrefs),
                "mt_apk_resource_list" to FakeMcp.toolResult(emptyHits),
                "mt_apk_read_text" to FakeMcp.toolResult(smali),
            )
        )
        val log = SessionLog()
        val runner = ToolRunner(server::call, log, 5_000)
        val result = StressTester(runner, log, "bin.mt.plus").run()
        assertTrue(result.success)
        val prefixes = server.calls
            .filter { it.first == "mt_apk_dex_list_classes" }
            .mapNotNull { call ->
                if (call.second.has("prefix")) call.second.get("prefix").asString else null
            }
            .filter { it.isNotEmpty() }
        assertTrue(prefixes.contains("La/"))
        assertTrue(prefixes.none { it == "LLa/" })
        runner.shutdown()
    }
}
