package com.muyun.app.data

import android.content.Context
import com.muyun.app.Config
import com.muyun.app.Store

/**
 * 配置持久化唯一入口（架构重构 · 阶段 2）
 *
 * 目的：业务层不再直接调用 Store.save / getSharedPreferences，
 * 便于以后换存储（DataStore / 数据库 / 云同步）时只改这一层。
 */
class StoreRepository(private val ctx: Context) {

    /** 读取配置（含迁移逻辑在 Store 内部） */
    fun load(): Config = Store.load(ctx)

    /** 保存配置 */
    fun save(cfg: Config) = Store.save(ctx, cfg)

    /** 通用偏好（端口记忆、视觉模型、MCP Token 等） */
    fun prefs(name: String = "clouddeck") =
        ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
}
