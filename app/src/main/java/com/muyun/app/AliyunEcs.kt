package com.muyun.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 阿里云 ECS 最小实现：只做「查状态 / 开机 / 关机」。
 * 使用 RPC 签名（HMAC-SHA1），签名规则见阿里云 ECS API 文档。
 */
class AliyunEcs(
    private val accessKeyId: String,
    private val accessKeySecret: String,
    private val region: String,
    private val instanceId: String,
    /** "ecs" 或 "swas"（轻量应用服务器） */
    private val kind: String = "ecs",
    /** 可选：返回 SOCKS 代理（跳板），为空则直连 */
    private val proxyProvider: (() -> java.net.Proxy?)? = null,
) {
    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).build()

        private fun pct(s: String): String =
            URLEncoder.encode(s, "UTF-8")
                .replace("+", "%20").replace("*", "%2A").replace("%7E", "~")

        private fun hmacSha1(key: String, data: String): ByteArray {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA1"))
            return mac.doFinal(data.toByteArray())
        }
    }

    private fun call(action: String, extra: Map<String, String> = emptyMap()): JSONObject {
        val params = TreeMap<String, String>()
        params["AccessKeyId"] = accessKeyId
        params["Action"] = action
        params["Format"] = "JSON"
        params["SignatureMethod"] = "HMAC-SHA1"
        params["SignatureVersion"] = "1.0"
        params["SignatureNonce"] = UUID.randomUUID().toString()
        params["Timestamp"] = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        params["Version"] = if (kind == "swas") "2020-06-01" else "2014-05-26"
        params.putAll(extra)

        val canonical = params.entries.joinToString("&") { "${pct(it.key)}=${pct(it.value)}" }
        val stringToSign = "GET&${pct("/")}&${pct(canonical)}"
        val signature = android.util.Base64.encodeToString(
            hmacSha1("$accessKeySecret&", stringToSign), android.util.Base64.NO_WRAP
        )
        params["Signature"] = signature

        val query = params.entries.joinToString("&") { "${pct(it.key)}=${pct(it.value)}" }
        val host = if (kind == "swas") "swas.$region.aliyuncs.com" else "ecs.$region.aliyuncs.com"
        val url = "https://$host/?$query"
        val client = run {
            val px = proxyProvider?.invoke()
            if (px != null) http.newBuilder().proxy(px).build() else http
        }
        client.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code}: ${txt.take(180)}")
            return JSONObject(txt)
        }
    }

    /** Running / Stopped / Starting / Stopping ... */
    suspend fun status(): String = withContext(Dispatchers.IO) {
        val action = if (kind == "swas") "ListInstances" else "DescribeInstances"
        val o = call(action, mapOf("RegionId" to region))
        // ECS: Instances.Instance[]   SWAS: Instances[]
        val arr = o.optJSONObject("Instances")?.optJSONArray("Instance") ?: o.optJSONArray("Instances")
        if (arr == null) return@withContext "NotFound"
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val id = item.optString("InstanceId")
            if (id == instanceId || instanceId.isBlank()) {
                return@withContext item.optString("Status", "Unknown")
            }
        }
        "NotFound"
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        if (kind == "swas") call("StartInstances", mapOf("RegionId" to region, "InstanceIds" to "[\"$instanceId\"]"))
        else call("StartInstance", mapOf("RegionId" to region, "InstanceId" to instanceId))
        Unit
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        if (kind == "swas") call("StopInstances", mapOf("RegionId" to region, "InstanceIds" to "[\"$instanceId\"]"))
        else call("StopInstance", mapOf("RegionId" to region, "InstanceId" to instanceId))
        Unit
    }
}

