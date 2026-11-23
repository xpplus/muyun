package com.muyun.app.feature.power

import kotlinx.coroutines.delay

/**
 * 开关机等待（阶段 4.8 · feature/power）
 *
 * 云机开机后状态变化很慢（instabox 的 state 会长时间停在 stopped，
 * 真正可信的是 desiredState + runtime.ready），所以需要轮询等待。
 *
 * 这里只负责"等"，不负责状态来源；调用方传入 currentState()。
 */
object PowerWait {

    /**
     * 轮询直到达到目标状态或超时。
     * @param starting true=等 running/available；false=等 stopped/shutdown
     * @param pollMs   轮询间隔
     * @param timeoutSec 超时秒数
     * @param currentState 读取当前状态（每次轮询调用）
     * @return 是否在超时前达到目标
     */
    suspend fun waitUntil(
        starting: Boolean,
        timeoutSec: Int,
        pollMs: Long = 1500,
        currentState: suspend () -> String,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(pollMs)
            val cur = runCatching { currentState() }.getOrDefault("").lowercase()
            if (cur.isBlank()) continue
            val settled = if (starting) {
                cur == "running" || cur == "available"
            } else {
                cur == "stopped" || cur == "shutdown" || cur == "suspended"
            }
            if (settled) return true
        }
        return false
    }
}
