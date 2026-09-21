package io.vaultx.app.core.vault

import io.vaultx.app.core.crypto.Argon2idKdf
import io.vaultx.app.core.crypto.KdfParams
import io.vaultx.app.core.crypto.KeyWrap
import io.vaultx.app.core.crypto.VaultCrypto
import io.vaultx.app.core.crypto.WrongPasswordException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.channels.SeekableByteChannel
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID

/**
 * 已解锁的库:元数据 + 加密上下文(VMK 存活于内存,锁定即清零)。
 * meta 在改密码/切换总密码后会被同步更新。
 *
 * [viaDecoy] = 本次会话由诱骗密码打开(诱骗 compartment)。真库与诱骗库共用
 * vaultId 与 blob 目录;管理类 UI(诱骗设置、生物识别)在诱骗会话中必须隐藏,
 * 否则会向诱骗密码持有者暴露"这是一个诱骗库"的事实。
 */
class UnlockedVault(
    var meta: VaultMeta,
    val crypto: VaultCrypto,
    val viaDecoy: Boolean = false,
) {
    val vaultId: String get() = meta.vaultId
}

/**
 * 库引擎:创建/解锁/改名/删除/改密码(密钥移植)/总密码开关/诱骗库/索引读写/blob 存储。
 *
 * 目录布局(filesDir/vaults/<vaultId>/):
 * ```
 * meta.vault            自描述头(明文;配置诱骗库后含第二组盐+包裹体)
 * index.dat|index.enc   索引(总密码开=加密)
 * index-decoy.enc       诱骗 compartment 的索引(永远加密,可选)
 * bio.wrap              VMK 的生物识别包裹体(可选)
 * blobs/<shard2>/<blobId>.vlt   分块加密内容(真库与诱骗库共用,靠 VMK 区分)
 * thumbs/<blobId>.vlt           加密缩略图
 * ```
 *
 * 根目录可注入,单测用临时目录。
 *
 * 密码参数约定:传入的 CharArray 由本类"接管"——函数返回前尽力清零。
 * 调用方持有的 String 状态无法在 JVM 里擦除,这是平台限制。
 */
class VaultManager(private val rootDir: File) {

    private val random = SecureRandom()

    /** 已解密索引的读缓存;锁定库时必须经 [onVaultLocked] 失效,否则明文列表会滞留堆内存。 */
    private val cacheLock = Any()
    private val indexCache = HashMap<String, VaultIndex>()

    init {
        File(rootDir, VAULTS_DIR).mkdirs()
    }

    // ---------------- 目录布局 ----------------

    private fun vaultsRoot() = File(rootDir, VAULTS_DIR)
    fun pendingRoot(): File = File(vaultsRoot(), PENDING_DIR).apply { mkdirs() }
    fun vaultDir(vaultId: String): File = File(vaultsRoot(), vaultId)
    private fun metaFile(vaultId: String) = File(vaultDir(vaultId), VaultMeta.FILE_NAME)
    private fun indexPlainFile(vaultId: String) = File(vaultDir(vaultId), INDEX_PLAIN)
    private fun indexEncFile(vaultId: String) = File(vaultDir(vaultId), INDEX_ENC)
    private fun indexDecoyEncFile(vaultId: String) = File(vaultDir(vaultId), INDEX_DECOY_ENC)
    private fun blobsDir(vaultId: String) = File(vaultDir(vaultId), BLOBS_DIR)
    private fun thumbsDir(vaultId: String) = File(vaultDir(vaultId), THUMBS_DIR)

    private fun decoyCacheKey(vaultId: String) = "$vaultId#decoy"

    fun blobFile(vaultId: String, blobId: String): File =
        File(File(blobsDir(vaultId), blobId.take(2)), "$blobId.vlt")

    fun thumbFile(vaultId: String, blobId: String): File =
        File(thumbsDir(vaultId), "$blobId.vlt")

    // ---------------- 库生命周期 ----------------

    fun listVaults(): List<VaultMeta> =
        vaultsRoot().listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.onEach { dir ->
                // 清理建库中断留下的孤儿目录(如 OOM 在写 meta 前崩溃)
                if (!File(dir, VaultMeta.FILE_NAME).isFile) dir.deleteRecursively()
            }
            ?.filter { File(it, VaultMeta.FILE_NAME).isFile }
            ?.mapNotNull { dir ->
                runCatching { VaultMeta.fromJson(File(dir, VaultMeta.FILE_NAME).readText()) }.getOrNull()
            }
            ?.sortedBy { it.createdAt }
            ?: emptyList()

    fun metaOf(vaultId: String): VaultMeta =
        VaultMeta.fromJson(metaFile(vaultId).readText())

    fun createVault(
        name: String,
        password: CharArray,
        masterGate: Boolean = false,
        kdfParams: KdfParams = KdfParams.DEFAULT,
        now: Long = System.currentTimeMillis(),
        /** 建库成功后回调已解锁会话(如登记进 VaultSessionHolder);传 null 则用完即清零。 */
        onCreated: ((UnlockedVault) -> Unit)? = null,
    ): VaultMeta {
        require(name.isNotBlank()) { "vault name must not be blank" }
        require(password.size >= MIN_PASSWORD_LENGTH) { "password too short" }
        try {
            val vaultId = UUID.randomUUID().toString()
            val dir = vaultDir(vaultId)
            check(!dir.exists()) { "vault id collision" }
            dir.mkdirs()
            blobsDir(vaultId).mkdirs()
            thumbsDir(vaultId).mkdirs()

            val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
            val vmk = VaultCrypto.generateVmk()
            val kek = try {
                Argon2idKdf.derive(password, salt, kdfParams)
            } catch (e: Throwable) {
                // 派生失败(如内存不足)时清除半成品目录,不留孤儿库壳
                dir.deleteRecursively()
                throw e
            }
            val wrapped = KeyWrap.wrap(kek, vmk)
            Arrays.fill(kek, 0.toByte())
            Arrays.fill(vmk, 0.toByte())

            val meta = VaultMeta.newMeta(
                vaultId = vaultId,
                name = name.trim(),
                createdAt = now,
                kdfParams = kdfParams,
                salt = salt,
                wrappedVmk = wrapped,
                masterGate = masterGate,
            )
            writeAtomic(metaFile(vaultId), meta.toJson().toByteArray(Charsets.UTF_8))

            // 写入初始空索引;onCreated 由回调方接管会话(VMK 不清零),否则用完即焚
            val unlocked = unlock(vaultId, password)
            saveIndex(unlocked, VaultIndex(updatedAt = now))
            if (onCreated != null) {
                onCreated(unlocked)
            } else {
                unlocked.crypto.zeroize()
            }
            return meta
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * 解锁。真密码与诱骗密码均可(失败表现一致)。
     *
     * [rewrapParams] 供"密码学敏捷性"使用:非空且不弱于库内现存参数时,
     * 解锁成功后用新参数+新盐把同一把 VMK 重新包裹并落盘——旧数据零重加密,
     * 参数升级对用户完全透明。verify 类方法绝不做重包裹(验证不得有副作用)。
     */
    @Throws(WrongPasswordException::class)
    fun unlock(
        vaultId: String,
        password: CharArray,
        rewrapParams: KdfParams? = null,
    ): UnlockedVault {
        try {
            val meta = metaOf(vaultId)
            try {
                val real = unlockWith(meta, meta.saltBytes(), meta.wrappedVmkBytes(), meta.kdfParams, password, viaDecoy = false)
                maybeRewrap(real, password, rewrapParams)
                return real
            } catch (e: WrongPasswordException) {
                // 真密码失败 → 若配置了诱骗库,再试诱骗密码;两者失败表现完全一致
                if (!meta.hasDecoy) throw e
                val decoy = unlockWith(
                    meta,
                    meta.saltDecoyBytes() ?: throw e,
                    meta.wrappedVmkDecoyBytes() ?: throw e,
                    meta.kdfDecoyParams,
                    password,
                    viaDecoy = true,
                )
                maybeRewrap(decoy, password, rewrapParams)
                return decoy
            }
        } finally {
            password.fill('\u0000')
        }
    }

    private fun unlockWith(
        meta: VaultMeta,
        salt: ByteArray,
        wrapped: ByteArray,
        params: KdfParams,
        password: CharArray,
        viaDecoy: Boolean,
    ): UnlockedVault {
        val kek = Argon2idKdf.derive(password, salt, params)
        try {
            val vmk = KeyWrap.unwrap(kek, wrapped)
            return UnlockedVault(meta, VaultCrypto(vmk), viaDecoy)
        } finally {
            Arrays.fill(kek, 0.toByte())
        }
    }

    /** 只升不降:参数相同或目标更弱时不动。密码此时仍存活。按会话所属链取当前参数。 */
    private fun maybeRewrap(unlocked: UnlockedVault, password: CharArray, target: KdfParams?) {
        val current = if (unlocked.viaDecoy) unlocked.meta.kdfDecoyParams else unlocked.meta.kdfParams
        if (target == null || target == current || !target.isAtLeast(current)) return
        rewrapVmk(unlocked, password, target)
    }

    /** 新盐 + 新参数派生 KEK → 重新包裹同一把 VMK → 原子重写 meta。真库/诱骗按会话路由,各自更新本链参数。 */
    private fun rewrapVmk(unlocked: UnlockedVault, password: CharArray, params: KdfParams) {
        val meta = unlocked.meta
        val newSalt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        val kek = Argon2idKdf.derive(password, newSalt, params)
        try {
            val newWrapped = KeyWrap.wrap(kek, unlocked.crypto.vmk)
            val b64 = java.util.Base64.getEncoder()
            val newMeta = if (unlocked.viaDecoy) {
                meta.copy(
                    kdfDecoyMemoryKiB = params.memoryKiB,
                    kdfDecoyIterations = params.iterations,
                    kdfDecoyParallelism = params.parallelism,
                    saltDecoy = b64.encodeToString(newSalt),
                    wrappedVmkDecoy = b64.encodeToString(newWrapped),
                )
            } else {
                meta.copy(
                    kdfMemoryKiB = params.memoryKiB,
                    kdfIterations = params.iterations,
                    kdfParallelism = params.parallelism,
                    salt = b64.encodeToString(newSalt),
                    wrappedVmk = b64.encodeToString(newWrapped),
                )
            }
            writeAtomic(metaFile(meta.vaultId), newMeta.toJson().toByteArray(Charsets.UTF_8))
            unlocked.meta = newMeta
        } finally {
            Arrays.fill(kek, 0.toByte())
        }
    }

    /** 仅校验密码(不解锁入库),用于导入归档前的快速验证。真密码与诱骗密码均通过。 */
    fun verifyPassword(meta: VaultMeta, password: CharArray): Boolean {
        try {
            return tryRealPassword(meta, password) || (meta.hasDecoy && tryDecoyPassword(meta, password))
        } finally {
            password.fill('\u0000')
        }
    }

    /** 只认真密码:移除诱骗库等敏感管理操作用,防止诱骗密码持有者反查/拆除诱骗。 */
    fun verifyRealPassword(meta: VaultMeta, password: CharArray): Boolean {
        try {
            return tryRealPassword(meta, password)
        } finally {
            password.fill('\u0000')
        }
    }

    private fun tryRealPassword(meta: VaultMeta, password: CharArray): Boolean {
        val kek = Argon2idKdf.derive(password, meta.saltBytes(), meta.kdfParams)
        return try {
            val vmk = KeyWrap.unwrap(kek, meta.wrappedVmkBytes())
            Arrays.fill(vmk, 0.toByte())
            true
        } catch (_: WrongPasswordException) {
            false
        } finally {
            Arrays.fill(kek, 0.toByte())
        }
    }

    private fun tryDecoyPassword(meta: VaultMeta, password: CharArray): Boolean {
        val salt = meta.saltDecoyBytes() ?: return false
        val wrapped = meta.wrappedVmkDecoyBytes() ?: return false
        val kek = Argon2idKdf.derive(password, salt, meta.kdfDecoyParams)
        return try {
            val vmk = KeyWrap.unwrap(kek, wrapped)
            Arrays.fill(vmk, 0.toByte())
            true
        } catch (_: WrongPasswordException) {
            false
        } finally {
            Arrays.fill(kek, 0.toByte())
        }
    }

    fun renameVault(vaultId: String, newName: String) {
        require(newName.isNotBlank()) { "vault name must not be blank" }
        val meta = metaOf(vaultId)
        writeAtomic(metaFile(vaultId), meta.copy(name = newName.trim()).toJson().toByteArray(Charsets.UTF_8))
    }

    fun deleteVault(vaultId: String) {
        vaultDir(vaultId).deleteRecursively()
        synchronized(cacheLock) {
            indexCache.remove(vaultId)
            indexCache.remove(decoyCacheKey(vaultId))
        }
    }

    fun vaultExists(vaultId: String): Boolean = metaFile(vaultId).isFile

    /** 库在磁盘上的总占用(bytes,含索引/meta/加密 blob/缩略图),供 UI 展示。 */
    fun vaultDiskUsage(vaultId: String): Long =
        vaultDir(vaultId).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // ---------------- 改密码(密钥移植) ----------------

    /**
     * 密钥移植:VMK 不变 → 新盐 + 新 KEK 重新包裹 → 原子重写 meta.vault。
     * 全部文件零重加密,旧数据无缝衔接;旧密码即刻失效。
     * 诱骗会话中调用 = 改诱骗密码;真会话中 = 改真密码。两者互不影响。
     * 生物识别包裹体包裹的是 VMK 本身,密码更换后仍然有效。
     */
    fun changePassword(unlocked: UnlockedVault, newPassword: CharArray) {
        require(newPassword.size >= MIN_PASSWORD_LENGTH) { "password too short" }
        try {
            // 按会话所属链取参数:改真密码用主链参数,改诱骗密码用诱骗链参数
            val params = if (unlocked.viaDecoy) unlocked.meta.kdfDecoyParams else unlocked.meta.kdfParams
            rewrapVmk(unlocked, newPassword, params)
        } finally {
            newPassword.fill('\u0000')
        }
    }

    // ---------------- 总密码开关 ----------------

    /**
     * 切换总密码:索引在明文/密文两种落盘形态间转换。
     * 顺序:先写新形态 → 更新 meta → 删旧形态,崩溃也不会两头落空。
     * 配置了诱骗库后总密码不可关闭——明文索引会把真库条目数泄露给诱骗密码持有者。
     */
    fun setMasterGate(unlocked: UnlockedVault, index: VaultIndex, enabled: Boolean, now: Long = System.currentTimeMillis()) {
        check(enabled || !unlocked.meta.hasDecoy) { "存在诱骗库时,总密码不能关闭" }
        val vaultId = unlocked.vaultId
        val plain = indexPlainFile(vaultId)
        val enc = indexEncFile(vaultId)
        if (enabled) {
            writeAtomic(enc, unlocked.crypto.encryptBlock(index.toJson().toByteArray(Charsets.UTF_8), VaultCrypto.indexAd(vaultId)))
        } else {
            writeAtomic(plain, index.toJson().toByteArray(Charsets.UTF_8))
        }
        val newMeta = unlocked.meta.copy(masterGate = enabled, indexEncrypted = enabled)
        writeAtomic(metaFile(vaultId), newMeta.toJson().toByteArray(Charsets.UTF_8))
        unlocked.meta = newMeta
        if (enabled) plain.delete() else enc.delete()
        synchronized(cacheLock) { indexCache[vaultId] = index }
    }

    // ---------------- 诱骗库 ----------------

    /**
     * 开启诱骗库:生成独立诱骗 VMK + 诱骗密码包裹体 + 空的诱骗索引。
     * 同时强制真索引加密(masterGate 开)——否则库列表免密可读条目数,诱骗形同虚设。
     * 崩溃安全:先写诱骗索引再写 meta;meta 未落盘前诱骗索引只是无害孤儿。
     */
    fun enableDecoy(unlocked: UnlockedVault, decoyPassword: CharArray, now: Long = System.currentTimeMillis()) {
        check(!unlocked.viaDecoy) { "诱骗会话中不能再配置诱骗" }
        check(!unlocked.meta.hasDecoy) { "诱骗库已存在" }
        require(decoyPassword.size >= MIN_PASSWORD_LENGTH) { "password too short" }
        try {
            val meta = unlocked.meta
            val vaultId = unlocked.vaultId

            // 真索引转密文(若当前为明文)
            if (!meta.indexEncrypted) {
                val idx = loadIndex(unlocked)
                writeAtomic(
                    indexEncFile(vaultId),
                    unlocked.crypto.encryptBlock(idx.toJson().toByteArray(Charsets.UTF_8), VaultCrypto.indexAd(vaultId)),
                )
                indexPlainFile(vaultId).delete()
            }

            val decoySalt = ByteArray(SALT_LENGTH).also(random::nextBytes)
            val decoyVmk = VaultCrypto.generateVmk()
            val kek = Argon2idKdf.derive(decoyPassword, decoySalt, meta.kdfParams)
            val wrappedDecoy = KeyWrap.wrap(kek, decoyVmk)
            Arrays.fill(kek, 0.toByte())

            val decoyCrypto = VaultCrypto(decoyVmk)
            writeAtomic(
                indexDecoyEncFile(vaultId),
                decoyCrypto.encryptBlock(VaultIndex(updatedAt = now).toJson().toByteArray(Charsets.UTF_8), VaultCrypto.decoyIndexAd(vaultId)),
            )
            decoyCrypto.zeroize()

            val newMeta = meta.copy(
                saltDecoy = java.util.Base64.getEncoder().encodeToString(decoySalt),
                wrappedVmkDecoy = java.util.Base64.getEncoder().encodeToString(wrappedDecoy),
                // 显式记录诱骗链参数,与主链解耦(之后任一链升级互不影响)
                kdfDecoyMemoryKiB = meta.kdfParams.memoryKiB,
                kdfDecoyIterations = meta.kdfParams.iterations,
                kdfDecoyParallelism = meta.kdfParams.parallelism,
                masterGate = true,
                indexEncrypted = true,
            )
            writeAtomic(metaFile(vaultId), newMeta.toJson().toByteArray(Charsets.UTF_8))
            unlocked.meta = newMeta
        } finally {
            decoyPassword.fill('\u0000')
        }
    }

    /** 移除诱骗库:销毁诱骗索引与包裹体。真库数据与密码完全不受影响;masterGate 保持开启(可手动关)。 */
    fun disableDecoy(unlocked: UnlockedVault) {
        check(!unlocked.viaDecoy) { "移除诱骗需要真密码会话" }
        val meta = unlocked.meta
        check(meta.hasDecoy) { "诱骗库不存在" }
        val vaultId = unlocked.vaultId
        indexDecoyEncFile(vaultId).delete()
        synchronized(cacheLock) { indexCache.remove(decoyCacheKey(vaultId)) }
        val newMeta = meta.copy(saltDecoy = null, wrappedVmkDecoy = null)
        writeAtomic(metaFile(vaultId), newMeta.toJson().toByteArray(Charsets.UTF_8))
        unlocked.meta = newMeta
    }

    // ---------------- 索引读写 ----------------

    /** 读取索引:加密形态需要已解锁的库;明文形态可直接读(库列表展示用)。带读缓存。 */
    fun loadIndex(unlocked: UnlockedVault): VaultIndex {
        val vaultId = unlocked.vaultId
        val cacheKey = if (unlocked.viaDecoy) decoyCacheKey(vaultId) else vaultId
        synchronized(cacheLock) { indexCache[cacheKey]?.let { return it.snapshot() } }
        val index = if (unlocked.viaDecoy) {
            val f = indexDecoyEncFile(vaultId)
            if (f.isFile) {
                VaultIndex.fromJson(
                    String(unlocked.crypto.decryptBlock(f.readBytes(), VaultCrypto.decoyIndexAd(vaultId)), Charsets.UTF_8),
                )
            } else {
                VaultIndex()
            }
        } else {
            val enc = indexEncFile(vaultId)
            val plain = indexPlainFile(vaultId)
            when {
                enc.isFile -> {
                    val bytes = unlocked.crypto.decryptBlock(enc.readBytes(), VaultCrypto.indexAd(vaultId))
                    VaultIndex.fromJson(bytes.toString(Charsets.UTF_8))
                }
                plain.isFile -> VaultIndex.fromJson(plain.readText())
                else -> VaultIndex()
            }
        }
        synchronized(cacheLock) { indexCache[cacheKey] = index.snapshot() }
        return index
    }

    /** 无需密码读取明文索引(仅 masterGate 关闭的库),用于库列表展示条目数。直读磁盘,不进缓存。 */
    fun loadPlainIndex(vaultId: String): VaultIndex? {
        val plain = indexPlainFile(vaultId)
        if (!plain.isFile) return null
        return runCatching { VaultIndex.fromJson(plain.readText()) }.getOrNull()
    }

    fun saveIndex(unlocked: UnlockedVault, index: VaultIndex) {
        val vaultId = unlocked.vaultId
        val stamped = index.copy(entries = index.entries.toMutableList(), updatedAt = System.currentTimeMillis())
        val json = stamped.toJson().toByteArray(Charsets.UTF_8)
        if (unlocked.viaDecoy) {
            // 诱骗索引只有密文形态,与真索引是两个独立文件
            writeAtomic(indexDecoyEncFile(vaultId), unlocked.crypto.encryptBlock(json, VaultCrypto.decoyIndexAd(vaultId)))
            synchronized(cacheLock) { indexCache[decoyCacheKey(vaultId)] = stamped }
        } else {
            if (unlocked.meta.indexEncrypted) {
                writeAtomic(indexEncFile(vaultId), unlocked.crypto.encryptBlock(json, VaultCrypto.indexAd(vaultId)))
            } else {
                writeAtomic(indexPlainFile(vaultId), json)
            }
            synchronized(cacheLock) { indexCache[vaultId] = stamped }
        }
    }

    /** 总密码关闭时,索引落盘无需密钥(仅元数据;内容仍全部加密)。 */
    fun savePlainIndex(vaultId: String, index: VaultIndex) {
        val meta = runCatching { metaOf(vaultId) }.getOrNull() ?: return
        check(!meta.indexEncrypted) { "vault index is encrypted; unlock first" }
        val snapshot = index.snapshot()
        writeAtomic(indexPlainFile(vaultId), snapshot.toJson().toByteArray(Charsets.UTF_8))
        synchronized(cacheLock) { indexCache[vaultId] = snapshot }
    }

    private fun VaultIndex.snapshot(): VaultIndex = copy(entries = entries.toMutableList())

    /**
     * 库被锁定时由会话层同步调用:把缓存里的明文索引一并丢弃。
     * 不这么做的话,已解密的文件列表会在堆里活到 GC,违背"锁定即清空"的语义。
     */
    fun onVaultLocked(vaultId: String?) {
        synchronized(cacheLock) {
            if (vaultId == null) {
                indexCache.clear()
            } else {
                indexCache.remove(vaultId)
                indexCache.remove(decoyCacheKey(vaultId))
            }
        }
    }

    // ---------------- blob / thumb ----------------

    fun newBlobId(): String = UUID.randomUUID().toString().replace("-", "")

    /** 供加密写入的目标文件(父目录一并创建)。 */
    fun prepareBlobSink(vaultId: String, blobId: String): File {
        val f = blobFile(vaultId, blobId)
        f.parentFile?.mkdirs()
        return f
    }

    // ---------------- 原子写 ----------------

    /**
     * 原子写:先写 tmp + fsync,再 rename;rename 失败重试一次,仍失败则抛错并保留旧文件——
     * 绝不原地半截覆写(崩溃瞬间不能把索引/密钥元数据写烂)。
     */
    fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(bytes)
                fos.fd.sync()
            }
            if (!tmp.renameTo(target) && !tmp.renameTo(target)) {
                throw IOException("atomic rename failed: $target")
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    // ---------------- blob / thumb 读写 ----------------

    fun blobExists(vaultId: String, blobId: String): Boolean = blobFile(vaultId, blobId).isFile

    fun blobSize(vaultId: String, blobId: String): Long = blobFile(vaultId, blobId).length()

    /** 顺序读解密流(导出/缩略图解码用)。 */
    fun openBlobStream(unlocked: UnlockedVault, blobId: String): InputStream =
        unlocked.crypto.decryptingStream(
            blobFile(unlocked.vaultId, blobId).inputStream(),
            VaultCrypto.blobAd(unlocked.vaultId, blobId),
        )

    /** 可随机定位的解密通道(ExoPlayer 按需拉取)。 */
    fun openBlobChannel(unlocked: UnlockedVault, blobId: String): SeekableByteChannel =
        unlocked.crypto.seekableDecryptingChannel(
            FileChannel.open(blobFile(unlocked.vaultId, blobId).toPath(), StandardOpenOption.READ),
            VaultCrypto.blobAd(unlocked.vaultId, blobId),
        )

    fun writeThumb(unlocked: UnlockedVault, blobId: String, thumbBytes: ByteArray) {
        writeAtomic(
            thumbFile(unlocked.vaultId, blobId),
            unlocked.crypto.encryptBlock(thumbBytes, VaultCrypto.thumbAd(unlocked.vaultId, blobId)),
        )
    }

    fun readThumb(unlocked: UnlockedVault, blobId: String): ByteArray? {
        val f = thumbFile(unlocked.vaultId, blobId)
        if (!f.isFile) return null
        return runCatching {
            unlocked.crypto.decryptBlock(f.readBytes(), VaultCrypto.thumbAd(unlocked.vaultId, blobId))
        }.getOrNull()
    }

    fun deleteBlob(vaultId: String, blobId: String) {
        blobFile(vaultId, blobId).delete()
        thumbFile(vaultId, blobId).delete()
    }

    // ---------------- 生物识别包裹体 ----------------

    fun bioWrapFile(vaultId: String): File = File(vaultDir(vaultId), BIO_WRAP)
    fun readBioWrap(vaultId: String): ByteArray? = bioWrapFile(vaultId).takeIf { it.isFile }?.readBytes()
    fun writeBioWrap(vaultId: String, bytes: ByteArray) = writeAtomic(bioWrapFile(vaultId), bytes)
    fun deleteBioWrap(vaultId: String) { bioWrapFile(vaultId).delete() }

    companion object {
        const val VAULTS_DIR = "vaults"
        const val PENDING_DIR = ".pending"
        const val INDEX_PLAIN = "index.dat"
        const val INDEX_ENC = "index.enc"
        const val INDEX_DECOY_ENC = "index-decoy.enc"
        const val BLOBS_DIR = "blobs"
        const val THUMBS_DIR = "thumbs"
        const val BIO_WRAP = "bio.wrap"
        const val SALT_LENGTH = 16
        const val MIN_PASSWORD_LENGTH = 4
    }
}
