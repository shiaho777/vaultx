package io.vaultx.app.core.crypto

/**
 * Argon2id 参数档。存进库头(meta.vault),以后可调——解锁时可透明升级(只升不降)。
 *
 * [DEFAULT] 64 MiB / t3 / p2:native libsodium 加持下的新标准档,
 * 远超 OWASP 下限(19 MiB/t2/p1),中端机解锁仍在亚秒级。
 * [HIGH_SECURITY] 128 MiB / t4 / p2:建库时可选的增强档,抗离线爆破再翻倍。
 * 旧库按头内旧参数解锁,首次解锁自动透明升级到 DEFAULT(零重加密)。
 */
data class KdfParams(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
) {

    /** this 是否不弱于 other(三维都 ≥)。用于"参数升级只能变强、不得降级"。 */
    fun isAtLeast(other: KdfParams): Boolean =
        memoryKiB >= other.memoryKiB &&
            iterations >= other.iterations &&
            parallelism >= other.parallelism

    companion object {
        val DEFAULT = KdfParams(memoryKiB = 64 * 1024, iterations = 3, parallelism = 2)
        val HIGH_SECURITY = KdfParams(memoryKiB = 128 * 1024, iterations = 4, parallelism = 2)
        /** 单测专用低参数,跑得快。 */
        val TEST = KdfParams(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1)
    }
}
