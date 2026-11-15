package com.musaed.analysis.mcp

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionLogTest {

    @Test
    fun `saves entries verbatim and truncates long raw results in text`() {
        val log = SessionLog()
        log.record(
            step = 1, attempt = 1, tool = "mt_apk_read_signature",
            argumentsJson = """{"workspaceId":"ws-1"}""",
            status = "ok", durationMs = 42,
            rawResult = """{"data":{"workspaceId":"ws-1"}}""", error = null,
        )
        val longRaw = "y".repeat(5000)
        log.record(
            step = 2, attempt = 1, tool = "mt_apk_search",
            argumentsJson = """{"query":"premium"}""",
            status = "ok", durationMs = 7,
            rawResult = longRaw, error = null,
        )
        val entries = log.entries()
        assertEquals(2, entries.size)
        assertEquals(1, entries[0].seq)
        assertEquals(2, entries[1].seq)
        assertEquals("""{"data":{"workspaceId":"ws-1"}}""", entries[0].rawResult)
        assertEquals(longRaw, entries[1].rawResult)
        // النص يقتطع النتيجة الطويلة عند 4000 حرف مع تنبيه أن الكامل في JSON
        val text = log.toText()
        assertTrue(text.contains("اقتُطعت"))
        assertTrue(text.contains("session_log.json"))
        assertTrue(text.contains("mt_apk_search"))
        // الحفظ في ملفين: JSON يحمل الكامل حرفياً، والنص يحمل المقتطع
        val dir = Files.createTempDirectory("sessionlog").toFile()
        val (jsonFile, textFile) = SessionLogStore.save(dir, log)
        assertTrue(jsonFile.readText().contains(longRaw))
        assertTrue(textFile.readText().contains("اقتُطعت"))
    }

    @Test
    fun `save archives previous session files instead of replacing them`() {
        val dir = Files.createTempDirectory("sessionlog-archive").toFile()
        val first = SessionLog()
        first.record(1, 1, "mt_apk_read_signature", "{}", "ok", 1, "FIRST_RUN", null)
        SessionLogStore.save(dir, first)
        val second = SessionLog()
        second.record(1, 1, "mt_apk_search", "{}", "ok", 1, "SECOND_RUN", null)
        SessionLogStore.save(dir, second)
        val current = File(dir, SessionLogStore.JSON_FILE).readText()
        assertTrue(current.contains("SECOND_RUN"))
        assertFalse(current.contains("FIRST_RUN"))
        val archived = dir.listFiles()?.filter {
            it.name.startsWith("session_log_") && it.name.endsWith(".json") &&
                it.name != SessionLogStore.JSON_FILE
        } ?: emptyList()
        assertEquals(1, archived.size)
        assertTrue(archived[0].readText().contains("FIRST_RUN"))
    }

    @Test
    fun `stepsPresent returns the set of present steps`() {
        val log = SessionLog()
        log.record(1, 1, "mt_apk_read_signature", "{}", "ok", 1, "{}", null)
        log.record(2, 1, "mt_apk_search", "{}", "ok", 1, "{}", null)
        log.record(2, 2, "mt_apk_search", "{}", "error", 1, "", "فشل عابر")
        log.record(5, 1, "mt_apk_read_text", "{}", "ok", 1, "{}", null)
        assertEquals(setOf(1, 2, 5), log.stepsPresent())
    }

    @Test
    fun `toJson round trip preserves entries`() {
        val log = SessionLog()
        log.record(
            step = 7, attempt = 2, tool = "mt_apk_dex_xref",
            argumentsJson = """{"locator":"dex_method:com/foo/Bar;->go()V"}""",
            status = "error", durationMs = 120,
            rawResult = "", error = "انتهت المهلة",
        )
        val parsed = JsonParser.parseString(log.toJsonString()).asJsonObject
        assertEquals(1, parsed.get("count").asInt)
        val entry = parsed.getAsJsonArray("entries")[0].asJsonObject
        assertEquals(7, entry.get("step").asInt)
        assertEquals(2, entry.get("attempt").asInt)
        assertEquals("mt_apk_dex_xref", entry.get("tool").asString)
        assertEquals("error", entry.get("status").asString)
        assertEquals("انتهت المهلة", entry.get("error").asString)
    }
}
