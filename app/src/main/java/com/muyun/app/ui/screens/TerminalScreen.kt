package com.muyun.app

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 灵枢 · 终端页（阶段 F2b · ui/screens）
 *
 * 多标签 SSH 终端 + 本机终端。
 * 终端输出可长按选中复制；长行自动缩小字号。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalTab(vm: MainVm) {
    var pick by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(vm.termText) { runCatching { scroll.animateScrollTo(scroll.maxValue) } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            vm.terms.forEach { t ->
                FilterChip(
                    selected = t.id == vm.activeTermId,
                    onClick = { vm.selectTerm(t.id) },
                    label = { Text(t.title, fontSize = 11.sp) },
                    trailingIcon = { Text("×", color = Gray, modifier = Modifier.clickable { vm.closeTerm(t.id) }) },
                )
            }
            TextButton({ pick = true }) { Text("＋ 连接", fontSize = 13.sp) }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (vm.termState == "已连接") Green else Gray, RoundedCornerShape(50)))
            Spacer(Modifier.width(8.dp))
            Text(vm.termState, color = Gray, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton({ vm.clearTerm() }) { Text("清屏") }
            TextButton({ vm.disconnect() }) { Text("断开") }
        }

        Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF0B0E13))) {
            if (vm.termText.isEmpty()) {
                Text("点「＋ 连接」选一台机器；连上后下方输入框敲命令",
                    color = Gray, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center).padding(24.dp))
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(8.dp)) {
                    // 终端内容可长按选中复制；长行自动缩小
                    val longest = remember(vm.termText) { vm.termText.split('\n').maxOfOrNull { it.length } ?: 0 }
                    val fs = when { longest > 120 -> 8.sp; longest > 80 -> 9.5.sp; else -> 11.sp }
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(vm.termText, color = Color(0xFFD8DEE9), fontSize = fs,
                            fontFamily = FontFamily.Monospace, lineHeight = (fs.value * 1.45).sp)
                    }
                }
            }
        }

        var input by remember { mutableStateOf("") }
        var editingQuick by remember { mutableStateOf<String?>(null) }

        // 快捷命令：单击填入输入框，长按直接发送；末尾 + 可添加，长按 ✕ 删除
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            vm.cfg.quickCommands.forEach { cmd ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = CardBg,
                    modifier = Modifier.combinedClickable(
                        onClick = { input = cmd },
                        onLongClick = { editingQuick = cmd }
                    )
                ) {
                    Text(cmd, fontSize = 10.sp, maxLines = 1,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
            TextButton({ vm.addQuickCommand(input) }, enabled = input.isNotBlank()) {
                Text("＋快捷", fontSize = 10.sp)
            }
        }
        editingQuick?.let { old ->
            var text by remember(old) { mutableStateOf(old) }
            AlertDialog(
                onDismissRequest = { editingQuick = null },
                title = { Text("编辑快捷命令", fontSize = 15.sp) },
                text = { OutlinedTextField(text, { text = it }, label = { Text("命令") }, singleLine = true) },
                confirmButton = {
                    TextButton({ vm.updateQuickCommand(old, text); editingQuick = null }) { Text("保存") }
                },
                dismissButton = {
                    Row {
                        TextButton({ vm.removeQuickCommand(old); editingQuick = null }) { Text("删除", color = Red) }
                        TextButton({ vm.sendKeys(text + "\r"); editingQuick = null }) { Text("执行") }
                        TextButton({ editingQuick = null }) { Text("取消") }
                    }
                }
            )
        }
        Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(input, { input = it }, placeholder = { Text("输入命令…", fontSize = 12.sp) },
                singleLine = true, modifier = Modifier.weight(1f))
            TextButton({ vm.sendKeys("\u0003") }) { Text("Ctrl+C") }
            Button({ vm.sendKeys(input + "\r"); input = "" }) { Text("发送") }
        }
    }

    if (pick) {
        AlertDialog(
            onDismissRequest = { pick = false },
            confirmButton = { TextButton({ pick = false }) { Text("关闭") } },
            title = { Text("选择机器（仅已开机）") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton({ pick = false; vm.connectLocal() }, Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("本机（这台手机）", color = Color.White, fontSize = 14.sp)
                            Text("不进 SSH，直接在本机执行命令（curl 测网络等）", color = Gray, fontSize = 11.sp)
                        }
                    }
                    vm.machines.filter { it.canSsh && (it.vcpus == 0 || it.state.equals("running", true)) }.forEach { m ->
                        TextButton({ pick = false; vm.connect(m) }, Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(m.name, color = Color.White, fontSize = 14.sp)
                                Text(stateText(m.state), color = stateColor(m.state), fontSize = 11.sp)
                            }
                        }
                    }
                    vm.spaces.filter { it.state.equals("Available", true) || it.state.equals("Running", true) }.forEach { c ->
                        TextButton({ pick = false; vm.connect(c) }, Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(c.display, color = Color.White, fontSize = 14.sp)
                                Text(stateText(c.state), color = stateColor(c.state), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        )
    }
}

// ---------------- 牧云小助手 ----------------
internal val UserBubble = Color(0xFF3B6DFF)
internal val AiBubble = Color(0xFF1B2233)
internal val ActionBubble = Color(0xFF16324A)

