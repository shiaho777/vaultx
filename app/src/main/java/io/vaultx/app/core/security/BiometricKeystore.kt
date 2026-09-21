package io.vaultx.app.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 生物识别解锁:VMK 再被 Android Keystore 里的**不可导出** AES 密钥包裹一层,
 * 解包必须过现场活体认证(CryptoObject 门)。密码永远是兜底——删掉生物识别或
 * 新增指纹会使 Keystore 密钥作废,自动回退密码解锁。
 *
 * 落盘物:`bio.wrap` = iv || GCM(vmk)。密钥本身不出 Keystore。
 */
class BiometricKeystore {

    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * 开启用的 CryptoObject:auth-required 密钥的 ENCRYPT 同样要现场活体
     * (不设有效期的密钥每次使用都必须认证)——开启流程也走 BiometricPrompt。
     */
    fun cryptoObjectForWrap(vaultId: String): BiometricPrompt.CryptoObject {
        val key = getOrCreateKey(vaultId)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return BiometricPrompt.CryptoObject(cipher)
    }

    /** 认证成功后:用 CryptoObject 里的 Cipher 包裹 VMK,返回可落盘的 iv+ct。 */
    fun wrapVmkWith(cryptoObject: BiometricPrompt.CryptoObject, vmk: ByteArray): ByteArray? {
        val cipher = cryptoObject.cipher ?: return null
        return runCatching { cipher.doFinal(vmk).let { cipher.iv + it } }.getOrNull()
    }

    /**
     * 解锁用的 CryptoObject:decrypt 模式 Cipher 需要活体认证后才可用。
     * @throws KeyInvalidatedException 密钥已失效(新录指纹等)——调用方应删 bio.wrap 回退密码
     */
    fun cryptoObjectForUnlock(vaultId: String, wrapped: ByteArray): BiometricPrompt.CryptoObject {
        val key = keystoreKey(vaultId) ?: throw KeyInvalidatedException()
        val iv = wrapped.copyOfRange(0, GCM_IV_LENGTH)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return BiometricPrompt.CryptoObject(cipher)
    }

    /** 认证成功后:用 CryptoObject 里的 Cipher 解出 VMK。 */
    fun unwrapVmk(cryptoObject: BiometricPrompt.CryptoObject, wrapped: ByteArray): ByteArray? {
        val cipher = cryptoObject.cipher ?: return null
        val ct = wrapped.copyOfRange(GCM_IV_LENGTH, wrapped.size)
        return runCatching { cipher.doFinal(ct) }.getOrNull()
    }

    fun deleteKey(vaultId: String) {
        runCatching {
            keyStore().deleteEntry(alias(vaultId))
        }
    }

    private fun getOrCreateKey(vaultId: String): SecretKey =
        keystoreKey(vaultId) ?: run {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            gen.init(
                KeyGenParameterSpec.Builder(
                    alias(vaultId),
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build(),
            )
            gen.generateKey()
        }

    private fun keystoreKey(vaultId: String): SecretKey? = try {
        (keyStore().getEntry(alias(vaultId), null) as? KeyStore.SecretKeyEntry)?.secretKey
    } catch (e: Exception) {
        null
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun alias(vaultId: String) = "vaultx_bio_$vaultId"

    class KeyInvalidatedException : Exception()

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private val random = SecureRandom()
    }
}
