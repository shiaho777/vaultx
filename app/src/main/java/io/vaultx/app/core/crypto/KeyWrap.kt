package io.vaultx.app.core.crypto

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * KEK → AES-256-GCM 包裹/解包 VMK。
 *
 * 密码验证 = unwrap 是否成功:没有独立的校验位可篡改,
 * 错密码/改盐/改包裹体都只会走到 AEADBadTag → WrongPasswordException。
 */
object KeyWrap {

    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    /** 输出 = iv || ciphertext || tag */
    fun wrap(kek: ByteArray, vmk: ByteArray): ByteArray {
        val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(vmk)
        return iv + ct
    }

    /** @throws WrongPasswordException 认证失败(密码错或数据被改) */
    fun unwrap(kek: ByteArray, wrapped: ByteArray): ByteArray {
        if (wrapped.size < IV_LENGTH + 16) throw WrongPasswordException()
        val iv = wrapped.copyOfRange(0, IV_LENGTH)
        val ct = wrapped.copyOfRange(IV_LENGTH, wrapped.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return try {
            cipher.doFinal(ct)
        } catch (e: AEADBadTagException) {
            throw WrongPasswordException()
        }
    }
}

/** 密码错误(或数据被篡改)的统一信号——对外界不区分这两种失败。 */
class WrongPasswordException : Exception()
