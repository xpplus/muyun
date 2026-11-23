package com.muyun.app.cloud

import com.muyun.app.Entry

/**
 * 云厂商统一抽象（架构重构 · 阶段 3）
 *
 * 目的：MainVm 不再用 when(entry.type) 散落判断；
 * 新增一个厂商 = 新增一个 Provider 实现 + 注册一行。
 */
interface CloudProvider {

    /** 是否支持开关机 */
    val supportsPower: Boolean

    /** 查询状态：running / stopped / starting / stopping / unknown */
    suspend fun status(): String

    /** 开机 */
    suspend fun start()

    /** 关机 */
    suspend fun stop()
}

/** 能力描述，供 UI 决定显示哪些按钮 */
data class Caps(
    val canPower: Boolean,
    val canSsh: Boolean,
    val canFile: Boolean,
)
