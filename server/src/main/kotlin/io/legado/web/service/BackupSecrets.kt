package io.legado.web.service

import io.legado.web.auth.BadRequestException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object BackupSecrets {
    fun encode(text: String, password: String): String {
        if (password.length < 8) throw BadRequestException("备份密码至少 8 位")
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(salt + iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    fun decode(text: String, password: String): String {
        try {
            val bytes = Base64.getDecoder().decode(text)
            if (bytes.size < 44) throw BadRequestException("加密备份格式无效")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, bytes.copyOfRange(0, 16)), GCMParameterSpec(128, bytes, 16, 12))
            return String(cipher.doFinal(bytes, 28, bytes.size - 28), Charsets.UTF_8)
        } catch (e: java.security.GeneralSecurityException) {
            throw BadRequestException("备份密码错误或文件已损坏")
        } catch (e: IllegalArgumentException) {
            throw BadRequestException("加密备份格式无效")
        }
    }

    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 210000, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword() }
    }
}
