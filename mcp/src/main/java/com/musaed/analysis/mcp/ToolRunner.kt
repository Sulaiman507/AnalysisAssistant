package com.musaed.analysis.mcp

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * مغلّف استدعاء أدوات MCP (المرحلة ٢): مهلة زمنية، إعادة محاولة واحدة
 * عند فشل النقل/المهلة فقط، ورسائل خطأ عربية مفهومة. كل محاولة تُسجَّل
 * في سجل الجلسة بنتيجتها الخام حرفياً — مرجع المدقّق وبطاقات الأدلة.
 *
 * إعادة المحاولة لأدوات القراءة فقط (كل أدوات المرحلة ٢ قراءة)؛ أدوات
 * الكتابة لاحقاً تمرّر allowRetry=false.
 */
class ToolRunner(
    private val callTool: (String, JsonObject) -> JsonObject,
    private val log: SessionLog,
    private val timeoutMs: Long = 60_000,
) {
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r).apply { isDaemon = true }
    }

    /**
     * يستدعي الأداة مرة، ويعيد المحاولة مرة واحدة عند فشل النقل أو انتهاء
     * المهلة فقط. لا إعادة عند isError=true من الخادم. يرمي McpException
     * عند الفشل النهائي برسالة عربية.
     */
    fun call(name: String, args: JsonObject, step: Int = 0, allowRetry: Boolean = true): JsonObject {
        val argsJson = Gson().toJson(args)
        var attempt = 0
        while (true) {
            attempt++
            val started = System.currentTimeMillis()
            var alreadyLogged = false
            try {
                val future: Future<JsonObject> = executor.submit<JsonObject> {
                    callTool(name, args)
                }
                val result: JsonObject = try {
                    future.get(timeoutMs, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    future.cancel(true)
                    throw McpException(
                        "انتهت مهلة استدعاء الأداة $name بعد ${timeoutMs}ms — أعد المحاولة أو تحقق من خدمة MT Manager",
                        e,
                    )
                }
                val duration = System.currentTimeMillis() - started
                if (result.has("isError") && result.get("isError").isJsonPrimitive &&
                    result.get("isError").asBoolean
                ) {
                    val raw = Gson().toJson(result)
                    log.record(
                        step = step, attempt = attempt, tool = name,
                        argumentsJson = argsJson, status = "error",
                        durationMs = duration, rawResult = raw,
                        error = "الأداة $name أعادت خطأ من الخادم",
                    )
                    alreadyLogged = true
                    throw McpException(
                        "الأداة $name أعادت خطأ: ${raw.take(300)}",
                        retryable = false,
                        rawResult = raw,
                    )
                }
                log.record(
                    step = step, attempt = attempt, tool = name,
                    argumentsJson = argsJson, status = "ok",
                    durationMs = duration, rawResult = Gson().toJson(result),
                    error = null,
                )
                return result
            } catch (e: McpException) {
                val duration = System.currentTimeMillis() - started
                if (!alreadyLogged) {
                    log.record(
                        step = step, attempt = attempt, tool = name,
                        argumentsJson = argsJson, status = "error",
                        durationMs = duration, rawResult = "",
                        error = e.message,
                    )
                }
                if (!e.retryable || !allowRetry || attempt >= 2) throw e
                // إعادة محاولة واحدة عند فشل النقل/المهلة فقط
            } catch (e: ExecutionException) {
                val duration = System.currentTimeMillis() - started
                val cause = e.cause
                if (cause is McpException && !cause.retryable) {
                    log.record(
                        step = step, attempt = attempt, tool = name,
                        argumentsJson = argsJson, status = "error",
                        durationMs = duration, rawResult = "",
                        error = cause.message,
                    )
                    throw cause
                }
                val msg = cause?.message ?: e.message ?: e.toString()
                log.record(
                    step = step, attempt = attempt, tool = name,
                    argumentsJson = argsJson, status = "error",
                    durationMs = duration, rawResult = "",
                    error = msg,
                )
                if (!allowRetry || attempt >= 2) {
                    throw McpException("فشل استدعاء الأداة $name: $msg", cause)
                }
                // إعادة محاولة واحدة
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                val duration = System.currentTimeMillis() - started
                log.record(
                    step = step, attempt = attempt, tool = name,
                    argumentsJson = argsJson, status = "error",
                    durationMs = duration, rawResult = "",
                    error = "قُوطع استدعاء الأداة $name",
                )
                throw McpException("قُوطع استدعاء الأداة $name", e, retryable = false)
            }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
