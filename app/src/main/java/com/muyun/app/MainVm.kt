package com.muyun.app

import com.muyun.app.core.toAppError

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class ProxyInfo(
    val port: Int,
    val running: Boolean,
    val phase: String = "",
    val exitIp: String? = null,
    val error: String? = null,
    val latencyMs: Long? = null,
    val speedMbps: Double? = null,
)

data class AiMsg(val role: String, val content: String)

/** 一次完整的对话记录（持久化） */
data class ChatSession(
    val id: String,
    val title: String,
    val at: Long,
    val msgs: List<AiMsg>,
)

/** 一个终端会话槽（支持多终端共存） */
data class TermSlot(
    val id: String,
    val title: String,
    val machineId: String,
    val state: String = "连接中…",
    val text: String = "",
)

class MainVm(app: Application) : AndroidViewModel(app) {

    /** 列出所有终端会话：sessionId | 机器 | 状态 | 是否当前聚焦 */
    fun termList(): String {
        if (terms.isEmpty()) return "没有终端会话（请先在「灵枢」页新建）"
        return terms.mapIndexed { i, t ->
            "[${i + 1}] session=${t.id} machine=${t.title} state=${t.state}" +
                if (t.id == activeTermId) " (UI焦点)" else ""
        }.joinToString("\n")
    }

    /** 解析目标会话：支持 sessionId 或机器名；为空时用当前聚焦；多会话且未指定则拒绝 */
    private fun resolveTerm(target: String): Pair<TermSlot?, String> {
        if (terms.isEmpty()) return null to "没有终端会话（请先在「灵枢」页新建）"
        val sel = target.trim()
        if (sel.isBlank() || sel.equals("local", true)) {
            if (terms.size > 1) {
                return null to "当前有 ${terms.size} 个终端会话，请指定 session 或机器名：\n" + termList()
            }
            val t = terms.firstOrNull { it.id == activeTermId } ?: terms.first()
            return t to ""
        }
        val idx = sel.toIntOrNull()
        if (idx != null && idx in 1..terms.size) return terms[idx - 1] to ""
        val exact = terms.filter { it.title.equals(sel, true) || it.id == sel }
        val fuzzy = if (exact.isEmpty()) terms.filter { it.title.contains(sel, true) } else exact
        if (fuzzy.size == 1) return fuzzy.first() to ""
        if (fuzzy.size > 1) {
            return null to "机器「$sel」有 ${fuzzy.size} 个终端会话，请用序号或 session 指定：\n" + termList()
        }
        return null to "找不到终端会话：$sel\n" + termList()
    }

    fun termRead(target: String, n: Int, since: Int = -1): String {
        val (slot, err) = resolveTerm(target)
        if (slot == null) return err
        val text = slot.text
        if (text.isBlank()) {
            return "终端会话「${slot.title}」已连接，但尚未产生输出（可能刚连上或命令未执行）。"
        }
        // 增量读：since>=0 时只返回该偏移之后的新内容；返回 offset 供下次续读
        val body = if (since >= 0) {
            val from = since.coerceIn(0, text.length)
            text.substring(from)
        } else {
            text.takeLast(n)
        }
        return "[machine=${slot.title} offset=${text.length}]\n$body"
    }

    fun termWrite(target: String, command: String): String {
        val (slot, err) = resolveTerm(target)
        if (slot == null) return err
        if (command.isBlank()) return "命令为空"
        val id = slot.id
        // 不改变 UI 焦点：直接写目标会话
        if (termSession.isLocal(id)) {
            appendTerm(id, "\n$ " + command + "\n")
            viewModelScope.launch {
                val out = withContext(Dispatchers.IO) { localRun(command, 60) }
                appendTerm(id, if (out.endsWith("\n")) out else "$out\n")
            }
        } else {
            val ssh = termSession.connection(id)
                ?: return "终端「${slot.title}」未连接（${id}）"
            viewModelScope.launch {
                val e = withContext(Dispatchers.IO) { ssh.write(command + "\r") }
                if (e != null) diag("term_write 失败 $id: $e")
            }
        }
        return "已发送到终端「${slot.title}」（session=$id）：$command"
    }

    /** 终端标签状态（必须在 init 之前声明，syncService 会用到） */
    private val termSession = com.muyun.app.feature.terminal.TermSession()

    /** 视觉记忆：模型名 → 是否支持/开启视觉（必须在 init 之前声明） */
    var visionModels by mutableStateOf<Set<String>>(emptySet()); private set

    /** 配置持久化统一入口（阶段 2） */
    private val repo by lazy { com.muyun.app.data.StoreRepository(getApplication()) }

    var cfg by mutableStateOf(Store.load(app)); private set
    var machines by mutableStateOf<List<Machine>>(emptyList()); private set
    var spaces by mutableStateOf<List<Cspace>>(emptyList()); private set
    var usage by mutableStateOf<Usage?>(null); private set
    var csReal by mutableStateOf<CsUsage?>(null); private set
    var busy by mutableStateOf(false); private set
    var pending by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var message by mutableStateOf<String?>(null); private set
    val terms: List<TermSlot> get() = termSession.terms
    var activeTermId: String?
        get() = termSession.activeTermId
        set(value) { value?.let { termSession.select(it) } }
    val termState: String get() = terms.firstOrNull { it.id == activeTermId }?.state ?: "未连接"
    val termText: String get() = terms.firstOrNull { it.id == activeTermId }?.text ?: ""
    var aiBusy by mutableStateOf(false); private set
    var aiStatus by mutableStateOf(""); private set
    var aiElapsed by mutableStateOf(0); private set
    var aiUsage by mutableStateOf(""); private set
    var aiLog by mutableStateOf<List<AiMsg>>(emptyList()); private set
    var aiModels by mutableStateOf<List<String>>(emptyList()); private set
    var chatSessions by mutableStateOf<List<ChatSession>>(emptyList()); private set
    var activeChatId by mutableStateOf<String?>(null); private set
    private var agentJob: kotlinx.coroutines.Job? = null
    /** 机器排序（全局生效） */
    var order by mutableStateOf<List<String>>(emptyList()); private set
    var ghCheck by mutableStateOf<String?>(null); private set
    var proxies by mutableStateOf<Map<String, ProxyInfo>>(emptyMap()); private set
    var tab by mutableStateOf(0)
    var keepAlive by mutableStateOf(false); private set
    /** MCP 调试服务 */

    /** 详细错误（弹窗可复制） */
    var errorDetail by mutableStateOf<String?>(null); private set
    // ---------------- MCP（阶段 E：实现已搬到 feature/mcp/McpVm） ----------------
    private val mcpVm: com.muyun.app.feature.mcp.McpVm by lazy {
        com.muyun.app.feature.mcp.McpVm(object : com.muyun.app.feature.mcp.McpHost {
            override fun app() = getApplication<Application>()
            override fun diag(msg: String) = this@MainVm.diag(msg)
            override fun notify(msg: String) { message = msg }
            override fun syncService() = this@MainVm.syncService()
            override fun prefs() = this@MainVm.prefs()
        })
    }

    val mcpRunning: Boolean get() = mcpVm.running
    val mcpPort: Int get() = mcpVm.port
    val mcpToken: String get() = mcpVm.token

    fun updateMcpToken(token: String) = mcpVm.updateToken(token)
    fun regenerateMcpToken(): String = mcpVm.regenerateToken()
    fun toggleMcp(port: Int = mcpPort) = mcpVm.toggle(port) { name, args -> mcpHandle(name, args) }

    /** 诊断日志（阶段 4：委托 core.Diag 统一管理） */
    val diagLog: List<String> get() = com.muyun.app.core.Diag.lines

    private fun diag(line: String) = com.muyun.app.core.Diag.i("APP", line)
    /** 轮询专用日志（与手动操作区分） */
    private fun diagPoll(line: String) = com.muyun.app.core.Diag.i("POLL", line)

    fun clearError() { errorDetail = null }

    fun reportError(title: String, t: Throwable?) {
        val sb = StringBuilder()
        sb.append(title).append("\n\n")
        var e: Throwable? = t
        var depth = 0
        while (e != null && depth < 5) {
            sb.append(if (depth == 0) "异常: " else "原因: ")
                .append(e.javaClass.name).append(": ").append(e.message ?: "(无描述)").append("\n")
            e = e.cause; depth++
        }
        sb.append("\n—— 诊断 ——\n")
        diagLog.takeLast(15).forEach { sb.append(it).append("\n") }
        errorDetail = sb.toString()
        diag("ERROR $title")
    }

    val termOut = MutableSharedFlow<String>(extraBufferCapacity = 8192)

    private val runtimes = mutableMapOf<String, Pair<SshEngine, Socks5Server>>()
    private val boxClients = mutableMapOf<String, InstaboxClient>()
    private val csClients = mutableMapOf<String, CodespacesClient>()
    private var ghLogin = ""
    private var lastUsageAt = 0L

    init {
        viewModelScope.launch {
            ghCheck = withContext(Dispatchers.IO) { Gh.selfCheck(getApplication()) }
        }
        loadChats()
        runCatching {
            val a = org.json.JSONArray(getApplication<Application>()
                .getSharedPreferences("clouddeck", android.content.Context.MODE_PRIVATE)
                .getString("visionModels", "") ?: "")
            visionModels = (0 until a.length()).map { a.getString(it) }.toSet()
        }
        if (cfg.aiProviders.isEmpty() && cfg.aiBaseUrl.isNotBlank()) {
            val p = AiProvider("ai_" + java.util.UUID.randomUUID().toString().take(8),
                label = "默认供应商", baseUrl = cfg.aiBaseUrl, key = cfg.aiKey, model = cfg.aiModel)
            cfg = cfg.copy(aiProviders = listOf(p), aiCurrentId = p.id)
            repo.save(cfg)
        }
        syncService()   // 启动时若无任何活动，清掉上一次残留的通知
        // MCP Token 由 McpVm 在初始化时自行读取
        viewModelScope.launch {
            while (true) {
                // 有代理在跑时暂停自动轮询：代理与跳板常走同一台机器/同一条手机上行，
                // 轮询会抢带宽导致代理变卡（用户实测 1.4Mbps → 0.5Mbps）。
                // 手动刷新不受影响；代理关闭后自动恢复轮询。
                if (runtimes.isEmpty()) {
                    silentRefresh()
                } else {
                    diagPoll("代理运行中，暂停自动轮询（${runtimes.size} 条隧道）")
                }
                delay(30_000L)   // 统一 30 秒轮询
            }
        }
    }

    // ---------------- 账号条目 ----------------
    fun saveEntry(e: Entry) {
        val list = cfg.entries.toMutableList()
        val i = list.indexOfFirst { it.id == e.id }
        if (i >= 0) list[i] = e else list.add(e)
        cfg = cfg.copy(entries = list)
        repo.save(cfg)
        message = "已保存：${e.label}"
        refresh()
    }

    fun deleteEntry(id: String) {
        val list = cfg.entries.filterNot { it.id == id }
        cfg = cfg.copy(entries = list)
        repo.save(cfg)
        stopProxy(id)
        message = "已删除"
        refresh()
    }

    /** 是否已获得「所有文件访问权限」 */
    fun hasAllFilesAccess(): Boolean =
        android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()

    /** 跳到系统设置申请所有文件访问权限 */
    fun requestAllFilesAccess() {
        val ctx = getApplication<Application>()
        runCatching {
            val i = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }.onFailure {
            runCatching {
                ctx.startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** 申请忽略电池优化（保活必需） */
    fun requestIgnoreBattery() {
        val ctx = getApplication<Application>()
        runCatching {
            val i = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${ctx.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }.onFailure {
            runCatching {
                ctx.startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** 自启动没有标准 API，跳转到应用详情页由用户手动开启 */
    fun openAutostartSettings() {
        val ctx = getApplication<Application>()
        runCatching {
            ctx.startActivity(android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${ctx.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        message = "请在系统设置中允许自启动/后台运行（各家路径不同）"
    }

    /** 供 UI 显示提示（message 本身是 private） */
    fun notify(text: String) { message = text }

    /** 供 UI 触发刷新（loadAll 是 private suspend） */
    fun refreshAll() {
        viewModelScope.launch { runCatching { loadAll() } }
    }

    /** 导出配置（JSON 明文，含凭据，由用户自行选择保存位置） */
    fun exportConfigJson(): String = org.json.JSONObject().apply {
        put("version", 1)
        put("entries", org.json.JSONArray(com.muyun.app.Store.toJson(cfg.entries)))
        put("vault", org.json.JSONArray().apply { cfg.vault.forEach { put(it.toJson()) } })
        put("aiProviders", org.json.JSONArray().apply { cfg.aiProviders.forEach { put(it.toJson()) } })
        put("jumpHosts", org.json.JSONArray().apply { cfg.jumpHosts.forEach { put(it.toJson()) } })
        put("aiKey", cfg.aiKey)
        put("aiBaseUrl", cfg.aiBaseUrl)
        put("aiModel", cfg.aiModel)
        put("aiCurrentId", cfg.aiCurrentId)
        put("quickCommands", org.json.JSONArray(cfg.quickCommands))
        put("exposeSecrets", cfg.exposeSecrets)
    }.toString(2)

    /** 导入配置（覆盖当前配置） */
    fun importConfigJson(text: String): String = runCatching {
        val o = org.json.JSONObject(text)
        val entries = o.optJSONArray("entries")?.let { arr ->
            (0 until arr.length()).map { Entry.fromJson(arr.getJSONObject(it)) }
        } ?: cfg.entries
        val vault = o.optJSONArray("vault")?.let { arr ->
            (0 until arr.length()).map { VaultItem.fromJson(arr.getJSONObject(it)) }
        } ?: cfg.vault
        val providers = o.optJSONArray("aiProviders")?.let { arr ->
            (0 until arr.length()).map { AiProvider.fromJson(arr.getJSONObject(it)) }
        } ?: cfg.aiProviders
        val jumps = o.optJSONArray("jumpHosts")?.let { arr ->
            (0 until arr.length()).map { JumpHost.fromJson(arr.getJSONObject(it)) }
        } ?: cfg.jumpHosts
        val quick = o.optJSONArray("quickCommands")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: cfg.quickCommands
        cfg = cfg.copy(
            entries = entries, vault = vault, aiProviders = providers, jumpHosts = jumps,
            aiKey = o.optString("aiKey", cfg.aiKey),
            aiBaseUrl = o.optString("aiBaseUrl", cfg.aiBaseUrl),
            aiModel = o.optString("aiModel", cfg.aiModel),
            aiCurrentId = o.optString("aiCurrentId", cfg.aiCurrentId),
            quickCommands = quick,
            exposeSecrets = o.optBoolean("exposeSecrets", cfg.exposeSecrets),
        )
        repo.save(cfg)
        "导入成功：${entries.size} 台服务器、${vault.size} 条密钥、${jumps.size} 台跳板"
    }.getOrElse { "导入失败：${it.message}" }

    fun addQuickCommand(cmd: String) {
        val c = cmd.trim()
        if (c.isBlank() || c in cfg.quickCommands) return
        cfg = cfg.copy(quickCommands = cfg.quickCommands + c)
        repo.save(cfg)
    }

    fun updateQuickCommand(old: String, newCmd: String) {
        val c = newCmd.trim()
        if (c.isBlank()) return
        cfg = cfg.copy(quickCommands = cfg.quickCommands.map { if (it == old) c else it })
        repo.save(cfg)
    }

    fun removeQuickCommand(cmd: String) {
        cfg = cfg.copy(quickCommands = cfg.quickCommands - cmd)
        repo.save(cfg)
    }

    fun setExposeSecrets(on: Boolean) {
        cfg = cfg.copy(exposeSecrets = on)
        repo.save(cfg)
        message = if (on) "AI/MCP 现在可以看到完整凭据" else "凭据已打码"
    }

    fun saveSettings(allowance: Int, poll: Int) {
        cfg = cfg.copy(csAllowance = allowance, idlePollSeconds = poll)
        repo.save(cfg)
        message = "设置已保存"
    }

    fun clearMessage() { message = null }

    /** 手动刷新某一台机器（最长 60 秒，刷新中按钮会变"停止"） */
    fun refreshMachine(id: String) {
        if (id in refreshing) {
            refreshing = refreshing - id
            diag("手动刷新取消：$id")
            return
        }
        diag("手动刷新开始：$id")
        refreshing = refreshing + id
        viewModelScope.launch {
            try {
                val entryId = id.substringBefore("|")
                val e = entry(entryId)
                if (e != null && e.statusUrl.isNotBlank()) {
                    val t0 = System.currentTimeMillis()
                    val st = statusMonitor.manual(e, 60_000)
                    diag("手动刷新结束：$id state=$st 耗时=${System.currentTimeMillis() - t0}ms")
                    machines = machines.map { if (it.id == id) it.copy(state = st) else it }
                    if (st == "unknown") message = "刷新超时（60s）：面板响应太慢或无网络"
                } else {
                    runCatching { loadAll() }
                }
            } finally {
                refreshing = refreshing - id
            }
        }
    }

    // ---------------- MCP 调试服务 ----------------
    /** Token 由用户自己设定（也可一键随机生成） */



    private fun mcpHandle(name: String, args: org.json.JSONObject): String = when (name) {
        "list_machines" -> buildString {
            machines.filter { entryVisible(it.id) }.forEach {
                append("${it.name} | ${it.state} | canPower=${it.canPower} canSsh=${it.canSsh}\n")
            }
            spaces.filter { entryVisible(it.name) }.forEach {
                append("${it.display} | ${it.state} | codespace\n")
            }
        }.ifBlank { "（没有机器）" }

        "power" -> {
            val target = args.optString("machine")
            val action = args.optString("action", "start")
            val m = findMachine(target)
            val c = findSpace(target)
            when {
                m != null -> { power(m, action); "已提交 $action：${m.name}" }
                c != null -> { csPower(c, action == "start"); "已提交 $action：${c.display}" }
                else -> "找不到机器：$target"
            }
        }

        "ssh_exec" -> sshRun(args.optString("machine"), args.optString("command"))

        "local_run" -> localRun(args.optString("command"))
        "term_list" -> termList()
        "term_write" -> termWrite(
            args.optString("session").ifBlank { args.optString("machine") }
                .ifBlank { args.optString("target") }.ifBlank { args.optString("name") },
            args.optString("command"))
        "vault_list" -> vaultDump()
        "vault_get" -> vaultGet(args.optString("name"), args.optString("key"))
        "term_read" -> termRead(
            args.optString("session").ifBlank { args.optString("machine") }
                .ifBlank { args.optString("target") }.ifBlank { args.optString("name") },
            args.optInt("chars", 4000),
            args.optInt("since", -1))
        "chat_read" -> aiLog.takeLast(args.optInt("count", 20))
            .joinToString("\n\n") { m -> "[${m.role}] ${m.content}" }.ifBlank { "（暂无聊天记录）" }
        "jump_list" -> if (cfg.jumpHosts.isEmpty()) "（暂无跳板机）"
            else cfg.jumpHosts.joinToString("\n") { "${it.label.ifBlank { it.host }} | ${it.user}@${it.host}:${it.port}" }
        "jump_update" -> upsertJumpHost(args.optString("entry"), args.optString("field"), args.optString("value"))
        "jump_delete" -> deleteJumpHostByName(args.optString("entry"))
        "jump_test" -> {
            val j = cfg.jumpHosts.firstOrNull { it.id == args.optString("entry") || it.label == args.optString("entry") || it.host == args.optString("entry") }
            if (j == null) "找不到跳板机" else { testJumpHost(j, quiet = true); "已发起测试（结果写入诊断日志）" }
        }
        "sftp_list" -> sftpOp(args.optString("machine"), "list", args.optString("path", "/"), "")
        "sftp_read" -> sftpOp(args.optString("machine"), "read", args.optString("path"), "")
        "sftp_write" -> sftpOp(args.optString("machine"), "write", args.optString("path"), args.optString("content"))
        "sftp_mkdir" -> sftpOp(args.optString("machine"), "mkdir", args.optString("path"), "")
        "sftp_download" -> sftpDownloadToPhone(args.optString("machine"), args.optString("path"))
        "sftp_rename" -> sftpOp(args.optString("machine"), "rename", args.optString("path"), args.optString("newPath"))
        "sftp_chmod" -> sftpOp(args.optString("machine"), "chmod", args.optString("path"), args.optString("mode", "644"))
        "file_upload" -> fileUploadFromPhone(
            args.optString("sourcePath"), args.optString("machine"), args.optString("remotePath"))
        "sftp_copy" -> sftpCopy(
            args.optString("fromMachine"), args.optString("fromPath"),
            args.optString("toMachine"), args.optString("toPath"))

        "proxy_start" -> {
            val target = args.optString("machine")
            val m = findMachine(target); val c = findSpace(target)
            when {
                m != null -> { startProxy(m.id, m.name, false, m.id.substringAfter("|")); "已请求开启代理：${m.name}" }
                c != null -> { startProxy(c.name, c.display, true, c.name.substringAfter("|")); "已请求开启代理：${c.display}" }
                else -> "找不到机器：$target"
            }
        }

        "proxy_stop" -> {
            val target = args.optString("machine")
            val m = findMachine(target); val c = findSpace(target)
            when {
                m != null -> { stopProxy(m.id); "已关闭代理：${m.name}" }
                c != null -> { stopProxy(c.name); "已关闭代理：${c.display}" }
                else -> "找不到机器：$target"
            }
        }

        "proxy_list" -> proxies.entries.joinToString("\n") { (k, v) ->
            "$k | 端口 ${v.port} | ${if (v.running) "运行中" else "未运行"} | 出口 ${v.exitIp ?: "-"} | 速度 ${v.speedMbps ?: "-"}"
        }.ifBlank { "当前没有代理" }

        "crash_log" -> com.muyun.app.core.CrashGuard.readRecent(getApplication())

        "help" -> """
            牧云 MCP 使用说明
            1) 先调 list_machines 获取机器名与状态
            2) power/ssh_exec/sftp_* 的 machine 参数使用上一步返回的名字
            3) term_* 需要先有终端：多会话时用 session/sessionId/机器名/序号（term_list 可查）
            4) file_upload 的 sourcePath 用 /Download/muyun/agent/ 下的路径（附件已自动保存）
            5) sftp_copy 用于机器之间复制文件或目录（手机中转）
            6) 常见错误码：port_in_use / sftp_not_enabled / auth_failed / not_running
            7) 私有账号不会出现在 list_machines/list_entries，也无法被操作
        """.trimIndent()

        "get_logs" -> {
            val n = args.optInt("lines", 30)
            diagLog.takeLast(n).joinToString("\n").ifBlank { "（无日志）" }
        }

        "list_entries" -> configDump()
        "entry_update" -> updateEntryField(
            args.optString("entry"), args.optString("field"), args.optString("value")
        )

        else -> {
            // 阶段 D2：MCP 未显式实现的工具，自动转交统一 ToolRuntime（与 AI 同一套逻辑）
            val target = args.optString("machine")
                .ifBlank { args.optString("entry") }
                .ifBlank { args.optString("name") }
                .ifBlank { "local" }
            val cmd = args.optString("command").ifBlank { args.optString("path") }
            runCatching { toolRuntime.execute(name, target, cmd, args.optString("content")) }
                .getOrElse { "未知工具：$name（${it.message}）" }
        }
    }

    // ---------------- 排序 ----------------
    private fun loadOrder(): List<String> {
        val sp = getApplication<Application>().getSharedPreferences("clouddeck", android.content.Context.MODE_PRIVATE)
        return (sp.getString("order", "") ?: "").split(",").filter { it.isNotBlank() }
    }

    fun reloadOrder() { order = loadOrder() }

    /** 上移/下移某台机器（影响所有界面的顺序） */
    fun moveMachine(id: String, up: Boolean) {
        val all = currentIds().toMutableList()
        val i = all.indexOf(id)
        if (i < 0) return
        val j = if (up) i - 1 else i + 1
        if (j < 0 || j >= all.size) return
        all[i] = all[j].also { all[j] = all[i] }
        order = all
        val sp = getApplication<Application>().getSharedPreferences("clouddeck", android.content.Context.MODE_PRIVATE)
        sp.edit().putString("order", all.joinToString(",")).apply()
        viewModelScope.launch { runCatching { loadAll() } }
    }

    /** 当前显示顺序的所有 id（与首页渲染一致） */
    fun currentIds(): List<String> = orderedItems().map {
        when (it) { is Machine -> it.id; is Cspace -> it.name; else -> "" }
    }.filter { it.isNotBlank() }

    /** 统一排序后的列表（跨厂商可调序） */
    fun orderedItems(): List<Any> {
        val ord = order.ifEmpty { loadOrder() }
        fun rank(id: String): Int = ord.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it }
        val all = mutableListOf<Any>()
        all.addAll(machines)
        all.addAll(spaces)
        return all.sortedBy {
            when (it) {
                is Machine -> rank(it.id)
                is Cspace -> rank(it.name)
                else -> Int.MAX_VALUE
            }
        }
    }

    private fun applyOrder(ms: List<Machine>, cs: List<Cspace>): Pair<List<Machine>, List<Cspace>> {
        val ord = order.ifEmpty { loadOrder() }
        if (ord.isEmpty()) return ms to cs
        fun rank(id: String) = ord.indexOf(id).let { if (it < 0) Int.MAX_VALUE else it }
        return ms.sortedBy { rank(it.id) } to cs.sortedBy { rank(it.name) }
    }

    fun toast(text: String) { message = text }

    private fun entry(id: String) = cfg.entries.firstOrNull { it.id == id }

    /** 账号是否对 AI/MCP 可见（私有 = 不可见/不可操作） */
    private fun entryVisible(idOrName: String): Boolean {
        val e = entry(idOrName.substringBefore("|")) ?: return true
        return e.visibility != "private"
    }

    /** 容忍 AI 把状态一起写进名字（如 "xpinstabox[instabox,running]"） */
    private fun normName(v: String): String =
        v.substringBefore("[").substringBefore("——").substringBefore(" (").trim().lowercase()

    private fun findMachine(name: String): Machine? {
        val n = normName(name)
        val pool = machines.filter { entryVisible(it.id) }
        return pool.firstOrNull { normName(it.name) == n }
            ?: pool.firstOrNull { normName(it.name).startsWith(n) && n.isNotBlank() }
            ?: pool.firstOrNull { n.contains(normName(it.name)) && normName(it.name).isNotBlank() }
    }

    private fun findSpace(name: String): Cspace? {
        val n = normName(name)
        val pool = spaces.filter { entryVisible(it.name) }
        return pool.firstOrNull { normName(it.display) == n }
            ?: pool.firstOrNull { normName(it.display).startsWith(n) && n.isNotBlank() }
    }

    /**
     * SSH 密码来源：
     *  - 自定义/SSH 类型：secret 就是密码（兼容旧数据），优先 sshPassword
     *  - instabox / GitHub / 阿里云：secret 是 API Key/AK Secret，绝不能当密码用
     */
    private fun sshPasswordOf(e: Entry): String? {
        val p = when (e.type) {
            "custom", "ssh", "" -> e.sshPassword.ifBlank { e.secret }
            else -> e.sshPassword
        }
        return p.takeIf { it.isNotBlank() }
    }

    // ---------------- 拉取 ----------------
    fun refresh() {
        viewModelScope.launch {
            busy = true
            runCatching { loadAll() }.onFailure { message = "读取失败：${it.message}" }
            busy = false
        }
    }

    private suspend fun silentRefresh() = runCatching { loadAll() }

    /** host → IP 缓存，避免每次轮询都做 DNS */
    private val hostIpCache = mutableMapOf<String, String?>()

    private fun ipOf(host: String): String? = hostIpCache.getOrPut(host) {
        runCatching { java.net.InetAddress.getByName(host).hostAddress }.getOrNull()
    }

    /**
     * 判断"正在运行的代理"与"被使用的跳板机"是否指向同一台机器。
     * 同时比较主机名与解析后的 IP（例如同一台机器的域名与公网 IP）。
     */
    private fun proxyJumpConflict(): Boolean {
        if (runtimes.isEmpty()) return false
        val jumpHosts = cfg.jumpHosts.filter { jh ->
            cfg.entries.any { it.jumpId == jh.id } && jh.host.isNotBlank()
        }
        if (jumpHosts.isEmpty()) return false
        val proxyHosts = runtimes.keys.mapNotNull { key ->
            val entryId = key.substringBefore("|")
            entry(entryId)?.host?.takeIf { it.isNotBlank() }
        }
        if (proxyHosts.isEmpty()) return false
        for (ph in proxyHosts) {
            val pip = ipOf(ph)
            for (jh in jumpHosts) {
                if (ph.equals(jh.host, true)) return true
                val jip = ipOf(jh.host)
                if (pip != null && jip != null && pip == jip) {
                    diagPoll("检测到代理与跳板同机：$ph ↔ ${jh.host} ($pip)")
                    return true
                }
            }
        }
        return false
    }

    private suspend fun loadAll() {
        boxClients.clear(); csClients.clear()
        val boxes = mutableListOf<Machine>()
        val cs = mutableListOf<Cspace>()

        for (e in cfg.entries) {
            when (e.type) {
                "instabox" -> {
                    val cl = InstaboxClient(e.secret, e.baseUrl.ifBlank { InstaboxClient.DEFAULT_BASE })
                    boxClients[e.id] = cl
                    runCatching { cl.machines() }.getOrNull()?.forEach { m ->
                        boxes += m.copy(id = "${e.id}|${m.id}", name = if (cfg.entries.count { it.type == "instabox" } > 1) "${m.name} · ${e.label}" else m.name)
                    }
                }
                "aliyun" -> {
                    var kind = e.aliyunKind.ifBlank { "ecs" }
                    var st = runCatching {
                        AliyunEcs(e.accessKeyId, e.secret, e.region, e.instanceId, kind) { jumpProxy(e) }.status()
                    }
                        .onFailure { diag("阿里云状态查询失败($kind): ${it.message}") }
                        .getOrDefault("Unknown")
                    // 选错产品类型时自动换另一种再试一次
                    if (st == "NotFound" || st == "Unknown" || st.isBlank()) {
                        val alt = if (kind == "ecs") "swas" else "ecs"
                        val altSt = runCatching {
                            AliyunEcs(e.accessKeyId, e.secret, e.region, e.instanceId, alt) { jumpProxy(e) }.status()
                        }
                            .getOrNull()
                        if (altSt != null && altSt != "NotFound" && altSt != "Unknown" && altSt.isNotBlank()) {
                            diag("阿里云类型自动纠正：$kind → $alt")
                            kind = alt
                            st = altSt
                            runCatching { saveEntry(e.copy(aliyunKind = alt)) }
                        }
                    }
                    boxes += Machine(
                        id = "${e.id}|aliyun", name = e.label, state = st,
                        desiredState = "", ready = st.equals("Running", true), vcpus = 0, memMiB = 0,
                        runStartedAt = null, autoSuspendSeconds = null, maxRunMinutes = null, free = false,
                        canPower = true, canSsh = e.host.isNotBlank(),
                    )
                }
                "http", "ssh", "custom" -> {
                    // 自定义服务器：SSH 为主，开关机接口可选
                    val canSsh = e.host.isNotBlank() && e.connMode != "sftp"
                    val canPower = e.startUrl.isNotBlank() || e.stopUrl.isNotBlank()
                    val mid = "${e.id}|custom"
                    val prev = machines.firstOrNull { it.id == mid }?.state
                    var st = if (e.statusUrl.isNotBlank()) statusMonitor.get(e) else "unknown"
                    // 已知状态绝不回退成 unknown
                    if (st == "unknown" && !prev.isNullOrBlank() && prev != "unknown") st = prev
                    if (canSsh || canPower || e.host.isNotBlank()) boxes += Machine(
                        id = "${e.id}|custom", name = e.label, state = st,
                        desiredState = "", ready = false, vcpus = 0, memMiB = 0,
                        runStartedAt = null, autoSuspendSeconds = null, maxRunMinutes = null, free = false,
                        canPower = canPower, canSsh = canSsh, canFile = e.host.isNotBlank(),
                    )
                }
                "github" -> {
                    val cl = CodespacesClient(e.secret)
                    csClients[e.id] = cl
                    runCatching { cl.list() }.getOrNull()?.forEach { c ->
                        cs += c.copy(name = "${e.id}|${c.name}")
                    }
                }
            }
        }
        val (om, oc) = applyOrder(boxes, cs)
        machines = om
        spaces = oc

        cfg.entries.firstOrNull { it.type == "instabox" }?.let { e ->
            usage = runCatching {
                InstaboxClient(e.secret, e.baseUrl.ifBlank { InstaboxClient.DEFAULT_BASE }).usage()
            }.getOrNull()
        }

        // 真实用量（每 60 秒最多一次）
        val now = System.currentTimeMillis()
        cfg.entries.firstOrNull { it.type == "github" }?.let { e ->
            if (csReal == null || now - lastUsageAt > 60_000) {
                val cl = csClients[e.id] ?: CodespacesClient(e.secret)
                if (ghLogin.isBlank()) ghLogin = runCatching { cl.login() }.getOrDefault("")
                if (ghLogin.isNotBlank()) {
                    val c = java.util.Calendar.getInstance()
                    csReal = runCatching {
                        cl.usageReport(ghLogin, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1)
                    }.getOrNull()
                    lastUsageAt = now
                }
            }
        }
    }

    // ---------------- 电源 ----------------
    fun power(m: Machine, action: String) {
        viewModelScope.launch {
            closeForMachine(m.id, null)
            pending = pending + (m.id to if (action == "start") "启动中" else "停止中")
            try {
                val entryId = m.id.substringBefore("|")
                val raw = m.id.substringAfter("|")
                val e = entry(entryId)
                diag("电源 action=$action machine=${m.name} type=${e?.type}")
                if (e != null && e.type == "aliyun") {
                    // 阶段 3：走统一 Provider
                    runCatching {
                        val p = com.muyun.app.cloud.AliyunProvider(e, { jumpProxy(e) })
                        if (action == "start") p.start() else p.stop()
                    }.onFailure {
                        message = "${m.name} 操作失败：${it.toAppError().userMessage()}"
                        diag("阿里云电源失败 ${it.toAppError().techDetail()}")
                    }
                } else if (e != null && e.type !in listOf("instabox", "github")) {
                    if (!m.canPower) { message = "${m.name} 未配置开关机接口"; return@launch }
                    runCatching {
                        val p = com.muyun.app.cloud.HttpProvider(e, proxyOrNull(e), ::diag)
                        if (action == "start") p.start() else p.stop()
                    }.onFailure {
                        message = "${m.name} 操作失败：${it.toAppError().userMessage()}"
                        diag("HTTP 电源失败 ${it.toAppError().techDetail()}")
                    }
                } else {
                    val cl = boxClients[entryId] ?: e?.let { InstaboxClient(it.secret, it.baseUrl.ifBlank { InstaboxClient.DEFAULT_BASE }) }
                    runCatching { cl?.power(raw, action) }
                        .onSuccess {
                            diag("instabox $action 已提交")
                            runCatching { loadAll() }
                        }
                        .onFailure {
                            diag("instabox $action 失败: ${it.javaClass.simpleName}: ${it.message}")
                            message = "${m.name} 操作失败：${it.toAppError().userMessage()}"
                        }
                }
                waitSettledBox(m.id, action == "start", if (action == "start") 180 else 90)
            } finally {
                pending = pending - m.id
            }
        }
    }

    fun csPower(c: Cspace, start: Boolean) {
        viewModelScope.launch {
            closeForMachine(c.name, c.name)
            pending = pending + (c.name to if (start) "启动中" else "停止中")
            try {
                val entryId = c.name.substringBefore("|")
                val raw = c.name.substringAfter("|")
                val cl = csClients[entryId] ?: entry(entryId)?.let { CodespacesClient(it.secret) }
                runCatching { if (start) cl?.start(raw) else cl?.stop(raw) }
                    .onFailure { message = "${c.display} 操作失败：${it.message}" }
                waitSettledCs(c.name, start, 120)
            } finally {
                pending = pending - c.name
            }
        }
    }

    /** 等到状态稳定（或超时），超时也返回，不阻塞按钮 */
    private suspend fun waitSettledBox(id: String, starting: Boolean, timeoutSec: Int) {
        com.muyun.app.feature.power.PowerWait.waitUntil(starting, timeoutSec) {
            runCatching { loadAll() }
            machines.firstOrNull { it.id == id }?.state ?: ""
        }
    }

    private suspend fun waitSettledCs(id: String, starting: Boolean, timeoutSec: Int) {
        com.muyun.app.feature.power.PowerWait.waitUntil(starting, timeoutSec) {
            runCatching { loadAll() }
            spaces.firstOrNull { it.name == id }?.state ?: ""
        }
    }

    fun batch(start: Boolean) {
        machines.filter { if (start) !it.state.equals("running", true) else it.state.equals("running", true) }
            .forEach { power(it, if (start) "start" else "stop") }
        spaces.filter {
            val up = it.state.equals("Available", true) || it.state.equals("Running", true)
            if (start) !up else up
        }.forEach { csPower(it, start) }
    }

    // ---------------- 终端（多会话） ----------------
    private fun newSlot(title: String, machineId: String) = termSession.newSlot(title, machineId)
    private fun updateSlot(id: String, f: (TermSlot) -> TermSlot) = termSession.update(id, f)
    private fun appendTerm(id: String, chunk: String) = termSession.append(id, chunk)

    fun selectTerm(id: String) = termSession.select(id)

    /** 打开一个"本机（手机）"终端 */
    fun connectLocal() {
        val slot = newSlot("本机（手机）", "local")
        termSession.markLocal(slot)
        updateSlot(slot) { it.copy(state = "已连接") }
        appendTerm(slot, "牧云本机终端（非交互，逐条执行）\n输入命令后点发送，例如：curl -s ifconfig.me\n\n")
    }

    fun closeTerm(id: String) {
        if (termSession.unmarkLocal(id)) {
            termSession.remove(id)
            return
        }
        val s = termSession.removeConnection(id)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { s?.close() }
            maybeStopService()
        }
        termSession.remove(id)
    }

    fun connect(m: Machine) {
        val entryId = m.id.substringBefore("|")
        val e = entry(entryId) ?: return
        val app = getApplication<Application>()
        val slot = newSlot(m.name, m.id)
        updateSlot(slot) { it.copy(state = "连接中…") }
        viewModelScope.launch {
          try {
            val knownStopped = m.state.equals("stopped", true) || m.state.equals("Stopped", true) ||
                m.state.equals("shutdown", true)
            if (!m.state.equals("running", true) && m.canPower && knownStopped) {
                updateSlot(slot) { it.copy(state = "启动中…") }
                runCatching { boxClients[entryId]?.power(m.id.substringAfter("|"), "start") }
                var waited = 0
                com.muyun.app.feature.power.PowerWait.waitUntil(starting = true, timeoutSec = 90) {
                    waited += 3
                    updateSlot(slot) { it.copy(state = "启动中… ${waited}s") }
                    runCatching { loadAll() }
                    machines.firstOrNull { it.id == m.id }?.state ?: ""
                }
            }
            val s = StdioSsh(
                ctx = app, token = "", target = m.id, kind = "ssh",
                host = e.host, port = e.port, user = e.user,
                password = sshPasswordOf(e),
                onOutput = { chunk -> appendTerm(slot, chunk) },
                onState = { st ->
                    updateSlot(slot) { it.copy(state = st) }
                    if (st == "连接已结束" || st == "连接失败" || st == "未连接") {
                        viewModelScope.launch(Dispatchers.IO) { maybeStopService() }
                    }
                },
            )
            termSession.register(slot, s)
            syncService()
            diag("连接 machine=${m.name} type=${e.type} host=${e.host}:${e.port} user=${e.user} auth=${if (sshPasswordOf(e) != null) "密码" else "免密"}")
            s.start(null) { err ->
                updateSlot(slot) { it.copy(state = "连接失败") }
                diag("失败: $err")
                errorDetail = buildString {
                    append("SSH 连接失败\n\n")
                    append("主机: ${e.host}:${e.port}\n用户: ${e.user}\n")
                    val pw = sshPasswordOf(e)
                    val pwSrc = when {
                        pw == null -> "未填写（走免密）"
                        e.sshPassword.isNotBlank() -> "已填写（SSH 密码框）"
                        else -> "已填写（沿用 secret，自定义类型）"
                    }
                    append("密码: $pwSrc\n")
                    append("类型: ${e.type}\n\n")
                    append("错误: $err\n\n—— 诊断 ——\n")
                    diagLog.takeLast(12).forEach { append(it).append("\n") }
                }
            }
          } catch (t: Throwable) {
              updateSlot(slot) { it.copy(state = "连接失败") }
              diag("连接异常: ${t.javaClass.simpleName}: ${t.message}")
              reportError("SSH 连接异常", t)
          }
        }
    }

    fun connect(c: Cspace) {
        val app = getApplication<Application>()
        val entryId = c.name.substringBefore("|")
        val token = entry(entryId)?.secret ?: ""
        val slot = newSlot(c.display, c.name)
        updateSlot(slot) { it.copy(state = "连接中…") }
        viewModelScope.launch {
          try {
            val up = c.state.equals("Available", true) || c.state.equals("Running", true)
            if (!up) {
                updateSlot(slot) { it.copy(state = "启动中…") }
                runCatching { csClients[entryId]?.start(c.name.substringAfter("|")) }
                var waited = 0
                com.muyun.app.feature.power.PowerWait.waitUntil(starting = true, timeoutSec = 120) {
                    waited += 3
                    updateSlot(slot) { it.copy(state = "启动中… ${waited}s") }
                    runCatching { loadAll() }
                    spaces.firstOrNull { it.name == c.name }?.state ?: ""
                }
            }
            val key = runCatching { Keys.ensure(app) }.getOrElse {
                updateSlot(slot) { it.copy(state = "未连接") }
                message = "密钥生成失败：${it.message}"; return@launch
            }
            val s = StdioSsh(
                ctx = app, token = token, target = c.name.substringAfter("|"), kind = "cs",
                host = "", port = 22, user = "", password = null,
                onOutput = { chunk -> appendTerm(slot, chunk) },
                onState = { st ->
                    updateSlot(slot) { it.copy(state = st) }
                    if (st == "连接已结束" || st == "连接失败" || st == "未连接") {
                        viewModelScope.launch(Dispatchers.IO) { maybeStopService() }
                    }
                },
            )
            termSession.register(slot, s)
            syncService()
            diag("连接 codespace ${c.name} user 自动探测")
            s.start(key) { err ->
                updateSlot(slot) { it.copy(state = "连接失败") }
                diag("失败: $err")
                errorDetail = "Codespace 连接失败\n\n${c.display}\n\n错误: $err\n\n—— 诊断 ——\n" +
                    diagLog.takeLast(12).joinToString("\n")
            }
          } catch (t: Throwable) {
              updateSlot(slot) { it.copy(state = "连接失败") }
              diag("连接异常: ${t.javaClass.simpleName}: ${t.message}")
              reportError("Codespace 连接异常", t)
          }
        }
    }

    fun disconnect() {
        activeTermId?.let { closeTerm(it) }
    }

    fun sendKeys(data: String) {
        val id = activeTermId ?: run { message = "发送失败：没有活动会话"; return }

        // 本地终端：直接在本机执行
        if (termSession.isLocal(id)) {
            val cmd = data.trim().trimEnd('\r')
            if (cmd.isBlank()) return
            appendTerm(id, "\n$ " + cmd + "\n⏳ 运行中…\n")
            viewModelScope.launch {
                val t0 = System.currentTimeMillis()
                val out = withContext(Dispatchers.IO) { localRun(cmd, 60) }
                val secs = (System.currentTimeMillis() - t0) / 1000.0
                appendTerm(id, out + if (out.endsWith("\n")) "" else "\n")
                appendTerm(id, "✔ 耗时 %.2f s\n".format(secs))
            }
            return
        }

        val s = termSession.connection(id) ?: run { message = "发送失败：该标签页未连接"; return }
        val slotTitle = terms.firstOrNull { it.id == id }?.title ?: id
        diag("终端 write machine=$slotTitle session=$id len=${data.length}")
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { s.write(data) }
            if (err != null) {
                message = "发送失败：$err"
                appendTerm(id, "\n[写入失败] $err\n")
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        val s = termSession.connection(activeTermId!!) ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { s.resize(cols, rows) } }
    }

    fun clearTerm() {
        activeTermId?.let { id -> updateSlot(id) { it.copy(text = "") } }
    }

    // ---------------- AI 助手（跨机器 Agent） ----------------
    /** AI 配置类操作默认不弹窗，只写日志 */
    private var quietUi = false

    // ---------------- 须弥（阶段 B：实现已搬到 feature/files/FilePaneVm） ----------------
    private val filePane: FilePaneVm by lazy {
        FilePaneVm(object : FilePaneHost {
            override fun app() = getApplication<Application>()
            override fun scope() = viewModelScope
            override fun machinesList() = machines
            override fun spacesList() = spaces
            override fun entryOf(machineId: String) = entry(machineId)
            override fun passwordOf(e: Entry) = sshPasswordOf(e)
            override fun diag(msg: String) = this@MainVm.diag(msg)
            override fun notify(msg: String) { message = msg }
            override fun downloadDir() = this@MainVm.downloadDir()
        })
    }

    val leftPane: FilePaneVm.PaneState get() = filePane.leftPane
    val rightPane: FilePaneVm.PaneState get() = filePane.rightPane
    val paneBusy: Boolean get() = filePane.paneBusy
    val paneProgress: Float get() = filePane.paneProgress
    val paneStatus: String get() = filePane.paneStatus

    fun paneOwnerName(owner: String) = filePane.paneOwnerName(owner)
    fun paneOpen(side: Int, owner: String, path: String) = filePane.paneOpen(side, owner, path)
    fun paneRefresh(side: Int) = filePane.paneRefresh(side)
    fun paneUp(side: Int) = filePane.paneUp(side)
    fun paneSelect(side: Int, name: String, on: Boolean) = filePane.paneSelect(side, name, on)
    fun paneSelectAll(side: Int, on: Boolean) = filePane.paneSelectAll(side, on)
    fun paneMkdir(side: Int, name: String) = filePane.paneMkdir(side, name)
    fun paneDelete(side: Int, names: Set<String>) = filePane.paneDelete(side, names)
    fun paneRename(side: Int, old: String, newName: String) = filePane.paneRename(side, old, newName)
    fun paneTransfer(fromSide: Int, move: Boolean) = filePane.paneTransfer(fromSide, move)
    fun paneExtractZip(side: Int, name: String) = filePane.paneExtractZip(side, name)
    fun paneDownloadFolder(side: Int, name: String) = filePane.paneDownloadFolder(side, name)
    fun paneDownloadFile(side: Int, name: String) = filePane.paneDownloadFile(side, name)
    fun paneOpenFile(side: Int, name: String) = filePane.paneOpenFile(side, name)

    /** 下载目录：优先公共 Download/muyun，失败退回 App 外部目录 */
    /** 根目录：/Download/muyun（无权限时退回 App 外部目录） */
    fun downloadDir(): java.io.File {
        val pub = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS), "muyun")
        if (pub.exists() || pub.mkdirs()) {
            pub.mkdirs()
            return pub
        }
        val appDir = java.io.File(
            getApplication<Application>().getExternalFilesDir(null)
                ?: getApplication<Application>().filesDir, "muyun")
        appDir.mkdirs()
        return appDir
    }

    /** 子目录：preview（打开用的副本）/ downloads（用户下载）/ agent（AI 附件） */
    fun subDir(name: String): java.io.File =
        java.io.File(downloadDir(), name).apply { mkdirs() }

    /** 附件备份到 agent 目录，同名自动加序号；超 200MB 清理最旧 */
    fun saveAgentAttachment(name: String, bytes: ByteArray): java.io.File {
        val dir = subDir("agent")
        var f = java.io.File(dir, name)
        if (f.exists()) {
            val base = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "")
            var i = 2
            while (f.exists() && i < 1000) {
                f = java.io.File(dir, if (ext.isBlank()) "$base ($i)" else "$base ($i).$ext")
                i++
            }
        }
        runCatching { f.writeBytes(bytes) }
        // 配额清理：超过 200MB 删除最旧
        runCatching {
            val all = dir.listFiles()?.sortedBy { it.lastModified() } ?: return@runCatching
            var total = all.sumOf { it.length() }
            for (old in all) {
                if (total <= 200L * 1024 * 1024) break
                total -= old.length()
                old.delete()
                diag("agent 目录清理：删除 ${old.name}")
            }
        }
        return f
    }

    fun clearLogs() {
        com.muyun.app.core.Diag.clear()
        message = "天机录已清空"
    }

    // ---------------- 万物密钥库 ----------------
    fun saveVaultItem(v: VaultItem) {
        val list = cfg.vault.toMutableList()
        val i = list.indexOfFirst { it.id == v.id }
        if (i >= 0) list[i] = v else list.add(v)
        cfg = cfg.copy(vault = list)
        repo.save(cfg)
        diag("密钥库已保存：${v.name}")
    }

    fun deleteVaultItem(id: String) {
        cfg = cfg.copy(vault = cfg.vault.filterNot { it.id == id })
        repo.save(cfg)
        diag("密钥库条目已删除")
    }

    /** 可见性判定：public 永远可见；secret 需开启"暴露完整凭据"；private 仅 App 内使用 */
    private fun vaultVisible(v: VaultItem): Boolean = v.visibility != "private"

    fun vaultDump(includePrivate: Boolean = false): String {
        val vis = cfg.vault.filter { vaultVisible(it) || (includePrivate && it.visibility == "private") }
        if (vis.isEmpty()) return "（密钥库为空或均不可见）"
        return vis.joinToString("\n\n") { v ->
            buildString {
                append("【").append(v.name).append("】可见性=").append(
                    when (v.visibility) { "public" -> "公开"; "secret" -> "受保护"; else -> "私有" })
                if (v.note.isNotBlank()) append(" 备注=").append(v.note)
                if (v.type == "text") {
                    append("\n  [文本] ").append(mask(v.text, if (v.visibility == "secret") "protected" else "public"))
                }
                v.pairs.filter { it.k.isNotBlank() }.forEach { p ->
                    append("\n  ").append(p.k).append(" = ")
                        .append(mask(p.v, if (v.visibility == "secret") "protected" else "public"))
                }
            }
        }
    }

    /**
     * 读取密钥：受三档可见性约束。
     *  公开   = 明文
     *  受保护 = 未开暴露开关时打码
     *  私有   = 不可读
     */
    fun vaultGet(name: String, key: String): String {
        val v = cfg.vault.firstOrNull { it.name.equals(name, true) || it.id == name }
            ?: return "找不到密钥条目：$name"
        if (!vaultVisible(v)) return "该条目不可见（可见性=${v.visibility}）"
        val vis = if (v.visibility == "secret") "protected" else "public"
        return if (key.isBlank()) {
            if (v.type == "text") mask(v.text, vis)
            else v.pairs.joinToString("\n") { p -> "${p.k} = ${mask(p.v, vis)}" }
        } else {
            val raw = v.pairs.firstOrNull { it.k.equals(key, true) }?.v ?: return "找不到键：$key"
            mask(raw, vis)
        }
    }

    fun saveAiProvider(p: AiProvider) {
        val list = cfg.aiProviders.toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list.add(p)
        cfg = cfg.copy(aiProviders = list)
        repo.save(cfg)
        diag("AI 供应商已保存：${p.label.ifBlank { p.baseUrl }}")
    }

    fun deleteAiProvider(id: String) {
        cfg = cfg.copy(aiProviders = cfg.aiProviders.filterNot { it.id == id },
            aiCurrentId = if (cfg.aiCurrentId == id) "" else cfg.aiCurrentId)
        repo.save(cfg)
        diag("已删除 AI 供应商")
    }

    /** 支持视觉的模型集合（按模型名记录） */
    fun isVisionModel(id: String) = id in visionModels

    /**
     * 视觉记忆：每个模型最后的状态单独保存。
     * 打开 → 记入集合；关闭 → 移除。重启后从 prefs 恢复。
     */
    fun rememberVision(id: String, on: Boolean) {
        val now = if (on) visionModels + id else visionModels - id
        if (now == visionModels) return
        visionModels = now
        getApplication<Application>().getSharedPreferences("clouddeck", android.content.Context.MODE_PRIVATE)
            .edit().putString("visionModels", org.json.JSONArray(now.toList()).toString()).apply()
        diag("视觉记忆：$id = $on")
    }

    fun toggleVisionModel(id: String) = rememberVision(id, id !in visionModels)

    fun currentAiProvider(): AiProvider? =
        cfg.aiProviders.firstOrNull { it.id == cfg.aiCurrentId }

    fun useAiProvider(id: String) {
        val p = cfg.aiProviders.firstOrNull { it.id == id } ?: return
        cfg = cfg.copy(aiCurrentId = id, aiBaseUrl = p.baseUrl, aiKey = p.key, aiModel = p.model)
        repo.save(cfg)
        aiModels = emptyList()
        diag("已切换 AI 供应商：${p.label.ifBlank { p.baseUrl }}")
        fetchModels()
    }

    fun saveAiQuiet(baseUrl: String, model: String, key: String) {
        cfg = cfg.copy(aiBaseUrl = baseUrl.trim(), aiModel = model.trim(), aiKey = key.trim())
        repo.save(cfg)
        diag("AI 配置已保存")
    }

    fun saveAi(baseUrl: String, model: String, key: String) {
        cfg = cfg.copy(aiBaseUrl = baseUrl.trim(), aiModel = model.trim(), aiKey = key.trim())
        repo.save(cfg)
        message = "AI 配置已保存"
    }

    /** 自动拉取可用模型：GET {base}/models */
    fun fetchModels() {
        if (cfg.aiKey.isBlank()) { message = "请先填 API Key"; return }
        viewModelScope.launch {
            aiStatus = "正在获取模型…"
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    AiApi.listModels(cfg.aiBaseUrl, cfg.aiKey, jumpProxyById(currentAiProvider()?.jumpId))
                }
            }
            res.onSuccess { list ->
                aiModels = list
                prefs().edit().putString("aiModels", org.json.JSONArray(list).toString()).apply()
                if (list.isNotEmpty() && (cfg.aiModel.isBlank() || cfg.aiModel !in list)) {
                    cfg = cfg.copy(aiModel = list.first())
                    repo.save(cfg)
                }
                aiStatus = if (list.isEmpty()) "没有可用模型" else "连接成功 ✅ 共 ${list.size} 个模型，当前：${cfg.aiModel}"
            }.onFailure { aiStatus = "获取失败：${it.message}" }
        }
    }


    // ---------------- 对话记录 ----------------
    private fun prefs() = getApplication<Application>().getSharedPreferences("clouddeck", android.content.Context.MODE_PRIVATE)

    private fun loadChats() {
        val raw = prefs().getString("chats", "") ?: ""
        chatSessions = runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val msgs = o.optJSONArray("msgs")
                val list = if (msgs == null) emptyList() else (0 until msgs.length()).map { j ->
                    val mo = msgs.getJSONObject(j)
                    AiMsg(mo.optString("role"), mo.optString("content"))
                }
                ChatSession(o.optString("id"), o.optString("title"), o.optLong("at"), list)
            }
        }.getOrDefault(emptyList())

        val modelsRaw = prefs().getString("aiModels", "") ?: ""
        aiModels = runCatching {
            val arr = org.json.JSONArray(modelsRaw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())

        // 恢复上次对话
        chatSessions.maxByOrNull { it.at }?.let {
            activeChatId = it.id
            aiLog = it.msgs
        }
    }

    private fun persistChat() {
        if (aiLog.isEmpty()) return
        val id = activeChatId ?: ("c" + System.currentTimeMillis()).also { activeChatId = it }
        val title = aiLog.firstOrNull { it.role == "user" }?.content?.take(24)?.replace("\n", " ") ?: "新对话"
        chatSessions = com.muyun.app.feature.ai.ChatStore.upsert(
            chatSessions, ChatSession(id, title, System.currentTimeMillis(), aiLog))
        prefs().edit().putString("chats", com.muyun.app.feature.ai.ChatStore.encode(chatSessions)).apply()
    }

    /** 删除第 index 条及其之后的所有消息（用于"删除接下来的对话"） */
    fun deleteFrom(index: Int) {
        if (index !in aiLog.indices) return
        aiLog = aiLog.take(index)
        persistChat()
    }

    /** 重新生成最后一条回答 */
    fun regenerate() {
        if (aiBusy) return
        val lastUser = aiLog.lastOrNull { it.role == "user" } ?: return
        val idx = aiLog.indexOf(lastUser)
        aiLog = aiLog.take(idx)
        persistChat()
        askAgent(lastUser.content)
    }

    fun newChat() {
        activeChatId = null
        aiLog = emptyList()
    }

    fun openChat(id: String) {
        chatSessions.firstOrNull { it.id == id }?.let {
            activeChatId = it.id
            aiLog = it.msgs
        }
    }

    fun deleteChat(id: String) {
        chatSessions = chatSessions.filterNot { it.id == id }
        if (activeChatId == id) newChat()
        val arr = org.json.JSONArray()
        chatSessions.forEach { cs ->
            arr.put(org.json.JSONObject().apply {
                put("id", cs.id); put("title", cs.title); put("at", cs.at)
                put("msgs", org.json.JSONArray().apply {
                    cs.msgs.forEach { m -> put(org.json.JSONObject().apply { put("role", m.role); put("content", m.content) }) }
                })
            })
        }
        prefs().edit().putString("chats", arr.toString()).apply()
    }

    fun clearAiLog() { aiLog = emptyList(); persistChat() }

    /** 强行停止 AI 当前任务 */
    fun stopAgent() {
        agentJob?.cancel()
        agentJob = null
        aiBusy = false
        aiLog = aiLog + AiMsg("system", "⛔ 已强行停止")
    }

    /** AI 可以跨机器执行动作：power_on / power_off / ssh_run */
    /** 本轮待发送的图片（data URL），仅视觉模型使用 */
    private var pendingImages: List<String> = emptyList()

    fun askAgent(prompt: String, display: String = prompt, images: List<String> = emptyList()) {
        if (cfg.aiKey.isBlank()) { message = "请先在设置里配置 AI"; return }
        if (prompt.isBlank()) return
        agentJob = viewModelScope.launch {
            aiBusy = true
            aiStatus = "连接模型中…"
            aiElapsed = 0
            val t0 = System.currentTimeMillis()
            val ticker = launch {
                while (aiBusy) { aiElapsed = ((System.currentTimeMillis() - t0) / 1000).toInt(); delay(1000) }
            }
            aiLog = aiLog + AiMsg("user", display)
            pendingImages = images
            persistChat()
            val agent = com.muyun.app.feature.ai.AiAgent(
                runtime = toolRuntime,
                callModel = { withContext(Dispatchers.IO) { callAgent() } },
                onMessage = { role, content ->
                    aiLog = aiLog + AiMsg(role, content)
                    if (role != "user") persistChat()
                },
                onStatus = { aiStatus = it },
            )
            try {
                agent.run()
            } finally {
                aiBusy = false
                aiStatus = ""
                ticker.cancel()
                persistChat()
            }
        }
    }

    private fun machineNames(): String =
        (machines.filter { m ->
            val e = entry(m.id.substringBefore("|"))
            e == null || e.visibility != "private"
        }.map {
            val e = entry(it.id.substringBefore("|"))
            val note = e?.agentNote?.takeIf { n -> n.isNotBlank() }?.let { n -> "，说明：$n" } ?: ""
            "${it.name}  —— ${if (it.vcpus > 0) "instabox" else "自定义"}, 状态 ${it.state}, SSH=${if (it.canSsh) "可用" else "无"}, 开关机=${if (it.canPower) "可用" else "无"}$note"
        } + spaces.filter { entryVisible(it.name) }.map {
            "${it.display}  —— codespace, 状态 ${it.state}"
        }).joinToString("\n")

    // ---------------- 跳板机 ----------------
    private class JumpTunnel(val engine: SshEngine, val socks: Socks5Server, val port: Int)

    private val jumpTunnels = mutableMapOf<String, JumpTunnel>()

    /** 状态监视器（重构 2.2 抽出） */
    private val statusMonitor: StatusMonitor by lazy {
        StatusMonitor(
            ctx = getApplication(),
            scope = viewModelScope,
            fetch = { e -> CloudHttp.fetchStatus(e, proxyOrNull(e), ::diag) },
            diag = ::diag,
        )
    }
    /** 正在手动刷新的机器 */
    var refreshing by mutableStateOf<Set<String>>(emptySet()); private set

    fun saveJumpHost(j: JumpHost) {
        val list = cfg.jumpHosts.toMutableList()
        val i = list.indexOfFirst { it.id == j.id }
        if (i >= 0) list[i] = j else list.add(j)
        cfg = cfg.copy(jumpHosts = list)
        repo.save(cfg)
        message = "跳板机已保存：${j.label.ifBlank { j.host }}"
    }

    fun deleteJumpHost(id: String) {
        cfg = cfg.copy(jumpHosts = cfg.jumpHosts.filterNot { it.id == id })
        repo.save(cfg)
        closeJumpTunnel(id)
        message = "跳板机已删除"
    }

    fun testJumpHost(j: JumpHost, quiet: Boolean = false) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val eng = SshEngine(getApplication())
                    try {
                        eng.connectPlain(j.host, j.port, j.user, j.password.takeIf { it.isNotBlank() })
                        val out = eng.exec("echo JUMP_OK; whoami; hostname").trim()
                        "跳板机连接成功 ✅\n$out"
                    } finally { runCatching { eng.close() } }
                }.getOrElse { "跳板机连接失败 ❌\n${it.message}" }
            }
            if (quiet) diag("跳板测试 ${j.label.ifBlank { j.host }}: $result") else message = result
        }
    }

    /** 用完即断：关闭指定账号所用跳板的隧道（用户要求：只在该请求期间建立） */
    fun releaseJumpFor(e: Entry?) {
        val id = e?.jumpId ?: return
        if (id.isBlank()) return
        // 同一跳板被左右栏/多账号共用时，这里简单关闭，下次请求再重连
        runCatching { closeJumpTunnel(id) }
        diag("跳板已断开（请求结束）：${cfg.jumpHosts.firstOrNull { it.id == id }?.label ?: id}")
    }

    private fun closeJumpTunnel(id: String) {
        jumpTunnels.remove(id)?.let {
            runCatching { it.socks.stop() }
            runCatching { it.engine.close() }
        }
    }

    /** 为账号取跳板代理：首次使用时建立 SSH 隧道并缓存 */
    private fun jumpProxy(e: Entry?): java.net.Proxy? = jumpProxyById(e?.jumpId)

    /** 跳板失败冷却：避免反复重建 + 每次请求都等超时 */
    private val jumpCooldown = mutableMapOf<String, Long>()

    private fun socksAlive(port: Int): Boolean {
        repeat(2) {
            val ok = runCatching {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 1500) }
                true
            }.getOrDefault(false)
            if (ok) return true
            Thread.sleep(200)
        }
        return false
    }

    /**
     * 只有账号真的配了跳板（jumpId 非空）才返回代理提供者；
     * 否则返回 null = 直连。修复"没配跳板却被当成跳板不可用"的 bug。
     */
    private fun proxyOrNull(e: Entry?): (() -> java.net.Proxy?)? =
        if (e == null || e.jumpId.isBlank()) null else ({ jumpProxy(e) })

    /** 按跳板 id 取代理（云机器 / AI 供应商共用） */
    private fun jumpProxyById(jumpId: String?): java.net.Proxy? {
        val jh = cfg.jumpHosts.firstOrNull { it.id == jumpId } ?: return null
        // 冷却期内直接返回 null（走直连），避免拖慢整页
        val until = jumpCooldown[jh.id] ?: 0L
        if (System.currentTimeMillis() < until) {
            diag("跳板冷却中，改走直连：${jh.label.ifBlank { jh.host }}（剩 ${(until - System.currentTimeMillis()) / 1000}s）")
            return null
        }

        val cached = jumpTunnels[jh.id]
        if (cached != null) {
            val alive = socksAlive(cached.port)
            diag("跳板健康检查 ${jh.label.ifBlank { jh.host }} port=${cached.port} alive=$alive")
            if (alive) {
                return java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", cached.port))
            }
        }
        // 隧道已死：清理后重建
        if (cached != null) {
            diag("跳板隧道失效，重建：${jh.label.ifBlank { jh.host }}")
            closeJumpTunnel(jh.id)
        }
        return try {
            val eng = SshEngine(getApplication())
            eng.connectPlain(jh.host, jh.port, jh.user, jh.password.takeIf { it.isNotBlank() })
            val port = runCatching {
                java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            }.getOrDefault(0)
            if (port == 0) { eng.close(); return null }
            val socks = Socks5Server(port, eng)
            socks.start()
            jumpTunnels[jh.id] = JumpTunnel(eng, socks, port)
            diag("跳板隧道已建立：${jh.label.ifBlank { jh.host }} → 127.0.0.1:$port")
            java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port))
        } catch (t: Throwable) {
            val top = t.stackTrace.firstOrNull()?.let { " @${it.className.substringAfterLast('.')}:${it.lineNumber}" } ?: ""
            diag("跳板连接失败：${t.javaClass.simpleName}: ${t.message ?: "(无描述)"}$top")
            // 立即重试一次（网络抖动/SSH 首连失败很常见）
            runCatching {
                Thread.sleep(400)
                val eng = SshEngine(getApplication())
                eng.connectPlain(jh.host, jh.port, jh.user, jh.password.takeIf { it.isNotBlank() })
                val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
                val socks = Socks5Server(port, eng)
                socks.start()
                jumpTunnels[jh.id] = JumpTunnel(eng, socks, port)
                diag("跳板重试成功：${jh.label.ifBlank { jh.host }} → 127.0.0.1:$port")
                return java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port))
            }.onFailure { diag("跳板重试仍失败：${it.javaClass.simpleName}: ${it.message}") }
            jumpCooldown[jh.id] = System.currentTimeMillis() + 15_000
            null
        }
    }

    /**
     * 凭据打码规则：
     *  - 公开：exposeSecrets 开 → 明文；关 → 打码
     *  - 受保护：始终打码（即使 exposeSecrets 开）
     *  - 私有：不出现在 dump 中
     */
    /**
     * 用户定义的三档：
     *  公开   = 所有信息暴露（服务器 API 与密钥）
     *  受保护 = 全部打码，是否明文由"暴露开关"控制
     *  私有   = 仅软件内可见
     */
    private fun mask(v: String, visibility: String = "public"): String = when {
        v.isBlank() -> "(空)"
        visibility == "public" -> v
        visibility == "protected" -> if (cfg.exposeSecrets) v else v.take(4) + "***"
        else -> "(私有)"
    }

    /** Header 打码：只保留 Key，值打码（Authorization/Cookie/Token 等敏感头） */
    private fun maskHeaders(raw: String, visibility: String): String =
        raw.lineSequence().filter { it.isNotBlank() }.joinToString(" | ") { line ->
            val k = line.substringBefore(":", line).trim()
            val v = line.substringAfter(":", "").trim()
            if (v.isBlank()) line else "$k: " + mask(v, visibility)
        }

    /** 完整配置（含凭据，取决于 exposeSecrets） */
    fun configDump(): String {
        val visibleJumps = cfg.jumpHosts.filter { it.visibility != "private" }
        val jumpPart = if (visibleJumps.isEmpty()) "（暂无跳板机）" else visibleJumps.joinToString("\n") { j ->
            "【跳板】${j.label.ifBlank { j.host }} id=${j.id} ${j.user}@${j.host}:${j.port} 密码=" +
                (if (j.password.isNotBlank()) mask(j.password, j.visibility) else "(免密)") +
                (if (j.agentNote.isNotBlank()) " 说明=${j.agentNote}" else "")
        }
        val visibleEntries = cfg.entries.filter { it.visibility != "private" }
        val hostPart = if (visibleEntries.isEmpty()) "（暂无账号）" else visibleEntries.joinToString("\n\n") { e ->
            buildString {
                append("【").append(e.label).append("】id=").append(e.id).append(" type=").append(e.type)
                if (e.type == "aliyun") append(" 产品=").append(if (e.aliyunKind == "swas") "轻量" else "ECS")
                    .append(" 地域=").append(e.region).append(" 实例=").append(e.instanceId)
                if (e.host.isNotBlank()) {
                    append("\n  连接: ").append(e.user).append("@").append(e.host).append(":").append(e.port)
                    append("  模式=").append(if (e.connMode == "sftp") "仅SFTP" else "SSH")
                }
                append("\n  凭据(secret)=").append(mask(e.secret, e.visibility))
                if (e.sshPassword.isNotBlank()) append("  SSH密码=").append(mask(e.sshPassword, e.visibility))
                if (e.statusUrl.isNotBlank()) append("\n  状态URL=").append(e.statusUrl)
                if (e.startUrl.isNotBlank() || e.stopUrl.isNotBlank()) {
                    append("\n  开机URL=").append(e.startUrl).append(" 方法=").append(e.httpMethod)
                    append("\n  关机URL=").append(e.stopUrl)
                    append("\n  开机Body=").append(e.bodyStart.ifBlank { e.body })
                    append("\n  关机Body=").append(e.bodyStop.ifBlank { e.body })
                }
                if (e.headers.isNotBlank()) append("\n  Headers=").append(maskHeaders(e.headers, e.visibility))
                if (e.agentNote.isNotBlank()) append("\n  说明=").append(e.agentNote)
                append("\n  可见性=").append(
                    when (e.visibility) { "protected" -> "受保护"; "private" -> "私有"; else -> "公开" })
                if (e.jumpId.isNotBlank()) {
                    val j = cfg.jumpHosts.firstOrNull { it.id == e.jumpId }
                    append("\n  跳板=").append(j?.let { it.label.ifBlank { it.host } } ?: "（已删除）")
                }
            }
        }
        return "== 跳板机列表 ==\n$jumpPart\n\n== 账号列表 ==\n$hostPart"
    }

    /** 新增/修改跳板机（AI 与 MCP 用） */
    fun upsertJumpHost(labelOrId: String, field: String, value: String): String {
        val existing = cfg.jumpHosts.firstOrNull { it.id == labelOrId || it.label == labelOrId || it.host == labelOrId }
        val f = field.trim().lowercase()
        val base = existing ?: JumpHost(id = "j_" + java.util.UUID.randomUUID().toString().take(8))
        val updated = when (f) {
            "label", "name" -> base.copy(label = value)
            "host", "addr", "address" -> base.copy(host = value)
            "port" -> base.copy(port = value.toIntOrNull() ?: base.port)
            "user", "username" -> base.copy(user = value)
            "password", "pass" -> base.copy(password = value)
            else -> return "不支持的跳板字段：$field"
        }
        saveJumpHost(updated)
        return "跳板机已保存：${updated.label.ifBlank { updated.host }}"
    }

    fun deleteJumpHostByName(labelOrId: String): String {
        val j = cfg.jumpHosts.firstOrNull { it.id == labelOrId || it.label == labelOrId || it.host == labelOrId }
            ?: return "找不到跳板机：$labelOrId"
        deleteJumpHost(j.id)
        return "已删除跳板机：${j.label.ifBlank { j.host }}"
    }

    /** 修改某个账号的字段（供 AI / MCP 运维用） */
    fun updateEntryField(entryIdOrLabel: String, field: String, value: String): String {
        val e = cfg.entries.firstOrNull { it.id == entryIdOrLabel }
            ?: cfg.entries.firstOrNull { it.label == entryIdOrLabel }
            ?: cfg.entries.firstOrNull { it.label.contains(entryIdOrLabel, true) }
            ?: return "找不到账号：$entryIdOrLabel"
        val f = field.trim().lowercase()
        val updated = when (f) {
            "host" -> e.copy(host = value)
            "port" -> e.copy(port = value.toIntOrNull() ?: e.port)
            "user" -> e.copy(user = value)
            "sshpassword", "password" -> e.copy(sshPassword = value)
            "secret", "token", "apikey" -> e.copy(secret = value)
            "statusurl" -> e.copy(statusUrl = value)
            "starturl" -> e.copy(startUrl = value)
            "stopurl" -> e.copy(stopUrl = value)
            "body", "bodystart" -> e.copy(bodyStart = value)
            "bodystop" -> e.copy(bodyStop = value)
            "headers" -> e.copy(headers = value)
            "connmode" -> e.copy(connMode = if (value.startsWith("s", true)) "sftp" else "ssh")
            "aliyunkind" -> e.copy(aliyunKind = if (value.startsWith("s", true)) "swas" else "ecs")
            "region" -> e.copy(region = value)
            "instanceid" -> e.copy(instanceId = value)
            "label" -> e.copy(label = value)
            "baseurl" -> e.copy(baseUrl = value)
            "timeout", "httptimeout" -> e.copy(httpTimeoutSec = value.toIntOrNull() ?: e.httpTimeoutSec)
            "jump", "jumphost" -> {
                val j = cfg.jumpHosts.firstOrNull { it.id == value || it.label == value || it.host == value }
                if (value.isBlank()) e.copy(jumpId = "") else if (j != null) e.copy(jumpId = j.id) else return "找不到跳板机：$value"
            }
            else -> return "不支持的字段：$field"
        }
        saveEntry(updated)
        return "已更新 ${updated.label} 的 $field"
    }

    /** 给 AI 的"基本情况"：账号类型/标签/是否已配置凭据（绝不含密钥明文） */
    private fun accountSummary(): String {
        if (cfg.entries.isEmpty()) return "（还没有配置任何账号）"
        return cfg.entries.joinToString("\n") { e ->
            val type = when (e.type) {
                "github" -> "GitHub Codespaces"
                "instabox" -> "instabox"
                "http" -> "自定义 HTTP API"
                else -> "自定义 SSH"
            }
            val cred = when {
                e.secret.isBlank() && e.type != "ssh" -> "凭据缺失 ⚠"
                e.type == "ssh" -> if (e.secret.isBlank()) "免密登录" else "密码已配置"
                else -> "凭据已配置 ✅"
            }
            val extra = when (e.type) {
                "instabox" -> "（SSH ${e.user}@${e.host}:${e.port}）"
                "ssh" -> "（${e.user}@${e.host}:${e.port}）"
                "http" -> "（开机/关机走 HTTP 接口）"
                else -> ""
            }
            "· ${e.label} — $type$extra — $cred"
        }
    }

    private fun runtimeSummary(): String {
        val running = machines.count { it.state.equals("running", true) } +
            spaces.count { it.state.equals("Available", true) || it.state.equals("Running", true) }
        val total = machines.size + spaces.size
        val proxy = proxies.filter { it.value.running }.map { "${it.value.port}" }
        return buildString {
            append("机器总计 $total 台，其中运行中 $running 台。\n")
            if (proxy.isNotEmpty()) append("代理端口：${proxy.joinToString(", ")}\n")
            append("AI 模型：${cfg.aiModel}；Codespaces 免费额度：${cfg.csAllowance} core-h/月")
        }
    }

    private fun callAgent(): String {
        val base = cfg.aiBaseUrl.trimEnd('/')
        val sys = AiPrompt.system(configDump(), runtimeSummary(), machineNames())

        val arr = org.json.JSONArray()
        arr.put(org.json.JSONObject().apply { put("role", "system"); put("content", sys) })
        val msgs = aiLog.takeLast(12)
        msgs.forEachIndexed { idx, m ->
            val isLastUser = m.role == "user" && idx == msgs.lastIndex && pendingImages.isNotEmpty()
            if (m.role != "system") {
                if (isLastUser) {
                    val contentArr = org.json.JSONArray().apply {
                        put(org.json.JSONObject().put("type", "text").put("text", m.content))
                        pendingImages.forEach { url ->
                            put(org.json.JSONObject().put("type", "image_url")
                                .put("image_url", org.json.JSONObject().put("url", url)))
                        }
                    }
                    arr.put(org.json.JSONObject().apply { put("role", "user"); put("content", contentArr) })
                } else {
                    arr.put(org.json.JSONObject().apply { put("role", m.role); put("content", m.content) })
                }
            } else {
                arr.put(org.json.JSONObject().apply { put("role", "user"); put("content", "执行结果：\n" + m.content) })
            }
        }
        pendingImages = emptyList()
        val body = org.json.JSONObject().apply {
            put("model", cfg.aiModel)
            put("messages", arr)
        }
        val req = okhttp3.Request.Builder().url("$base/chat/completions")
            .header("Authorization", "Bearer ${cfg.aiKey}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val cb = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        jumpProxyById(currentAiProvider()?.jumpId)?.let { cb.proxy(it) }
        cb.build().newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code}: ${txt.take(200)}")
            return org.json.JSONObject(txt).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content")
        }
    }


    /** AI 与 MCP 共用的工具执行器（阶段 D2） */
    private val toolRuntime: com.muyun.app.feature.tools.ToolRuntime =
        object : com.muyun.app.feature.tools.ToolRuntime {
            override fun execute(kind: String, target: String, command: String, content: String): String =
                executeActionInner(AiAction(kind, target, command, content))
        }

    private fun executeAction(a: AiAction): String = runCatching {
        // AI 执行期间不弹提示，统一写诊断日志
        message = null
        val r = executeActionInner(a)
        message?.let { diag("AI: $it") }
        message = null
        r
    }.getOrElse { "动作执行失败：${it.message}" }

    private fun executeActionInner(a: AiAction): String = runCatching {
        when (a.kind) {
            "power_on", "power_off" -> {
                val m = findMachine(a.target)
                val c = findSpace(a.target)
                when {
                    m != null -> { power(m, if (a.kind == "power_on") "start" else "stop"); "已提交开关机指令：${m.name}" }
                    c != null -> { csPower(c, a.kind == "power_on"); "已提交开关机指令：${c.display}" }
                    else -> "找不到机器：${a.target}"
                }
            }
            "ssh_run" -> sshRun(a.target, a.command)
            "proxy_on" -> {
                val m = findMachine(a.target)
                val c = findSpace(a.target)
                when {
                    m != null -> { startProxy(m.id, m.name, false, m.id.substringAfter("|")); "已开启代理（端口待分配）" }
                    c != null -> { startProxy(c.name, c.display, true, c.name.substringAfter("|")); "已开启代理（端口待分配）" }
                    else -> "找不到机器：${a.target}"
                }
            }
            "proxy_off" -> {
                val m = findMachine(a.target)
                val c = findSpace(a.target)
                when {
                    m != null -> { stopProxy(m.id); "已关闭代理" }
                    c != null -> { stopProxy(c.name); "已关闭代理" }
                    else -> "找不到机器：${a.target}"
                }
            }
            "local_run" -> localRun(a.command)
            "vault_list" -> vaultDump()
            "vault_get" -> {
                val kv = a.command.split("=", limit = 2)
                vaultGet(a.target, kv.getOrElse(0) { "" })
            }
            "vault_set" -> {
                val kv = a.command.split("=", limit = 2)
                if (kv.size < 2) "格式应为 COMMAND: 键=值" else {
                    val old = cfg.vault.firstOrNull { it.name.equals(a.target, true) }
                    val pairs = (old?.pairs ?: emptyList()).toMutableList()
                    val idx = pairs.indexOfFirst { it.k.equals(kv[0], true) }
                    if (idx >= 0) pairs[idx] = VaultPair(kv[0], kv[1]) else pairs.add(VaultPair(kv[0], kv[1]))
                    saveVaultItem(old?.copy(pairs = pairs) ?: VaultItem(
                        id = "v_" + java.util.UUID.randomUUID().toString().take(8),
                        name = a.target, pairs = pairs, visibility = "secret"))
                    "已写入密钥库：${a.target} / ${kv[0]}"
                }
            }
            "config_get" -> configDump()
            "term_list" -> termList()
            "term_write" -> termWrite(a.target, a.command)
        "term_read" -> {
                val n = a.command.toIntOrNull() ?: 4000
                val t = termText
                if (t.isBlank()) "（当前终端没有内容）" else t.takeLast(n)
            }
            "chat_read" -> aiLog.takeLast(a.command.toIntOrNull() ?: 20)
                .joinToString("\n\n") { m -> "[${m.role}] ${m.content}" }.ifBlank { "（暂无聊天记录）" }
            "logs" -> diagLog.takeLast(a.command.toIntOrNull() ?: 60).joinToString("\n").ifBlank { "（暂无日志）" }
            "crash_log" -> com.muyun.app.core.CrashGuard.readRecent(getApplication())
            "jump_list" -> if (cfg.jumpHosts.isEmpty()) "（暂无跳板机）"
                else cfg.jumpHosts.joinToString("\n") { "${it.label.ifBlank { it.host }} | ${it.user}@${it.host}:${it.port}" }
            "jump_set" -> {
                val kv = a.command.split("=", limit = 2)
                if (kv.size < 2) "格式应为 COMMAND: 字段=值（label/host/port/user/password）"
                else upsertJumpHost(a.target, kv[0], kv[1])
            }
            "jump_delete" -> deleteJumpHostByName(a.target)
            "jump_test" -> {
                val j = cfg.jumpHosts.firstOrNull { it.id == a.target || it.label == a.target || it.host == a.target }
                if (j == null) "找不到跳板机：${a.target}" else { testJumpHost(j, quiet = true); "已发起跳板机测试（结果写入诊断日志）" }
            }
            "jump_assign" -> updateEntryField(a.target, "jump", a.command)
            "config_set" -> {
                val kv = a.command.split("=", limit = 2)
                if (kv.size < 2) "格式应为 COMMAND: 字段=值"
                else updateEntryField(a.target, kv[0], kv[1])
            }
            "sftp_list" -> sftpOp(a.target, "list", a.command, "")
            "sftp_read" -> sftpOp(a.target, "read", a.command, "")
            "sftp_write" -> sftpOp(a.target, "write", a.command, a.content)
            "sftp_delete" -> sftpOp(a.target, "delete", a.command, "")
            "sftp_rename" -> {
                val parts = a.command.split("|", limit = 2)
                if (parts.size < 2) "格式：COMMAND: 原路径|新路径" else sftpOp(a.target, "rename", parts[0].trim(), parts[1].trim())
            }
            "sftp_chmod" -> {
                val parts = a.command.split("|", limit = 2)
                if (parts.size < 2) "格式：COMMAND: 路径|644" else sftpOp(a.target, "chmod", parts[0].trim(), parts[1].trim())
            }
            "sftp_mkdir" -> sftpOp(a.target, "mkdir", a.command, "")
            "sftp_download" -> sftpDownloadToPhone(a.target, a.command)
            "file_upload" -> {
                val parts = a.command.split("|", limit = 2)
                if (parts.size < 2) "格式：COMMAND: 本地路径|远端路径"
                else fileUploadFromPhone(parts[0].trim(), a.target, parts[1].trim())
            }
            "proxy_list" -> proxies.entries.joinToString("\n") { (k, v) ->
                "$k | 端口 ${v.port} | ${if (v.running) "运行中" else "未运行"} | 出口 ${v.exitIp ?: "-"}"
            }.ifBlank { "当前没有代理" }
            else -> "未知动作 ${a.kind}"
        }
    }.getOrElse { "执行失败：${it.message}" }

    /** 在本机（手机）上执行命令，用于排查网络等问题 */
    fun localRun(cmd: String, timeoutSec: Long = 60): String {
        if (cmd.isBlank()) return "命令为空"
        return try {
            val work = downloadDir()
            val pb = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .directory(work)
            // 补齐本地终端环境：HOME/TMPDIR 指向可写目录，PATH 用系统默认
            pb.environment().apply {
                put("HOME", work.absolutePath)
                put("TMPDIR", getApplication<Application>().cacheDir.absolutePath)
                putIfAbsent("PATH", "/system/bin:/system/xbin:/vendor/bin")
            }
            val p = pb.start()
            val out = StringBuilder()
            val t = Thread {
                runCatching {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(out) { if (out.length < 8000) out.append(line).append('\n') }
                    }
                }
            }
            t.start()
            val finished = p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) { p.destroy(); t.join(500); return "[超时 ${timeoutSec}s]\n" + synchronized(out) { out.toString() } }
            t.join(800)
            synchronized(out) { out.toString().ifBlank { "(无输出)" } }
        } catch (t: Throwable) {
            "执行失败：${t.message}"
        }
    }

    /** SFTP 操作（无 shell 的空间只能走这个） */
    private fun sftpOp(target: String, op: String, path: String, content: String): String {
        val app = getApplication<Application>()
        val m = findMachine(target)
        if (m == null) return "找不到机器：$target"
        val e = entry(m.id.substringBefore("|")) ?: return "找不到账号配置"
        if (e.host.isBlank()) return "该机器未配置 SSH/SFTP 地址"
        val engine = SshEngine(app)
        return try {
            engine.connectPlain(e.host, e.port, e.user, sshPasswordOf(e))
            val p = path.ifBlank { "/" }
            when (op) {
                "list" -> engine.sftpList(p)
                "read" -> engine.sftpRead(p)
                "write" -> engine.sftpWrite(p, content)
                "delete" -> engine.sftpDelete(p)
                "mkdir" -> { engine.sftpMkdir(p); "已创建目录 $p" }
                "rename" -> { engine.sftpRename(p, content); "已重命名 $p → $content" }
                "chmod" -> engine.sftpChmod(p, content.toIntOrNull(8) ?: 644)
                else -> "未知操作 $op"
            }
        } catch (t: Throwable) {
            diag("SFTP 失败 ${t.javaClass.simpleName}: ${t.message} @${op} ${path}")
            "SFTP 失败：${t.toAppError().userMessage()}"
        } finally {
            runCatching { engine.close() }
        }
    }

    /**
     * 把手机上的文件直传到机器（二进制安全，供 AI/MCP）
     *  - 只回传结果摘要，不回显内容，多大多小都行
     *  - 支持仅 SFTP 的机器
     */
    /** 把 AI 常见的 /Download/... 、/sdcard/... 映射成真实路径 */
    private fun normalizeLocalPath(p: String): String {
        val root = android.os.Environment.getExternalStorageDirectory().absolutePath // /storage/emulated/0
        return when {
            p.startsWith("/Download/") -> p.replaceFirst("/Download", "$root/Download")
            p.startsWith("/sdcard/") -> p.replaceFirst("/sdcard", root)
            else -> p
        }
    }

    fun fileUploadFromPhone(sourcePath: String, machine: String, remotePath: String): String {
        // 容错：AI 只给文件名时，自动在 agent/downloads/根目录里找
        var resolved = normalizeLocalPath(sourcePath)
        if (sourcePath.isNotBlank() && !sourcePath.contains("/")) {
            val candidates = listOf("agent", "downloads", "").map { java.io.File(subDir(it.ifBlank { "." }), sourcePath) }
            candidates.firstOrNull { it.isFile }?.let { resolved = it.absolutePath }
        }
        val f = java.io.File(resolved)
        if (!f.exists() || !f.isFile) {
            return "本地文件不存在：$sourcePath。附件默认在 ${subDir("agent").absolutePath}/，请使用绝对路径"
        }
        val m = findMachine(machine) ?: return "找不到机器：$machine"
        val e = entry(m.id.substringBefore("|")) ?: return "账号配置缺失"
        if (e.host.isBlank()) return "该机器未配置 SSH/SFTP 地址"
        val engine = SshEngine(getApplication())
        return try {
            engine.connectPlain(e.host, e.port, e.user, sshPasswordOf(e))
            val target = if (remotePath.endsWith("/")) remotePath + f.name else remotePath
            engine.sftpUpload(f, target)
            val size = f.length()
            val sha = runCatching {
                java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes())
                    .joinToString("") { "%02x".format(it) }.take(16)
            }.getOrDefault("-")
            "上传成功：${f.name} → $target（$size 字节，sha256:$sha…）"
        } catch (t: Throwable) {
            "上传失败：${t.toAppError().userMessage()}"
        } finally {
            runCatching { engine.close() }
        }
    }

    /** 机器之间复制文件/目录（供 AI/MCP） */
    private fun sftpCopy(fromMachine: String, fromPath: String, toMachine: String, toPath: String): String {
        if (fromPath.isBlank() || toPath.isBlank()) return "路径不能为空"
        val mf = findMachine(fromMachine) ?: return "找不到源机器：$fromMachine"
        val mt = findMachine(toMachine) ?: return "找不到目标机器：$toMachine"
        val ef = entry(mf.id.substringBefore("|")) ?: return "源账号配置缺失"
        val et = entry(mt.id.substringBefore("|")) ?: return "目标账号配置缺失"
        val engF = SshEngine(getApplication())
        val engT = SshEngine(getApplication())
        return try {
            engF.connectPlain(ef.host, ef.port, ef.user, sshPasswordOf(ef))
            engT.connectPlain(et.host, et.port, et.user, sshPasswordOf(et))
            val tmp = java.io.File(getApplication<Application>().cacheDir, "ai_copy_" + System.currentTimeMillis())
            tmp.mkdirs()
            var files = 0
            val isDir = runCatching { engF.sftpListDetailed(fromPath).isNotEmpty() }.getOrDefault(false)
            if (isDir) {
                fun down(remoteDir: String, localDir: java.io.File) {
                    localDir.mkdirs()
                    engF.sftpListDetailed(remoteDir).forEach { e ->
                        if (e.name == "." || e.name == "..") return@forEach
                        val rp = if (remoteDir.endsWith("/")) remoteDir + e.name else "$remoteDir/${e.name}"
                        val lp = java.io.File(localDir, e.name)
                        if (e.isDir) down(rp, lp) else { engF.sftpDownload(rp, lp); files++ }
                    }
                }
                down(fromPath, tmp)
                fun up(dir: java.io.File, remoteDir: String) {
                    runCatching { engT.sftpMkdir(remoteDir) }
                    dir.listFiles()?.forEach { f ->
                        if (f.isDirectory) up(f, if (remoteDir.endsWith("/")) remoteDir + f.name else "$remoteDir/${f.name}")
                        else engT.sftpUpload(f, if (remoteDir.endsWith("/")) remoteDir + f.name else "$remoteDir/${f.name}")
                    }
                }
                up(tmp, toPath)
            } else {
                val f = java.io.File(tmp, fromPath.substringAfterLast('/'))
                engF.sftpDownload(fromPath, f)
                engT.sftpUpload(f, toPath)
                files = 1
            }
            tmp.deleteRecursively()
            "已复制 $files 个文件：$fromMachine:$fromPath → $toMachine:$toPath"
        } catch (t: Throwable) {
            "复制失败：${t.message}"
        } finally {
            runCatching { engF.close() }; runCatching { engT.close() }
        }
    }

    /** 把远端文件下载到手机 /Download/muyun（供 AI/MCP） */
    private fun sftpDownloadToPhone(target: String, remotePath: String): String {
        if (remotePath.isBlank()) return "路径为空"
        val m = findMachine(target) ?: return "找不到机器：$target"
        val e = entry(m.id.substringBefore("|")) ?: return "找不到账号配置"
        if (e.host.isBlank()) return "该机器未配置 SSH 地址"
        val engine = SshEngine(getApplication())
        return try {
            engine.connectPlain(e.host, e.port, e.user, sshPasswordOf(e))
            val name = remotePath.substringAfterLast('/').ifBlank { "download.bin" }
            val f = java.io.File(downloadDir(), name)
            engine.sftpDownload(remotePath, f)
            "已下载到 ${f.absolutePath}（${f.length() / 1024} KB）"
        } catch (t: Throwable) {
            "下载失败：${t.message}"
        } finally {
            runCatching { engine.close() }
        }
    }

    /** 在指定机器上执行一条命令（用于 AI） */
    private fun sshRun(target: String, command: String): String {
        if (command.isBlank()) return "命令为空"
        val app = getApplication<Application>()
        val engine = SshEngine(app)
        return try {
            val boxEntry = cfg.entries.firstOrNull { it.type == "instabox" || it.type == "ssh" }
            val m = findMachine(target)
            val c = findSpace(target)
            when {
                m != null -> {
                    val e = entry(m.id.substringBefore("|")) ?: boxEntry ?: return "没有可用的 SSH 配置"
                    if (e.host.isBlank()) return "该机器未配置 SSH 地址：${m.name}"
                    engine.connectPlain(e.host, e.port, e.user, sshPasswordOf(e))
                    engine.exec(command, 30000)
                }
                c != null -> {
                    val entryId = c.name.substringBefore("|")
                    val token = entry(entryId)?.secret ?: ""
                    engine.connectCodespace(c.name.substringAfter("|"), token, Keys.ensure(app))
                    engine.exec(command, 30000)
                }
                else -> "找不到机器：$target"
            }
        } catch (t: Throwable) {
            val hint = t.toAppError().userMessage()
            "执行失败：$hint（${t.javaClass.simpleName}）"
        } finally {
            runCatching { engine.close() }
        }
    }

    // ---------------- 代理 ----------------
    fun proxyPortOf(id: String): Int {
        val sp = getApplication<Application>().getSharedPreferences("clouddeck", android.content.Context.MODE_PRIVATE)
        return sp.getInt("port_$id", 4129)
    }

    fun setProxyPort(id: String, port: Int) {
        getApplication<Application>().getSharedPreferences("clouddeck", android.content.Context.MODE_PRIVATE)
            .edit().putInt("port_$id", port.coerceIn(1024, 65535)).apply()
    }

    fun isProxyRunning(id: String) = proxies[id]?.running == true

    fun stopProxy(id: String) {
        val rt = runtimes.remove(id)
        proxies = proxies - id
        viewModelScope.launch(Dispatchers.IO) {
            rt?.let { (en, sk) ->
                runCatching { sk.stop() }
                runCatching { en.close() }
            }
            maybeStopService()
        }
    }

    /** 没有代理、没有终端、也没开保活时，撤掉前台通知 */
    /** 统一同步前台服务：代理 / 终端 / MCP / 手动保活 任一存在就需要 */
    private fun syncService() {
        val need = keepAlive || runtimes.isNotEmpty() ||
            termSession.anyAlive() || mcpRunning
        ProxyService.needed = need
        val app = getApplication<Application>()
        runCatching {
            if (need) app.startForegroundService(android.content.Intent(app, ProxyService::class.java))
            else app.stopService(android.content.Intent(app, ProxyService::class.java))
        }
    }

    @Deprecated("use syncService")
    private fun maybeStopService() = syncService()

    private fun proxyPortPrefs() = getApplication<Application>()
        .getSharedPreferences("clouddeck", android.content.Context.MODE_PRIVATE)

    fun rememberProxyPort(id: String, port: Int) {
        proxyPortPrefs().edit().putInt("proxyPort_$id", port).apply()
    }

    fun savedProxyPort(id: String): Int =
        proxyPortPrefs().getInt("proxyPort_$id", 4129)

    fun proxyActualPort(id: String): Int? = proxies[id]?.takeIf { it.running }?.port

    fun startProxy(id: String, name: String, isCodespace: Boolean, rawName: String, wantPort: Int? = null) {
        if (isProxyRunning(id)) return
        val app = getApplication<Application>()
        var port = (wantPort ?: savedProxyPort(id)).coerceIn(1024, 65535)
        // 端口冲突：自动尝试后续端口（最多 20 个），并记录到天机录
        fun portFree(p: Int) = runCatching {
            java.net.ServerSocket(p, 1, java.net.InetAddress.getByName("127.0.0.1")).close()
        }.isSuccess
        if (!portFree(port)) {
            val wanted = port
            val alt = (1..20).map { port + it }.firstOrNull { it <= 65535 && portFree(it) }
            if (alt == null) {
                proxies = proxies + (id to ProxyInfo(wanted, false, "失败", error = "端口 $wanted 被占用，且找不到可用端口"))
                diag("端口 $wanted 被占用，自动换端口失败")
                return
            }
            diag("端口 $wanted 被占用，自动改用 $alt")
            message = "端口 $wanted 被占用，已自动改用 $alt"
            port = alt
        }
        rememberProxyPort(id, port)
        val boxMachine = machines.firstOrNull { it.id == id }
        val running = if (isCodespace)
            spaces.firstOrNull { it.name == id }?.state?.equals("Available", true) == true
        else if (boxMachine != null && (!boxMachine.canPower || boxMachine.state.equals("unknown", true) ||
                boxMachine.state.equals("NotFound", true))) true   // 未知状态不阻塞
        else boxMachine?.state?.equals("running", true) == true
        if (!running) {
            proxies = proxies + (id to ProxyInfo(port, false, "失败", error = "机器未运行，请先开机"))
            return
        }
        proxies = proxies + (id to ProxyInfo(port, false, "连接中…"))
        viewModelScope.launch(Dispatchers.IO) {
            val engine = SshEngine(app)
            try {
                if (isCodespace) {
                    val entryId = id.substringBefore("|")
                    val token = entry(entryId)?.secret ?: ""
                    engine.connectCodespace(rawName, token, Keys.ensure(app))
                } else {
                    val entryId = id.substringBefore("|")
                    val e = entry(entryId) ?: error("账号不存在")
                    engine.connectPlain(e.host, e.port, e.user, sshPasswordOf(e))
                }
                val socks = Socks5Server(port, engine)
                socks.start()
                runtimes[id] = engine to socks
                syncService()
                val ip = runCatching { exitIp(port) }.getOrNull()
                proxies = proxies + (id to ProxyInfo(port, true, "运行中", exitIp = ip))
            } catch (t: Throwable) {
                runCatching { engine.close() }
                proxies = proxies + (id to ProxyInfo(port, false, "失败", error = t.message))
            }
        }
    }

    /** 测延迟 + 下行速度（走该 SOCKS5） */
    fun testProxy(id: String) {
        val info = proxies[id] ?: return
        viewModelScope.launch {
            val r = com.muyun.app.feature.proxy.ProxyProbe.measure(info.port)
            proxies = proxies + (id to info.copy(
                exitIp = r.exitIp.ifBlank { info.exitIp },
                latencyMs = r.latencyMs,
                speedMbps = r.speedMbps,
            ))
            diag("代理测速 $id: 出口=${r.exitIp} 延迟=${r.latencyMs}ms 速度=${"%.1f".format(r.speedMbps)}Mbps")
        }
    }

    private fun exitIp(port: Int): String {
        val p = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port))
        val c = okhttp3.OkHttpClient.Builder().proxy(p)
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
        c.newCall(okhttp3.Request.Builder().url("https://api.ipify.org").build()).execute().use {
            return it.body?.string()?.trim().orEmpty()
        }
    }



    /**
     * 状态 URL：
     *  - 返回体里有 "current_state"（Pterodactyl）→ 用它
     *  - 有 "status" / "state" → 用它
     *  - 否则 HTTP 200 视为在线
     */


    private fun closeForMachine(id: String, codespace: String?) {
        stopProxy(id)
        terms.filter { it.machineId == id || (codespace != null && it.machineId == codespace) }
            .forEach { closeTerm(it.id) }
    }

    // ---------------- 测试 ----------------
    fun testEntry(e: Entry) {
        viewModelScope.launch {
            message = when (e.type) {
                "instabox" -> runCatching {
                    val cl = InstaboxClient(e.secret, e.baseUrl.ifBlank { InstaboxClient.DEFAULT_BASE })
                    val list = cl.machines()
                    val u = runCatching { cl.usage() }.getOrNull()
                    buildString {
                        append("instabox 连接成功 ✅\n")
                        append("机器 ${list.size} 台：\n")
                        list.forEach { append("· ${it.name} — ${stateText(it.state)}\n") }
                        if (e.host.isBlank() || e.user.isBlank()) {
                            append("\n⚠ SSH 未配置完整（Host/用户为空），终端与代理不可用\n")
                            append("　请点「编辑」补上：ssh.instabox.sh / 端口 2222 / 用户名 ibssh_xxx")
                        } else {
                            append("\nSSH：${e.user}@${e.host}:${e.port} ✅")
                        }
                        if (u != null) {
                            append("\n用量（实时）\n")
                            append("· 免费时长 ${"%.1f".format(u.freeSandboxHours)} h\n")
                            append("· 本月 vCPU ${"%.1f".format(u.vcpuHours)} h / 内存 ${"%.1f".format(u.memGiBHours)} GiB·h\n")
                            append("· 成本 $${"%.2f".format(u.costUSD)}\n")
                            append("· 机器 ${u.machines}/${u.maxMachines} · 快照 ${u.snapshots}/${u.maxSnapshots}")
                        }
                    }
                }.getOrElse { "instabox 连接失败 ❌\n${it.message}" }
                "github" -> runCatching {
                    val cl = CodespacesClient(e.secret)
                    val login = cl.login()
                    val list = cl.list()
                    val cal = java.util.Calendar.getInstance()
                    val u = runCatching {
                        cl.usageReport(login, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1)
                    }.getOrNull()
                    buildString {
                        append("GitHub 连接成功 ✅\n账号：$login\nCodespaces ${list.size} 台：\n")
                        list.forEach { append("· ${it.display} — ${stateText(it.state)}\n") }
                        if (u != null) {
                            append("\n用量（${u.monthLabel}，GitHub 实计）\n")
                            append("· compute ${"%.1f".format(u.coreHours)} core-h\n")
                            append("· 存储 ${"%.2f".format(u.storageGbMonth)} GB·月\n")
                            append("· 毛额 $${"%.2f".format(u.gross)} · 实付 $${"%.2f".format(u.net)}")
                        }
                    }
                }.getOrElse { "GitHub 连接失败 ❌\n${it.message}" }
                "aliyun" -> runCatching {
                    val kindName = if (e.aliyunKind == "swas") "轻量应用服务器" else "ECS"
                    val ecs = AliyunEcs(e.accessKeyId, e.secret, e.region, e.instanceId, e.aliyunKind)
                    val st = ecs.status()
                    val ssh = if (e.host.isNotBlank()) "\nSSH：${e.user}@${e.host}:${e.port}" else "\n⚠ 未填 SSH 地址"
                    buildString {
                        append("阿里云（$kindName）连接成功 ✅\n")
                        append("地域：${e.region}\n实例：${e.instanceId}\n")
                        if (st == "NotFound") {
                            append("状态：❓ 未找到该实例\n")
                            append("请确认：① 实例 ID 是否完整 ② 是 ECS 还是轻量应用服务器（类型选错会找不到）\n")
                        } else {
                            append("状态：${stateText(st)}\n")
                        }
                        append(ssh)
                    }
                }.getOrElse { "阿里云连接失败 ❌\n${it.message}" }
                else -> withContext(Dispatchers.IO) {
                    runCatching {
                        val eng = SshEngine(getApplication())
                        try {
                            eng.connectPlain(e.host, e.port, e.user, e.secret.takeIf { it.isNotBlank() })
                            val out = eng.exec("echo SSH_OK; whoami").trim()
                            "SSH 连接成功 ✅\n$out"
                        } finally { eng.close() }
                    }.getOrElse { "SSH 连接失败 ❌\n${it.message}" }
                }
            }
        }
    }

    /** 重置连接（不删配置）：关闭该账号/全部账号的 SSH 会话与代理，然后重新拉取 */
    fun resetConnections(entryId: String? = null) {
        val termIds = terms.filter { entryId == null || it.machineId.startsWith("$entryId|") }.map { it.id }
        termIds.forEach { closeTerm(it) }
        val proxyIds = proxies.keys.filter { entryId == null || it.startsWith("$entryId|") }
        proxyIds.forEach { stopProxy(it) }
        runCatching { java.security.Security.removeProvider("BC") }
        runCatching {
            java.security.Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
        }
        message = "已重置连接缓存" + if (entryId != null) "（单个账号）" else "（全部）"
        refresh()
    }

    /** 后台保活：常驻前台服务 */
    fun toggleKeepAlive() {
        keepAlive = !keepAlive
        viewModelScope.launch(Dispatchers.IO) { syncService() }
        message = if (keepAlive) "已开启后台保活（通知栏可见）" else "已关闭后台保活"
    }

    fun resetCsUsage() {
        getApplication<Application>().getSharedPreferences("clouddeck", android.content.Context.MODE_PRIVATE)
            .edit().putFloat("cs_coreh", 0f).putLong("cs_tick", 0L).apply()
        csReal = null
        message = "已重置用量统计"
    }
}
