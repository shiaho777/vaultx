package io.vaultx.app.core.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.util.Arrays
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test

class CryptoTest {

    private val params = KdfParams.TEST
    private val password = "correct horse".toCharArray()

    // ---------------- Argon2id ----------------

    @Test
    fun kdfDeterministic() {
        val salt = ByteArray(16) { it.toByte() }
        val a = Argon2idKdf.derive(password.copyOf(), salt, params)
        val b = Argon2idKdf.derive(password.copyOf(), salt, params)
        assertArrayEquals(a, b)
        assertEquals(32, a.size)
    }

    @Test
    fun kdfSaltSensitive() {
        val s1 = ByteArray(16) { 1 }
        val s2 = ByteArray(16) { 2 }
        val a = Argon2idKdf.derive(password.copyOf(), s1, params)
        val b = Argon2idKdf.derive(password.copyOf(), s2, params)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun kdfPasswordSensitive() {
        val salt = ByteArray(16) { 7 }
        val a = Argon2idKdf.derive("pw1".toCharArray(), salt, params)
        val b = Argon2idKdf.derive("pw2".toCharArray(), salt, params)
        assertFalse(a.contentEquals(b))
    }

    // ---------------- KeyWrap ----------------

    @Test
    fun wrapRoundtrip() {
        val kek = Argon2idKdf.derive(password.copyOf(), ByteArray(16) { 3 }, params)
        val vmk = VaultCrypto.generateVmk()
        val wrapped = KeyWrap.wrap(kek, vmk)
        assertArrayEquals(vmk, KeyWrap.unwrap(kek, wrapped))
    }

    @Test
    fun unwrapWrongPasswordFails() {
        val salt = ByteArray(16) { 5 }
        val kek = Argon2idKdf.derive(password.copyOf(), salt, params)
        val wrong = Argon2idKdf.derive("wrong".toCharArray(), salt, params)
        val wrapped = KeyWrap.wrap(kek, VaultCrypto.generateVmk())
        try {
            KeyWrap.unwrap(wrong, wrapped)
            fail("expected WrongPasswordException")
        } catch (_: WrongPasswordException) {
        }
    }

    @Test
    fun unwrapTamperFails() {
        val kek = Argon2idKdf.derive(password.copyOf(), ByteArray(16) { 9 }, params)
        val wrapped = KeyWrap.wrap(kek, VaultCrypto.generateVmk())
        wrapped[wrapped.size - 1] = (wrapped.last() + 1).toByte()
        try {
            KeyWrap.unwrap(kek, wrapped)
            fail("expected WrongPasswordException")
        } catch (_: WrongPasswordException) {
        }
    }

    // ---------------- VaultCrypto / streaming ----------------

    @Test
    fun streamRoundtrip() {
        val crypto = VaultCrypto(VaultCrypto.generateVmk())
        val plain = ByteArray(1024 * 1024).also { Random(42).nextBytes(it) }
        val ad = VaultCrypto.blobAd("v1", "b1")
        val out = ByteArrayOutputStream()
        crypto.encryptingStream(out, ad).use { it.write(plain) }
        val dec = crypto.decryptingStream(ByteArrayInputStream(out.toByteArray()), ad).use { it.readBytes() }
        assertArrayEquals(plain, dec)
    }

    @Test
    fun ciphertextBoundToVaultAndBlob() {
        val crypto = VaultCrypto(VaultCrypto.generateVmk())
        val enc = crypto.encryptBlock("secret".toByteArray(), VaultCrypto.blobAd("v1", "b1"))
        // 同库不同 blob
        try {
            crypto.decryptBlock(enc, VaultCrypto.blobAd("v1", "b2"))
            fail("cross-blob must fail")
        } catch (_: Exception) {
        }
        // 跨库
        try {
            crypto.decryptBlock(enc, VaultCrypto.blobAd("v2", "b1"))
            fail("cross-vault must fail")
        } catch (_: Exception) {
        }
        // 用途不同(thumb AD)
        try {
            crypto.decryptBlock(enc, VaultCrypto.thumbAd("v1", "b1"))
            fail("cross-purpose must fail")
        } catch (_: Exception) {
        }
    }

    @Test
    fun ciphertextBoundToVmk() {
        val c1 = VaultCrypto(VaultCrypto.generateVmk())
        val c2 = VaultCrypto(VaultCrypto.generateVmk())
        val ad = VaultCrypto.indexAd("v")
        val enc = c1.encryptBlock("x".toByteArray(), ad)
        try {
            c2.decryptBlock(enc, ad)
            fail("different VMK must fail")
        } catch (_: Exception) {
        }
    }

    @Test
    fun seekableChannelRandomReads() {
        val crypto = VaultCrypto(VaultCrypto.generateVmk())
        val ad = VaultCrypto.blobAd("v", "b")
        val plain = ByteArray(200_000).also { Random(7).nextBytes(it) }

        // 加密到内存
        val encOut = ByteArrayOutputStream()
        crypto.encryptingStream(encOut, ad).use { it.write(plain) }
        val encBytes = encOut.toByteArray()

        val channel = crypto.seekableDecryptingChannel(InMemoryChannel(encBytes), ad)
        val rng = Random(123)
        repeat(50) {
            val off = rng.nextInt(plain.size - 1024)
            val len = rng.nextInt(1024) + 1
            channel.position(off.toLong())
            val buf = ByteBuffer.allocate(len)
            while (buf.hasRemaining() && channel.read(buf) >= 0) {}
            val got = buf.array().copyOf(buf.position())
            val expect = plain.copyOfRange(off, off + buf.position())
            assertArrayEquals("offset $off len ${buf.position()}", expect, got)
        }
        channel.close()
    }

    // ---------------- PortableCipher (.vlt) ----------------

    @Test
    fun portableRoundtrip() {
        val plain = "便携格式全链路 0123456789".repeat(200).toByteArray()
        val enc = PortableCipher.encryptBlock("pw123".toCharArray(), plain, params)
        val dec = PortableCipher.decryptBlock("pw123".toCharArray(), enc)
        assertArrayEquals(plain, dec)
    }

    @Test
    fun portableWrongPasswordFails() {
        val enc = PortableCipher.encryptBlock("right".toCharArray(), "data".toByteArray(), params)
        try {
            PortableCipher.decryptBlock("wrong".toCharArray(), enc)
            fail("expected WrongPasswordException")
        } catch (_: WrongPasswordException) {
        }
    }

    @Test
    fun portableBadMagicFails() {
        try {
            PortableCipher.decryptBlock("pw".toCharArray(), "not a vault file".toByteArray())
            fail("expected PortableFormatException")
        } catch (_: PortableFormatException) {
        }
    }

    @Test
    fun portableMaliciousKdfParamsRejected() {
        // 构造头部:合法 magic+version,但 memoryKiB=2GiB——必须在派生前被拒而不是 OOM
        val enc = PortableCipher.encryptBlock("pw".toCharArray(), "x".toByteArray(), params)
        val ba = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(ba)
        out.write(enc.copyOfRange(0, 5)) // magic + version
        out.writeInt(Int.MAX_VALUE) // memoryKiB
        out.writeInt(3)
        out.writeInt(1)
        out.write(enc.copyOfRange(17, enc.size)) // saltLen + salt + 密文
        out.flush()
        try {
            PortableCipher.decryptBlock("pw".toCharArray(), ba.toByteArray())
            fail("expected PortableFormatException for absurd KDF params")
        } catch (_: PortableFormatException) {
        }
    }

    // ---------------- KdfParams ----------------

    @Test
    fun kdfParamsOrdering() {
        assertTrue(KdfParams.HIGH_SECURITY.isAtLeast(KdfParams.DEFAULT))
        assertTrue(KdfParams.DEFAULT.isAtLeast(KdfParams.TEST))
        assertFalse(KdfParams.TEST.isAtLeast(KdfParams.DEFAULT))
        assertTrue(KdfParams.DEFAULT.isAtLeast(KdfParams.DEFAULT))
        // 内存更高但迭代更低 → 不算"更强"
        assertFalse(
            KdfParams(64 * 1024, 1, 1).isAtLeast(KdfParams.DEFAULT),
        )
    }

    /** 供 seekable 测试用的内存 channel。 */
    private class InMemoryChannel(private val data: ByteArray) : SeekableByteChannel {
        private var pos = 0L
        private var open = true
        override fun isOpen() = open
        override fun close() { open = false }
        override fun position() = pos
        override fun position(newPosition: Long): SeekableByteChannel {
            require(newPosition >= 0); pos = newPosition; return this
        }
        override fun size() = data.size.toLong()
        override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()
        override fun read(dst: ByteBuffer): Int {
            if (pos >= data.size) return -1
            val n = minOf(dst.remaining().toLong(), data.size - pos).toInt()
            dst.put(data, pos.toInt(), n)
            pos += n
            return n
        }
        override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException()
    }

    @Test
    fun zeroizeWipesVmk() {
        val vmk = VaultCrypto.generateVmk()
        val crypto = VaultCrypto(vmk)
        crypto.zeroize()
        assertTrue(vmk.all { it == 0.toByte() })
        assertNotEquals(Arrays.hashCode(VaultCrypto.generateVmk()), 0)
    }

    @Test
    fun zeroizeSealsApi() {
        // zeroize 只清 vmk 数组,Tink keyset 内还有副本——必须连 API 面一起封死
        val crypto = VaultCrypto(VaultCrypto.generateVmk())
        val ad = VaultCrypto.blobAd("v", "b")
        val enc = crypto.encryptBlock("x".toByteArray(), ad)
        crypto.zeroize()
        assertThrows(IllegalStateException::class.java) { crypto.decryptBlock(enc, ad) }
        assertThrows(IllegalStateException::class.java) {
            crypto.encryptBlock("y".toByteArray(), ad)
        }
        assertThrows(IllegalStateException::class.java) {
            crypto.decryptingStream(enc.inputStream(), ad)
        }
    }
}
