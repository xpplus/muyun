package com.muyun.app.feature.proxy

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * 代理探测（阶段 4.5 · feature/proxy）
 * 纯探测逻辑，不持有状态；结果由调用方写回。
 */
object ProxyProbe {

    data class Result(val exitIp: String, val latencyMs: Long, val speedMbps: Double)

    suspend fun measure(port: Int): Result = withContext(Dispatchers.IO) {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build()

        val t0 = System.currentTimeMillis()
        val ip = runCatching {
            client.newCall(Request.Builder().url("https://api.ipify.org").build()).execute().use {
                it.body?.string()?.trim().orEmpty()
            }
        }.getOrDefault("")
        val latency = System.currentTimeMillis() - t0

        val speed = runCatching {
            val t1 = System.currentTimeMillis()
            val bytes = client.newCall(
                Request.Builder().url("https://speed.cloudflare.com/__down?bytes=5000000").build()
            ).execute().use { it.body?.bytes()?.size ?: 0 }
            val sec = (System.currentTimeMillis() - t1) / 1000.0
            if (sec > 0) bytes / 1048576.0 / sec else 0.0
        }.getOrDefault(0.0)

        Result(ip, latency, speed)
    }
}
