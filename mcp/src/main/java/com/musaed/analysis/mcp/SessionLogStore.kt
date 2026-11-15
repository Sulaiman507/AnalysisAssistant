package com.musaed.analysis.mcp

import java.io.File

/** يحفظ سجل الجلسة في ملفين: JSON كامل + نص مقروء — على نمط ToolsListStore. */
object SessionLogStore {
    const val JSON_FILE = "session_log.json"
    const val TEXT_FILE = "session_log.txt"

    fun save(dir: File, log: SessionLog): Pair<File, File> {
        dir.mkdirs()
        OutputArchive.preserve(dir, JSON_FILE)
        OutputArchive.preserve(dir, TEXT_FILE)
        val jsonFile = File(dir, JSON_FILE)
        jsonFile.writeText(log.toJsonString())
        val textFile = File(dir, TEXT_FILE)
        textFile.writeText(log.toText())
        return jsonFile to textFile
    }
}
