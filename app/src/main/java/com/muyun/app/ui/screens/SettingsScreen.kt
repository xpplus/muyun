package com.muyun.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

/**
 * 天道 · 设置页（阶段 F2d · ui/screens）
 *
 * 包含：
 *  - 万机契约（服务器列表 / 添加 / 编辑 / 测试 / 删除）
 *  - 万物密钥库（键值对/文本，三级可见性）
 *  - 灵智源（AI 供应商 + 模型 + 视觉记忆 + 跳板）
 *  - 幽径（代理）
 *  - 虚空跳板（跳板机管理）
 *  - 灵脉接口（MCP 服务）
 *  - 天机录（日志/诊断/权限）
 */
@Composable
fun ConfigTab(vm: MainVm) {
    var editing by remember { mutableStateOf<Entry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var showGlobal by remember { mutableStateOf(false) }
    var showEntries by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🗄 万机契约（${vm.cfg.entries.size}）", color = Color.White,
                            fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (showEntries) TextButton({ adding = true }) { Text("＋ 添加", fontSize = 11.sp) }
                        TextButton({ showEntries = !showEntries }) { Text(if (showEntries) "收起" else "展开", fontSize = 11.sp) }
                    }
                }
            }
            if (showEntries) items(vm.cfg.entries, key = { it.id }) { e ->
                EntryCard(e, onEdit = { editing = e }, onTest = { vm.testEntry(e) },
                    onDelete = { vm.deleteEntry(e.id) }, onReset = { vm.resetConnections(e.id) })
            }

            item { VaultCard(vm) }
            item { AiProvidersCard(vm) }
            item { ProxySettingsCard(vm) }
            item { JumpHostsCard(vm) }
            item { McpCard(vm) }
            item {
                OutlinedButton({ showGlobal = true }, Modifier.fillMaxWidth()) { Text("天道法则") }
            }
            item {
                var showHelp by remember { mutableStateOf(false) }
                OutlinedButton({ showHelp = true }, Modifier.fillMaxWidth()) { Text("使用帮助") }
                if (showHelp) HelpDialog { showHelp = false }
            }

            item {
                val ctx2 = androidx.compose.ui.platform.LocalContext.current
                var showCrash by remember { mutableStateOf(false) }
                var crashText by remember { mutableStateOf("") }

                fun buildReport(): String {
                    val crash = runCatching {
                        java.io.File(ctx2.filesDir, "crash.log").let { if (it.exists()) it.readText().takeLast(8000) else "(无)" }
                    }.getOrDefault("(无)")
                    val diag = if (vm.diagLog.isEmpty()) "(无)" else vm.diagLog.joinToString("\n")
                    return buildString {
                        append("牧云 诊断报告\n")
                        append("时间: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())).append("\n")
                        append("版本: ").append(
                            runCatching {
                                val pi = ctx2.packageManager.getPackageInfo(ctx2.packageName, 0)
                                pi.versionName
                            }.getOrDefault("?")
                        ).append("\n")
                        append("设备: ").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL)
                        append(" / Android ").append(android.os.Build.VERSION.RELEASE).append("\n\n")
                        append("===== 诊断日志 =====\n").append(diag).append("\n\n")
                        append("===== 崩溃日志 =====\n").append(crash)
                    }
                }

                OutlinedButton({
                    crashText = buildReport()
                    showCrash = true
                }, Modifier.fillMaxWidth()) { Text("天机录 · 日志与诊断") }

                if (showCrash) {
                    AlertDialog(
                        onDismissRequest = { showCrash = false },
                        confirmButton = {
                            TextButton({
                                runCatching {
                                    val f = java.io.File(ctx2.getExternalFilesDir(null) ?: ctx2.filesDir, "muyun-log.txt")
                                    f.writeText(crashText)
                                    vm.toast("已保存到：${f.absolutePath}")
                                }
                            }) { Text("保存文件") }
                        },
                        dismissButton = {
                            Row {
                                TextButton({
                                    val cm = ctx2.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("log", crashText))
                                    vm.toast("已复制到剪贴板")
                                }) { Text("复制") }
                                TextButton({
                                    runCatching {
                                        val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(android.content.Intent.EXTRA_SUBJECT, "牧云诊断报告")
                                            putExtra(android.content.Intent.EXTRA_TEXT, crashText)
                                        }
                                        ctx2.startActivity(android.content.Intent.createChooser(i, "分享日志"))
                                    }
                                }) { Text("分享") }
                                TextButton({ showCrash = false }) { Text("关闭") }
                            }
                        },
                        title = { Text("日志与诊断") },
                        text = {
                            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                                Text(crashText, color = Color(0xFFDCE3F0), fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    )
                }
            }
            item {
                var showDiag by remember { mutableStateOf(false) }
                OutlinedButton({ showDiag = true }, Modifier.fillMaxWidth()) { Text("查阅天机录") }
                if (showDiag) {
                    AlertDialog(
                        onDismissRequest = { showDiag = false },
                        confirmButton = {
                            Row {
                                TextButton({ vm.clearLogs() }) { Text("清空") }
                                TextButton({ showDiag = false }) { Text("关闭") }
                            }
                        },
                        title = { Text("天机录") },
                        text = {
                            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                                Text(
                                    if (vm.diagLog.isEmpty()) "(暂无)" else vm.diagLog.joinToString("\n"),
                                    color = Color(0xFFD8DEE9), fontSize = 10.sp, fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)); InfoCard("运行时自检", vm.ghCheck ?: "检测中…") }
            item {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
                ) { uri ->
                    if (uri != null) runCatching {
                        ctx.contentResolver.openOutputStream(uri)?.use {
                            it.write(vm.exportConfigJson().toByteArray())
                        }
                        vm.notify("配置已导出")
                    }.onFailure { vm.notify("导出失败：${it.message}") }
                }
                val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
                ) { uri ->
                    if (uri != null) runCatching {
                        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                        vm.notify(vm.importConfigJson(text))
                        vm.refreshAll()
                    }.onFailure { vm.notify("导入失败：${it.message}") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton({ exportLauncher.launch("muyun-config.json") }, Modifier.weight(1f)) {
                        Text("导出配置", fontSize = 12.sp)
                    }
                    OutlinedButton({ importLauncher.launch(arrayOf("application/json", "text/*")) }, Modifier.weight(1f)) {
                        Text("导入配置", fontSize = 12.sp)
                    }
                }
            }
            item { AboutCard() }
        }
    }

    if (adding) AddEntryDialog(vm = vm, onDismiss = { adding = false }) { e -> vm.saveEntry(e); adding = false }
    editing?.let { e -> AddEntryDialog(existing = e, vm = vm, onDismiss = { editing = null }) { vm.saveEntry(it); editing = null } }
    if (showGlobal) GlobalSettingsDialog(vm, onClose = { showGlobal = false })
}

@Composable
fun EntryCard(e: Entry, onEdit: () -> Unit, onTest: () -> Unit, onDelete: () -> Unit, onReset: () -> Unit) {
    val (icon, typeName) = when (e.type) {
        "github" -> "🐙" to "GitHub Codespaces"
        "instabox" -> "📦" to "instabox"
        "aliyun" -> "🟠" to "阿里云 ECS"
        "http", "custom" -> "🔗" to "自定义服务器"
        else -> "🖥" to "自定义 SSH"
    }
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(typeName + if (e.type == "ssh") " · ${e.host}:${e.port}" else "", color = Gray, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onTest, Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
                    Text("测试 / 用量", fontSize = 12.sp)
                }
                OutlinedButton(onEdit, Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
                    Text("编辑", fontSize = 12.sp)
                }
                OutlinedButton(onReset, Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
                    Text("重置连接", fontSize = 12.sp)
                }
                TextButton(onDelete) { Text("删除", color = Red, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun SecretField(value: String, onChange: (String) -> Unit, label: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        visualTransformation = if (show) androidx.compose.ui.text.input.VisualTransformation.None
                               else PasswordVisualTransformation(),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (show) "◉" else "◎", color = Color.White, fontSize = 20.sp,
                    modifier = Modifier.clickable { show = !show }.padding(end = 10.dp))
                Text("复制", color = Color.White, fontSize = 13.sp, modifier = Modifier.clickable {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
                })
            }
        }
    )
}

@Composable
fun JumpPicker(vm: MainVm?, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val hosts = vm?.cfg?.jumpHosts ?: emptyList()
    val cur = hosts.firstOrNull { it.id == value }
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) {
            Text("跳板机：" + (cur?.let { it.label.ifBlank { it.host } } ?: "不使用（直连）"), fontSize = 11.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("不使用（直连）", fontSize = 12.sp) }, onClick = { onChange(""); open = false })
            if (hosts.isEmpty()) {
                DropdownMenuItem(text = { Text("（还没有跳板机，去设置里添加）", fontSize = 11.sp) }, onClick = { open = false })
            }
            hosts.forEach { j ->
                DropdownMenuItem(
                    text = { Text("${j.label.ifBlank { j.host }}  ·  ${j.host}:${j.port}", fontSize = 12.sp) },
                    onClick = { onChange(j.id); open = false })
            }
        }
    }
}

/** 阿里云常用地域（RegionId → 显示名） */
val AliyunRegions = listOf(
    "cn-hangzhou" to "杭州", "cn-shanghai" to "上海", "cn-beijing" to "北京",
    "cn-shenzhen" to "深圳", "cn-guanzhou" to "广州", "cn-chengdu" to "成都",
    "cn-hongkong" to "香港", "ap-southeast-1" to "新加坡",
    "ap-southeast-2" to "悉尼", "ap-southeast-3" to "吉隆坡",
    "ap-southeast-5" to "雅加达", "ap-northeast-1" to "东京",
    "ap-northeast-2" to "韩国首尔", "ap-south-1" to "孟买",
    "us-east-1" to "美国弗吉尼亚", "us-west-1" to "美国硅谷",
    "eu-central-1" to "德国法兰克福", "eu-west-1" to "英国伦敦",
    "me-east-1" to "阿联酋迪拜", "cn-zhangjiakou" to "张家口",
    "cn-huhehaote" to "呼和浩特", "cn-wulanchabu" to "乌兰察布",
    "cn-heyuan" to "河源", "cn-guangzhou" to "广州",
)

@Composable
fun AliyunRegionPicker(value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(value, { onChange(it) }, label = { Text("地域 RegionId") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), trailingIcon = {
                TextButton({ open = true }) { Text("选择") }
            })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AliyunRegions.forEach { (id, name) ->
                DropdownMenuItem(text = { Text("$name  ($id)", fontSize = 12.sp) }, onClick = {
                    onChange(id); open = false
                })
            }
        }
    }
}

@Composable
fun ProxySettingsCard(vm: MainVm) {
    var expanded by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🌐 幽径", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    modifier = Modifier.weight(1f).clickable { expanded = !expanded })
                TextButton({ expanded = !expanded }) { Text(if (expanded) "收起" else "展开", fontSize = 11.sp) }
            }
            if (expanded) {
                Text("只看已开机设备 · 隧道走 SSH direct-tcpip", color = Gray, fontSize = 10.sp)
                @Composable
                fun proxyRow(
                    key: String, title: String, state: String, isCs: Boolean, raw: String
                ) {
                    val info = vm.proxies[key]
                    val running = info?.running == true
                    var portText by remember(key) { mutableStateOf(vm.savedProxyPort(key).toString()) }
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(title, color = Color.White, fontSize = 13.sp)
                                Text(
                                    state + " · " + (if (running) "端口 ${info?.port}" else "未开启"),
                                    color = Gray, fontSize = 10.sp)
                            }
                            OutlinedTextField(portText, { portText = it.filter { c -> c.isDigit() }.take(5) },
                                label = { Text("端口", fontSize = 9.sp) }, singleLine = true,
                                modifier = Modifier.width(78.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                            TextButton({
                                if (running) vm.stopProxy(key)
                                else vm.startProxy(key, title, isCs, raw, portText.toIntOrNull())
                            }) { Text(if (running) "关闭" else "开启", fontSize = 11.sp) }
                        }
                        if (running) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton({ vm.testProxy(key) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                                    Text("测速", fontSize = 11.sp)
                                }
                                Text(
                                    (info?.exitIp?.let { "出口 $it" } ?: "出口 -") +
                                        (info?.latencyMs?.let { " · ${it}ms" } ?: "") +
                                        (info?.speedMbps?.let { " · ${"%.1f".format(it)} Mbps" } ?: ""),
                                    color = if (info?.speedMbps != null) Green else Gray, fontSize = 10.sp)
                            }
                        }
                    }
                }
                vm.spaces.filter { c ->
                    val st = c.state.lowercase()
                    st == "available" || st == "starting" || st == "running"
                }.forEach { c ->
                    proxyRow(c.name, "☁ " + c.display, c.state, true, c.name.substringAfter("|"))
                }
                vm.machines.filter { it.canSsh && it.vcus_safe() }.forEach { m ->
                    proxyRow(m.id, m.name, m.state, false, m.id.substringAfter("|"))
                }
            }
        }
    }
}

private fun Machine.vcus_safe(): Boolean =
    vcpus == 0 || state.equals("running", true) || state.equals("starting", true)

@Composable
fun VaultCard(vm: MainVm) {
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<VaultItem?>(null) }
    var adding by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🗝 万物密钥库（${vm.cfg.vault.size}）", color = Color.White,
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    modifier = Modifier.weight(1f).clickable { expanded = !expanded })
                if (expanded) TextButton({ adding = true }) { Text("＋ 添加", fontSize = 12.sp) }
                TextButton({ expanded = !expanded }) { Text(if (expanded) "收起" else "展开", fontSize = 11.sp) }
            }
            if (expanded) {
                Text("存放 API Key、邮箱、密码等键值对；可见性决定 AI/MCP 能否读取", color = Gray, fontSize = 10.sp)
                vm.cfg.vault.forEach { v ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(v.name, color = Color.White, fontSize = 13.sp)
                            Text(
                                when (v.visibility) { "public" -> "公开"; "secret" -> "受保护（受开关控制）"; else -> "私有（仅 App）" } +
                                    " · ${v.pairs.size} 项", color = Gray, fontSize = 10.sp)
                        }
                        TextButton({ editing = v }) { Text("编辑", fontSize = 11.sp) }
                        TextButton({ vm.deleteVaultItem(v.id) }) { Text("删", color = Red, fontSize = 11.sp) }
                    }
                }
            }
        }
    }
    if (adding) VaultDialog(onDismiss = { adding = false }) { vm.saveVaultItem(it); adding = false }
    editing?.let { v -> VaultDialog(existing = v, onDismiss = { editing = null }) { vm.saveVaultItem(it); editing = null } }
}

@Composable
fun VaultDialog(existing: VaultItem? = null, onDismiss: () -> Unit, onSave: (VaultItem) -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var vis by remember { mutableStateOf(existing?.visibility ?: "secret") }
    val pairs = remember {
        mutableStateListOf<VaultPair>().apply {
            (existing?.pairs ?: listOf(VaultPair())).forEach { add(it) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onSave(VaultItem(
                    id = existing?.id ?: ("v_" + java.util.UUID.randomUUID().toString().take(8)),
                    name = name.trim(), pairs = pairs.filter { it.k.isNotBlank() },
                    visibility = vis, note = note.trim()
                ))
            }, enabled = name.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
        title = { Text(if (existing == null) "新建密钥条目" else "编辑密钥条目") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称（如 cloudflare）") }, singleLine = true)
                OutlinedTextField(note, { note = it }, label = { Text("备注（选填）") }, singleLine = true)
                Text("可见性", color = Gray, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("public" to "公开", "secret" to "受保护", "private" to "私有").forEach { (k, n) ->
                        FilterChip(selected = vis == k, onClick = { vis = k }, label = { Text(n, fontSize = 11.sp) })
                    }
                }
                Text("键值对", color = Gray, fontSize = 11.sp)
                pairs.forEachIndexed { i, p ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(p.k, { pairs[i] = p.copy(k = it) }, label = { Text("键") },
                            singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(p.v, { pairs[i] = p.copy(v = it) }, label = { Text("值") },
                            singleLine = true, modifier = Modifier.weight(1.3f))
                        Text("✕", color = Red, fontSize = 16.sp, modifier = Modifier.clickable { pairs.removeAt(i) })
                    }
                }
                OutlinedButton({ pairs.add(VaultPair()) }, Modifier.fillMaxWidth()) { Text("＋ 添加键值对") }
            }
        }
    )
}

@Composable
fun AiProvidersCard(vm: MainVm) {
    var editing by remember { mutableStateOf<AiProvider?>(null) }
    var adding by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🧠 灵智源（${vm.cfg.aiProviders.size}）", color = Color.White,
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    modifier = Modifier.weight(1f).clickable { expanded = !expanded })
                if (expanded) TextButton({ adding = true }) { Text("＋ 添加", fontSize = 12.sp) }
                TextButton({ expanded = !expanded }) { Text(if (expanded) "收起" else "展开", fontSize = 11.sp) }
            }
            if (expanded) {
            Text("可保存多个供应商，点「使用」切换；当前供应商可单独指定跳板", color = Gray, fontSize = 10.sp)
            vm.cfg.aiProviders.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text((if (vm.cfg.aiCurrentId == p.id) "✅ " else "") + p.label.ifBlank { p.baseUrl },
                            color = Color.White, fontSize = 13.sp)
                        Text(p.baseUrl.take(46) + if (p.model.isNotBlank()) " · ${p.model}" else "", color = Gray, fontSize = 10.sp)
                    }
                    TextButton({ vm.useAiProvider(p.id) }) { Text("使用", fontSize = 11.sp) }
                    TextButton({ editing = p }) { Text("编辑", fontSize = 11.sp) }
                    TextButton({ vm.deleteAiProvider(p.id) }) { Text("删", color = Red, fontSize = 11.sp) }
                }
            }
            }
        }
    }
    if (adding) AiProviderDialog(vm = vm, onDismiss = { adding = false }) { p ->
        vm.saveAiProvider(p)
        if (vm.cfg.aiCurrentId.isBlank()) vm.useAiProvider(p.id)
        adding = false
    }
    editing?.let { p -> AiProviderDialog(vm = vm, existing = p, onDismiss = { editing = null }) { np ->
        vm.saveAiProvider(np)
        if (vm.cfg.aiCurrentId == np.id) {
            vm.saveAiQuiet(np.baseUrl, np.model, np.key)
            vm.fetchModels()
        }
        editing = null
    } }
}

@Composable
fun AiProviderDialog(vm: MainVm? = null, existing: AiProvider? = null, onDismiss: () -> Unit, onSave: (AiProvider) -> Unit) {
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var base by remember { mutableStateOf(existing?.baseUrl ?: "") }
    var key by remember { mutableStateOf(existing?.key ?: "") }
    var model by remember { mutableStateOf(existing?.model ?: "") }
    var jumpId by remember { mutableStateOf(existing?.jumpId ?: "") }
    var showKey by remember { mutableStateOf(false) }
    var testMsg by remember { mutableStateOf("") }
    // 跟随全局状态实时更新（之前只显示一次"正在获取…"）
    val liveStatus = vm?.aiStatus ?: ""
    val liveModels = vm?.aiModels ?: emptyList()
    LaunchedEffect(liveStatus) { if (liveStatus.isNotBlank()) testMsg = liveStatus }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onSave(AiProvider(
                    id = existing?.id ?: ("ai_" + java.util.UUID.randomUUID().toString().take(8)),
                    label = label.trim(), baseUrl = base.trim(), key = key.trim(), model = model.trim(),
                    jumpId = jumpId
                ))
            }, enabled = base.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
        title = { Text(if (existing == null) "结契新灵智源" else "结契灵智源") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("备注名（如 硅基流动 / AMD）") }, singleLine = true)
                OutlinedTextField(base, { base = it }, label = { Text("API Base（OpenAI 兼容）") }, singleLine = true)
                OutlinedTextField(key, { key = it }, label = { Text("API Key") }, singleLine = true,
                    visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Text(if (showKey) "◉" else "◎", color = Color.White, fontSize = 20.sp,
                            modifier = Modifier.clickable { showKey = !showKey })
                    })
                OutlinedTextField(model, { model = it }, label = { Text("默认模型（可留空）") }, singleLine = true)
                if (vm != null) JumpPicker(vm, jumpId) { jumpId = it }

                if (vm != null) {
                    OutlinedButton({
                        testMsg = "测试中…"
                        vm.saveAiProvider(AiProvider(existing?.id ?: "ai_tmp", label, base, key, model, jumpId))
                        vm.saveAiQuiet(base, model, key)
                        vm.fetchModels()
                        testMsg = "正在获取模型…"
                        vm.aiModels.firstOrNull()?.let { if (model.isBlank()) model = it }
                    }, Modifier.fillMaxWidth()) { Text("通灵 · 探测模型", fontSize = 12.sp) }
                    if (testMsg.isNotBlank()) Text(testMsg, color = Accent, fontSize = 10.sp)
                    if (liveModels.isNotEmpty()) {
                        Text("可用模型（${liveModels.size}）：${liveModels.take(8).joinToString("、")}" +
                            if (liveModels.size > 8) " …" else "", color = Gray, fontSize = 10.sp)
                        if (model.isBlank()) {
                            Text("（已自动填入第一个）", color = Gray, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun JumpHostsCard(vm: MainVm) {
    var editing by remember { mutableStateOf<JumpHost?>(null) }
    var adding by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🌉 虚空跳板（${vm.cfg.jumpHosts.size}）", color = Color.White,
                    fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    modifier = Modifier.weight(1f).clickable { expanded = !expanded })
                if (expanded) TextButton({ adding = true }) { Text("＋ 添加", fontSize = 12.sp) }
                TextButton({ expanded = !expanded }) { Text(if (expanded) "收起" else "展开", fontSize = 11.sp) }
            }
            if (expanded) {
            Text("给某个云单独指定跳板：该云的 API 请求会经这台 SSH 主机的 SOCKS5 转发",
                color = Gray, fontSize = 10.sp)
            vm.cfg.jumpHosts.forEach { j ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(j.label.ifBlank { j.host }, color = Color.White, fontSize = 13.sp)
                        Text("${j.user}@${j.host}:${j.port}", color = Gray, fontSize = 11.sp)
                    }
                    TextButton({ vm.testJumpHost(j) }) { Text("测试", fontSize = 11.sp) }
                    TextButton({ editing = j }) { Text("编辑", fontSize = 11.sp) }
                    TextButton({ vm.deleteJumpHost(j.id) }) { Text("删", color = Red, fontSize = 11.sp) }
                }
            }
            }
        }
    }
    if (adding) JumpHostDialog(onDismiss = { adding = false }) { vm.saveJumpHost(it); adding = false }
    editing?.let { j -> JumpHostDialog(existing = j, onDismiss = { editing = null }) { vm.saveJumpHost(it); editing = null } }
}

@Composable
fun JumpHostDialog(existing: JumpHost? = null, onDismiss: () -> Unit, onSave: (JumpHost) -> Unit) {
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(existing?.user ?: "root") }
    var pass by remember { mutableStateOf(existing?.password ?: "") }
    var jumpNote by remember { mutableStateOf(existing?.agentNote ?: "") }
    var jumpVis by remember { mutableStateOf(existing?.visibility ?: "protected") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    JumpHost(
                        id = existing?.id ?: ("j_" + java.util.UUID.randomUUID().toString().take(8)),
                        label = label.trim(), host = host.trim(),
                        port = port.toIntOrNull() ?: 22, user = user.trim(), password = pass,
                        agentNote = jumpNote.trim(), visibility = jumpVis
                    )
                )
            }, enabled = host.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
        title = { Text(if (existing == null) "添加跳板机" else "编辑跳板机") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("备注名") }, singleLine = true)
                OutlinedTextField(host, { host = it }, label = { Text("主机 / IP") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(user, { user = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.weight(2f))
                    OutlinedTextField(port, { port = it }, label = { Text("端口") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                }
                // 可见性：与服务器/密钥库一致的三档
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("public" to "公开", "protected" to "受保护", "private" to "私有").forEach { (k, n) ->
                        FilterChip(selected = jumpVis == k, onClick = { jumpVis = k },
                            label = { Text(n, fontSize = 11.sp) })
                    }
                }
                Text(
                    when (jumpVis) {
                        "protected" -> "受保护：所有信息打码（是否明文由暴露开关控制）"
                        "private" -> "私有：仅软件内可见，AI 看不到也不能操作"
                        else -> "公开：所有信息暴露（含跳板密码）"
                    }, color = Gray, fontSize = 10.sp
                )
                OutlinedTextField(jumpNote, { jumpNote = it },
                    label = { Text("给 AI 的说明（如：与代理同机，少轮询）") }, singleLine = true)
                OutlinedTextField(pass, { pass = it }, label = { Text("密码（留空走免密/密钥）") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
            }
        }
    )
}

@Composable
fun McpCard(vm: MainVm) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var port by remember { mutableStateOf(vm.mcpPort.toString()) }
    var token by remember { mutableStateOf(vm.mcpToken) }
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔌 灵脉接口 · MCP", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    modifier = Modifier.weight(1f).clickable { expanded = !expanded })
                Text(if (vm.mcpRunning) "运行中" else "已停止", color = if (vm.mcpRunning) Green else Gray, fontSize = 11.sp)
                TextButton({ expanded = !expanded }) { Text(if (expanded) "收起" else "展开", fontSize = 11.sp) }
            }
            if (expanded) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(port, { port = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text("端口", fontSize = 11.sp) }, singleLine = true, enabled = !vm.mcpRunning,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(100.dp))
                var confirmNoAuth by remember { mutableStateOf(false) }
                Button({
                    if (!vm.mcpRunning && token.isBlank()) confirmNoAuth = true
                    else vm.toggleMcp(port.toIntOrNull() ?: 8765)
                }, Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                    Text(if (vm.mcpRunning) "停止服务" else "启动服务", fontSize = 13.sp)
                }
                if (confirmNoAuth) {
                    AlertDialog(
                        onDismissRequest = { confirmNoAuth = false },
                        title = { Text("未设置 Token", fontSize = 15.sp) },
                        text = { Text("关闭鉴权后，本机任何 App 都可以调用 MCP 执行命令、读写文件。确定要继续吗？", fontSize = 12.sp) },
                        confirmButton = {
                            TextButton({ confirmNoAuth = false; vm.toggleMcp(port.toIntOrNull() ?: 8765) }) {
                                Text("仍然启动", color = Red)
                            }
                        },
                        dismissButton = { TextButton({ confirmNoAuth = false }) { Text("取消") } }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Token（可自定义）", fontSize = 11.sp) }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton({
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("token", token))
                        vm.toast("Token 已复制")
                    }) { Text("复制", fontSize = 11.sp) }
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.updateMcpToken(token) }, Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
                    Text("保存 Token", fontSize = 12.sp)
                }
                OutlinedButton({ token = vm.regenerateMcpToken() }, Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
                    Text("随机生成", fontSize = 12.sp)
                }
            }
            if (vm.mcpRunning) {
                Spacer(Modifier.height(6.dp))
                Text("POST http://127.0.0.1:${vm.mcpPort}/mcp", color = Gray, fontSize = 11.sp)
                Text("Authorization: Bearer $token", color = Gray, fontSize = 11.sp)
                Text("工具：list_machines / power / ssh_exec / proxy_start / proxy_stop / proxy_list / get_logs / list_entries",
                    color = Gray, fontSize = 10.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text("仅监听本机回环地址，需 Token；供 MCP 客户端或调试脚本调用",
                color = Gray, fontSize = 10.sp)
            }
        }
    }
}

@Composable
fun AddEntryDialog(existing: Entry? = null, vm: MainVm? = null, onDismiss: () -> Unit, onSave: (Entry) -> Unit) {
    var type by remember { mutableStateOf(existing?.type ?: "") }
    var label by remember { mutableStateOf(existing?.label ?: "") }
    var secret by remember { mutableStateOf(existing?.secret ?: "") }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: InstaboxClient.DEFAULT_BASE) }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(existing?.user ?: "") }
    var startUrl by remember { mutableStateOf(existing?.startUrl ?: "") }
    var stopUrl by remember { mutableStateOf(existing?.stopUrl ?: "") }
    var headers by remember { mutableStateOf(existing?.headers ?: "") }
    var body by remember { mutableStateOf(existing?.body ?: "") }
    var statusUrl by remember { mutableStateOf(existing?.statusUrl ?: "") }
    var akId by remember { mutableStateOf(existing?.accessKeyId ?: "") }
    var region by remember { mutableStateOf(existing?.region ?: "") }
    var instanceId by remember { mutableStateOf(existing?.instanceId ?: "") }
    var sshPass by remember { mutableStateOf(existing?.sshPassword ?: "") }
    var akKind by remember { mutableStateOf(existing?.aliyunKind ?: "ecs") }
    var connMode by remember { mutableStateOf(existing?.connMode ?: "ssh") }
    var httpMethod by remember { mutableStateOf(existing?.httpMethod ?: "POST") }
    var bodyStart by remember { mutableStateOf(existing?.bodyStart ?: "") }
    var bodyStop by remember { mutableStateOf(existing?.bodyStop ?: "") }
    var jumpId by remember { mutableStateOf(existing?.jumpId ?: "") }
    var agentNote by remember { mutableStateOf(existing?.agentNote ?: "") }
    var visibility by remember { mutableStateOf(existing?.visibility ?: "protected") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    Entry(
                        id = existing?.id ?: "e_" + UUID.randomUUID().toString().take(8),
                        type = type,
                        label = label.ifBlank { if (type == "github") "GitHub" else if (type == "instabox") "instabox" else host },
                        secret = secret.trim(), baseUrl = baseUrl.trim(), host = host.trim(),
                        port = port.toIntOrNull() ?: 22, user = user.trim(),
                        startUrl = startUrl.trim(), stopUrl = stopUrl.trim(), headers = headers, body = body,
                        statusUrl = statusUrl.trim(),
                        accessKeyId = akId.trim(), region = region.trim(), instanceId = instanceId.trim(),
                        sshPassword = sshPass.trim(), aliyunKind = akKind, connMode = connMode, httpMethod = httpMethod, bodyStart = bodyStart, bodyStop = bodyStop, jumpId = jumpId,
                        agentNote = agentNote.trim(), visibility = visibility,
                    )
                )
            }, enabled = type.isNotBlank()) { Text("保存") }
        },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
        title = { Text(if (existing == null) "添加服务器" else "编辑服务器") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("类型", color = Gray, fontSize = 11.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("github" to "GitHub", "instabox" to "instabox", "aliyun" to "阿里云", "custom" to "自定义").forEach { (t, n) ->
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(n, fontSize = 11.sp) })
                    }
                }
                OutlinedTextField(label, { label = it }, label = { Text("名称（备注）") }, singleLine = true)
                when (type) {
                    "github" -> SecretField(secret, { secret = it }, "GitHub Token")
                    "instabox" -> {
                        SecretField(secret, { secret = it }, "instabox API Key")
                        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("API Base URL（选填）") }, singleLine = true)
                        Text("SSH（终端用，选填）", color = Gray, fontSize = 11.sp)
                        OutlinedTextField(host, { host = it }, label = { Text("SSH Host") }, singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(user, { user = it }, label = { Text("SSH 用户") }, singleLine = true, modifier = Modifier.weight(2f))
                            OutlinedTextField(port, { port = it }, label = { Text("端口") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        }
                    }
                    "aliyun" -> {
                        Text("阿里云产品类型（选错会查不到实例）", color = Accent, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = akKind == "ecs", onClick = { akKind = "ecs" },
                                label = { Text("ECS 云服务器", fontSize = 11.sp) })
                            FilterChip(selected = akKind == "swas", onClick = { akKind = "swas" },
                                label = { Text("轻量应用服务器", fontSize = 11.sp) })
                        }
                        Text("必填：RAM 访问密钥（建议只授 ECS/轻量 权限）", color = Gray, fontSize = 11.sp)
                        OutlinedTextField(akId, { akId = it }, label = { Text("AccessKey ID") }, singleLine = true)
                        SecretField(secret, { secret = it }, "AccessKey Secret")
                        AliyunRegionPicker(region) { region = it }
                        OutlinedTextField(instanceId, { instanceId = it },
                            label = { Text("实例 ID（i- 开头，如 i-2zeabc123）") }, singleLine = true)
                        Text("首尔 = ap-northeast-2 · 地域可在 ECS 控制台实例详情页查看",
                            color = Gray, fontSize = 10.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("SSH（选填，填了才能用终端/代理/小助手）", color = Gray, fontSize = 11.sp)
                        OutlinedTextField(host, { host = it }, label = { Text("公网 IP / 域名") }, singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(user, { user = it }, label = { Text("用户，如 root") }, singleLine = true, modifier = Modifier.weight(2f))
                            OutlinedTextField(port, { port = it }, label = { Text("端口") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        }
                        SecretField(sshPass, { sshPass = it }, "SSH 密码（选填）")
                    }
                    "custom" -> {
                        Text("连接方式", color = Accent, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = connMode == "ssh", onClick = { connMode = "ssh" },
                                label = { Text("SSH 终端", fontSize = 11.sp) })
                            FilterChip(selected = connMode == "sftp", onClick = { connMode = "sftp" },
                                label = { Text("仅 SFTP（无 shell）", fontSize = 11.sp) })
                        }
                        Text("SFTP 模式：不能开终端/代理，但小助手和 MCP 可以读写文件", color = Gray, fontSize = 10.sp)
                        Text("SSH / SFTP 连接参数", color = Gray, fontSize = 11.sp)
                        OutlinedTextField(host, { host = it }, label = { Text("Host") }, singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(user, { user = it }, label = { Text("用户") }, singleLine = true, modifier = Modifier.weight(2f))
                            OutlinedTextField(port, { port = it }, label = { Text("端口") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        }
                        SecretField(secret, { secret = it }, "SSH 密码（选填，留空走免密）")
                        Spacer(Modifier.height(4.dp))
                        Text("开关机接口（选填，填了才有开机/关机按钮）", color = Gray, fontSize = 11.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("请求方法", color = Gray, fontSize = 11.sp)
                            FilterChip(selected = httpMethod == "POST", onClick = { httpMethod = "POST" }, label = { Text("POST", fontSize = 11.sp) })
                            FilterChip(selected = httpMethod == "GET", onClick = { httpMethod = "GET" }, label = { Text("GET", fontSize = 11.sp) })
                        }
                        OutlinedTextField(statusUrl, { statusUrl = it }, label = { Text("状态 URL（选填，200=在线）") }, singleLine = true)
                        OutlinedTextField(startUrl, { startUrl = it }, label = { Text("开机 URL（选填）") }, singleLine = true)
                        OutlinedTextField(stopUrl, { stopUrl = it }, label = { Text("关机 URL（选填）") }, singleLine = true)
                        OutlinedTextField(headers, { headers = it }, label = { Text("Headers（每行 Key: Value）") }, minLines = 2)
                        OutlinedTextField(bodyStart, { bodyStart = it }, label = { Text("开机 Body（如 {\"signal\":\"start\"}）") }, singleLine = true)
                        OutlinedTextField(bodyStop, { bodyStop = it }, label = { Text("关机 Body（如 {\"signal\":\"stop\"}）") }, singleLine = true)
                        JumpPicker(vm, jumpId) { jumpId = it }
                    }
                }
                // 所有类型通用：给 AI 的说明 + 三级可见性
                OutlinedTextField(agentNote, { agentNote = it },
                    label = { Text("给 AI 的说明（如：生产环境，禁止重启）") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("public" to "公开", "protected" to "受保护", "private" to "私有").forEach { (k, n) ->
                        FilterChip(selected = visibility == k, onClick = { visibility = k },
                            label = { Text(n, fontSize = 11.sp) })
                    }
                }
                Text(
                    when (visibility) {
                        "protected" -> "受保护：所有信息打码（是否明文由暴露开关控制）"
                        "private" -> "私有：仅软件内可见，AI 看不到也不能操作"
                        else -> "公开：所有信息暴露（服务器 API 与密钥）"
                    }, color = Gray, fontSize = 10.sp
                )
            }
        }
    )
}

/** 使用帮助（阶段 1.0.12） */
@Composable
fun HelpDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClose) { Text("知道了") } },
        title = { Text("牧云 · 使用帮助", fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. 添加服务器", color = Accent, fontSize = 13.sp)
                Text("天道 → 万机契约 → ＋添加，选择类型后按提示填写。GitHub 只需 Token；instabox 只需 API Key；阿里云需要 AccessKey + 地域 + 实例 ID。", fontSize = 11.sp, color = Gray)
                Text("2. 连接终端", color = Accent, fontSize = 13.sp)
                Text("万机页点机器 → 灵枢打开终端；也可在灵枢点「＋连接」。机器人关闭时会自动开机并等待。", fontSize = 11.sp, color = Gray)
                Text("3. 文件传输", color = Accent, fontSize = 13.sp)
                Text("须弥页左右两栏各自选本机/云机，长按文件可复制到对栏、下载到本机、重命名、删除；下载目录 /Download/muyun/downloads。", fontSize = 11.sp, color = Gray)
                Text("4. AI 助手", color = Accent, fontSize = 13.sp)
                Text("天道 → 灵智源添加供应商（Base URL + Key），灵智页选择模型即可对话。发给 AI 的附件会保存在 /Download/muyun/agent。", fontSize = 11.sp, color = Gray)
                Text("5. 跳板与代理", color = Accent, fontSize = 13.sp)
                Text("虚空跳板添加跳板机；服务器编辑里选择跳板即可。幽径给单台云机开 SOCKS5 代理（端口可改，冲突会自动换）。", fontSize = 11.sp, color = Gray)
                Text("6. 可见性", color = Accent, fontSize = 13.sp)
                Text("公开：AI 看到全部；受保护：默认打码，打开暴露开关后明文；私有：仅软件内可见。", fontSize = 11.sp, color = Gray)
                Text("7. MCP", color = Accent, fontSize = 13.sp)
                Text("灵脉接口启动后，客户端连 http://127.0.0.1:4000/mcp；Token 留空表示不校验。可先调用 help 工具查看用法。", fontSize = 11.sp, color = Gray)
                Text("8. 常见问题", color = Accent, fontSize = 13.sp)
                Text("SFTP 连不上：NAS 需开启 SFTP 服务；阿里云查不到实例：确认产品类型/地域/实例 ID；代理卡：代理与跳板同机时会自动暂停轮询。", fontSize = 11.sp, color = Gray)
            }
        }
    )
}

/**
 * 关于（阶段 1.0.3）
 * 展示版本、设备、作者与快捷入口。
 */
@Composable
fun AboutCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val version = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
    }
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("ℹ️ 关于", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("牧云 v$version", color = Accent, fontSize = 13.sp)
            Text("作者：XP", color = Gray, fontSize = 11.sp)
            Text("https://github.com/xpplus/muyun", color = Accent, fontSize = 11.sp)
            Text("万机归牧 · 一令通神", color = Gray, fontSize = 10.sp)
        }
    }
}

@Composable
fun GlobalSettingsDialog(vm: MainVm, onClose: () -> Unit) {

    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            TextButton({
                vm.saveSettings(vm.cfg.csAllowance, vm.cfg.idlePollSeconds)
                onClose()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClose) { Text("取消") } },
        title = { Text("天道法则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.resetConnections(null) }, Modifier.fillMaxWidth()) { Text("清理全部连接缓存") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("受保护账号：向 AI 显示明文凭据", color = Color.White, fontSize = 13.sp)
                        Text("开启后 AI 能看到 Token/密码，便于排查；关闭则打码", color = Gray, fontSize = 10.sp)
                    }
                    Switch(checked = vm.cfg.exposeSecrets, onCheckedChange = { vm.setExposeSecrets(it) })
                }
                val granted = vm.hasAllFilesAccess()
                OutlinedButton({ vm.requestAllFilesAccess() }, Modifier.fillMaxWidth()) {
                    Text(if (granted) "所有文件访问权限：已授予 ✅" else "申请所有文件访问权限", fontSize = 12.sp)
                }
                OutlinedButton({ vm.requestIgnoreBattery() }, Modifier.fillMaxWidth()) {
                    Text("忽略电池优化（保活必需）", fontSize = 12.sp)
                }
                OutlinedButton({ vm.openAutostartSettings() }, Modifier.fillMaxWidth()) {
                    Text("自启动设置（需手动开启）", fontSize = 12.sp)
                }
                Button({ vm.toggleKeepAlive() }, Modifier.fillMaxWidth()) {
                    Text(if (vm.keepAlive) "关闭后台保活（当前已开启）" else "开启后台保活")
                }
                Text("开启后会常驻通知栏，SSH 与代理在切后台后不会被系统冻结",
                    color = Gray, fontSize = 11.sp)
            }
        }
    )
}
