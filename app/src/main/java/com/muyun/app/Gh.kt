package com.muyun.app

import android.content.Context
import java.io.File

/** 内嵌 gh 运行时：二进制放在 nativeLibraryDir（唯一可执行目录） */
object Gh {
    fun bin(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir, "libgh.so")

    fun home(ctx: Context): File = File(ctx.filesDir, "ghhome").apply { mkdirs() }

    fun cert(ctx: Context): File {
        val f = File(ctx.filesDir, "cacert.pem")
        if (!f.exists() || f.length() < 1000) {
            ctx.assets.open("cacert.pem").use { input -> f.outputStream().use { input.copyTo(it) } }
        }
        return f
    }

    fun env(ctx: Context, token: String): Map<String, String> {
        val px = LocalProxy.start()
        return mapOf(
        "HTTPS_PROXY" to "http://127.0.0.1:$px",
        "HTTP_PROXY" to "http://127.0.0.1:$px",
        "ALL_PROXY" to "http://127.0.0.1:$px",
        "NO_PROXY" to "127.0.0.1,localhost",
        "HOME" to home(ctx).absolutePath,
        "GH_TOKEN" to token,
        "GH_CONFIG_DIR" to File(home(ctx), "config").apply { mkdirs() }.absolutePath,
        "SSL_CERT_FILE" to cert(ctx).absolutePath,
        "GH_NO_UPDATE_NOTIFIER" to "1",
        "GH_PAGER" to "cat",
        "PATH" to ctx.applicationInfo.nativeLibraryDir + ":/system/bin:/system/xbin",
        "TMPDIR" to ctx.cacheDir.absolutePath,
        "GIT_TERMINAL_PROMPT" to "0",
        )
    }

    /** 执行任意 gh 子命令，返回合并输出 */
    fun run(ctx: Context, token: String, vararg args: String): String = try {
        val p = ProcessBuilder(listOf(bin(ctx).absolutePath) + args.toList())
            .apply { environment().putAll(env(ctx, token)) }
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out.ifBlank { "(无输出)" }
    } catch (t: Throwable) {
        "gh 执行失败: ${t.message}"
    }

    /** 自检：返回 gh 版本或错误 */
    fun selfCheck(ctx: Context): String = try {
        val p = ProcessBuilder(bin(ctx).absolutePath, "--version")
            .apply { environment().putAll(env(ctx, "")) }
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out.ifBlank { "gh 无输出" }
    } catch (t: Throwable) {
        "gh 启动失败: ${t.message}"
    }
}
