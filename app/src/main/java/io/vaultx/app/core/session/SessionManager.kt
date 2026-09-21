package io.vaultx.app.core.session

import io.vaultx.app.core.crypto.KdfParams
import io.vaultx.app.core.crypto.PortableCipher
import io.vaultx.app.core.crypto.PortableFormatException
import io.vaultx.app.core.crypto.WrongPasswordException
import io.vaultx.app.core.vault.MediaKind
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 无锁模式的临时空间——纯文件操作,纯 JVM 可测。
 *
 * 会话文件落在 `cacheDir/sessions/` 下,退出即焚(SessionViewModel 退出时
 * [destroy];进程死亡的残骸由 AppContainer 冷启动清扫)。
 * 文件名即显示名,重名按 "name (2).ext" 消解——不引入显示名/存储名分离的坑。
 */
class SessionManager(private val cacheDir: File) {

    data class SessionFile(
        val storedName: String,
        val sizeBytes: Long,
        val kind: MediaKind,
    ) {
        val displayName: String get() = storedName
    }

    private fun sessionDir(): File = File(cacheDir, SESSIONS_DIR)

    private fun kindOf(name: String): MediaKind = when (name.substringAfterLast('.', "").lowercase()) {
        in IMAGE_EXTS -> MediaKind.IMAGE
        in VIDEO_EXTS -> MediaKind.VIDEO
        in AUDIO_EXTS -> MediaKind.AUDIO
        else -> MediaKind.OTHER
    }

    fun listFiles(): List<SessionFile> =
        sessionDir().listFiles()
            ?.filter { it.isFile }
            ?.map { SessionFile(it.name, it.length(), kindOf(it.name)) }
            ?.sortedBy { it.storedName }
            ?: emptyList()

    fun file(storedName: String): File = File(sessionDir(), storedName)

    /** 导入一个文件进临时空间;重名自动消解。返回最终条目。 */
    fun importFile(name: String, input: InputStream): SessionFile {
        val finalName = uniqueName(name)
        val target = File(sessionDir().apply { mkdirs() }, finalName)
        target.outputStream().use { out -> input.copyTo(out) }
        return SessionFile(finalName, target.length(), kindOf(finalName))
    }

    /** 改名;目标名冲突时仍自动消解,返回新条目。 */
    fun rename(storedName: String, newName: String): SessionFile? {
        val src = file(storedName)
        if (!src.isFile || newName.isBlank()) return null
        val finalName = if (newName == storedName) storedName else uniqueName(newName)
        val dest = File(sessionDir(), finalName)
        if (!src.renameTo(dest)) return null
        return SessionFile(finalName, dest.length(), kindOf(finalName))
    }

    fun delete(storedName: String) {
        file(storedName).delete()
    }

    /**
     * 加密转换:会话文件 → `.vlt` 便携密文(一次性密码)。
     * @param output 目的流(SAF 打开的 uri 输出)
     */
    fun exportAsVlt(storedName: String, password: CharArray, output: OutputStream, params: KdfParams = KdfParams.DEFAULT) {
        val src = file(storedName)
        require(src.isFile) { "no such session file" }
        src.inputStream().use { ins -> PortableCipher.encryptTo(password, ins, output, params) }
    }

    /**
     * 打开 `.vlt` 进临时空间预览:解密落进会话目录。
     * @throws WrongPasswordException 密码错
     * @throws PortableFormatException 不是 .vlt 文件
     */
    fun importVlt(displayName: String, input: InputStream, password: CharArray): SessionFile {
        // 去掉 .vlt 后缀作为展示名
        val base = displayName.removeSuffix(".vlt")
        val finalName = uniqueName(base)
        val target = File(sessionDir().apply { mkdirs() }, finalName)
        try {
            target.outputStream().use { out -> PortableCipher.decryptTo(password, input, out) }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        return SessionFile(finalName, target.length(), kindOf(finalName))
    }

    /** 会话即焚:清空全部临时文件。 */
    fun destroy() {
        sessionDir().deleteRecursively()
    }

    fun hasFiles(): Boolean = sessionDir().listFiles()?.any { it.isFile } == true

    /** "name (2).ext" 消解,与库里同一约定。 */
    private fun uniqueName(name: String): String {
        val taken = sessionDir().listFiles()?.filter { it.isFile }?.map { it.name }?.toSet() ?: emptySet()
        if (name !in taken) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (true) {
            val candidate = "$stem ($i)$ext"
            if (candidate !in taken) return candidate
            i++
        }
    }

    companion object {
        const val SESSIONS_DIR = "sessions"
        private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
        private val VIDEO_EXTS = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "ts")
        private val AUDIO_EXTS = setOf("mp3", "aac", "flac", "wav", "ogg", "m4a", "opus", "wma")
    }
}
