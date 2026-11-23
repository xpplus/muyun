package com.muyun.app.core

/**
 * 统一错误模型（架构重构 · 核心层）
 *
 * 设计目标：
 *  - 业务层只抛/返回 AppError，不再到处拼字符串
 *  - 每个错误同时提供「给用户看的中文提示」和「给日志看的技术细节」
 *  - UI 只负责展示，不再判断异常类型
 */
sealed class AppError {

    /** 网络问题：超时、DNS、连接被拒 */
    data class Network(val cause: Throwable, val retryable: Boolean = true) : AppError()

    /** 认证失败：密码错、密钥不接受、Token 无效 */
    data class Auth(val detail: String) : AppError()

    /** 功能不支持：例如主机没开 SFTP、模型不支持视觉 */
    data class Unsupported(val feature: String, val hint: String) : AppError()

    /** 配置缺失或错误：Host 为空、端口非法、未填 Key */
    data class Config(val field: String, val hint: String) : AppError()

    /** 权限不足：存储权限、通知权限 */
    data class Permission(val which: String) : AppError()

    /** 端口占用 */
    data class PortInUse(val port: Int) : AppError()

    /** 其它未知错误 */
    data class Unknown(val cause: Throwable) : AppError()

    /** 用户可读的中文提示（简洁、可操作） */
    fun userMessage(): String = when (this) {
        is Network -> if (retryable) "网络不通或超时，请稍后重试" else "网络连接失败"
        is Auth -> "认证失败：$detail"
        is Unsupported -> hint
        is Config -> "配置有误（$field）：$hint"
        is Permission -> "缺少权限：$which"
        is PortInUse -> "端口 $port 已被占用，请换一个"
        is Unknown -> "操作失败：${cause.message ?: cause.javaClass.simpleName}"
    }

    /** 技术细节（进天机录 / 导出日志） */
    fun techDetail(): String = when (this) {
        is Network -> "Network(${cause.javaClass.simpleName}): ${cause.message}"
        is Auth -> "Auth: $detail"
        is Unsupported -> "Unsupported($feature): $hint"
        is Config -> "Config($field): $hint"
        is Permission -> "Permission($which)"
        is PortInUse -> "PortInUse($port)"
        is Unknown -> "Unknown(${cause.javaClass.name}): ${cause.message}"
    }
}

/**
 * 异常 → AppError 的映射。
 * 集中处理各协议/库的原始报错，避免 UI 里出现英文堆栈。
 */
fun Throwable.toAppError(context: String = ""): AppError {
    val msg = message.orEmpty()
    val lower = msg.lowercase()
    return when {
        // SSH / SFTP
        lower.contains("subsystem") || lower.contains("unsupported") ->
            AppError.Unsupported("SFTP", "该主机未开启 SFTP 服务（群晖：控制面板 → 文件服务 → 启用 SFTP）")
        lower.contains("exhausted available authentication") || lower.contains("auth fail") ->
            AppError.Auth("SSH 用户名/密码或密钥不正确")
        lower.contains("permission denied") ->
            AppError.Auth("没有登录权限（可能禁止了该用户或 root）")
        // 网络
        lower.contains("timeout") || lower.contains("timed out") ->
            AppError.Network(this)
        lower.contains("unknownhost") || lower.contains("failed to connect") || lower.contains("connection refused") ->
            AppError.Network(this, retryable = false)
        lower.contains("address already in use") ->
            AppError.PortInUse(0)
        // 权限
        lower.contains("permission") && lower.contains("storage") ->
            AppError.Permission("文件访问权限")
        else -> AppError.Unknown(this)
    }
}
