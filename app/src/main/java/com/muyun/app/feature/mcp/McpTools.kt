package com.muyun.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 工具清单（架构重构 · feature/mcp）
 *
 * 这里只描述工具的名称 / 描述 / 参数 Schema；执行逻辑在 MainVm.mcpHandle()。
 * 新增工具：此处加 Schema + MainVm 加同名分支，即可被 MCP 客户端发现。
 */
object McpTools {

    fun tools(machineProp: JSONObject): JSONArray {
        fun t(name: String, desc: String, props: JSONObject, required: JSONArray) = JSONObject().apply {
            put("name", name)
            put("description", desc)
            put("inputSchema", JSONObject().apply {
                put("type", "object"); put("properties", props); put("required", required)
            })
        }

        return JSONArray().apply {
            put(t("help", "返回牧云 MCP 使用说明与常用示例", JSONObject(), JSONArray()))
            put(t("list_machines", "列出所有机器及其状态", JSONObject(), JSONArray()))
            put(t("power", "开关机", JSONObject().apply {
                put("machine", machineProp)
                put("action", JSONObject().put("type", "string").put("enum", JSONArray().put("start").put("stop")))
            }, JSONArray().put("machine").put("action")))
            put(t("ssh_exec", "在指定机器执行命令并返回输出", JSONObject().apply {
                put("machine", machineProp); put("command", JSONObject().put("type", "string"))
            }, JSONArray().put("machine").put("command")))

            put(t("term_list", "列出所有终端会话（sessionId/机器/状态/是否聚焦）", JSONObject(), JSONArray()))
            put(t("term_read", "读取指定终端最近内容", JSONObject().apply {
                put("session", JSONObject().put("type", "string").put("description", "sessionId / 机器名 / 序号（term_list 中的 [n]）；只有一个会话时可省略"))
                put("chars", JSONObject().put("type", "integer"))
                put("since", JSONObject().put("type", "integer")
                    .put("description", "上次返回的 offset；传入则只返回新增内容"))
            }, JSONArray()))
            put(t("term_write", "向指定终端写入命令并回车", JSONObject().apply {
                put("session", JSONObject().put("type", "string").put("description", "sessionId / 机器名 / 序号（term_list 中的 [n]）；只有一个会话时可省略"))
                put("machine", JSONObject().put("type", "string").put("description", "机器名（session 的别名）"))
                put("command", JSONObject().put("type", "string"))
            }, JSONArray().put("command")))
            put(t("chat_read", "读取最近 AI 聊天记录", JSONObject().apply {
                put("count", JSONObject().put("type", "integer"))
            }, JSONArray()))
            put(t("local_run", "在手机本机执行 shell 命令", JSONObject().apply {
                put("command", JSONObject().put("type", "string"))
            }, JSONArray().put("command")))

            put(t("sftp_list", "列出远端目录（SFTP）", JSONObject().apply {
                put("machine", machineProp); put("path", JSONObject().put("type", "string"))
            }, JSONArray().put("machine").put("path")))
            put(t("sftp_read", "读取远端文本文件（SFTP）", JSONObject().apply {
                put("machine", machineProp); put("path", JSONObject().put("type", "string"))
            }, JSONArray().put("machine").put("path")))
            put(t("sftp_write", "写入远端文本文件（SFTP）", JSONObject().apply {
                put("machine", machineProp); put("path", JSONObject().put("type", "string"))
                put("content", JSONObject().put("type", "string"))
            }, JSONArray().put("machine").put("path").put("content")))
            put(t("sftp_mkdir", "在远端创建目录（SFTP）", JSONObject().apply {
                put("machine", machineProp); put("path", JSONObject().put("type", "string"))
            }, JSONArray().put("machine").put("path")))
            put(t("sftp_download", "把远端文件下载到手机 /Download/muyun", JSONObject().apply {
                put("machine", machineProp); put("path", JSONObject().put("type", "string"))
            }, JSONArray().put("machine").put("path")))

            put(t("file_upload", "把手机本地文件直传到机器（二进制安全，字节不进上下文）", JSONObject().apply {
                put("sourcePath", JSONObject().put("type", "string").put("description", "手机本地绝对路径"))
                put("machine", machineProp)
                put("remotePath", JSONObject().put("type", "string").put("description", "远端目录或完整文件名"))
            }, JSONArray().put("sourcePath").put("machine").put("remotePath")))
            put(t("sftp_rename", "重命名远端文件/目录（SFTP）", JSONObject().apply {
                put("machine", machineProp)
                put("path", JSONObject().put("type", "string"))
                put("newPath", JSONObject().put("type", "string"))
            }, JSONArray().put("machine").put("path").put("newPath")))
            put(t("sftp_chmod", "修改远端文件权限（SFTP，八进制）", JSONObject().apply {
                put("machine", machineProp)
                put("path", JSONObject().put("type", "string"))
                put("mode", JSONObject().put("type", "string").put("description", "如 644 / 755"))
            }, JSONArray().put("machine").put("path").put("mode")))
            put(t("sftp_copy", "机器之间复制文件或目录（SFTP 中转）", JSONObject().apply {
                put("fromMachine", machineProp)
                put("fromPath", JSONObject().put("type", "string"))
                put("toMachine", machineProp)
                put("toPath", JSONObject().put("type", "string"))
            }, JSONArray().put("fromMachine").put("fromPath").put("toMachine").put("toPath")))
            put(t("proxy_start", "为指定机器开启 SOCKS5 代理", JSONObject().apply {
                put("machine", machineProp)
            }, JSONArray().put("machine")))
            put(t("proxy_stop", "关闭指定机器的代理", JSONObject().apply {
                put("machine", machineProp)
            }, JSONArray().put("machine")))
            put(t("proxy_list", "列出当前所有代理及其端口/出口 IP", JSONObject(), JSONArray()))

            put(t("jump_list", "列出所有跳板机", JSONObject(), JSONArray()))
            put(t("jump_update", "新增或修改跳板机", JSONObject().apply {
                put("entry", JSONObject().put("type", "string"))
                put("field", JSONObject().put("type", "string").put("description", "label/host/port/user/password"))
                put("value", JSONObject().put("type", "string"))
            }, JSONArray().put("entry").put("field").put("value")))
            put(t("jump_delete", "删除跳板机", JSONObject().apply {
                put("entry", JSONObject().put("type", "string"))
            }, JSONArray().put("entry")))
            put(t("jump_test", "测试跳板机 SSH 连通性", JSONObject().apply {
                put("entry", JSONObject().put("type", "string"))
            }, JSONArray().put("entry")))

            put(t("list_entries", "列出所有账号完整配置（含凭据）", JSONObject(), JSONArray()))
            put(t("entry_update", "修改某个账号的字段", JSONObject().apply {
                put("entry", JSONObject().put("type", "string"))
                put("field", JSONObject().put("type", "string"))
                put("value", JSONObject().put("type", "string"))
            }, JSONArray().put("entry").put("field").put("value")))
            put(t("vault_list", "列出密钥库中 AI 可见的条目", JSONObject(), JSONArray()))
            put(t("vault_get", "读取密钥库某个键的值", JSONObject().apply {
                put("name", JSONObject().put("type", "string"))
                put("key", JSONObject().put("type", "string"))
            }, JSONArray().put("name")))

            put(t("get_logs", "读取最近诊断日志", JSONObject().apply {
                put("lines", JSONObject().put("type", "integer"))
            }, JSONArray()))
            put(t("crash_log", "读取最近崩溃堆栈", JSONObject(), JSONArray()))
        }
    }
}
