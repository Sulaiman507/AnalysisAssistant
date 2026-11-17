package com.musaed.analysis.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/** رسالة محادثة واحدة للنموذج. */
data class ChatMessage(val role: String, val content: String)

/** خطأ عميل النموذج. retryable=true يعني فشلاً عابراً (شبكة/مهلة/ضغط) يستحق إعادة محاولة. */
class ModelException(
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean = true,
) : Exception(message, cause)

/**
 * عميل نموذج عام متوافق مع OpenAI (المرحلة 3): يتكلم مع أي مزوّد يقدّم
 * واجهة chat/completions — DeepSeek افتراضياً، وأي مزوّد يختاره المستخدم
 * (مثل GonkaRouter) عبر الإعدادات: الاسم والمفتاح وBase URL والمسار والنموذج.
 *
 * لا يوجد مزوّد مكتوب في الكود إلا الافتراضي؛ كل شيء آخر يأتي من الإعدادات.
 * النقل قابل للحقن في الاختبارات (request) — الافتراضي HttpURLConnection.
 */
class ModelClient(
    private val config: ModelConfig,
    private val request: (method: String, url: String, body: String?, apiKey: String) -> String = ::defaultRequest,
) {
    /** إعدادات المزوّد. baseUrl هو المضيف مع ‎/v1 مثل https://api.deepseek.com/v1 ، وapiPath مثل ‎/chat/completions. */
    data class ModelConfig(
        val baseUrl: String,
        val apiPath: String,
        val apiKey: String,
        val model: String,
        val timeoutMs: Long = 120_000,
        val maxTokens: Int = 8000,
        val temperature: Double = 0.0,
    )

    private fun chatUrl(): String = config.baseUrl.trimEnd('/') + normalizePath(config.apiPath)
    private fun modelsUrl(): String = config.baseUrl.trimEnd('/') + "/models"

    private fun normalizePath(path: String): String {
        val p = path.trim()
        return if (p.startsWith("/")) p else "/$p"
    }

    /** محادثة واحدة: يرسل الرسائل ويعيد نص رد النموذج (content). يرمي ModelException عند الفشل. */
    fun chat(messages: List<ChatMessage>): String {
        require(config.apiKey.isNotBlank()) { "لا يوجد مفتاح API — أدخل مفتاح المزوّد في الإعدادات أولاً." }
        require(config.model.isNotBlank()) { "لا يوجد نموذج محدد — اختر نموذجاً في الإعدادات أولاً." }
        val body = JsonObject().apply {
            addProperty("model", config.model)
            add("messages", JsonArray().apply {
                messages.forEach { m ->
                    add(JsonObject().apply {
                        addProperty("role", m.role)
                        addProperty("content", m.content)
                    })
                }
            })
            addProperty("temperature", config.temperature)
            addProperty("max_tokens", config.maxTokens)
        }
        val raw = try {
            request("POST", chatUrl(), body.toString(), config.apiKey)
        } catch (e: ModelException) {
            throw e
        } catch (e: Exception) {
            throw ModelException("تعذّر الوصول إلى المزوّد ${config.baseUrl}: ${e.message ?: e.toString()}", e)
        }
        return contentOf(raw)
    }

    /** يقطع الاتصال الجاري مع المزوّد فوراً (زر «إيقاف إجباري»). */
    fun cancelActive() {
        ActiveHttp.cancel()
    }

    /** يسرد نماذج المزوّد (GET /models) — لاختيار النموذج من القائمة الفعلية. */
    fun listModels(): List<String> {
        require(config.apiKey.isNotBlank()) { "لا يوجد مفتاح API — أدخل مفتاح المزوّد في الإعدادات أولاً." }
        val raw = try {
            request("GET", modelsUrl(), null, config.apiKey)
        } catch (e: ModelException) {
            throw e
        } catch (e: Exception) {
            throw ModelException("تعذّر جلب نماذج المزوّد ${config.baseUrl}: ${e.message ?: e.toString()}", e)
        }
        return modelsOf(raw)
    }

    /** يستخرج نص الرد من JSON لمزوّد chat/completions. */
    internal fun contentOf(raw: String): String {
        val root = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (e: Exception) {
            throw ModelException("رد المزوّد ليس JSON صالحاً: ${raw.take(300)}", e, retryable = false)
        }
        if (root.has("error")) {
            val err = root.getAsJsonObject("error")
            val msg = if (err.has("message")) err.get("message").asString else err.toString()
            throw ModelException("رفض المزوّد الطلب: ${msg.take(300)}", retryable = false)
        }
        val choices = if (root.has("choices") && root.get("choices").isJsonArray) {
            root.getAsJsonArray("choices")
        } else {
            throw ModelException("رد المزوّد بلا choices: ${raw.take(300)}", retryable = false)
        }
        if (choices.size() == 0) throw ModelException("رد المزوّد بلا اختيارات.", retryable = false)
        val message = choices[0].asJsonObject.getAsJsonObject("message")
            ?: throw ModelException("رد المزوّد بلا رسالة.", retryable = false)
        val content = if (message.has("content") && !message.get("content").isJsonNull) {
            message.get("content").asString
        } else {
            ""
        }
        if (content.isBlank()) throw ModelException("رد المزوّد بلا محتوى.", retryable = false)
        return content
    }

    /** يستخرج معرّفات النماذج من JSON لقائمة ‎/models. */
    internal fun modelsOf(raw: String): List<String> {
        val root = try {
            JsonParser.parseString(raw).asJsonObject
        } catch (e: Exception) {
            throw ModelException("قائمة النماذج ليست JSON صالحاً: ${raw.take(300)}", e, retryable = false)
        }
        if (root.has("error")) {
            val err = root.getAsJsonObject("error")
            val msg = if (err.has("message")) err.get("message").asString else err.toString()
            throw ModelException("رفض المزوّد طلب النماذج: ${msg.take(300)}", retryable = false)
        }
        if (!root.has("data") || !root.get("data").isJsonArray) return emptyList()
        return root.getAsJsonArray("data").mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val obj = el.asJsonObject
            if (obj.has("id") && obj.get("id").isJsonPrimitive) obj.get("id").asString else null
        }
    }

    companion object {
        /**
         * النقل الافتراضي عبر HttpURLConnection. يرسل مفتاح API بطريقتين
         * (Authorization: Bearer و x-api-key) لأن بعض المزوّدات (مثل
         * GonkaRouter) تقبل أياً منهما، وإرسال الاثنين متوافق مع كليهما.
         */
        fun defaultRequest(method: String, url: String, body: String?, apiKey: String): String {
            val conn = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 10_000
                readTimeout = 120_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("x-api-key", apiKey)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            ActiveHttp.connection = conn
            try {
                if (body != null) {
                    conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use(BufferedReader::readText) ?: ""
                if (status !in 200..299) {
                    val retryable = status == 429 || status >= 500
                    throw ModelException("المزوّد أعاد HTTP $status: ${text.take(300)}", retryable = retryable)
                }
                return text
            } catch (e: ModelException) {
                throw e
            } catch (e: java.net.ConnectException) {
                throw ModelException("تعذّر الاتصال بالمزوّد $url — تحقق من الإنترنت وBase URL.", e)
            } catch (e: java.net.SocketTimeoutException) {
                throw ModelException("انتهت مهلة الاتصال بالمزوّد $url.", e)
            } finally {
                if (ActiveHttp.connection === conn) ActiveHttp.connection = null
                conn.disconnect()
            }
        }
    }
}
