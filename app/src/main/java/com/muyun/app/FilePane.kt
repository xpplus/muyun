package com.muyun.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 双栏文件交互核心（重构 3.10/3.11）
 *  - 每一栏可以是「本机」或某台云机
 *  - 支持 复制/移动/删除/重命名/新建目录
 *  - 本地支持 ZIP 解压（/Download/muyun/tmp 或同目录）
 */
object FilePane {

    const val LOCAL = "local"

    data class Entry(val name: String, val size: Long, val isDir: Boolean)

    fun localRoot(ctx: Context): File {
        val pub = File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS), "muyun")
        if (pub.exists() || pub.mkdirs()) return pub
        val appDir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "muyun")
        appDir.mkdirs()
        return appDir
    }

    fun listLocal(dir: File): List<Entry> {
        val files = dir.listFiles() ?: return emptyList()
        return files.map { Entry(it.name, if (it.isDirectory) 0 else it.length(), it.isDirectory) }
            .sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name })
    }

    fun listRemote(engine: SshEngine, path: String): List<Entry> =
        engine.sftpListDetailed(path).map { Entry(it.name, it.size, it.isDir) }

    fun deleteLocal(f: File): Boolean = if (f.isDirectory) f.deleteRecursively() else f.delete()

    fun renameLocal(f: File, newName: String): Boolean =
        f.renameTo(File(f.parentFile, newName))

    /** 本地 ZIP 解压到同目录下的同名文件夹 */
    fun unzipLocal(zip: File, destDir: File): Int {
        var count = 0
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val out = com.muyun.app.feature.files.FileOps.safeChild(destDir, e.name)
                if (e.isDirectory) { out.mkdirs() } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                    count++
                }
                zin.closeEntry()
            }
        }
        return count
    }

    suspend fun downloadToLocal(engine: SshEngine, remotePath: String, localFile: File,
                                onProgress: (Long, Long) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) { engine.sftpDownload(remotePath, localFile, onProgress) }

    suspend fun uploadFromLocal(engine: SshEngine, localFile: File, remotePath: String,
                                onProgress: (Long, Long) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) { engine.sftpUpload(localFile, remotePath, onProgress) }
}
