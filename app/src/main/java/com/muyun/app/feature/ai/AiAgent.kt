package com.muyun.app.feature.ai

import com.muyun.app.AiCore
import com.muyun.app.feature.tools.ToolRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI Agent 循环（阶段 D3 · feature/ai）
 *
 * 职责：
 *  1. 调模型
 *  2. 用 AiCore 解析回复中的动作
 *  3. 逐个交给 ToolRuntime 执行
 *  4. 把执行结果回灌给模型，继续下一轮
 *
 * 不持有 UI 状态：消息、状态、结束都通过回调交给调用方。
 */
class AiAgent(
    private val runtime: ToolRuntime,
    private val callModel: suspend () -> String,
    private val onMessage: (role: String, content: String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val maxRounds: Int = 4,
) {

    /**
     * 运行一轮完整对话（含工具调用回灌）。
     * @return 是否正常结束（false 表示达到最大轮数仍未收敛）
     */
    suspend fun run(): Boolean {
        var rounds = 0
        var lastReply = ""
        while (rounds < maxRounds) {
            rounds++
            onStatus("思考中…")
            val reply = runCatching { callModel() }.getOrElse { t ->
                val msg = t.message.orEmpty()
                val friendly = when {
                    msg.contains("429") -> "模型限流了（429），请稍等或换个模型"
                    msg.contains("401") || msg.contains("403") -> "API Key 无效或无权限（401/403）"
                    msg.contains("timeout", true) -> "AI 请求超时，请检查网络或跳板"
                    msg.contains("stream was reset", true) -> "模型服务端中断了连接，请重试"
                    else -> "请求失败：$msg"
                }
                onMessage("assistant", friendly)
                return false
            }
            lastReply = reply

            // 只展示清洗后的文本；XML/think 不进入 UI
            val shown = AiCore.sanitizeReply(reply)
            if (shown.isNotBlank()) onMessage("assistant", shown)

            val actions = AiCore.parseActions(reply)
            if (actions.isEmpty()) return true

            val results = StringBuilder()
            for (a in actions) {
                onStatus("执行命令中…（${a.kind}）")
                // 关键：工具执行涉及网络/文件，必须在 IO 线程（D3 曾漏掉导致 NetworkOnMainThreadException）
                val r = withContext(Dispatchers.IO) {
                    runCatching { runtime.execute(a.kind, a.target, a.command, a.content) }
                        .getOrElse { "动作执行失败：${it.message ?: it.javaClass.simpleName}" }
                }
                results.append("动作 ").append(a.kind).append(' ').append(a.target)
                    .append(" → ").append(r).append('\n')
            }
            // 执行结果回灌，让模型决定下一步
            onMessage("system", results.toString().trim())
        }
        if (lastReply.isBlank()) onMessage("assistant", "(无回复)")
        return false
    }
}
