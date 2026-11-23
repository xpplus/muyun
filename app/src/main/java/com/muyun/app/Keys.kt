package com.muyun.app

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

/** 首次运行生成 RSA 2048 密钥对：私钥 PKCS8 PEM，公钥 OpenSSH 格式（gh 注入用） */
object Keys {
    fun ensure(ctx: Context): File {
        val dir = File(ctx.filesDir, "ssh").apply { mkdirs() }
        val priv = File(dir, "id_rsa")
        val pub = File(dir, "id_rsa.pub")
        if (priv.exists() && pub.exists() && priv.length() > 100) return priv

        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val kp = kpg.generateKeyPair()

        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.encodeToString(kp.private.encoded, Base64.DEFAULT)
                .chunked(64).joinToString("\n") +
            "\n-----END PRIVATE KEY-----\n"
        priv.writeText(pem)
        pub.writeText("${openSshPublic(kp.public as RSAPublicKey)} clouddeck@android\n")
        return priv
    }

    private fun openSshPublic(k: RSAPublicKey): String {
        val blob = ByteArrayOutputStream().apply {
            write(sshString("ssh-rsa".toByteArray()))
            write(sshString(k.publicExponent.toByteArray()))
            write(sshString(k.modulus.toByteArray()))
        }.toByteArray()
        return "ssh-rsa " + Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    private fun sshString(b: ByteArray): ByteArray {
        val len = b.size
        return byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()) + b
    }
}
