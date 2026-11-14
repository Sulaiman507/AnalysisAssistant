package com.musaed.analysis.mcp

/**
 * خطأ MCP. retryable=true يعني أن الفشل عابر (نقل/مهلة) ويستحق إعادة
 * محاولة واحدة؛ retryable=false يعني أن الخادم رفض الطلب نفسه (مثلاً
 * isError=true) فلا فائدة من الإعادة.
 */
class McpException(
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean = true,
) : Exception(message, cause)
