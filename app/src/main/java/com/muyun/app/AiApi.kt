package com.muyun.app

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * AI 接口层（重构 2.4 抽出）：
 *  - listModels：拉取 OpenAI 兼容 /models
 *  - 以后可放 chat/completions 等
 */
object AiApi {

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 返回模型 id 列表；失败抛异常 */
    fun listModels(baseUrl: String, key: String, proxy: java.net.Proxy? = null): List<String> {
        val candidates = listOf(
            baseUrl.trimEnd('/'),
            baseUrl.trimEnd('/').removeSuffix("/v1"),
        ).distinct()
        var lastErr: Throwable? = null
        for (b in candidates) {
            try { return listModelsOnce(b, key, proxy) } catch (t: Throwable) { lastErr = t }
        }
        throw (lastErr ?: IllegalStateException("获取模型失败"))
    }

    private fun listModelsOnce(base0: String, key: String, proxy: java.net.Proxy?): List<String> {
        val base = base0.trimEnd('/')
        val req = Request.Builder().url("$base/models")
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .build()
        val c = client().newBuilder().apply { if (proxy != null) proxy(proxy) }.build()
        c.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code}: ${txt.take(160)}")
            val arr = org.json.JSONObject(txt).optJSONArray("data") ?: return emptyList()
            return (0 until arr.length()).mapNotNull {
                arr.getJSONObject(it).optString("id").takeIf { id -> id.isNotBlank() }
            }
        }
    }
}
