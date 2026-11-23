package com.muyun.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 通用 HTTP 云 API 层（重构 2.1 抽出）：
 *  - fetchStatus：查询状态，支持 Pterodactyl(current_state) 等常见字段
 *  - power：开机/关机，支持 GET/POST、独立 Body、自定义 Headers、跳板代理
 */
object CloudHttp {

    private fun buildClient(entry: Entry, proxy: java.net.Proxy?): OkHttpClient {
        val t = entry.httpTimeoutSec.coerceIn(5, 120).toLong()
        val b = OkHttpClient.Builder()
            .connectTimeout(t, TimeUnit.SECONDS)
            .readTimeout(t, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        if (proxy != null) b.proxy(proxy)
        return b.build()
    }

    private fun applyHeaders(b: Request.Builder, entry: Entry) {
        entry.headers.split("\n").map { it.trim() }.filter { it.contains(":") }.forEach { line ->
            b.header(line.substringBefore(":").trim(), line.substringAfter(":").trim())
        }
    }

    /** 查询状态；失败返回 "unknown"（由调用方决定是否保留旧值） */
    suspend fun fetchStatus(entry: Entry, proxyProvider: (() -> java.net.Proxy?)?, diag: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            // 关键：代理必须在 IO 线程内创建（之前在主线程建 SSH 隧道导致 NetworkOnMainThreadException）
            val proxy = runCatching { proxyProvider?.invoke() }
                .onFailure { diag("代理创建失败 entry=${entry.label}: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrNull()
            if (proxyProvider != null && proxy == null) {
                diag("跳板不可用，已取消请求（按配置不允许直连）entry=${entry.label}")
                return@withContext "unknown"
            }
            val via = if (proxy == null) "直连" else "跳板(${proxy.address()})"
            // 只记录跳板请求（直连轮询不刷屏）
            if (proxy != null) diag("状态请求 entry=${entry.label} via=$via")
            runCatching {
                val req = Request.Builder().url(entry.statusUrl.trim()).get()
                applyHeaders(req, entry)
                val client = buildClient(entry, proxy)

                var resp: okhttp3.Response? = null
                var lastErr: Throwable? = null
                val maxAttempts = if (proxy != null) 1 else 3
                for (attempt in 1..maxAttempts) {
                    try {
                        resp = client.newCall(req.build()).execute()
                        if (resp.isSuccessful) break
                    } catch (t: Throwable) {
                        lastErr = t
                        diag("状态查询第 $attempt 次失败: ${t.message}")
                        if (attempt < 3) Thread.sleep(1200)
                    }
                }
                val r = resp ?: throw (lastErr ?: IllegalStateException("状态查询失败"))
                r.use { resp2 ->
                    val txt = resp2.body?.string().orEmpty()
                    if (!resp2.isSuccessful) return@withContext "unknown"
                    val json = runCatching { org.json.JSONObject(txt) }.getOrNull()
                    val attrs = json?.optJSONObject("attributes")
                    val raw = attrs?.optString("current_state")?.takeIf { it.isNotBlank() }
                        ?: json?.optString("current_state")?.takeIf { it.isNotBlank() }
                        ?: attrs?.optString("state")?.takeIf { it.isNotBlank() }
                        ?: json?.optString("state")?.takeIf { it.isNotBlank() }
                        ?: json?.optString("status")?.takeIf { it.isNotBlank() }
                        ?: attrs?.optString("status")?.takeIf { it.isNotBlank() }
                    val cost = System.currentTimeMillis() - t0
                    if (cost > 3000) diag("状态请求偏慢 entry=${entry.label} 耗时=${cost}ms state=$raw")
                    when (raw?.lowercase()) {
                        null, "" -> "running"
                        "running" -> "running"
                        "starting" -> "starting"
                        "stopping" -> "stopping"
                        "offline", "stopped", "suspended" -> "stopped"
                        else -> raw
                    }
                }
            }.onFailure {
                diag("状态请求失败 entry=${entry.label} 耗时=${System.currentTimeMillis() - t0}ms 错误=${it.javaClass.simpleName}: ${it.message}")
            }.getOrDefault("unknown")
        }

    /** 开机/关机 */
    suspend fun power(entry: Entry, start: Boolean, proxyProvider: (() -> java.net.Proxy?)?, diag: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val proxy = runCatching { proxyProvider?.invoke() }.getOrNull()
            if (proxyProvider != null && proxy == null) {
                error("跳板不可用，已取消操作")
            }
            val url = (if (start) entry.startUrl else entry.stopUrl).trim()
            if (url.isBlank()) error("未配置${if (start) "开机" else "关机"} URL")
            val b = Request.Builder().url(url)
            applyHeaders(b, entry)
            if (entry.httpMethod.equals("GET", true)) {
                b.get()
            } else {
                val raw = (if (start) entry.bodyStart else entry.bodyStop).trim().ifBlank { entry.body.trim() }
                b.post(raw.ifBlank { "{}" }.toRequestBody("application/json".toMediaType()))
            }
            buildClient(entry, proxy).newCall(b.build()).execute().use { r ->
                val t = r.body?.string().orEmpty()
                diag("HTTP ${r.code} ${if (start) "start" else "stop"} $url")
                if (!r.isSuccessful) error("HTTP ${r.code}: ${t.take(160)}")
            }
        }
}
