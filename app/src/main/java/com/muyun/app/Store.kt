package com.muyun.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一台"服务器账号"。type: github / instabox / ssh */
data class Entry(
    val id: String,
    val type: String,
    val label: String,
    val secret: String = "",     // token / API key / 密码
    val baseUrl: String = "",
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    // 自定义 HTTP API 型云
    val startUrl: String = "",
    val stopUrl: String = "",
    val headers: String = "",
    val body: String = "",
    /** 选填：状态查询 URL，200 视为在线 */
    val statusUrl: String = "",
    // 阿里云 ECS
    val accessKeyId: String = "",
    val region: String = "",
    val instanceId: String = "",
    /** SSH 密码（阿里云等场景与 secret 分开存） */
    val sshPassword: String = "",
    /** 阿里云子类型：ecs / swas(轻量应用服务器) */
    val aliyunKind: String = "ecs",
    /** 连接模式：ssh（有终端） / sftp（只能传文件） */
    val connMode: String = "ssh",
    /** 开关机请求方法：POST / GET */
    val httpMethod: String = "POST",
    /** 开机/关机各自的 Body（留空则回退到 body） */
    val bodyStart: String = "",
    val bodyStop: String = "",
    /** 跳板：用哪台机器的 SOCKS 代理去访问该云的 API（空=直连） */
    val jumpId: String = "",
    /** 该云 HTTP 请求超时（秒），默认 25 */
    val httpTimeoutSec: Int = 25,
    /** 给 AI/MCP 看的说明（如"生产环境，禁止重启"） */
    val agentNote: String = "",
    /** public=公开 / protected=受保护（默认）/ private=私有 */
    val visibility: String = "protected",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("type", type); put("label", label); put("secret", secret)
        put("baseUrl", baseUrl); put("host", host); put("port", port); put("user", user)
        put("startUrl", startUrl); put("stopUrl", stopUrl); put("headers", headers); put("body", body)
        put("statusUrl", statusUrl)
        put("accessKeyId", accessKeyId); put("region", region); put("instanceId", instanceId)
        put("sshPassword", sshPassword); put("aliyunKind", aliyunKind); put("connMode", connMode)
        put("httpMethod", httpMethod); put("bodyStart", bodyStart); put("bodyStop", bodyStop); put("jumpId", jumpId); put("httpTimeoutSec", httpTimeoutSec)
        put("agentNote", agentNote); put("visibility", visibility)
    }

    companion object {
        fun fromJson(o: JSONObject) = Entry(
            id = o.optString("id"),
            type = o.optString("type"),
            label = o.optString("label"),
            secret = o.optString("secret"),
            baseUrl = o.optString("baseUrl"),
            host = o.optString("host"),
            port = o.optInt("port", 22),
            user = o.optString("user"),
            startUrl = o.optString("startUrl"),
            stopUrl = o.optString("stopUrl"),
            headers = o.optString("headers"),
            body = o.optString("body"),
            statusUrl = o.optString("statusUrl"),
            accessKeyId = o.optString("accessKeyId"),
            region = o.optString("region"),
            instanceId = o.optString("instanceId"),
            sshPassword = o.optString("sshPassword"),
            aliyunKind = o.optString("aliyunKind", "ecs"),
            connMode = o.optString("connMode", "ssh"),
            httpMethod = o.optString("httpMethod", "POST"),
            bodyStart = o.optString("bodyStart"),
            bodyStop = o.optString("bodyStop"),
            jumpId = o.optString("jumpId"),
            httpTimeoutSec = o.optInt("httpTimeoutSec", 25),
            agentNote = o.optString("agentNote"),
            visibility = o.optString("visibility", "protected"),
        )
    }
}

/** 密钥库条目：一个名字 + 多组键值对 */
data class VaultPair(val k: String = "", val v: String = "")
data class VaultItem(
    val id: String,
    val name: String = "",
    val pairs: List<VaultPair> = emptyList(),
    /** public=公开；secret=秘密(暴露凭据开关打开才给 AI)；private=私有(仅软件内) */
    val visibility: String = "secret",
    val note: String = "",
    /** kv=键值对；text=整段文本（私钥/JSON 等原样保存） */
    val type: String = "kv",
    val text: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("visibility", visibility); put("note", note)
        put("pairs", org.json.JSONArray().apply { pairs.forEach { put(JSONObject().put("k", it.k).put("v", it.v)) } })
        put("type", type); put("text", text)
    }
    companion object {
        fun fromJson(o: JSONObject) = VaultItem(
            id = o.optString("id"), name = o.optString("name"),
            pairs = runCatching {
                val a = o.optJSONArray("pairs") ?: return@runCatching emptyList<VaultPair>()
                (0 until a.length()).map { VaultPair(a.getJSONObject(it).optString("k"), a.getJSONObject(it).optString("v")) }
            }.getOrDefault(emptyList()),
            visibility = o.optString("visibility", "secret"),
            note = o.optString("note"),
            type = o.optString("type", "kv"),
            text = o.optString("text"),
        )
    }
}

/** AI 供应商（可保存多个，随时切换） */
data class AiProvider(
    val id: String,
    val label: String = "",
    val baseUrl: String = "",
    val key: String = "",
    val model: String = "",
    /** 该供应商走哪台跳板机（空=直连） */
    val jumpId: String = "",
    /** 是否支持视觉（图片理解） */
    val vision: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("label", label); put("baseUrl", baseUrl); put("key", key)
        put("model", model); put("jumpId", jumpId); put("vision", vision)
    }
    companion object {
        fun fromJson(o: JSONObject) = AiProvider(
            id = o.optString("id"), label = o.optString("label"), baseUrl = o.optString("baseUrl"),
            key = o.optString("key"), model = o.optString("model"), jumpId = o.optString("jumpId"),
            vision = o.optBoolean("vision", false),
        )
    }
}

/** 独立的跳板机（任意 SSH 主机） */
data class JumpHost(
    val id: String,
    val label: String = "",
    val host: String = "",
    val port: Int = 22,
    val user: String = "root",
    val password: String = "",
    val agentNote: String = "",
    val visibility: String = "protected",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("label", label); put("host", host)
        put("port", port); put("user", user); put("password", password)
        put("agentNote", agentNote); put("visibility", visibility)
    }

    companion object {
        fun fromJson(o: JSONObject) = JumpHost(
            id = o.optString("id"),
            label = o.optString("label"),
            host = o.optString("host"),
            port = o.optInt("port", 22),
            user = o.optString("user", "root"),
            password = o.optString("password"),
            agentNote = o.optString("agentNote"),
            visibility = o.optString("visibility", "protected"),
        )
    }
}

data class Config(
    val entries: List<Entry> = emptyList(),
    val csAllowance: Int = 120,
    val idlePollSeconds: Int = 30,
    /** 终端快捷命令（点击填入输入框，长按直接执行） */
    val quickCommands: List<String> = listOf("uptime", "df -h", "free -m", "docker ps"),
    val sshHost: String = "ssh.instabox.sh",
    val sshPort: String = "2222",
    val sshUser: String = "",
    val aiBaseUrl: String = "https://api.deepseek.com/v1",
    val aiModel: String = "deepseek-chat",
    val aiKey: String = "",
    /** 是否允许 AI/MCP 看到完整凭据（Token/密钥/密码） */
    val exposeSecrets: Boolean = false,
    /** 跳板机列表 */
    val jumpHosts: List<JumpHost> = emptyList(),
    /** AI 供应商列表 + 当前使用 */
    val vault: List<VaultItem> = emptyList(),
    val aiProviders: List<AiProvider> = emptyList(),
    val aiCurrentId: String = "",
)

object Store {
    private const val P = "clouddeck"

    /** 读取加密字段：优先密文，兼容旧明文 */
    private fun sec(sp: android.content.SharedPreferences, key: String, def: String = ""): String {
        val enc = sp.getString(key + "_enc", null)
        if (!enc.isNullOrBlank()) {
            com.muyun.app.data.SecureStore.decrypt(enc)?.let { return it }
        }
        return sp.getString(key, def) ?: def
    }

    fun load(c: Context): Config {
        val sp = c.getSharedPreferences(P, Context.MODE_PRIVATE)
        val raw = sec(sp, "entries")
        val entries = if (raw.isNullOrBlank()) migrate(sp) else runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Entry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())

        return Config(
            entries = entries,
            csAllowance = sp.getInt("csAllowance", 120),
            idlePollSeconds = sp.getInt("idlePollSeconds", 30),
            quickCommands = runCatching {
                val arr = JSONArray(sp.getString("quickCommands", "") ?: "")
                (0 until arr.length()).map { arr.getString(it) }
            }.getOrDefault(listOf("uptime", "df -h", "free -m", "docker ps")),
            sshHost = sp.getString("sshHost", "ssh.instabox.sh") ?: "ssh.instabox.sh",
            sshPort = sp.getString("sshPort", "2222") ?: "2222",
            sshUser = sp.getString("sshUser", "") ?: "",
            aiBaseUrl = sp.getString("aiBaseUrl", "https://api.deepseek.com/v1") ?: "https://api.deepseek.com/v1",
            aiModel = sp.getString("aiModel", "deepseek-chat") ?: "deepseek-chat",
            aiKey = sec(sp, "aiKey"),
            exposeSecrets = sp.getBoolean("exposeSecrets", false),
            vault = runCatching {
                val arr = JSONArray(sec(sp, "vault"))
                (0 until arr.length()).map { VaultItem.fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList()),
            aiProviders = runCatching {
                val arr = JSONArray(sec(sp, "aiProviders"))
                (0 until arr.length()).map { AiProvider.fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList()),
            aiCurrentId = sp.getString("aiCurrentId", "") ?: "",
            jumpHosts = runCatching {
                val arr = JSONArray(sec(sp, "jumpHosts"))
                (0 until arr.length()).map { JumpHost.fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList()),
        )
    }

    /** 老版本单账号字段 → 条目列表 */
    private fun migrate(sp: android.content.SharedPreferences): List<Entry> {
        val out = mutableListOf<Entry>()
        val apiKey = sp.getString("apiKey", "") ?: ""
        if (apiKey.isNotBlank()) {
            out += Entry("e_ib0", "instabox", "instabox 主账号", secret = apiKey,
                baseUrl = sp.getString("baseUrl", InstaboxClient.DEFAULT_BASE) ?: InstaboxClient.DEFAULT_BASE,
                user = sp.getString("sshUser", "") ?: "",
                host = sp.getString("sshHost", "ssh.instabox.sh") ?: "ssh.instabox.sh",
                port = (sp.getString("sshPort", "2222") ?: "2222").toIntOrNull() ?: 2222)
        }
        val gh = sp.getString("ghToken", "") ?: ""
        if (gh.isNotBlank()) out += Entry("e_gh0", "github", "GitHub 账号", secret = gh)
        sp.edit().putString("entries", toJson(out)).apply()
        return out
    }

    fun toJson(list: List<Entry>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    private fun putSec(
        e: android.content.SharedPreferences.Editor, key: String, value: String
    ): android.content.SharedPreferences.Editor {
        runCatching { e.putString(key + "_enc", com.muyun.app.data.SecureStore.encrypt(value)) }
            .onFailure { e.putString(key, value) }
        e.remove(key)
        return e
    }

    fun save(c: Context, cfg: Config) {
        val ed = c.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
        putSec(ed, "entries", toJson(cfg.entries))
        putSec(ed, "vault", JSONArray().apply { cfg.vault.forEach { put(it.toJson()) } }.toString())
        putSec(ed, "aiProviders", JSONArray().apply { cfg.aiProviders.forEach { put(it.toJson()) } }.toString())
        putSec(ed, "jumpHosts", JSONArray().apply { cfg.jumpHosts.forEach { put(it.toJson()) } }.toString())
        putSec(ed, "aiKey", cfg.aiKey)
        ed
            .putInt("csAllowance", cfg.csAllowance)
            .putInt("idlePollSeconds", cfg.idlePollSeconds)
            .putString("quickCommands", JSONArray().apply { cfg.quickCommands.forEach { put(it) } }.toString())
            .putString("sshHost", cfg.sshHost)
            .putString("sshPort", cfg.sshPort)
            .putString("sshUser", cfg.sshUser)
            .putString("aiBaseUrl", cfg.aiBaseUrl)
            .putString("aiModel", cfg.aiModel)
            .putBoolean("exposeSecrets", cfg.exposeSecrets)
            .putString("aiCurrentId", cfg.aiCurrentId)
            .apply()
    }
}
