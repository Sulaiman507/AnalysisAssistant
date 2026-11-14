package com.musaed.analysis.mcp

/** أداة MCP واحدة كما رجعت حياً من tools/list — لا أسماء مكتوبة مسبقاً هنا. */
data class McpTool(
    val name: String,
    val description: String?,
    val inputSchema: com.google.gson.JsonObject?,
)
