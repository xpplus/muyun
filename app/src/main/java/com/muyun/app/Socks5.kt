package com.muyun.app

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 本地 SOCKS5 服务：每条连接都通过 SSH 的 direct-tcpip 通道出去。
 * 不需要在云端安装任何软件。
 */
class Socks5Server(private val port: Int, private val engine: SshEngine) {

    @Volatile private var running = false
    private var server: ServerSocket? = null

    fun start() {
        if (running) return
        val s = ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"))
        server = s
        running = true
        thread(name = "socks5-$port") {
            while (running) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread { runCatching { handle(c) } }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    fun isRunning() = running

    private fun handle(client: Socket) {
        client.use { c ->
            c.soTimeout = 30_000
            val input = c.getInputStream()
            val out = c.getOutputStream()

            // 1) 握手：VER NMETHODS METHODS...
            val ver = input.read()
            if (ver != 5) return
            val n = input.read()
            val methods = ByteArray(n.coerceAtLeast(0))
            var read = 0
            while (read < n) { val r = input.read(methods, read, n - read); if (r < 0) return; read += r }
            out.write(byteArrayOf(5, 0)); out.flush()   // 无需认证

            // 2) 请求：VER CMD RSV ATYP ...
            val hdr = ByteArray(4)
            if (input.readNBytes(hdr, 0, 4) < 4) return
            if (hdr[1].toInt() != 1) { reply(out, 7); return }   // 只支持 CONNECT

            val host = when (hdr[3].toInt()) {
                1 -> {  // IPv4
                    val b = ByteArray(4); if (input.readNBytes(b, 0, 4) < 4) return
                    InetAddress.getByAddress(b).hostAddress ?: return
                }
                3 -> {  // 域名
                    val len = input.read(); if (len < 0) return
                    val b = ByteArray(len); if (input.readNBytes(b, 0, len) < len) return
                    String(b, Charsets.UTF_8)
                }
                4 -> {  // IPv6
                    val b = ByteArray(16); if (input.readNBytes(b, 0, 16) < 16) return
                    InetAddress.getByAddress(b).hostAddress ?: return
                }
                else -> { reply(out, 8); return }
            }
            val p = (input.read() shl 8) or (input.read() and 0xFF)
            if (p <= 0) { reply(out, 1); return }

            // 3) 经 SSH 打开通道
            val conn = engine.openDirect(host, p)
            if (conn == null) { reply(out, 5); return }
            reply(out, 0)

            thread { runCatching { pump(conn.inputStream, out) }.also { runCatching { conn.close() } } }
            pump(input, conn.outputStream)
            runCatching { conn.close() }
        }
    }

    private fun reply(out: OutputStream, code: Int) {
        out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
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
