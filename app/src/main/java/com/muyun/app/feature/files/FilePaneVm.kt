package com.muyun.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.muyun.app.feature.files.FileOps
import com.muyun.app.feature.files.PaneNav
import com.muyun.app.feature.files.Transfer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 须弥 · 双栏文件交互（阶段 B · feature/files）
 *
 * 从 MainVm 整体搬出：
 *  - 左右两栏状态、连接缓存与回收
 *  - 浏览 / 选择 / 新建 / 重命名 / 删除
 *  - 三方向传输（本机↔远端、远端↔远端）
 *  - ZIP 本地解压、打开文件、递归下载文件夹
 *
 * 依赖通过 [FilePaneHost] 注入，避免直接耦合 ViewModel。
 */
interface FilePaneHost {
    fun app(): Application
    fun scope(): CoroutineScope
    fun machinesList(): List<Machine>
    fun spacesList(): List<Cspace>
    fun entryOf(machineId: String): Entry?
    fun passwordOf(e: Entry): String?
    fun diag(msg: String)
    fun notify(msg: String)
    fun downloadDir(): File
}

class FilePaneVm(private val host: FilePaneHost) {

    /** 单栏状态：来源（本机/机器 id/cs|名字）+ 当前路径 + 列表 + 勾选 */
    data class PaneState(
        val owner: String = FilePane.LOCAL,
        val path: String = "",
        val entries: List<FilePane.Entry> = emptyList(),
        val selected: Set<String> = emptySet(),
    )

    var leftPane by mutableStateOf(PaneState()); private set
    var rightPane by mutableStateOf(PaneState()); private set
    var paneBusy by mutableStateOf(false); private set
    var paneProgress by mutableStateOf(0f); private set
    var paneStatus by mutableStateOf(""); private set

    /** 当前保留的 SSH 连接（左右两栏最多各一条） */
    private val engines = mutableMapOf<String, SshEngine>()

    private fun pane(side: Int) = if (side == 0) leftPane else rightPane
    private fun setPane(side: Int, v: PaneState) { if (side == 0) leftPane = v else rightPane = v }

    fun paneOwnerName(owner: String): String = when {
        owner == FilePane.LOCAL -> "本机"
        owner.startsWith("cs|") ->
            host.spacesList().firstOrNull { it.name == owner.removePrefix("cs|") }?.display ?: "Codespace"
        else -> host.machinesList().firstOrNull { it.id == owner }?.name ?: "未知"
    }

    /** 切换主机后回收不再使用的连接（用户要求） */
    private fun evictIdleEngines() {
        val keep = setOf(leftPane.owner, rightPane.owner)
            .filter { it != FilePane.LOCAL }
            .toSet()
        val it = engines.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in keep) {
                runCatching { e.value.close() }
                it.remove()
                host.diag("须弥回收连接：${paneOwnerName(e.key)}")
            }
        }
    }

    /** 取（或建立）某个来源的连接：支持普通 SSH 与 Codespaces */
    private fun engineFor(owner: String): SshEngine {
        engines[owner]?.let { return it }
        if (owner.startsWith("cs|")) {
            val csName = owner.removePrefix("cs|")
            val c = host.spacesList().firstOrNull { it.name == csName } ?: error("找不到 Codespace")
            val e = host.entryOf(csName.substringBefore("|")) ?: error("找不到 GitHub 账号")
            val eng = SshEngine(host.app())
            host.diag("须弥连接 Codespace：${c.display}")
            eng.connectCodespace(csName.substringAfter("|"), e.secret, Keys.ensure(host.app()))
            engines[owner] = eng
            return eng
        }
        val m = host.machinesList().firstOrNull { it.id == owner } ?: error("找不到机器")
        val e = host.entryOf(m.id.substringBefore("|")) ?: error("找不到账号配置")
        if (e.host.isBlank()) error("该机器未配置 SSH 地址")
        val eng = SshEngine(host.app())
        eng.connectPlain(e.host, e.port, e.user, host.passwordOf(e))
        engines[owner] = eng
        return eng
    }

    // ---------------- 浏览 ----------------

    fun paneOpen(side: Int, owner: String, path: String) {
        host.diag("须弥打开 side=$side owner=${paneOwnerName(owner)} path=$path")
        host.scope().launch {
            paneBusy = true
            val cur = pane(side)
            val newPath = path.ifBlank {
                val m = host.machinesList().firstOrNull { it.id == owner }
                val e = m?.let { host.entryOf(it.id.substringBefore("|")) }
                PaneNav.defaultPath(owner, e?.user == "root")
            }
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (owner == FilePane.LOCAL) FilePane.listLocal(File(newPath))
                    else FilePane.listRemote(engineFor(owner), newPath)
                }
            }
            evictIdleEngines()
            r.onSuccess { list ->
                setPane(side, cur.copy(owner = owner, path = newPath, entries = list, selected = emptySet()))
                paneStatus = "${paneOwnerName(owner)} · ${list.size} 项"
            }.onFailure {
                val msg = it.message.orEmpty()
                paneStatus = when {
                    msg.contains("subsystem", true) || msg.contains("unsupported", true) ->
                        "该主机未开启 SFTP 服务（NAS 请到控制面板启用 SFTP）"
                    msg.contains("timeout", true) -> "连接超时，请检查端口转发/网络"
                    else -> "打开失败：$msg"
                }
                host.diag("须弥打开失败 ${paneOwnerName(owner)} $newPath: $msg")
            }
            paneBusy = false
        }
    }

    fun paneRefresh(side: Int) {
        val p = pane(side)
        paneOpen(side, p.owner, p.path)
    }

    fun paneUp(side: Int) {
        val p = pane(side)
        paneOpen(side, p.owner, PaneNav.parentPath(p.owner, p.path))
    }

    fun paneSelect(side: Int, name: String, on: Boolean) {
        val p = pane(side)
        setPane(side, p.copy(selected = if (on) p.selected + name else p.selected - name))
    }

    fun paneSelectAll(side: Int, on: Boolean) {
        val p = pane(side)
        setPane(side, p.copy(selected = if (on) p.entries.map { it.name }.toSet() else emptySet()))
    }

    // ---------------- 写操作 ----------------

    fun paneMkdir(side: Int, name: String) {
        val p = pane(side)
        host.scope().launch {
            paneBusy = true
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (p.owner == FilePane.LOCAL) File(p.path, name).mkdirs()
                    else {
                        val eng = engineFor(p.owner)
                        val full = FileOps.joinPath(p.path, name)
                        runCatching { eng.sftpMkdir(full) }.onFailure {
                            eng.exec("mkdir -p '${full.replace("'", "'\\''")}'")
                        }
                        true
                    }
                }
            }
            r.onFailure { paneStatus = "新建失败：${it.message}" }
            paneBusy = false
            paneRefresh(side)
        }
    }

    fun paneDelete(side: Int, names: Set<String>) {
        if (names.isEmpty()) return
        val p = pane(side)
        host.scope().launch {
            paneBusy = true
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (p.owner == FilePane.LOCAL) names.forEach { FilePane.deleteLocal(File(p.path, it)) }
                    else {
                        val eng = engineFor(p.owner)
                        names.forEach { eng.sftpDeleteRecursive(FileOps.joinPath(p.path, it)) }
                    }
                }
            }
            r.onFailure { paneStatus = "删除失败：${it.message}" }
                .onSuccess { paneStatus = "已删除 ${names.size} 项" }
            paneBusy = false
            paneRefresh(side)
        }
    }

    fun paneRename(side: Int, oldName: String, newName: String) {
        val p = pane(side)
        host.scope().launch {
            paneBusy = true
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    if (p.owner == FilePane.LOCAL) FilePane.renameLocal(File(p.path, oldName), newName)
                    else {
                        engineFor(p.owner).sftpRename(
                            FileOps.joinPath(p.path, oldName), FileOps.joinPath(p.path, newName))
                        true
                    }
                }
            }
            r.onFailure { paneStatus = "重命名失败：${it.message}" }
            paneBusy = false
            paneRefresh(side)
        }
    }

    // ---------------- 传输 ----------------

    /** 把 fromSide 选中的项复制到另一栏（不支持移动，按用户要求） */
    fun paneTransfer(fromSide: Int, move: Boolean) {
        val from = pane(fromSide)
        val to = pane(1 - fromSide)
        if (from.selected.isEmpty()) { paneStatus = "请先选择文件"; return }
        host.scope().launch {
            paneBusy = true; paneProgress = 0f; paneStatus = "传输中…"
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    var okCount = 0
                    var failCount = 0
                    from.selected.forEach { name ->
                        val isDir = from.entries.firstOrNull { it.name == name }?.isDir == true
                        try {
                            if (isDir) {
                                when {
                                    from.owner == FilePane.LOCAL && to.owner == FilePane.LOCAL -> {
                                        File(from.path, name).copyRecursively(File(to.path, name), overwrite = true)
                                    }
                                    from.owner == FilePane.LOCAL && to.owner != FilePane.LOCAL -> {
                                        okCount += Transfer.uploadDir(
                                            engineFor(to.owner), File(from.path, name),
                                            FileOps.joinPath(to.path, name)
                                        ) { d, t -> if (t > 0) paneProgress = d.toFloat() / t }
                                    }
                                    to.owner == FilePane.LOCAL -> {
                                        okCount += Transfer.downloadDir(
                                            engineFor(from.owner), FileOps.joinPath(from.path, name),
                                            File(to.path, name)
                                        ) { d, t -> if (t > 0) paneProgress = d.toFloat() / t }
                                    }
                                    else -> {
                                        val tmp = File(host.app().cacheDir, "pane_" + System.currentTimeMillis())
                                            .apply { mkdirs() }
                                        okCount += Transfer.copyRemoteToRemote(
                                            engineFor(from.owner), FileOps.joinPath(from.path, name),
                                            engineFor(to.owner), FileOps.joinPath(to.path, name), tmp
                                        ) { d, t -> if (t > 0) paneProgress = d.toFloat() / t }
                                    }
                                }
                                if (move && from.owner != FilePane.LOCAL) {
                                    engineFor(from.owner).sftpDeleteRecursive(FileOps.joinPath(from.path, name))
                                }
                            } else {
                                val srcFile = File(from.path, name)
                                val srcRemote = FileOps.joinPath(from.path, name)
                                val dstLocal = File(to.path, name)
                                val dstRemote = FileOps.joinPath(to.path, name)
                                when {
                                    from.owner == FilePane.LOCAL && to.owner == FilePane.LOCAL ->
                                        srcFile.copyTo(dstLocal, overwrite = true)
                                    from.owner == FilePane.LOCAL && to.owner != FilePane.LOCAL ->
                                        engineFor(to.owner).sftpUpload(srcFile, dstRemote) { d, t ->
                                            if (t > 0) paneProgress = d.toFloat() / t
                                        }
                                    from.owner != FilePane.LOCAL && to.owner == FilePane.LOCAL ->
                                        engineFor(from.owner).sftpDownload(srcRemote, dstLocal) { d, t ->
                                            if (t > 0) paneProgress = d.toFloat() / t
                                        }
                                    else -> {
                                        val tmp = File(host.app().cacheDir, name)
                                        engineFor(from.owner).sftpDownload(srcRemote, tmp)
                                        engineFor(to.owner).sftpUpload(tmp, dstRemote) { d, t ->
                                            if (t > 0) paneProgress = d.toFloat() / t
                                        }
                                        tmp.delete()
                                    }
                                }
                                okCount++
                            }
                        } catch (t: Throwable) {
                            failCount++
                            host.diag("传输失败 $name: ${t.message}")
                        }
                    }
                    if (failCount > 0) error("失败 $failCount 项（成功 $okCount 项），详见天机录")
                    okCount
                }
            }
            r.onSuccess { paneStatus = "复制完成：$it 项" }
                .onFailure { paneStatus = "传输失败：${it.message}" }
            paneBusy = false; paneProgress = 0f
            evictIdleEngines()
            paneRefresh(fromSide); paneRefresh(1 - fromSide)
        }
    }

    /** 本地 ZIP 解压到同目录同名文件夹 */
    fun paneExtractZip(side: Int, name: String) {
        val p = pane(side)
        if (p.owner != FilePane.LOCAL) {
            paneStatus = "ZIP 解压仅支持本机（远端可用 ssh unzip）"
            return
        }
        host.scope().launch {
            paneBusy = true; paneStatus = "解压中…"
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val zip = File(p.path, name)
                    val out = File(p.path, name.substringBeforeLast('.'))
                    out.mkdirs()
                    FileOps.unzip(zip, out)
                }
            }
            r.onSuccess { paneStatus = "已解压 $it 个文件 → ${name.substringBeforeLast('.')}/" }
                .onFailure { paneStatus = "解压失败：${it.message}" }
            paneBusy = false
            paneRefresh(side)
        }
    }

    /** 打开文件：本地直接调系统选择器；远端先下载到缓存再打开 */
    fun paneOpenFile(side: Int, name: String) {
        val p = pane(side)
        val entry = p.entries.firstOrNull { it.name == name }
        if (entry?.isDir == true) {
            paneOpen(side, p.owner, PaneNav.childPath(p.owner, p.path, name))
            return
        }
        if (p.owner == FilePane.LOCAL) {
            openLocal(File(p.path, name))
            return
        }
        host.scope().launch {
            paneBusy = true; paneStatus = "下载中…"
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(host.downloadDir(), "preview").apply { mkdirs() }
                    val f = File(dir, name)
                    engineFor(p.owner).sftpDownload(FileOps.joinPath(p.path, name), f)
                    f
                }
            }
            r.onSuccess { openLocal(it) }.onFailure { paneStatus = "打开失败：${it.message}" }
            paneBusy = false
        }
    }

    private fun openLocal(f: File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                host.app(), "com.muyun.app.fileprovider", f)
            val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, host.app().contentResolver.getType(uri) ?: "*/*")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            val chooser = android.content.Intent.createChooser(i, "选择打开方式")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            host.app().startActivity(chooser)
        }.onFailure { host.notify("打开失败：${it.message}") }
    }

    /** 下载单个文件到 /Download/muyun/downloads */
    fun paneDownloadFile(side: Int, name: String) {
        val p = pane(side)
        host.scope().launch {
            paneBusy = true; paneStatus = "下载中…"
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(host.downloadDir(), "downloads").apply { mkdirs() }
                    val local = File(dir, name)
                    if (p.owner == FilePane.LOCAL) {
                        File(p.path, name).copyTo(local, overwrite = true).absolutePath
                    } else {
                        engineFor(p.owner).sftpDownload(FileOps.joinPath(p.path, name), local)
                        local.absolutePath
                    }
                }
            }
            r.onSuccess { paneStatus = "已下载：$it" }
                .onFailure { paneStatus = "下载失败：${it.message}" }
            paneBusy = false
        }
    }

    /** 递归下载文件夹到本机下载目录 */
    fun paneDownloadFolder(side: Int, name: String) {
        val p = pane(side)
        host.scope().launch {
            paneBusy = true; paneStatus = "下载文件夹 $name …"
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val localRoot = File(File(host.downloadDir(), "downloads").apply { mkdirs() }, name)
                        .apply { mkdirs() }
                    if (p.owner == FilePane.LOCAL) {
                        File(p.path, name).copyRecursively(localRoot, overwrite = true)
                        0
                    } else {
                        Transfer.downloadDir(
                            engineFor(p.owner), FileOps.joinPath(p.path, name), localRoot
                        ) { d, t -> if (t > 0) paneProgress = d.toFloat() / t }
                    }
                }
            }
            r.onSuccess { paneStatus = "文件夹已下载到 ${host.downloadDir().absolutePath}/$name（$it 个文件）" }
                .onFailure { paneStatus = "下载失败：${it.message}" }
            paneBusy = false; paneProgress = 0f
        }
    }
}
