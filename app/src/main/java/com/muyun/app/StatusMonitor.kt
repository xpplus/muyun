package com.muyun.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 状态监视器（重构 2.2 抽出）
 *
 * 策略：stale-while-revalidate
 *  - 有缓存 → 立刻返回缓存值（UI 永不闪成 unknown）
 *  - 缓存超过 20 秒 → 后台静默刷新一次
 *  - 无缓存 → 最多等 10 秒做一次快速查询；失败则用上次持久化的已知状态
 *  - 手动刷新 → 最多 60 秒反复重试
 */
class StatusMonitor(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val fetch: suspend (Entry) -> String,
    private val diag: (String) -> Unit,
) {
    private val cache = mutableMapOf<String, Pair<String, Long>>()
    private val refreshing = mutableSetOf<String>()
    /** 失败冷却：entryId → 冷却截止时间（避免反复超时拖慢 UI/MCP） */
    private val cooldownUntil = mutableMapOf<String, Long>()

    private fun prefs() = ctx.getSharedPreferences("clouddeck", Context.MODE_PRIVATE)

    private fun saveLast(entryId: String, st: String) {
        prefs().edit().putString("lastState_$entryId", st).apply()
    }

    private fun loadLast(entryId: String): String? =
        prefs().getString("lastState_$entryId", null)

    fun isRefreshing(id: String) = synchronized(refreshing) { id in refreshing }

    /** 首页轮询用：永不阻塞超过 10 秒 */
    suspend fun get(e: Entry): String {
        val now = System.currentTimeMillis()
        val c = cache[e.id]
        if (c != null) {
            // 走跳板的账号轮询更省（60 秒），避免频繁占用跳板通道影响代理带宽
            val ttl = if (e.jumpId.isNotBlank()) 60_000L else 20_000L
            if (now - c.second > ttl && !isRefreshing(e.id)) {
                scope.launch(Dispatchers.IO) { refreshOnce(e, save = true) }
            }
            return c.first
        }
        // 无缓存：先返回上次持久化状态（立刻），后台再刷新，
        // 避免首屏被慢面板卡住几十秒
        val last = loadLast(e.id)
        if (!isRefreshing(e.id)) {
            scope.launch(Dispatchers.IO) { refreshOnce(e, save = true) }
        }
        return last ?: "unknown"
    }

    private suspend fun refreshOnce(e: Entry, save: Boolean): String {
        val now = System.currentTimeMillis()
        val until = cooldownUntil[e.id] ?: 0L
        if (now < until) {
            return cache[e.id]?.first ?: loadLast(e.id) ?: "unknown"
        }
        val st = try { fetch(e) } catch (t: Throwable) { diag("状态查询异常: ${t.message}"); "unknown" }
        if (st == "unknown") {
            cooldownUntil[e.id] = now + 60_000
            diag("状态查询失败，冷却 60s：${e.label}")
        } else {
            cooldownUntil.remove(e.id)
        }
        if (st != "unknown") {
            cache[e.id] = st to System.currentTimeMillis()
            if (save) saveLast(e.id, st)
        }
        return st
    }

    /** 手动刷新：最长 maxMs，拿到明确状态即停 */
    suspend fun manual(e: Entry, maxMs: Long = 60_000): String {
        synchronized(refreshing) { refreshing += e.id }
        try {
            val deadline = System.currentTimeMillis() + maxMs
            var st = "unknown"
            var attempt = 0
            while (System.currentTimeMillis() < deadline) {
                attempt++
                val t0 = System.currentTimeMillis()
                st = refreshOnce(e, save = true)
                if (attempt > 1 || st == "unknown") {
                    diag("手动刷新 attempt=$attempt entry=${e.label} state=$st 用时=${System.currentTimeMillis() - t0}ms")
                }
                if (st != "unknown") return st
                if (System.currentTimeMillis() + 2000 >= deadline) break
                Thread.sleep(2000)
            }
            return cache[e.id]?.first ?: loadLast(e.id) ?: "unknown"
        } finally {
            synchronized(refreshing) { refreshing -= e.id }
        }
    }

    fun invalidate(entryId: String) { cache.remove(entryId) }

    fun clearAll() = cache.clear()

    fun snapshot(): Map<String, String> = cache.mapValues { it.value.first }
}
