package com.musaed.analysis.mcp

import java.io.File

/**
 * نقطة دخول للاختبار من سطر الأوامر (JVM) — نفس عميل التطبيق، بلا واجهة.
 * الاستخدام: McpProbe [--url http://127.0.0.1:8787/mcp] [--out dir]
 * يطبع قائمة الأدوات ويحفظ tools_list.json + tools_list.txt في --out.
 */
fun main(args: Array<String>) {
    var url = McpClient.DEFAULT_URL
    var outDir = File("build/probe-output")
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--url" -> {
                url = args.getOrNull(++i) ?: url
            }
            "--out" -> {
                outDir = File(args.getOrNull(++i) ?: outDir.path)
            }
        }
        i++
    }
    println("MCP probe — connecting to $url ...")
    val client = McpClient(url)
    try {
        client.connect()
        println("Connected: server=${client.lastServerName} version=${client.lastServerVersion}")
        val tools = client.listTools()
        println("Tools: ${tools.size}")
        tools.forEach { tool ->
            val desc = if (!tool.description.isNullOrBlank()) " — ${tool.description}" else ""
            println(" - ${tool.name}$desc")
        }
        val (jsonFile, textFile) = ToolsListStore.save(
            outDir, tools, client.lastServerName, client.lastServerVersion
        )
        println("Saved: ${jsonFile.absolutePath}")
        println("Saved: ${textFile.absolutePath}")
    } catch (e: McpException) {
        System.err.println("MCP FAILED: ${e.message}")
        kotlin.system.exitProcess(1)
    }
}
