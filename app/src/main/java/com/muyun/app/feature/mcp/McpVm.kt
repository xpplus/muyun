package com.muyun.app.feature.mcp

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.muyun.app.McpServer
import org.json.JSONObject

/**
 * MCP 服务生命周期（阶段 E · feature/mcp）
 *
 * 从 MainVm 抽出：端口、Token、启停、前台服务联动。
 * 工具执行由调用方通过 handler 注入（即统一 ToolRuntime）。
 */
interface McpHost {
    fun app(): Application
    fun diag(msg: String)
    fun notify(msg: String)
    /** 有活动需要保活时同步前台服务 */
    fun syncService()
    fun prefs(): android.content.SharedPreferences
}

class McpVm(private val host: McpHost) {

    var running by mutableStateOf(false); private set
    var port by mutableStateOf(4000); private set
    var token by mutableStateOf(""); private set

    private var server: McpServer? = null

    init {
        runCatching {
            // 首次运行生成随机 Token（安全默认值）；用户手动清空即表示不校验
            val saved = host.prefs().getString("mcpToken", null)
            token = if (saved == null) {
                val gen = java.security.SecureRandom().let { rnd ->
                    ByteArray(16).also { rnd.nextBytes(it) }
                }.joinToString("") { "%02x".format(it) }
                host.prefs().edit().putString("mcpToken", gen).apply()
                gen
            } else saved
        }
    }

    /** 用户自定义 Token（留空 = 不校验） */
    fun updateToken(t: String) {
        token = t.trim()
        host.prefs().edit().putString("mcpToken", token).apply()
        if (running) host.notify("Token 已更新，请重启 MCP 服务生效")
    }

    fun regenerateToken(): String {
        val t = (1..28).map { "abcdefghijkmnpqrstuvwxyz23456789".random() }.joinToString("")
        updateToken(t)
        return t
    }

    /** 启动/停止；handler 由调用方提供（统一 ToolRuntime） */
    fun toggle(wantPort: Int, tokenCheck: String = token, handler: (String, JSONObject) -> String) {
        if (running) {
            server?.stop()
            server = null
            running = false
            host.syncService()
            host.notify("MCP 服务已停止")
            return
        }
        port = wantPort.coerceIn(1024, 65535)
        if (tokenCheck.isBlank()) host.notify("MCP 已启动（未设置 Token，本机客户端可直接连接）")
        val srv = McpServer(port, tokenCheck) { name, args -> handler(name, args) }
        runCatching { srv.start() }
            .onSuccess {
                server = srv
                running = true
                host.syncService()
                host.diag("MCP 已启动：127.0.0.1:$port")
            }
            .onFailure { host.notify("MCP 启动失败：${it.message}") }
    }
}
