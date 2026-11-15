package com.musaed.analysis.mcp

import java.io.File

/** يحفظ المحادثة وسجل أدواتها: JSON + نص مقروء — على نمط مخازن المرحلة ٢. */
object ChatStore {
    const val JSON_FILE = "chat_history.json"
    const val TEXT_FILE = "chat_history.txt"
    const val LOG_JSON_FILE = "chat_log.json"
    const val LOG_TEXT_FILE = "chat_log.txt"

    fun save(dir: File, session: ChatSession, log: SessionLog) {
        dir.mkdirs()
        OutputArchive.preserve(dir, JSON_FILE)
        OutputArchive.preserve(dir, TEXT_FILE)
        OutputArchive.preserve(dir, LOG_JSON_FILE)
        OutputArchive.preserve(dir, LOG_TEXT_FILE)
        File(dir, JSON_FILE).writeText(session.toJson())
        File(dir, TEXT_FILE).writeText(toText(session))
        File(dir, LOG_JSON_FILE).writeText(log.toJsonString())
        File(dir, LOG_TEXT_FILE).writeText(log.toText())
    }

    /** يقرأ المحادثة المحفوظة؛ ملف غائب أو تالف يعني محادثة جديدة. */
    fun load(dir: File): ChatSession {
        val file = File(dir, JSON_FILE)
        if (!file.exists()) return ChatSession()
        return try {
            ChatSession.fromJson(file.readText())
        } catch (_: Exception) {
            ChatSession()
        }
    }

    /** نص مقروء للمحادثة: كل دور بسطره وبدايته «أنت:» أو «النموذج:». */
    fun toText(session: ChatSession): String {
        return buildString {
            appendLine("محادثة مع النموذج — مساعد التحليل")
            for (turn in session.turns()) {
                val who = if (turn.role == "user") "أنت" else "النموذج"
                appendLine()
                appendLine("$who: ${turn.content}")
            }
        }
    }
}
