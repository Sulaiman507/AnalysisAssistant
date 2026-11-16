package com.musaed.analysis.mcp

import com.google.gson.JsonParser

/** بطاقة دليل واحدة من تقرير JSON — كما تُعرض قابلة للفتح في الواجهة. */
data class EvidenceCard(
    val id: Int,
    val tool: String,
    val identifier: String,
    val excerpt: String,
)

/** ملخص تقرير JSON جاهز للعرض: الحكم أولاً ثم الأدلة. */
data class ReportSummary(
    /** قيمة الحكم الخام: local|local_cached|server|none|uncertain. */
    val verdict: String,
    /** تسمية الحكم العربية. */
    val verdictLabel: String,
    val confidence: Double?,
    /** نص البوابة «class.method(signature)» أو null إن لم تثبت بوابة. */
    val gateText: String?,
    val evidence: List<EvidenceCard>,
    /** true إن كانت خطة التعديل فارغة (كما في «خادم صرف»). */
    val patchPlanEmpty: Boolean,
    /** مكتبة الدفع أو "none" أو null إن غابت. */
    val paymentSdk: String?,
)

/**
 * يقرأ تقرير JSON (صيغة البرومبت [٦]) إلى ملخص عرض — دفاعي بالكامل:
 * أي حقل غائب أو مشوّه يُترك فارغاً ولا يُرمي أبداً.
 */
object ReportView {

    fun verdictLabel(verdict: String): String = when (verdict) {
        "local" -> "محلي صرف"
        "local_cached" -> "محلي مع كاش خادم"
        "server" -> "خادم صرف"
        "none" -> "غير موجود"
        "uncertain" -> "غير مؤكد"
        else -> verdict
    }

    /** يرجع null إن لم يكن النص JSON صالحاً بكائن علوي. */
    fun parse(reportJson: String?): ReportSummary? {
        if (reportJson.isNullOrBlank()) return null
        val obj = try {
            JsonParser.parseString(reportJson).asJsonObject
        } catch (_: Exception) {
            return null
        }
        val verdict = stringOf(obj, "verdict") ?: return null
        val confidence = try {
            if (obj.has("confidence") && obj.get("confidence").isJsonPrimitive) {
                obj.get("confidence").asDouble
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        val gateText = if (obj.has("gate") && obj.get("gate").isJsonObject) {
            val gate = obj.getAsJsonObject("gate")
            val cls = stringOf(gate, "class")
            val method = stringOf(gate, "method")
            val sig = stringOf(gate, "signature")
            if (cls == null && method == null) {
                null
            } else {
                buildString {
                    if (cls != null) append(cls)
                    if (method != null) {
                        if (isNotEmpty()) append(".")
                        append(method)
                    }
                    if (sig != null) append(sig)
                }
            }
        } else {
            null
        }
        val evidence = mutableListOf<EvidenceCard>()
        if (obj.has("evidence") && obj.get("evidence").isJsonArray) {
            obj.getAsJsonArray("evidence").forEachIndexed { index, element ->
                if (!element.isJsonObject) return@forEachIndexed
                val card = element.asJsonObject
                val id = try {
                    if (card.has("id") && card.get("id").isJsonPrimitive) card.get("id").asInt
                    else index + 1
                } catch (_: Exception) {
                    index + 1
                }
                evidence += EvidenceCard(
                    id = id,
                    tool = stringOf(card, "tool") ?: "",
                    identifier = stringOf(card, "identifier") ?: "",
                    excerpt = stringOf(card, "excerpt") ?: "",
                )
            }
        }
        val patchPlanEmpty = if (obj.has("patch_plan") && obj.get("patch_plan").isJsonArray) {
            obj.getAsJsonArray("patch_plan").size() == 0
        } else {
            true
        }
        return ReportSummary(
            verdict = verdict,
            verdictLabel = verdictLabel(verdict),
            confidence = confidence,
            gateText = gateText,
            evidence = evidence,
            patchPlanEmpty = patchPlanEmpty,
            paymentSdk = stringOf(obj, "payment_sdk"),
        )
    }

    private fun stringOf(obj: com.google.gson.JsonObject, key: String): String? {
        if (!obj.has(key)) return null
        val value = obj.get(key)
        if (!value.isJsonPrimitive) return null
        val text = value.asString
        return text.ifBlank { null }
    }
}
