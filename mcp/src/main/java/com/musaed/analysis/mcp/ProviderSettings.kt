package com.musaed.analysis.mcp

import com.google.gson.Gson
import com.google.gson.JsonParser

/**
 * إعدادات مزوّد النموذج (المرحلة 3): الاسم والمفتاح وBase URL والمسار
 * والنموذج — كما في شاشة «Add Provider» التي يستخدمها سليمان: المزوّد
 * متوافق مع OpenAI، والمستخدم يختاره بنفسه.
 *
 * المفتاح يبقى في جهاز المستخدم فقط (SharedPreferences) ولا يُحفظ في
 * الكود ولا في المستودع أبداً.
 */
data class ProviderSettings(
    val name: String,
    val apiKey: String,
    val baseUrl: String,
    val apiPath: String,
    val model: String,
) {
    companion object {
        /** DeepSeek — الافتراضي (كما في القرارات المثبّتة). */
        fun deepSeek(apiKey: String = "") = ProviderSettings(
            name = "DeepSeek",
            apiKey = apiKey,
            baseUrl = "https://api.deepseek.com/v1",
            apiPath = "/chat/completions",
            model = "deepseek-reasoner",
        )

        /**
         * GonkaRouter — كما وثّقته مواده الرسمية (gonkarouter.io/docs):
         * Base URL ‏https://api.gonkarouter.io/v1‏، والمسار ‎/chat/completions،
         * والمصادقة Bearer أو x-api-key. النموذج الافتراضي نموذج DeepSeek
         * المتاح لديه؛ القائمة الفعلية تُجلب بزر «جلب النماذج».
         */
        fun gonkaRouter(apiKey: String = "") = ProviderSettings(
            name = "GonkaRouter",
            apiKey = apiKey,
            baseUrl = "https://api.gonkarouter.io/v1",
            apiPath = "/chat/completions",
            model = "deepseek-ai/DeepSeek-V4-Flash-0731",
        )

        fun fromJson(json: String?): ProviderSettings? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JsonParser.parseString(json).asJsonObject
                ProviderSettings(
                    name = obj.get("name")?.asString ?: "",
                    apiKey = obj.get("apiKey")?.asString ?: "",
                    baseUrl = obj.get("baseUrl")?.asString ?: "",
                    apiPath = obj.get("apiPath")?.asString ?: "",
                    model = obj.get("model")?.asString ?: "",
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    /** جاهز للاستخدام: مفتاح ونموذج وعنوان صحيح. */
    fun isConfigured(): Boolean {
        return apiKey.isNotBlank() && model.isNotBlank() &&
            (baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) &&
            apiPath.isNotBlank()
    }

    fun toModelConfig(timeoutMs: Long = 120_000, maxTokens: Int = 8000, temperature: Double = 0.0) =
        ModelClient.ModelConfig(
            baseUrl = baseUrl,
            apiPath = apiPath,
            apiKey = apiKey,
            model = model,
            timeoutMs = timeoutMs,
            maxTokens = maxTokens,
            temperature = temperature,
        )

    fun toJson(): String = Gson().toJson(this)
}
