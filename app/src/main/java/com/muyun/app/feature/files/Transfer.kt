package com.muyun.app.feature.files

import com.muyun.app.SshEngine
import java.io.File

/**
 * 文件传输算法（阶段 4.3 · feature/files）
 *
 * 从 MainVm.paneTransfer 中抽出，输入输出都是显式参数：
 *  - 不持有状态
 *  - 进度通过回调上报
 *  - 返回实际传输的文件数
 *
 * 三个方向：
 *  1. 本机 → 本机
 *  2. 本机 ↔ 远端
 *  3. 远端 → 远端（手机缓存中转）
 */
object Transfer {

    /** 递归上传本地目录 */
    suspend fun uploadDir(
        engine: SshEngine,
        localDir: File,
        remoteDir: String,
        onProgress: (Long, Long) -> Unit,
    ): Int {
        var count = 0
        runCatching { engine.sftpMkdir(remoteDir) }
        localDir.listFiles()?.forEach { f ->
            val target = FileOps.joinPath(remoteDir, f.name)
            if (f.isDirectory) count += uploadDir(engine, f, target, onProgress)
            else {
                engine.sftpUpload(f, target, onProgress)
                count++
            }
        }
        return count
    }

    /** 递归下载远端目录 */
    suspend fun downloadDir(
        engine: SshEngine,
        remoteDir: String,
        localDir: File,
        onProgress: (Long, Long) -> Unit,
    ): Int {
        var count = 0
        localDir.mkdirs()
        engine.sftpListDetailed(remoteDir).forEach { e ->
            if (e.name == "." || e.name == "..") return@forEach
            val rp = FileOps.joinPath(remoteDir, e.name)
            val lp = File(localDir, e.name)
            if (e.isDir) count += downloadDir(engine, rp, lp, onProgress)
            else {
                engine.sftpDownload(rp, lp, onProgress)
                count++
            }
        }
        return count
    }

    /** 远端 → 远端：先下到临时目录，再上传 */
    suspend fun copyRemoteToRemote(
        from: SshEngine, fromPath: String,
        to: SshEngine, toPath: String,
        tempDir: File,
        onProgress: (Long, Long) -> Unit,
    ): Int {
        val count = downloadDir(from, fromPath, tempDir, onProgress)
        uploadDir(to, tempDir, toPath, onProgress)
        tempDir.deleteRecursively()
        return count
    }
}
