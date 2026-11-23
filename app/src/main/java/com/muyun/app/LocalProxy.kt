package com.muyun.app

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/**
 * 本地 HTTP 代理。
 * 目的：Go 静态二进制（gh）在 Android 沙箱里没有 /etc/resolv.conf，无法解析域名。
 * 让 gh 走 HTTP_PROXY=127.0.0.1:port，由这里用 Android 的 DNS 解析并转发。
 */
object LocalProxy {
    @Volatile var port: Int = 0
        private set
    private var server: ServerSocket? = null

    fun start(): Int {
        if (server != null) return port
        val s = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = s
        port = s.localPort
        thread(name = "local-proxy") {
            while (true) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread { runCatching { handle(c) } }
            }
        }
        return port
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun handle(client: Socket) {
        client.use { c ->
            c.soTimeout = 60_000
            val input = c.getInputStream().buffered()
            val out = c.getOutputStream()
            val first = readLine(input) ?: return
            val parts = first.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]

            val headers = StringBuilder()
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                // 去掉代理特有头，避免上游困惑
                val lower = h.lowercase()
                if (lower.startsWith("proxy-connection")) continue
                headers.append(h).append("\r\n")
            }

            if (method.equals("CONNECT", true)) {
                val host = target.substringBefore(':')
                val p = target.substringAfter(':', "443").toIntOrNull() ?: 443
                val up = Socket()
                up.connect(InetSocketAddress(InetAddress.getByName(host), p), 20_000)
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                out.flush()
                thread { runCatching { pump(up.getInputStream(), out) }.also { up.close() } }
                pump(input, up.getOutputStream())
                up.close()
            } else {
                val u = URL(target)
                val p = if (u.port > 0) u.port else 80
                val up = Socket()
                up.connect(InetSocketAddress(InetAddress.getByName(u.host), p), 20_000)
                val path = u.file.ifEmpty { "/" }
                up.getOutputStream().write("$method $path HTTP/1.1\r\nHost: ${u.host}\r\n$headers\r\n".toByteArray())
                up.getOutputStream().flush()
                thread { runCatching { pump(up.getInputStream(), out) }.also { up.close() } }
                pump(input, up.getOutputStream())
                up.close()
            }
        }
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
