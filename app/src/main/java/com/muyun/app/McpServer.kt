package com.muyun.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 极简 MCP（Model Context Protocol）服务：
 *  - HTTP + JSON-RPC 2.0（POST /mcp）
 *  - 只监听 127.0.0.1，需 Bearer Token
 * 供调试 / 接入支持 HTTP 的 MCP 客户端使用。
 */
class McpServer(
    private val port: Int,
    private val token: String,
    private val call: (name: String, args: JSONObject) -> String,
) {
    private val sessionId = (1..12).map { "abcdef0123456789".random() }.joinToString("")
    @Volatile private var running = false
    private var server: ServerSocket? = null

    fun start() {
        if (running) return
        val s = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        server = s
        running = true
        thread(name = "mcp-server") {
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
            c.soTimeout = 20_000
            val input = c.getInputStream().buffered()
            val out = c.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: "GET"
            val path = parts.getOrNull(1) ?: "/"

            var contentLength = 0
            var auth = ""
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                val lower = h.lowercase()
                if (lower.startsWith("content-length:")) contentLength = h.substringAfter(":").trim().toIntOrNull() ?: 0
                if (lower.startsWith("authorization:")) auth = h.substringAfter(":").trim()
            }
            val body = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            } else ""

            fun respond(code: Int, json: String) {
                val bytes = json.toByteArray(Charsets.UTF_8)
                out.write(("HTTP/1.1 $code OK\r\nContent-Type: application/json\r\n" +
                    "Mcp-Session-Id: muyun-$sessionId\r\n" +

                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(bytes); out.flush()
            }

            if (path == "/health") { respond(200, """{"ok":true}"""); return }
            if (method == "GET" && (path == "/" || path == "/mcp")) {
                respond(200, """{"name":"muyun","mcp":"POST /mcp","hint":"use JSON-RPC over POST"}""")
                return
            }
            if (path.startsWith("/.well-known/")) { respond(404, """{"error":"no oauth"}"""); return }
            if (method == "OPTIONS") { respond(200, """{}"""); return }
            if (method != "POST" || path != "/mcp") { respond(404, """{"error":"not found"}"""); return }
            // Token 留空 = 不验证（直接用）；非空才校验
            if (token.isNotBlank() && auth != "Bearer $token") {
                val body401 = """{"error":"unauthorized"}"""
                val bytes = body401.toByteArray()
                out.write(("HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\n" +
                    "WWW-Authenticate: Bearer realm=\"muyun\"\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(bytes); out.flush()
                return
            }

            val req = runCatching { JSONObject(body) }.getOrElse { respond(400, """{"error":"bad json"}"""); return }
            val rpcMethod = req.optString("method")
            if (rpcMethod.startsWith("notifications/")) {
                out.write("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush(); return
            }
            val id = req.opt("id")
            val result: Any = when (rpcMethod) {
                "initialize" -> JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", JSONObject().put("tools", JSONObject()))
                    put("serverInfo", JSONObject().put("name", "muyun").put("version", "1.0.0"))
                }
                "notifications/initialized" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", tools())
                "tools/call" -> {
                    val p = req.optJSONObject("params") ?: JSONObject()
                    val name = p.optString("name")
                    val args = p.optJSONObject("arguments") ?: JSONObject()
                    val text = runCatching { call(name, args) }.getOrElse { "错误: ${it.message}" }
                    JSONObject().put("content", JSONArray().put(JSONObject().apply {
                        put("type", "text"); put("text", text)
                    }))
                }
                else -> JSONObject().put("error", "unknown method")
            }
            val resp = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }
            respond(200, resp.toString())
        }
    }

    private fun tools(): JSONArray = McpTools.tools(machineProp())

    private fun machineProp() = JSONObject().put("type", "string")
        .put("description", "机器名称（与 list_machines 返回一致）")

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }
}
