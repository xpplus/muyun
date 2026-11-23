package com.muyun.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.UUID

internal val Bg = Color(0xFF0F1115)
internal val CardBg = Color(0xFF181C23)
internal val Accent = Color(0xFF4C8DFF)
internal val Green = Color(0xFF35C759)
internal val Amber = Color(0xFFFFB020)
internal val Gray = Color(0xFF8A93A3)
internal val Red = Color(0xFFFF5A5F)

fun fmtSize(bytes: Long): String = com.muyun.app.feature.files.FileOps.fmtSize(bytes)

fun stateText(s: String): String = when (s.lowercase()) {
    "running", "available" -> "运行中"
    "stopped", "shutdown" -> "已停止"
    "suspended" -> "已挂起"
    "starting", "provisioning", "queued", "creating", "awaiting" -> "启动中"
    "stopping", "shuttingdown" -> "停止中"
    "rebuilding", "updating", "exporting", "migrating" -> "处理中"
    "failed", "error", "unavailable" -> "异常"
    "unknown" -> "未知"
    "starting" -> "启动中"
    "stopping" -> "停止中"
    else -> s
}

fun stateColor(s: String): Color = when (s.lowercase()) {
    "running", "available" -> Green
    "suspended" -> Amber
    "stopped", "shutdown", "unknown", "" -> Gray
    "failed", "error", "unavailable" -> Red
    else -> Accent
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.muyun.app.core.Diag.init(this)
        com.muyun.app.core.CrashGuard.install(this)
        runCatching {
            java.security.Security.removeProvider("BC")
            java.security.Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
        }
        // Android 13+ 需要显式申请通知权限，否则前台服务通知不显示
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching {
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
                }
            }
        }
        enableEdgeToEdge()
        setContent { Theme { Home(viewModel()) } }
    }
}

@Composable
fun Theme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Accent, background = Bg, surface = CardBg,
        onPrimary = Color.White, onBackground = Color(0xFFE8ECF3), onSurface = Color(0xFFE8ECF3),
    ), content = content
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(vm: MainVm) {
    Scaffold(
        containerColor = Bg,
        topBar = {
            // 终端 / 小助手页不显示顶栏，给内容更多空间
            if (vm.tab != 1 && vm.tab != 2 && vm.tab != 3) {
                TopAppBar(
                    title = {
                        Column {
                            Text("牧云", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                            Text("万机归牧 · 一令通神", color = Gray, fontSize = 11.sp)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = Color.White)
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = CardBg) {
                NavigationBarItem(vm.tab == 0, { vm.tab = 0 }, icon = { Text("☁") }, label = { Text("万机", fontSize = 10.sp) })
                NavigationBarItem(vm.tab == 1, { vm.tab = 1 }, icon = { Text(">_") }, label = { Text("灵枢", fontSize = 10.sp) })
                NavigationBarItem(vm.tab == 2, { vm.tab = 2 }, icon = { Text("🤖") }, label = { Text("灵智", fontSize = 10.sp) })
                NavigationBarItem(vm.tab == 3, { vm.tab = 3 }, icon = { Text("🗂") }, label = { Text("须弥", fontSize = 10.sp) })
                NavigationBarItem(vm.tab == 4, { vm.tab = 4 }, icon = { Text("⚙") }, label = { Text("天道", fontSize = 10.sp) })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (vm.tab) {
                0 -> MachinesTab(vm)
                1 -> TerminalTab(vm)
                2 -> AiTab(vm)
                3 -> FileTab(vm)
                else -> ConfigTab(vm)
            }
        }
    }
    vm.errorDetail?.let { detail ->
        val ctx = androidx.compose.ui.platform.LocalContext.current
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            confirmButton = {
                TextButton({
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("error", detail))
                    vm.toast("已复制")
                }) { Text("复制") }
            },
            dismissButton = { TextButton({ vm.clearError() }) { Text("关闭") } },
            title = { Text("连接详情") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text(detail, color = Color(0xFFD8DEE9), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }

    vm.message?.let { m ->
        AlertDialog(
            onDismissRequest = { vm.clearMessage() },
            confirmButton = { TextButton({ vm.clearMessage() }) { Text("知道了") } },
            title = { Text("提示") },
            text = { Text(m) }
        )
    }
}

// ---------------- 云机 ----------------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MachinesTab(vm: MainVm) {
    var showOrder by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.cfg.entries.isEmpty()) {
            InfoCard("还没有服务器", "去「设置」页点「＋ 添加服务器」，先加 instabox / GitHub / SSH / HTTP API。")
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vm.orderedItems(), key = {
                when (it) { is Machine -> "m:" + it.id; is Cspace -> "c:" + it.name; else -> it.toString() }
            }) { row ->
                when (row) {
                    is Machine -> UnifiedCard(
                        name = row.name,
                        subtitle = if (row.vcpus > 0) "${row.vcpus} vCPU · ${row.memMiB / 1024} GiB"
                        else buildString {
                            append(if (row.canPower && row.canSsh) "云服务器" else if (row.canSsh) "SSH 主机" else "云主机")
                            vm.cfg.entries.firstOrNull { row.id.startsWith(it.id + "|") }?.let { e ->
                                if (e.host.isNotBlank()) append(" · ${e.user}@${e.host}:${e.port}")
                                if (e.type == "aliyun") {
                                    append(" · " + (if (e.aliyunKind == "swas") "轻量" else "ECS") + " · ${e.region}")
                                }
                            }
                        },
                        state = row.state,
                        pending = vm.pending[row.id],
                        onPower = { vm.power(row, if (row.state.equals("running", true) || row.state.equals("Running", true)) "stop" else "start") },
                        onSsh = { vm.tab = 1; vm.connect(row) },
                        onLongClick = { showOrder = true },
                        canPower = row.canPower,
                        canSsh = row.canSsh,
                        onRefresh = { vm.refreshMachine(row.id) },
                        refreshing = row.id in vm.refreshing,
                    )
                    is Cspace -> {
                        val up = row.state.equals("Available", true) || row.state.equals("Running", true)
                        UnifiedCard(
                            name = row.display,
                            subtitle = "${row.cpus} vCPU · ${row.memGb} GiB" + if (row.repo.isNotBlank()) " · ${row.repo}" else "",
                            state = row.state,
                            pending = vm.pending[row.name],
                            onPower = { vm.csPower(row, !up) },
                            onSsh = { vm.tab = 1; vm.connect(row) },
                            onLongClick = { showOrder = true },
                            onRefresh = { vm.refreshMachine(row.name) },
                            refreshing = row.name in vm.refreshing,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    if (showOrder) {
        val ordered = vm.orderedItems()
        AlertDialog(
            onDismissRequest = { showOrder = false },
            confirmButton = { TextButton({ showOrder = false }) { Text("完成") } },
            title = { Text("调整顺序") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ordered.forEachIndexed { idx, item ->
                        val id = when (item) { is Machine -> item.id; is Cspace -> item.name; else -> "" }
                        val nm = when (item) { is Machine -> item.name; is Cspace -> item.display; else -> id }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${idx + 1}. $nm", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton({ vm.moveMachine(id, true) }) { Text("↑") }
                            TextButton({ vm.moveMachine(id, false) }) { Text("↓") }
                        }
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UnifiedCard(name: String, subtitle: String, state: String, pending: String?,
                onPower: () -> Unit, onSsh: () -> Unit, onLongClick: () -> Unit = {},
                canPower: Boolean = true, canSsh: Boolean = true,
                onRefresh: () -> Unit = {}, refreshing: Boolean = false) {
    val up = state.equals("running", true) || state.equals("Available", true) || state.equals("Running", true)
    val busy = pending != null
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = {}, onLongClick = onLongClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(stateColor(state), RoundedCornerShape(50)))
                Spacer(Modifier.width(8.dp))
                Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(pending ?: stateText(state), color = stateColor(state), fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (refreshing) "停止" else "刷新",
                    color = if (refreshing) Red else Accent, fontSize = 11.sp,
                    modifier = Modifier.clickable { onRefresh() }
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Gray, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            if (canPower || canSsh) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canPower) {
                        Button(onPower, enabled = !busy, modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 6.dp)) {
                            Text(if (up) "关机" else "开机", fontSize = 13.sp)
                        }
                    }
                    if (canSsh) {
                        OutlinedButton(onSsh, enabled = !busy, modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 6.dp)) {
                            Text("SSH", fontSize = 13.sp)
                        }
                    }
                }
            } else {
                Text("仅文件操作（SFTP）· 可用小助手或 MCP 读写文件", color = Gray, fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun InfoCard(title: String, body: String) {
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(body, color = Gray, fontSize = 12.sp)
        }
    }
}

// ---------------- 终端 ----------------
@Composable
fun ModelPickerDialog(vm: MainVm, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = vm.aiModels.filter { q.isBlank() || it.contains(q, true) }
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClose) { Text("关闭") } },
        title = { Text("选择模型（已记录 ${vm.aiModels.size} 个）") },
        text = {
            Column(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(q, { q = it }, placeholder = { Text("搜索模型…", fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (vm.aiModels.isEmpty()) {
                    Text("还没有模型记录，去「设置 → 牧云小助手」点「测试并拉取模型」", color = Gray, fontSize = 12.sp)
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    list.forEach { id ->
                        Row(Modifier.fillMaxWidth().clickable {
                            vm.saveAi(vm.cfg.aiBaseUrl, id, vm.cfg.aiKey); onClose()
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(id, color = if (id == vm.cfg.aiModel) Accent else Color(0xFFDCE3F0),
                                fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
                                Text("◉ 视觉",
                                    color = if (vm.isVisionModel(id)) Green else Gray,
                                    fontSize = 12.sp,
                                    modifier = Modifier.clickable { vm.toggleVisionModel(id) })
                            }
                            Box(Modifier.width(18.dp), contentAlignment = Alignment.CenterEnd) {
                                if (id == vm.cfg.aiModel) Text("✓", color = Accent, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun HistoryDialog(vm: MainVm, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClose) { Text("关闭") } },
        title = { Text("历史对话（${vm.chatSessions.size}）") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (vm.chatSessions.isEmpty()) Text("还没有记录", color = Gray, fontSize = 12.sp)
                vm.chatSessions.forEach { cs ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { vm.openChat(cs.id); onClose() }) {
                            Text(cs.title, color = Color.White, fontSize = 13.sp)
                            Text("${cs.msgs.size} 条 · " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(cs.at)), color = Gray, fontSize = 11.sp)
                        }
                        TextButton({ vm.deleteChat(cs.id) }) { Text("删除", color = Red, fontSize = 11.sp) }
                    }
                    HorizontalDivider(color = Color(0xFF232A38))
                }
            }
        }
    )
}

// ---------------- 代理 ----------------

@Composable
fun ProxyCard(name: String, state: ProxyInfo?, portInit: Int, running: Boolean, onToggle: (Int) -> Unit, onTest: () -> Unit) {
    var port by remember(portInit) { mutableStateOf(portInit.toString()) }
    Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(if (running) Green else Gray, RoundedCornerShape(50)))
                Spacer(Modifier.width(8.dp))
                Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(
                    when { state?.error != null -> "失败"; running -> "运行中"; state != null -> state.phase; else -> "未开启" },
                    color = if (running) Green else if (state?.error != null) Red else Gray, fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(port, { port = it.filter { ch -> ch.isDigit() }.take(5) },
                    label = { Text("端口", fontSize = 11.sp) }, singleLine = true, enabled = !running,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(110.dp))
                Button({ onToggle(port.toIntOrNull() ?: 4129) }, Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)) {
                    Text(if (running) "关闭代理" else "开启代理", fontSize = 13.sp)
                }
            }
            state?.exitIp?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp)); Text("出口 IP：$it", color = Gray, fontSize = 11.sp)
            }
            if (running) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    state?.latencyMs?.let { Text("延迟 ${it} ms", color = Gray, fontSize = 11.sp) }
                    state?.speedMbps?.let { Text("下行 %.1f MB/s".format(it), color = Gray, fontSize = 11.sp) }
                    TextButton({ onTest() }) { Text("测速", fontSize = 12.sp) }
                }
            }
            state?.error?.let { Spacer(Modifier.height(4.dp)); Text(it, color = Red, fontSize = 11.sp) }
        }
    }
}

// ---------------- 设置 ----------------
