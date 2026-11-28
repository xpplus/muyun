package com.muyun.app

import android.content.Context
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthNone
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * 统一 SSH 引擎：
 *  - codespace：走 `gh cs ssh --stdio` 提供的裸 SSH 流（本地 socket 桥接）
 *  - 普通主机：直连 TCP（instabox 允许 none 认证）
 */
/**
 * TOFU（Trust On First Use）主机密钥校验：
 *  - 首次连接：记录 SHA-256 指纹并信任
 *  - 后续连接：指纹不一致则拒绝连接（防止中间人）
 *  - 记录保存在 SharedPreferences("known_hosts")
 */
class TofuHostKeyVerifier(private val ctx: Context) : HostKeyVerifier {
    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val id = "$hostname:$port"
        val fp = MessageDigest.getInstance("SHA-256").digest(key.encoded)
            .joinToString("") { "%02x".format(it) }
        val sp = ctx.getSharedPreferences("known_hosts", Context.MODE_PRIVATE)
        val known = sp.getString(id, null)
        if (known == null) {
            sp.edit().putString(id, fp).apply()
            return true
        }
        return known == fp
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): MutableList<String> = mutableListOf()
}

class SshEngine(private val ctx: Context) {

    /**
     * 创建 SSH 客户端：跳过 curve25519 密钥交换。
     * Android 自带 BouncyCastle 是裁剪版，没有 X25519，会报
     * "no such algorithm: X25519 for provider BC"；改用 ECDH(nistp256)/DH 即可。
     */
    private fun newClient(): SSHClient {
        val cfg = net.schmizz.sshj.DefaultConfig()
        runCatching {
            val filtered = cfg.keyExchangeFactories.filterNot { it.name.contains("curve25519", true) }
            if (filtered.isNotEmpty()) cfg.keyExchangeFactories = filtered
        }
        runCatching {
            val comp = cfg.compressionFactories
            if (comp != null && comp.isNotEmpty()) cfg.compressionFactories = comp
        }
        val c = SSHClient(cfg)
        c.addHostKeyVerifier(TofuHostKeyVerifier(ctx))
        return c
    }

    private var client: SSHClient? = null
    private var proc: Process? = null
    private var bridge: ServerSocket? = null

    fun isConnected(): Boolean = client?.isConnected == true

    /** 连接到 Codespace（带重试：gh 注入公钥/启动 sshd 有延迟） */
    fun connectCodespace(codespace: String, token: String, keyFile: File) {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                // 第 2 次尝试走直连（不经 LocalProxy），排除代理层故障
                connectCodespaceOnce(codespace, token, keyFile, useProxy = attempt != 1)
                return
            } catch (t: Throwable) {
                last = t
                com.muyun.app.core.Diag.e("SSH", "codespace 连接失败 attempt=${attempt + 1}: ${t.message}")
                close()
                if (attempt < 2) Thread.sleep(8000)
            }
        }
        throw last ?: IllegalStateException("连接失败")
    }

    private fun connectCodespaceOnce(codespace: String, token: String, keyFile: File, useProxy: Boolean = true) {
        val gh = Gh.bin(ctx)
        val p = ProcessBuilder(
            gh.absolutePath, "codespace", "ssh", "-c", codespace,
            "--stdio", "--", "-i", keyFile.absolutePath
        ).apply { environment().putAll(Gh.env(ctx, token, useProxy)) }
            .redirectErrorStream(false)
            .start()
        proc = p
        // 捕获 gh 的 stderr，出错时一起报出来
        val ghErr = StringBuilder()
        thread(name = "gh-stderr") {
            runCatching {
                p.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(ghErr) { if (ghErr.length < 800) ghErr.append(line).append('\n') }
                }
            }
        }

        val srv = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        bridge = srv
        thread(name = "gh-bridge") {
            try {
                val sock = srv.accept()
                thread { pump(sock.getInputStream(), p.outputStream) }
                pump(p.inputStream, sock.outputStream)
            } catch (_: Throwable) { }
        }

        val c = newClient()
        c.connectTimeout = 45_000
        try {
            c.connect("127.0.0.1", srv.localPort)
        } catch (t: Throwable) {
            Thread.sleep(300)
            val detail = synchronized(ghErr) { ghErr.toString().trim() }
            val state = if (p.isAlive) "gh 进程仍在运行" else runCatching { "gh 已退出 code=${p.exitValue()}" }.getOrDefault("gh 已退出")
            com.muyun.app.core.Diag.e("SSH", "codespace 桥接失败: $state ${detail.take(300)}")
            throw IllegalStateException(
                (t.message ?: "连接失败") + "（$state）" +
                    if (detail.isNotBlank()) "\ngh: ${detail.take(500)}" else ""
            )
        }
        // 不同 devcontainer 的 SSH 用户不同（常见 codespace / root / vscode），逐个尝试
        var authed = false
        var lastErr: Throwable? = null
        for (u in listOf("codespace", "root", "vscode")) {
            try {
                c.authPublickey(u, PKCS8KeyFile().apply { init(keyFile) })
                authed = true
                break
            } catch (t: Throwable) { lastErr = t }
        }
        if (!authed) {
            val detail = synchronized(ghErr) { ghErr.toString().trim() }
            throw IllegalStateException(
                (lastErr?.message ?: "认证失败") + if (detail.isNotBlank()) "\ngh: $detail" else ""
            )
        }
        optimize(c)
        client = c
    }

    /** 连接到普通 SSH 主机 */
    fun connectPlain(host: String, port: Int, user: String, password: String?) {
        val c = newClient()
        c.connectTimeout = 25_000
        c.connect(host, port)
        optimize(c)
        if (!password.isNullOrBlank()) {
            // 同时支持 password 和 keyboard-interactive（很多 VPS 只开后者）
            val finder = object : PasswordFinder {
                override fun reqPassword(resource: Resource<*>?): CharArray = password.toCharArray()
                override fun shouldRetry(resource: Resource<*>?): Boolean = false
            }
            c.auth(user, AuthPassword(finder), AuthKeyboardInteractive(PasswordResponseProvider(finder)))
        } else {
            // instabox 这类沙箱允许 none 认证；失败则退回公钥（用它自己的密钥）
            try {
                c.auth(user, AuthNone())
            } catch (t: Throwable) {
                val key = File(ctx.filesDir, "ssh/id_rsa")
                if (key.exists()) c.authPublickey(user, PKCS8KeyFile().apply { init(key) }) else throw t
            }
        }
        client = c
    }

    /** 降低延迟：关闭 Nagle、开启心跳、启用压缩 */
    private fun optimize(c: SSHClient) {
        runCatching {
            c.connection.keepAlive.keepAliveInterval = 30
        }
        runCatching {
            // SSHJ 的 transport 不暴露 socket，改为在连接层设置
            c.connection.keepAlive.keepAliveInterval = 30
        }
    }

    // ---------------- SFTP（无 shell 的空间也能用） ----------------
    data class RemoteEntry(val name: String, val size: Long, val isDir: Boolean)

    fun sftpListDetailed(path: String): List<RemoteEntry> {
        val c = client ?: error("未连接")
        c.newSFTPClient().use { sftp ->
            return sftp.ls(path).map { r ->
                RemoteEntry(r.name, r.attributes.size, r.isDirectory)
            }.sortedWith(compareByDescending<RemoteEntry> { it.isDir }.thenBy { it.name })
        }
    }

    fun sftpDownload(remote: String, local: java.io.File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val c = client ?: error("未连接")
        c.newSFTPClient().use { sftp ->
            val total = runCatching { sftp.stat(remote).size }.getOrDefault(0L)
            sftp.open(remote).use { rf ->
                local.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var offset = 0L
                    while (true) {
                        val n = rf.read(offset, buf, 0, buf.size)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        offset += n
                        onProgress(offset, total)
                    }
                }
            }
        }
    }

    fun sftpUpload(local: java.io.File, remote: String, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val c = client ?: error("未连接")
        c.newSFTPClient().use { sftp ->
            sftp.open(remote, setOf(
                net.schmizz.sshj.sftp.OpenMode.WRITE,
                net.schmizz.sshj.sftp.OpenMode.CREAT,
                net.schmizz.sshj.sftp.OpenMode.TRUNC
            )).use { rf ->
                val total = local.length()
                local.inputStream().use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var offset = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n <= 0) break
                        rf.write(offset, buf, 0, n)
                        offset += n
                        onProgress(offset, total)
                    }
                }
            }
        }
    }

    /** 递归删除（目录会先清空） */
    fun sftpDeleteRecursive(path: String) {
        val c = client ?: error("未连接")
        c.newSFTPClient().use { sftp ->
            fun del(p: String) {
                val attrs = runCatching { sftp.stat(p) }.getOrNull() ?: return
                if (attrs.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) {
                    sftp.ls(p).forEach { e ->
                        if (e.name != "." && e.name != "..") del(if (p.endsWith("/")) p + e.name else "$p/${e.name}")
                    }
                    sftp.rmdir(p)
                } else {
                    sftp.rm(p)
                }
            }
            del(path)
        }
    }

    fun sftpMkdir(path: String) {
        val c = client ?: error("未连接")
        c.newSFTPClient().use { it.mkdir(path) }
    }

    /** 修改权限：mode 为八进制数字，如 644 / 755 */
    fun sftpChmod(path: String, mode: Int): String {
        val c = client ?: error("未连接")
        c.newSFTPClient().use { it.chmod(path, mode) }
        return "已设置权限 $path → ${Integer.toOctalString(mode)}"
    }

    fun sftpRename(from: String, to: String) {
        val c = client ?: error("未连接")
        c.newSFTPClient().use { it.rename(from, to) }
    }

    fun sftpList(path: String): String {
        val c = client ?: error("未连接")
        c.newSFTPClient().use { sftp ->
            return sftp.ls(path).joinToString("\n") { r ->
                val kind = if (r.isDirectory) "d" else "-"
                "$kind ${r.attributes.size.toString().padStart(10)}\t${r.name}"
            }.ifBlank { "(空目录)" }
        }
    }

    fun sftpRead(path: String, maxBytes: Int = 200_000): String {
        val c = client ?: error("未连接")
        c.newSFTPClient().use { sftp ->
            sftp.open(path).use { rf ->
                val len = minOf(rf.length(), maxBytes.toLong()).toInt()
                val buf = ByteArray(len)
                var off = 0
                while (off < len) {
                    val n = rf.read(off.toLong(), buf, off, len - off)
                    if (n <= 0) break
                    off += n
                }
                return String(buf, 0, off, Charsets.UTF_8)
            }
        }
    }

    fun sftpWrite(path: String, content: String): String {
        val c = client ?: error("未连接")
        c.newSFTPClient().use { sftp ->
            sftp.open(path, setOf(
                net.schmizz.sshj.sftp.OpenMode.WRITE,
                net.schmizz.sshj.sftp.OpenMode.CREAT,
                net.schmizz.sshj.sftp.OpenMode.TRUNC
            )).use { rf ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                var off = 0
                while (off < bytes.size) {
                    val len = minOf(32768, bytes.size - off)
                    rf.write(off.toLong(), bytes, off, len)
                    off += len
                }
            }
        }
        return "已写入 ${content.length} 字符到 $path"
    }

    fun sftpDelete(path: String): String {
        val c = client ?: error("未连接")
        c.newSFTPClient().use { it.rm(path) }
        return "已删除 $path"
    }

    /** 打开交互式 PTY shell */
    fun openShell(cols: Int, rows: Int): net.schmizz.sshj.connection.channel.direct.Session {
        val c = client ?: error("未连接")
        val s = c.startSession()
        s.allocatePTY("xterm-256color", cols, rows, 0, 0, HashMap())
        s.startShell()
        return s
    }

    /** 在 SSH 上打开一条到 host:port 的通路（SOCKS5 就靠它） */
    fun openDirect(host: String, port: Int): DirectConnection? =
        runCatching { client?.newDirectConnection(host, port) }.getOrNull()

    /** 远端执行命令，返回输出 */
    fun exec(cmd: String, timeoutMs: Int = 15000): String {
        val c = client ?: return ""
        return runCatching {
            c.startSession().use { s ->
                val ch = s.exec(cmd)
                val out = ch.inputStream.bufferedReader().readText()
                ch.join(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                out
            }
        }.getOrDefault("")
    }

    fun close() {
        runCatching { client?.disconnect() }
        runCatching { proc?.destroy() }
        runCatching { bridge?.close() }
        client = null; proc = null; bridge = null
    }

    private fun pump(input: InputStream, output: OutputStream) {
        try {
            val buf = ByteArray(16384)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n); output.flush()
            }
        } catch (_: Throwable) { }
    }
}
