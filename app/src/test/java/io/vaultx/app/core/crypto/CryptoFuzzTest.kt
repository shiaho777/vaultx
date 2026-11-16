package io.vaultx.app.core.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 加密格式模糊/篡改测试(信任工程):
 * - 随机尺寸往返:空、单字节、段边界(4KB)前后、跨多段——流式 AEAD 的分段封装不能在任何尺寸上出错
 * - 位翻转篡改:密文任何一处被改,解密必须失败(认证加密的核心承诺)
 * - 截断:任意位置截断的 .vlt 必须被拒绝,不能吐出半截"可信"明文
 * - 垃圾输入:随机字节喂给 .vlt 解析器,只允许受控异常,不允许崩溃/挂起
 *
 * 全部用固定种子的伪随机(可复现);KDF 用 TEST 档保证测试速度。
 */
class CryptoFuzzTest {

    private val params = KdfParams.TEST
    private val rng = Random(0x5EED)

    // ---------------- 流式 AEAD 随机尺寸往返 ----------------

    @Test
    fun streamRoundtripAcrossSegmentBoundaries() {
        val crypto = VaultCrypto(VaultCrypto.generateVmk())
        val ad = VaultCrypto.blobAd("fuzz-vault", "fuzz-blob")
        // 4KB 段边界及其邻域 + 随机尺寸
        val sizes = listOf(0, 1, 2, 4095, 4096, 4097, 8191, 8192, 8193, 65536) +
            List(20) { rng.nextInt(200_000) }
        for (size in sizes) {
            val plain = ByteArray(size).also(rng::nextBytes)
            val out = ByteArrayOutputStream()
            crypto.encryptingStream(out, ad).use { it.write(plain) }
            val dec = crypto.decryptingStream(ByteArrayInputStream(out.toByteArray()), ad).use { it.readBytes() }
            assertArrayEquals("size=$size", plain, dec)
        }
    }

    @Test
    fun streamTamperAnywhereFails() {
        val crypto = VaultCrypto(VaultCrypto.generateVmk())
        val ad = VaultCrypto.blobAd("fuzz-vault", "fuzz-blob")
        val plain = ByteArray(20_000).also(rng::nextBytes)
        val out = ByteArrayOutputStream()
        crypto.encryptingStream(out, ad).use { it.write(plain) }
        val cipher = out.toByteArray()
        // 头部/中部/尾部各翻转一位:任何位置都必须认证失败
        for (pos in listOf(0, cipher.size / 2, cipher.size - 1)) {
            val bad = cipher.copyOf()
            bad[pos] = (bad[pos].toInt() xor 0x01).toByte()
            try {
                crypto.decryptingStream(ByteArrayInputStream(bad), ad).use { it.readBytes() }
                fail("tamper at $pos must fail")
            } catch (_: Exception) {
            }
        }
    }

    // ---------------- .vlt 随机尺寸往返 ----------------

    @Test
    fun portableRoundtripRandomSizes() {
        val sizes = listOf(0, 1, 4095, 4096, 4097, 100_000) + List(10) { rng.nextInt(150_000) }
        for (size in sizes) {
            val plain = ByteArray(size).also(rng::nextBytes)
            val enc = PortableCipher.encryptBlock("fuzz pw".toCharArray(), plain, params)
            val dec = PortableCipher.decryptBlock("fuzz pw".toCharArray(), enc)
            assertArrayEquals("size=$size", plain, dec)
        }
    }

    @Test
    fun portableWrongPasswordAlwaysFails() {
        val enc = PortableCipher.encryptBlock("right".toCharArray(), ByteArray(5000).also(rng::nextBytes), params)
        repeat(5) {
            try {
                PortableCipher.decryptBlock("wrong$it".toCharArray(), enc)
                fail("wrong password must fail")
            } catch (_: WrongPasswordException) {
            }
        }
    }

    @Test
    fun portableTamperAnywhereFails() {
        val plain = ByteArray(10_000).also(rng::nextBytes)
        val enc = PortableCipher.encryptBlock("pw".toCharArray(), plain, params)
        // 密文区(头之后)随机 10 处位翻转;头部篡改由格式校验/派生失败兜底
        val headerLen = 4 + 1 + 4 + 4 + 4 + 1 + 16
        repeat(10) {
            val pos = headerLen + rng.nextInt(enc.size - headerLen)
            val bad = enc.copyOf()
            bad[pos] = (bad[pos].toInt() xor 0x01).toByte()
            try {
                PortableCipher.decryptBlock("pw".toCharArray(), bad)
                fail("tamper at $pos must fail")
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun portableTruncationAlwaysRejected() {
        val plain = ByteArray(30_000).also(rng::nextBytes)
        val enc = PortableCipher.encryptBlock("pw".toCharArray(), plain, params)
        // 任意位置截断:要么格式异常要么密码异常,绝不返回完整明文
        val cuts = listOf(0, 1, 4, 5, 10, 21, 22, 37, enc.size / 2, enc.size - 1)
        for (cut in cuts) {
            val truncated = enc.copyOf(cut)
            try {
                val dec = PortableCipher.decryptBlock("pw".toCharArray(), truncated)
                // 空明文合法场景不存在(本例 30KB):解出任何完整数据都算失败
                fail("truncation at $cut must fail, got ${dec.size} bytes")
            } catch (_: Exception) {
            }
        }
    }

    @Test
    fun portableGarbageInputOnlyControlledExceptions() {
        // 随机垃圾喂解析器:只允许 WrongPassword/PortableFormat/IO 系受控异常
        repeat(30) {
            val garbage = ByteArray(rng.nextInt(300)).also(rng::nextBytes)
            try {
                PortableCipher.decryptBlock("pw".toCharArray(), garbage)
                // 极小概率垃圾恰好构成合法头+合法密文——不可能(无有效 tag),到这里即失败
                fail("garbage must not decrypt")
            } catch (e: Exception) {
                assertTrue(
                    "unexpected exception type: ${e.javaClass.name}",
                    e is WrongPasswordException || e is PortableFormatException ||
                        e is java.io.IOException || e is IllegalArgumentException,
                )
            }
        }
    }

    @Test
    fun portableHostileKdfParamsRejected() {
        // 构造巨型 memoryKiB 的头:必须在派生前被钳制拒绝,不能 OOM
        val header = ByteArrayOutputStream()
        header.write(byteArrayOf('V'.code.toByte(), 'X'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte()))
        header.write(1) // version
        header.write(byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())) // memoryKiB = Int.MAX
        header.write(byteArrayOf(0, 0, 0, 1))
        header.write(byteArrayOf(0, 0, 0, 1))
        header.write(16)
        header.write(ByteArray(16))
        try {
            PortableCipher.decryptBlock("pw".toCharArray(), header.toByteArray())
            fail("hostile KDF params must be rejected")
        } catch (_: PortableFormatException) {
        }
    }

    // ---------------- 块加密(索引/缩略图)随机往返与篡改 ----------------

    @Test
    fun blockRoundtripAndTamper() {
        val crypto = VaultCrypto(VaultCrypto.generateVmk())
        val ad = VaultCrypto.indexAd("fuzz-vault")
        repeat(15) {
            val plain = ByteArray(rng.nextInt(50_000)).also(rng::nextBytes)
            val enc = crypto.encryptBlock(plain, ad)
            assertArrayEquals(plain, crypto.decryptBlock(enc, ad))
            if (enc.isNotEmpty()) {
                val bad = enc.copyOf()
                bad[rng.nextInt(bad.size)] = (bad[0].toInt() xor 0xFF).toByte()
                try {
                    crypto.decryptBlock(bad, ad)
                    fail("block tamper must fail")
                } catch (_: Exception) {
                }
            }
        }
    }
}