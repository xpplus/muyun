package com.muyun.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 须弥 · 双栏文件页（阶段 F2 · ui/screens）
 *
 * 只负责渲染与交互；状态与 IO 在 feature/files/FilePaneVm。
 * 交互约定：
 *  - 单击文件夹：进入
 *  - 单击文件：勾选
 *  - 双击文件：打开
 *  - 长按：菜单（进入/打开/复制到对栏/下载文件夹/解压/重命名/删除）
 */
@Composable
fun FileTab(vm: MainVm) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            PaneColumn(vm, 0, Modifier.weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF232A36)))
            PaneColumn(vm, 1, Modifier.weight(1f))
        }
        if (vm.paneBusy) {
            LinearProgressIndicator(progress = { vm.paneProgress }, modifier = Modifier.fillMaxWidth())
        }
        Text(vm.paneStatus.ifBlank { "长按文件可复制/移动/删除；勾选多选" },
            color = Gray, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
fun PaneColumn(vm: MainVm, side: Int, modifier: Modifier = Modifier) {
    val p = if (side == 0) vm.leftPane else vm.rightPane
    var ownerOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var mkdirOpen by remember { mutableStateOf(false) }
    var renameFor by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }

    Column(modifier.fillMaxHeight().padding(4.dp)) {
        // 来源选择
        Box {
            Text(
                "▼ " + vm.paneOwnerName(p.owner),
                color = Accent, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().clickable { ownerOpen = true }.padding(vertical = 6.dp)
            )
            DropdownMenu(expanded = ownerOpen, onDismissRequest = { ownerOpen = false }) {
                DropdownMenuItem(text = { Text("本机", fontSize = 12.sp) },
                    onClick = { ownerOpen = false; vm.paneOpen(side, "local", android.os.Environment.getExternalStorageDirectory().absolutePath) })
                vm.spaces.filter { c ->
                    val st = c.state.lowercase()
                    st == "available" || st == "starting" || st == "running"
                }.forEach { c ->
                    DropdownMenuItem(text = { Text("☁ " + c.display, fontSize = 12.sp) },
                        onClick = { ownerOpen = false; vm.paneOpen(side, "cs|" + c.name, "/workspaces") })
                }
                vm.machines.filter {
                    (it.canSsh || it.canFile) && it.state.lowercase().let { st ->
                        st == "running" || st == "starting" || st == "running " 
                    }
                }.forEach { m ->
                    DropdownMenuItem(text = { Text(m.name, fontSize = 12.sp) },
                        onClick = { ownerOpen = false; vm.paneOpen(side, m.id, "") })
                }
            }
        }
        // 路径栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ vm.paneUp(side) }, contentPadding = PaddingValues(0.dp)) { Text("↑", fontSize = 15.sp) }
            Text(p.path, color = Color.White, fontSize = 9.sp, maxLines = 1,
                modifier = Modifier.weight(1f).clickable { vm.paneRefresh(side) })
            TextButton({ mkdirOpen = true }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("＋", fontSize = 13.sp) }
        }
        HorizontalDivider(color = Color(0xFF232A36))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            p.entries.forEach { f ->
                val checked = f.name in p.selected
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked, onCheckedChange = { vm.paneSelect(side, f.name, it) },
                        modifier = Modifier.size(26.dp))
                    Text(if (f.isDir) "📁" else "📄", fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    val dirPath = com.muyun.app.feature.files.PaneNav.childPath(p.owner, p.path, f.name)
                    Column(Modifier.weight(1f)
                        .background(if (checked) Color(0x223B82F6) else Color.Transparent)
                        .pointerInput(f.name) {
                            detectTapGestures(
                                onTap = {
                                    if (f.isDir) vm.paneOpen(side, p.owner, dirPath)
                                    else vm.paneSelect(side, f.name, !checked)
                                },
                                onDoubleTap = {
                                    if (f.isDir) vm.paneOpen(side, p.owner, dirPath)
                                    else vm.paneOpenFile(side, f.name)
                                },
                                onLongPress = { menuFor = f.name }
                            )
                        }) {
                        Text(f.name, color = Color.White, fontSize = 13.sp, maxLines = 1)
                        if (!f.isDir) Text(fmtSize(f.size), color = Gray, fontSize = 10.sp)
                    }
                }
            }
        }
    }

    // 长按菜单
    menuFor?.let { name ->
        val isZip = name.lowercase().endsWith(".zip")
        AlertDialog(
            onDismissRequest = { menuFor = null },
            confirmButton = { TextButton({ menuFor = null }) { Text("关闭") } },
            title = { Text(name, fontSize = 14.sp) },
            text = {
                val isDir = p.entries.firstOrNull { it.name == name }?.isDir == true
                Column {
                    @Composable
                    fun item(label: String, icon: String, color: Color = Color(0xFFDCE3F0), action: () -> Unit) {
                        Row(Modifier.fillMaxWidth().clickable { action() }.padding(horizontal = 4.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(icon, fontSize = 16.sp, modifier = Modifier.width(28.dp))
                            Text(label, color = color, fontSize = 14.sp)
                        }
                        HorizontalDivider(color = Color(0xFF232A36))
                    }
                    if (isDir) item("进入文件夹", "📂") {
                        vm.paneOpen(side, p.owner,
                            com.muyun.app.feature.files.PaneNav.childPath(p.owner, p.path, name))
                        menuFor = null
                    } else item("打开", "📄") { vm.paneOpenFile(side, name); menuFor = null }
                    item("复制到对栏", "⧉") {
                        if (name !in p.selected) vm.paneSelect(side, name, true)
                        vm.paneTransfer(side, move = false)
                        menuFor = null
                    }
                    if (isDir) item("下载文件夹到本机", "⬇") { vm.paneDownloadFolder(side, name); menuFor = null }
                    else item("下载文件到本机", "⬇") { vm.paneDownloadFile(side, name); menuFor = null }
                    if (p.owner == FilePane.LOCAL && isZip) item("解压到当前目录", "🗜") { vm.paneExtractZip(side, name); menuFor = null }
                    item("重命名", "✏") { renameFor = name; newName = name; menuFor = null }
                    item("删除", "🗑", Red) {
                        val targets = if (name in p.selected) p.selected else setOf(name)
                        vm.paneDelete(side, targets)
                        menuFor = null
                    }
                }
            }
        )
    }

    if (mkdirOpen) {
        AlertDialog(
            onDismissRequest = { mkdirOpen = false },
            confirmButton = { TextButton({ vm.paneMkdir(side, newName); newName = ""; mkdirOpen = false },
                enabled = newName.isNotBlank()) { Text("创建") } },
            dismissButton = { TextButton({ mkdirOpen = false }) { Text("取消") } },
            title = { Text("新建文件夹") },
            text = { OutlinedTextField(newName, { newName = it }, label = { Text("名称") }, singleLine = true) }
        )
    }
    renameFor?.let { old ->
        AlertDialog(
            onDismissRequest = { renameFor = null },
            confirmButton = { TextButton({ vm.paneRename(side, old, newName); renameFor = null },
                enabled = newName.isNotBlank() && newName != old) { Text("确定") } },
            dismissButton = { TextButton({ renameFor = null }) { Text("取消") } },
            title = { Text("重命名") },
            text = { OutlinedTextField(newName, { newName = it }, label = { Text("新名称") }, singleLine = true) }
        )
    }
}

