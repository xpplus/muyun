package com.muyun.app.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局诊断日志（架构重构 · 阶段 4 第一步）
 *
 * 从 MainVm 下沉到 core，让各 Feature VM 都能统一写日志，
 * 而不用互相持有引用。
 *
 * 特性：
 *  - 环形缓冲 500 条
 *  - 1 秒内完全相同消息去重（修复"保存/删除记两遍"）
 *  - 分类：CLOUD / SSH / SFTP / PROXY / AI / MCP / UI
 *  - Compose 可直接观察 lines
 */
object Diag {

    const val MAX_LINES = 500

    var lines by mutableStateOf<List<String>>(emptyList())
        private set

    private var lastMsg = ""
    private var lastAt = 0L
    private var file: File? = null

    /** 初始化文件落盘（App 启动时调用一次），崩溃后仍可追溯 */
    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "diag.log")
        file = f
        // 重启后把上次的日志读回内存，避免"崩溃后日志清 0"
        runCatching {
            if (f.exists()) {
                lines = f.readLines().takeLast(MAX_LINES)
            }
        }
    }
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 写一条日志；category 仅用于阅读，level: I/W/E */
    fun log(category: String, level: Char, message: String) {
        val now = System.currentTimeMillis()
        if (message == lastMsg && now - lastAt < 1000) return
        lastMsg = message
        lastAt = now
        val line = "[${fmt.format(Date())}] [$category/$level] $message"
        lines = (lines + line).takeLast(MAX_LINES)
        // 落盘：超过 256KB 轮转为 diag.1.log
        runCatching {
            val f = file ?: return@runCatching
            if (f.length() > 256 * 1024) {
                File(f.parentFile, "diag.1.log").let { old ->
                    if (old.exists()) old.delete()
                    f.renameTo(old)
                }
            }
            f.appendText(line + "\n")
        }
    }

    fun i(category: String, message: String) = log(category, 'I', message)
    fun w(category: String, message: String) = log(category, 'W', message)
    fun e(category: String, message: String) = log(category, 'E', message)

    fun clear() {
        lines = emptyList()
    }

    fun tail(n: Int): List<String> = lines.takeLast(n)

    fun text(n: Int = MAX_LINES): String = tail(n).joinToString("\n")
}
