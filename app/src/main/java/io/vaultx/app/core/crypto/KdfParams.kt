package io.vaultx.app.core.crypto

/**
 * Argon2id 参数档。存进库头(meta.vault),以后可调——解锁时可透明升级(只升不降)。
 *
 * [DEFAULT] 32 MiB:移动端内存约束下的稳妥档(OWASP 建议下限 ~19 MiB 之上)。
 * [HIGH_SECURITY] 64 MiB:建库时可选的增强档,派生更慢、抗离线爆破更强。
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
        val DEFAULT = KdfParams(memoryKiB = 32 * 1024, iterations = 3, parallelism = 1)
        val HIGH_SECURITY = KdfParams(memoryKiB = 64 * 1024, iterations = 4, parallelism = 1)
        /** 单测专用低参数,跑得快。 */
        val TEST = KdfParams(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1)
    }
}
