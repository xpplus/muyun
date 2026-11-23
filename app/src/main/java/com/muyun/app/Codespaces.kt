package com.muyun.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Cspace(
    val name: String,
    val display: String,
    val state: String,
    val repo: String,
    val cpus: Int,
    val memGb: Int,
    val lastUsed: String?,
)

data class CsUsage(
    val coreHours: Double,
    val storageGbMonth: Double,
    val gross: Double,
    val net: Double,
    val monthLabel: String,
)

/** GitHub Codespaces 控制面（REST），只用于列表/开关机；SSH 走 gh --stdio */
class CodespacesClient(private val token: String) {
    companion object {
        private val JSON = "application/json".toMediaType()
        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }

    private fun call(path: String, post: Boolean): String {
        val b = Request.Builder().url("https://api.github.com$path")
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github+json")
        if (post) b.post("{}".toRequestBody(JSON))
        http.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code}: ${body.take(160)}")
            return body
        }
    }

    suspend fun list(): List<Cspace> = withContext(Dispatchers.IO) {
        val arr = JSONObject(call("/user/codespaces", false)).getJSONArray("codespaces")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val m = o.optJSONObject("machine")
            val repo = o.optJSONObject("repository")?.optString("full_name").orEmpty()
            Cspace(
                name = o.getString("name"),
                display = o.optString("display_name").ifBlank { o.getString("name") },
                state = o.optString("state", "Unknown"),
                repo = repo,
                cpus = m?.optInt("cpus") ?: 0,
                memGb = ((m?.optLong("memory_in_bytes") ?: 0L) / 1024 / 1024 / 1024).toInt(),
                lastUsed = o.optString("last_used_at").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }

    /** 取当前登录名（用于 billing 接口） */
    suspend fun login(): String = withContext(Dispatchers.IO) {
        JSONObject(call("/user", false)).optString("login")
    }

    /**
     * 官方真实用量：GET /users/{login}/settings/billing/usage
     * 里面 codespaces 的 sku 形如 "Codespaces compute 4-core"，quantity 单位是小时。
     */
    suspend fun usageReport(login: String, year: Int, month: Int): CsUsage = withContext(Dispatchers.IO) {
        val path = "/users/$login/settings/billing/usage?year=$year&month=$month"
        val raw = call(path, false)
        val items = JSONObject(raw).optJSONArray("usageItems")
        var core = 0.0; var storage = 0.0; var gross = 0.0; var net = 0.0
        if (items != null) {
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                if (!o.optString("product").equals("codespaces", true)) continue
                val sku = o.optString("sku")
                val qty = o.optDouble("quantity", 0.0)
                gross += o.optDouble("grossAmount", 0.0)
                net += o.optDouble("netAmount", 0.0)
                val cores = Regex("(\\d+)-core").find(sku)?.groupValues?.get(1)?.toIntOrNull()
                if (cores != null) core += qty * cores
                else if (sku.contains("storage", true)) storage += qty
            }
        }
        CsUsage(core, storage, gross, net, "%d-%02d".format(year, month))
    }

    suspend fun start(name: String) = withContext(Dispatchers.IO) {
        try { call("/user/codespaces/$name/start", true) } catch (e: IllegalStateException) {
            // 409 = 已经在运行/正在启动，视为成功
            if (e.message?.contains("409") == true) Unit else throw e
        }
    }
    suspend fun stop(name: String) = withContext(Dispatchers.IO) { call("/user/codespaces/$name/stop", true); Unit }
}
