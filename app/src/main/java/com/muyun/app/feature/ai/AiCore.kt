package com.muyun.app

/**
 * AI 助手核心解析层（架构重构 · feature/ai）
 *
 * 职责（只做纯函数，不碰网络/状态）：
 *  1. 清洗模型输出：移除 <think> 思考块与原生 <tool_call> XML
 *  2. 解析动作：同时支持两种格式
 *       - 自定义格式：ACTION / TARGET / COMMAND / CONTENT
 *       - 原生 XML：<function=name><parameter=key>value</parameter></function>
 *
 * 这样 AiVm 只负责"调用模型 + 执行动作"，解析逻辑可单独测试。
 */

/** 一条待执行动作 */
data class AiAction(
    val kind: String,
    val target: String,
    val command: String,
    val content: String = "",
)

object AiCore {

    private val THINK_BLOCK = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)
    private val TOOLCALL_BLOCK = Regex("<tool_call>[\\s\\S]*?</tool_call>", RegexOption.IGNORE_CASE)
    private val RESIDUAL_TAG = Regex("</?(think|tool_call)>", RegexOption.IGNORE_CASE)

    /** 去掉模型输出的思考块与原始 XML，避免脏 UI */
    fun sanitizeReply(t: String): String = t
        .replace(THINK_BLOCK, "")
        .replace(TOOLCALL_BLOCK, "")
        .replace(RESIDUAL_TAG, "")
        .trim()

    private val XML_FUNC = Regex("<function=([A-Za-z_]+)>(.*?)</function>", RegexOption.DOT_MATCHES_ALL)
    private val XML_PARAM = Regex("<parameter=([A-Za-z_]+)>(.*?)</parameter>", RegexOption.DOT_MATCHES_ALL)
    private val ACTION_LINE = Regex("ACTION:\\s*([A-Za-z_]+)", RegexOption.IGNORE_CASE)
    private val TARGET_LINE = Regex("TARGET:\\s*([^\\n]+)", RegexOption.IGNORE_CASE)
    private val COMMAND_LINE = Regex("COMMAND:\\s*([^\\n]+)", RegexOption.IGNORE_CASE)
    private val CONTENT_LINE = Regex("CONTENT:\\s*([^\\n]+)", RegexOption.IGNORE_CASE)

    /** 解析模型回复中的所有动作 */
    fun parseActions(reply: String): List<AiAction> {
        val xml = parseXmlActions(reply)
        if (xml.isNotEmpty()) return xml

        val out = mutableListOf<AiAction>()
        // 按 ACTION 切块，避免跨动作串行
        val blocks = Regex("(?=ACTION:)", RegexOption.IGNORE_CASE).split(reply)
        for (block in blocks) {
            val kind = ACTION_LINE.find(block)?.groupValues?.get(1)?.trim() ?: continue
            val target = TARGET_LINE.find(block)?.groupValues?.get(1)?.trim() ?: ""
            val cmd = COMMAND_LINE.find(block)?.groupValues?.get(1)?.trim() ?: ""
            val content = CONTENT_LINE.find(block)?.groupValues?.get(1)?.trim() ?: ""
            out += AiAction(kind, target, cmd, content)
        }
        return out
    }

    /** 解析模型原生 XML 工具调用 */
    private fun parseXmlActions(reply: String): List<AiAction> =
        XML_FUNC.findAll(reply).map { m ->
            val name = m.groupValues[1]
            val body = m.groupValues[2]
            fun p(k: String) = XML_PARAM.findAll(body)
                .firstOrNull { it.groupValues[1] == k }?.groupValues?.get(2)?.trim() ?: ""
            AiAction(
                kind = name,
                target = p("target").ifBlank { "local" },
                command = p("command").ifBlank { p("path") },
                content = p("content"),
            )
        }.toList()
}
