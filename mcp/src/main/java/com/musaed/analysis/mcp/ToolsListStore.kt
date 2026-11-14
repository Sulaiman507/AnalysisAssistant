package com.musaed.analysis.mcp

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** يحفظ قائمة الأدوات الحية في ملفين: JSON خام + نص مقروء — لمراجعة الإنسان ومشاركته. */
object ToolsListStore {
    const val JSON_FILE = "tools_list.json"
    const val TEXT_FILE = "tools_list.txt"

    fun save(dir: File, tools: List<McpTool>, serverName: String, serverVersion: String): Pair<File, File> {
        dir.mkdirs()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val root = JsonObject().apply {
            addProperty("fetchedAt", now)
            addProperty("serverName", serverName)
            addProperty("serverVersion", serverVersion)
            addProperty("toolCount", tools.size)
            val arr = JsonArray()
            tools.forEach { tool ->
                arr.add(JsonObject().apply {
                    addProperty("name", tool.name)
                    if (tool.description != null) addProperty("description", tool.description)
                    if (tool.inputSchema != null) add("inputSchema", tool.inputSchema)
                })
            }
            add("tools", arr)
        }
        val jsonFile = File(dir, JSON_FILE)
        jsonFile.writeText(GsonBuilder().setPrettyPrinting().create().toJson(root))
        val text = buildString {
            appendLine("قائمة أدوات MT MCP — جُلبت حياً من الجهاز")
            appendLine("التاريخ: $now")
            appendLine("الخادم: $serverName ($serverVersion)")
            appendLine("عدد الأدوات: ${tools.size}")
            appendLine()
            tools.forEachIndexed { index, tool ->
                appendLine("${index + 1}. ${tool.name}")
                if (!tool.description.isNullOrBlank()) appendLine("   ${tool.description}")
            }
        }
        val textFile = File(dir, TEXT_FILE)
        textFile.writeText(text)
        return jsonFile to textFile
    }
}
