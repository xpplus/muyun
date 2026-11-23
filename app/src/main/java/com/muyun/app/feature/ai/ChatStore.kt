package com.muyun.app.feature.ai

import com.muyun.app.AiMsg
import com.muyun.app.ChatSession
import org.json.JSONArray
import org.json.JSONObject

/**
 * 对话记录序列化（阶段 D1 · feature/ai）
 *
 * 从 MainVm 抽出的纯函数：只负责 ChatSession 列表 ⇄ JSON。
 * 存储位置（SharedPreferences）由调用方决定。
 */
object ChatStore {

    private const val MAX_SESSIONS = 30

    fun encode(sessions: List<ChatSession>): String {
        val arr = JSONArray()
        sessions.forEach { cs ->
            arr.put(JSONObject().apply {
                put("id", cs.id)
                put("title", cs.title)
                put("at", cs.at)
                put("msgs", JSONArray().apply {
                    cs.msgs.forEach { m ->
                        put(JSONObject().apply {
                            put("role", m.role)
                            put("content", m.content)
                        })
                    }
                })
            })
        }
        return arr.toString()
    }

    fun decode(text: String?): List<ChatSession> = runCatching {
        val arr = JSONArray(text ?: "")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ChatSession(
                id = o.optString("id"),
                title = o.optString("title"),
                at = o.optLong("at"),
                msgs = runCatching {
                    val ms = o.optJSONArray("msgs") ?: return@runCatching emptyList<AiMsg>()
                    (0 until ms.length()).map { j ->
                        val m = ms.getJSONObject(j)
                        AiMsg(m.optString("role"), m.optString("content"))
                    }
                }.getOrDefault(emptyList()),
            )
        }
    }.getOrDefault(emptyList())

    /** 插入/更新一条会话，按时间倒序，最多保留 30 条 */
    fun upsert(sessions: List<ChatSession>, session: ChatSession): List<ChatSession> =
        (sessions.filterNot { it.id == session.id } + session)
            .sortedByDescending { it.at }
            .take(MAX_SESSIONS)
}
