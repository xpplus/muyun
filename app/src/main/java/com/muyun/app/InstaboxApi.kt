package com.muyun.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Machine(
    val id: String,
    val name: String,
    val state: String,
    val desiredState: String,
    val ready: Boolean,
    val vcpus: Int,
    val memMiB: Int,
    val runStartedAt: String?,
    val autoSuspendSeconds: Int?,
    val maxRunMinutes: Int?,
    val free: Boolean,
    /** 是否支持开关机（自定义服务器可能没配开关机接口） */
    val canPower: Boolean = true,
    /** 是否支持 SSH */
    val canSsh: Boolean = true,
    /** 是否可做文件操作（SFTP，仅 SFTP 的机器也算） */
    val canFile: Boolean = false,
)

data class Usage(
    val freeSandboxHours: Double,
    val vcpuHours: Double,
    val memGiBHours: Double,
    val costUSD: Double,
    val maxMachines: Int,
    val maxSnapshots: Int,
    val maxStoredGiB: Int,
    val machines: Int,
    val snapshots: Int,
    val periodStart: String?,
)

/**
 * instabox REST 客户端。
 * 只有 baseUrl 和 apiKey 是外部变量（可手填覆盖），其余全部走接口读取。
 */
class InstaboxClient(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE,
) {
    companion object {
        const val DEFAULT_BASE = "https://api.instabox.sh/v1"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun get(path: String, auth: Boolean = true): String {
        val b = Request.Builder().url(baseUrl.trimEnd('/') + path).header("Accept", "application/json")
        if (auth) b.header("Authorization", "Bearer $apiKey")
        http.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code}: ${body.take(180)}")
            return body
        }
    }

    private fun post(path: String, idem: String): String {
        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", idem)
            .post("{}".toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code}: ${body.take(180)}")
            return body
        }
    }

    suspend fun machines(): List<Machine> = withContext(Dispatchers.IO) {
        val arr = JSONObject(get("/machines")).getJSONArray("machines")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val spec = o.optJSONObject("spec")
            val runtime = o.optJSONObject("runtime")
            Machine(
                id = o.getString("id"),
                name = o.optString("name", o.getString("id")),
                state = run {
                    // instabox 的 state 在开机过程中会长时间保持 stopped，
                    // 真正可信的是 desiredState + runtime.ready，这里合成一个"有效状态"
                    val raw = o.optString("state", "unknown")
                    val desired = o.optString("desiredState", "")
                    val rdy = runtime?.optBoolean("ready") ?: false
                    when {
                        desired == "running" && (rdy || raw == "running") -> "running"
                        desired == "running" -> "starting"
                        desired == "stopped" && raw != "stopped" -> "stopping"
                        else -> raw
                    }
                },
                desiredState = o.optString("desiredState", ""),
                ready = runtime?.optBoolean("ready") ?: false,
                vcpus = spec?.optInt("vcpus") ?: 0,
                memMiB = spec?.optInt("memMiB") ?: 0,
                runStartedAt = o.optString("runStartedAt").takeIf { it.isNotBlank() && it != "null" },
                autoSuspendSeconds = o.optInt("autoSuspendAfterSeconds", 0).takeIf { it > 0 },
                maxRunMinutes = o.optInt("maxRunMinutes", 0).takeIf { it > 0 },
                free = o.optBoolean("free", false),
            )
        }
    }

    suspend fun usage(): Usage = withContext(Dispatchers.IO) {
        val root = JSONObject(get("/usage"))
        val cost = root.optJSONObject("cost") ?: JSONObject()
        val limits = root.optJSONObject("limits") ?: JSONObject()
        Usage(
            freeSandboxHours = cost.optDouble("freeSandboxHours", 0.0),
            vcpuHours = cost.optDouble("vcpuHours", 0.0),
            memGiBHours = cost.optDouble("memGiBHours", 0.0),
            costUSD = cost.optDouble("costUSD", 0.0),
            maxMachines = limits.optInt("maxMachines", 0),
            maxSnapshots = limits.optInt("maxSnapshots", 0),
            maxStoredGiB = limits.optInt("maxStoredGiB", 0),
            machines = root.optInt("machines", 0),
            snapshots = root.optInt("snapshots", 0),
            periodStart = cost.optString("periodStart").takeIf { it.isNotBlank() && it != "null" },
        )
    }

    /** action: start | stop | restart */
    suspend fun power(id: String, action: String): Unit = withContext(Dispatchers.IO) {
        val idem = "clouddeck-$action-$id-" + System.currentTimeMillis()
        post("/machines/$id/$action", idem)
        Unit
    }
}
