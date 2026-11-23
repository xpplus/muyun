package com.muyun.app.cloud

import com.muyun.app.AliyunEcs
import com.muyun.app.CloudHttp
import com.muyun.app.Entry
import com.muyun.app.InstaboxClient

/**
 * 各厂商 Provider 实现（阶段 3）
 * 说明：这里只做"协议适配"，不持有 UI 状态；代理/日志通过构造参数注入。
 */

/** instabox：状态由 InstaboxClient 提供，开关机按 machineId 操作 */
class InstaboxProvider(
    private val entry: Entry,
    private val machineId: String,
) : CloudProvider {
    override val supportsPower = true
    private val client = InstaboxClient(
        entry.secret,
        entry.baseUrl.ifBlank { InstaboxClient.DEFAULT_BASE }
    )

    override suspend fun status(): String =
        client.machines().firstOrNull { it.name == machineId }?.state ?: "unknown"

    override suspend fun start() = client.power(machineId, "start")
    override suspend fun stop() = client.power(machineId, "stop")
}

/** 阿里云 ECS / 轻量：内部已处理产品类型与签名 */
class AliyunProvider(
    private val entry: Entry,
    private val proxy: () -> java.net.Proxy? = { null },
) : CloudProvider {
    override val supportsPower = true
    private val ecs = AliyunEcs(
        accessKeyId = entry.accessKeyId,
        accessKeySecret = entry.secret,
        region = entry.region,
        instanceId = entry.instanceId,
        kind = entry.aliyunKind,
        proxyProvider = proxy,
    )
    override suspend fun status(): String = ecs.status()
    override suspend fun start() = ecs.start()
    override suspend fun stop() = ecs.stop()
}

/** 自定义 HTTP 开关机：状态/开机/关机都走 URL */
class HttpProvider(
    private val entry: Entry,
    private val proxy: (() -> java.net.Proxy?)?,
    private val diag: (String) -> Unit,
) : CloudProvider {
    override val supportsPower = entry.startUrl.isNotBlank() || entry.stopUrl.isNotBlank()

    override suspend fun status(): String =
        if (entry.statusUrl.isBlank()) "unknown"
        else CloudHttp.fetchStatus(entry, proxy, diag)

    override suspend fun start() = CloudHttp.power(entry, true, proxy, diag)
    override suspend fun stop() = CloudHttp.power(entry, false, proxy, diag)
}
