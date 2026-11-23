package com.muyun.app.feature.tools

/**
 * 统一工具执行入口（阶段 D2 · feature/tools）
 *
 * 目标：AI 助手与 MCP 服务共用同一套工具执行逻辑，
 * 避免"AI 能用但 MCP 不能"的能力漂移。
 *
 * 实现由 MainVm 提供（内部委托 executeActionInner），
 * 后续 D3 会把实现搬到 `feature/tools/ToolRuntimeImpl.kt`。
 */
interface ToolRuntime {
    /**
     * @param kind    动作名：power_on / ssh_run / sftp_list / vault_get / term_write ...
     * @param target  目标：机器名、账号名、条目名，或 local
     * @param command 命令 / 路径 / "字段=值"
     * @param content 仅 sftp_write 等需要的内容
     * @return 可直接回给模型 / MCP 客户端的文本结果
     */
    fun execute(kind: String, target: String, command: String, content: String = ""): String
}
