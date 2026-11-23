package com.muyun.app.feature.files

import java.io.File
import java.util.zip.ZipInputStream

/**
 * 文件操作纯函数（阶段 4 · feature/files）
 * 不依赖 ViewModel，便于复用与单测。
 */
object FileOps {

    fun joinPath(base: String, name: String): String =
        if (base.endsWith("/")) base + name else "$base/$name"

    fun parentOf(remotePath: String): String =
        remotePath.substringBeforeLast('/', "/").ifBlank { "/" }

    /** 本地 ZIP 解压到同名目录，返回文件数 */
    /** 防 Zip Slip：条目解析后必须仍在目标目录内 */
    fun safeChild(destDir: File, entryName: String): File {
        val out = File(destDir, entryName)
        val root = destDir.canonicalPath + File.separator
        if (!out.canonicalPath.startsWith(root)) {
            throw SecurityException("非法 ZIP 路径：$entryName")
        }
        return out
    }

    fun unzip(zip: File, destDir: File): Int {
        var count = 0
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val out = safeChild(destDir, e.name)
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                    count++
                }
                zin.closeEntry()
            }
        }
        return count
    }

    fun fmtSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        else -> String.format("%.2f GB", bytes / 1024.0 / 1024 / 1024)
    }
}
