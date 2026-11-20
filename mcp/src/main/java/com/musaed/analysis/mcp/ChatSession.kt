package com.musaed.analysis.mcp

import com.google.gson.Gson
import com.google.gson.JsonParser

/** دور واحد في المحادثة: "user" (سليمان) أو "model" (النموذج). */
data class ChatTurn(val role: String, val content: String)

/**
 * محادثة واحدة مع النموذج: أدوار المستخدم والنموذج بالترتيب.
 *
 * تُحفظ على الجهاز فقط (ChatStore) ولا تغادره إلا إلى مزوّد النموذج
 * نفسه مع كل رسالة. التقليم يحفظ آخر التبادلات فقط حتى لا يمتلئ سياق
 * النموذج — الأقدم يُنسى من الرسائل المرسلة، لا من الملف المحفوظ.
 */
class ChatSession {
    private val turns = mutableListOf<ChatTurn>()

    fun turns(): List<ChatTurn> = turns.toList()

    fun addUser(text: String) {
        if (text.isNotBlank()) turns += ChatTurn("user", text)
    }

    fun addModel(text: String) {
        if (text.isNotBlank()) turns += ChatTurn("model", text)
    }

    fun clear() = turns.clear()

    fun isEmpty(): Boolean = turns.isEmpty()

    /**
     * الأدوار كما تُرسل للنموذج: بالترتيب، مقلَّمة بعدد الأدوار والحروف
     * معاً — إن تجاوزت السقف سقط الأقدم.
     */
    fun historyForModel(): List<ChatMessage> {
        val kept = mutableListOf<ChatTurn>()
        var chars = 0
        for (turn in turns.asReversed()) {
            if (kept.size >= MAX_TURNS_SENT) break
            if (chars + turn.content.length > MAX_CHARS_SENT && kept.isNotEmpty()) break
            kept += turn
            chars += turn.content.length
        }
        return kept.asReversed().map { turn ->
            ChatMessage(if (turn.role == "model") "assistant" else "user", turn.content)
        }
    }

    fun toJson(): String = Gson().toJson(turns)

    companion object {
        /** آخر عدد أدوار يُرسل للنموذج (10 تبادلات). */
        const val MAX_TURNS_SENT = 20

        /** سقف حروف الأدوار المرسلة للنموذج. */
        const val MAX_CHARS_SENT = 12_000

        fun fromJson(json: String?): ChatSession {
            val session = ChatSession()
            if (json.isNullOrBlank()) return session
            try {
                val arr = JsonParser.parseString(json).asJsonArray
                for (el in arr) {
                    if (!el.isJsonObject) continue
                    val obj = el.asJsonObject
                    val role = if (obj.has("role")) obj.get("role").asString else continue
                    val content = if (obj.has("content")) obj.get("content").asString else continue
                    if (role == "user" || role == "model") session.turns += ChatTurn(role, content)
                }
            } catch (_: Exception) {
                // ملف تالف — ابدأ محادثة جديدة بدل الانهيار
                session.turns.clear()
            }
            return session
        }
    }
}
