package com.muyun.app.core

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获（阶段 4.7 · core）
 *
 * 从 MainActivity 抽出：
 *  - 写入 filesDir/crash.log，带时间、线程、异常头部、设备信息
 *  - 保留完整 cause 链（之前只截尾部导致首行缺失）
 *  - 不吞异常，仍然交回系统默认处理器
 */
object CrashGuard {

    fun install(ctx: Context) {
        val appCtx = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val head = buildString {
                    append("\n===== ")
                    append(SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                    append(" =====\n")
                    append("thread=").append(t.name).append('\n')
                    append("exception=").append(e.toString()).append('\n')
                    append("device=").append(android.os.Build.MANUFACTURER).append(' ')
                        .append(android.os.Build.MODEL).append(" Android ")
                        .append(android.os.Build.VERSION.RELEASE).append('\n')
                    append("----- 最近诊断日志 -----\n")
                    append(Diag.tail(80).joinToString("\n")).append("\n")
                    append("----- stack -----\n")
                }
                File(appCtx.filesDir, "crash.log").appendText(head + sw.toString())
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** 读取最近崩溃：保证头部可见 */
    fun readRecent(ctx: Context, maxChars: Int = 6000): String {
        val f = File(ctx.filesDir, "crash.log")
        if (!f.exists()) return "（无崩溃记录）"
        val text = f.readText()
        if (text.length <= maxChars) return text
        // 保留头部 1500 + 尾部剩余
        return text.take(1500) + "\n……（中间省略）……\n" + text.takeLast(maxChars - 1500)
    }
}
