package com.muyun.app

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 统一终端会话：
 *  - kind = "cs"  → 走 gh --stdio 隧道连 Codespace
 *  - kind = "ssh" → 直连普通 SSH 主机（instabox / 自定义）
 */
class StdioSsh(
    private val ctx: Context,
    private val token: String,
    private val target: String,
    private val kind: String,
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String?,
    private val onOutput: (String) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val engine = SshEngine(ctx)
    private var session: net.schmizz.sshj.connection.channel.direct.Session? = null
    private var outStream: java.io.OutputStream? = null
    private val alive = AtomicBoolean(false)
    @Volatile private var manualClose = false
    @Volatile private var cols = 100
    @Volatile private var rows = 30

    fun isAlive() = alive.get()

    fun start(keyFile: File?, onError: (String) -> Unit) {
        if (alive.get()) return
        onState("连接中…")
        thread(name = "ssh-term") {
            try {
                if (kind == "cs") {
                    engine.connectCodespace(target, token, keyFile ?: error("缺少密钥"))
                } else {
                    engine.connectPlain(host, port, user, password)
                }
                val s = engine.openShell(cols, rows)
                session = s
                // 立刻缓存输出流：部分环境下延迟获取会失败
                outStream = runCatching { s.outputStream }.getOrNull()
                alive.set(true)
                onState("已连接")

                val reader = s.inputStream.reader(Charsets.UTF_8)
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    onOutput(String(buf, 0, n))
                }
                onState("连接已结束")
            } catch (t: Throwable) {
                // 主动断开（或关机时被动释放）不弹错误
                if (!manualClose) onError(t.message ?: t.javaClass.simpleName)
                else onState("未连接")
            } finally {
                alive.set(false)
                close()
            }
        }
    }

    /** 返回 null 表示成功，否则是失败原因 */
    fun write(data: String): String? {
        session ?: return "会话尚未建立"
        return try {
            val out = outStream ?: session?.outputStream ?: return "输出流不可用"
            out.write(data.toByteArray(Charsets.UTF_8))
            out.flush()
            null
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}: ${t.message}"
        }
    }


    fun resize(c: Int, r: Int) {
        cols = c; rows = r
        runCatching { session?.allocatePTY("xterm-256color", c, r, 0, 0, HashMap()) }
    }

    fun close() {
        manualClose = true
        alive.set(false)
        runCatching { outStream?.close() }
        outStream = null
        runCatching { session?.close() }
        runCatching { engine.close() }
        session = null
    }
}
