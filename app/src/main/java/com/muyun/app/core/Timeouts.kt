package com.muyun.app.core

/**
 * 统一超时与重试策略（阶段 4）
 * 避免每个文件各写一个魔法数字。
 */
object Timeouts {
    /** 云 API 状态查询（秒） */
    const val STATUS_SEC = 25L
    /** 电源操作（秒） */
    const val POWER_SEC = 20L
    /** HTTP 连接测试（秒） */
    const val TEST_SEC = 20L
    /** AI 模型列表（秒） */
    const val MODELS_SEC = 30L
    /** AI 对话（秒） */
    const val CHAT_SEC = 120L
    /** SSH 连接（毫秒） */
    const val SSH_CONNECT_MS = 15_000
    /** 状态查询重试次数 */
    const val STATUS_RETRIES = 3
    /** 重试间隔（毫秒） */
    const val RETRY_DELAY_MS = 1_200L
}
