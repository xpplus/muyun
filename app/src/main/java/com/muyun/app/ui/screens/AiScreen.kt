package com.muyun.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 灵智 · AI 助手页（阶段 F2c · ui/screens）
 *
 * 功能：多供应商模型选择、附件（落盘并告知路径）、导出、新对话、
 *      重生成、历史、状态提示（思考中/执行命令中 + 计时）。
 */
@Composable
fun AiTab(vm: MainVm) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scroll = rememberScrollState()
    var input by remember { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    // (文件名, 文本内容或 dataURL, 本地绝对路径)
    var attach by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val picked = uris.take(10).mapNotNull { u ->
            runCatching {
                val name = u.lastPathSegment?.substringAfterLast('/') ?: "file"
                val bytes = ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: return@runCatching null
                val isImage = name.lowercase().let {
                    it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp")
                }
                val isText = bytes.none { it == 0.toByte() } &&
                    runCatching { String(bytes, Charsets.UTF_8) }.isSuccess
                // 关键：落盘并记录真实路径，AI 用 file_upload 直传时不需要内容进上下文
                // 保存到 /Download/muyun/agent，供 AI 直接用 file_upload
                val dst = vm.saveAgentAttachment(name, bytes)
                val content = when {
                    isImage -> "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    isText -> String(bytes, Charsets.UTF_8)
                    else -> "[二进制文件 $name，${bytes.size / 1024} KB]"
                }
                Triple(name, content, dst.absolutePath)
            }.getOrNull()
        }
        attach = (attach + picked).take(10)
    }
    LaunchedEffect(vm.aiLog.size) { runCatching { scroll.animateScrollTo(scroll.maxValue) } }

    Column(Modifier.fillMaxSize()) {
        // 顶部第一行：模型 + 状态/停止
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChipBox(
                text = vm.cfg.aiModel.ifBlank { "选择模型" } +
                    if (vm.isVisionModel(vm.cfg.aiModel)) "  ◉" else "",
                onClick = { showModels = true }
            )
            Spacer(Modifier.weight(1f))
            if (vm.aiBusy) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    Text("⛔", color = Red, fontSize = 14.sp, modifier = Modifier.clickable { vm.stopAgent() })
                }
            }
        }

        // 状态单独一行，固定高度，绝不撑变形
        if (vm.aiBusy) {
            Text(
                "${vm.aiStatus} ${vm.aiElapsed}s",
                color = Accent, fontSize = 10.sp,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
        }

        // 第二行：导出 / 新对话 / 历史
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
            TextButton({
                val sb = StringBuilder()
                sb.append("# 牧云小助手 对话导出\n\n")
                vm.aiLog.forEach { m ->
                    sb.append("**").append(if (m.role == "user") "我" else if (m.role == "system") "执行结果" else "小助手")
                        .append("**:\n").append(m.content).append("\n\n")
                }
                val text = sb.toString()
                runCatching {
                    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("chat", text))
                }
                runCatching {
                    val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "牧云对话导出")
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                    }
                    ctx.startActivity(android.content.Intent.createChooser(i, "导出对话"))
                }
            }) { Text("导出", fontSize = 12.sp) }
            TextButton({ vm.newChat() }) { Text("新对话", fontSize = 12.sp) }
            TextButton({ vm.regenerate() }) { Text("重生成", fontSize = 12.sp) }
            TextButton({ showHistory = true }) { Text("历史", fontSize = 12.sp) }
        }

        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0B0E13))) {
            if (vm.aiLog.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("☁️", fontSize = 42.sp)
                    Text("牧云小助手", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("可以帮你开关机、在任意机器上执行命令\n· 把 instabox 关机\n· 在 xxx 上查磁盘占用\n· 现在哪几台在运行？",
                        color = Gray, fontSize = 12.sp)
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    vm.aiLog.forEachIndexed { idx, m -> ChatBubble(m, onDelete = { vm.deleteFrom(idx) }) }
                    if (vm.aiBusy) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Accent)
                            Spacer(Modifier.width(8.dp))
                            Text("思考中…", color = Gray, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (attach.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                attach.forEachIndexed { i, a ->
                    AssistChip(
                        onClick = { attach = attach.filterIndexed { j, _ -> j != i } },
                        label = { Text("📄 ${a.first} ✕", fontSize = 10.sp) }
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ filePicker.launch(arrayOf("*/*")) }) { Text("📎", fontSize = 16.sp) }
            OutlinedTextField(input, { input = it }, placeholder = { Text("跟小助手说点什么…", fontSize = 13.sp) },
                modifier = Modifier.weight(1f), maxLines = 4,
                shape = RoundedCornerShape(18.dp))
            Button({
                val names = attach.joinToString("、") { it.first }
                val texts = attach.filterNot { it.second.startsWith("data:image") }
                    .joinToString("\n\n") { a -> "【附件 ${a.first}】\n${a.second}" }
                // 告诉 AI 每个附件的真实路径，便于 file_upload / local_run 直接使用
                val paths = attach.joinToString("\n") { "附件：${it.first}\n真实绝对路径：${it.third}" } +
                    if (attach.isNotEmpty()) "\n（调用 file_upload 时必须使用上面的绝对路径，不要只写文件名）" else ""
                val images = if (vm.isVisionModel(vm.cfg.aiModel))
                    attach.filter { it.second.startsWith("data:image") }.map { it.second }
                else emptyList()
                val prompt = listOf(input.trim(), texts, paths).filter { it.isNotBlank() }.joinToString("\n\n")
                val display = listOf(input.trim(), if (names.isNotBlank()) "📎 $names" else "")
                    .filter { it.isNotBlank() }.joinToString("\n\n")
                if (prompt.isNotBlank() || images.isNotEmpty()) {
                    input = ""; attach = emptyList()
                    vm.askAgent(prompt.ifBlank { "请看附件" }, display, images)
                }
            }, enabled = !vm.aiBusy && (input.isNotBlank() || attach.isNotEmpty()),
                modifier = Modifier.height(48.dp), shape = RoundedCornerShape(18.dp)) { Text("发送") }
        }
    }

    if (showModels) ModelPickerDialog(vm, onClose = { showModels = false })
    if (showHistory) HistoryDialog(vm, onClose = { showHistory = false })
}

