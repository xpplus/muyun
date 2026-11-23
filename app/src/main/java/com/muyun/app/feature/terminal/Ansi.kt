package com.muyun.app.feature.terminal

/**
 * 终端输出清洗（阶段 C · 第一步）
 *
 * 从 MainVm.appendTerm 抽出的纯函数：
 *  - 统一换行符
 *  - 去掉 ANSI 颜色/光标控制序列
 *  - 去掉 OSC 标题序列
 */
object Ansi {

    private val CSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    private val OSC = Regex("\u001B\\][^\u0007]*\u0007")

    fun clean(chunk: String): String = chunk
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace(CSI, "")
        .replace(OSC, "")
}
