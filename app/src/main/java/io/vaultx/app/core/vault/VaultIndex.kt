package io.vaultx.app.core.vault

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 五类文件管理:图片/视频/音频/文件/文件夹。 */
enum class MediaKind { IMAGE, VIDEO, AUDIO, OTHER, FOLDER }

/**
 * 索引里的一条目。文件类条目指向加密 blob([blobId]);文件夹是虚条目([blobId]==null)。
 * [parentId] = 所在文件夹条目的 id,null = 根目录。
 */
@Serializable
data class VaultEntry(
    val id: String,
    val name: String,
    val kind: MediaKind,
    val blobId: String? = null,
    val sizeBytes: Long = 0,
    val parentId: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val mimeType: String? = null,
    /** 是否已生成加密缩略图(thumbs/<blobId>.vlt) */
    val hasThumb: Boolean = false,
) {
    val isFolder: Boolean get() = kind == MediaKind.FOLDER
}

/**
 * `index.dat`(明文)或 `index.enc`(密文)的内容:条目表 + 更新时间。
 * 只含元数据;真正的文件内容永远是密文 blob。
 */
@Serializable
data class VaultIndex(
    val entries: MutableList<VaultEntry> = mutableListOf(),
    val updatedAt: Long = 0,
) {

    fun find(id: String): VaultEntry? = entries.firstOrNull { it.id == id }

    /** 某文件夹的直接子项(含子文件夹);null = 根目录。 */
    fun childrenOf(folderId: String?): List<VaultEntry> =
        entries.filter { it.parentId == folderId }

    /** 全局搜索:跨文件夹/分类,忽略大小写,跳过文件夹自身。 */
    fun search(query: String): List<VaultEntry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return entries.filter { !it.isFolder && it.name.contains(q, ignoreCase = true) }
    }

    /** 文件夹的完整子孙条目 id 集(删除/移动时级联用)。 */
    fun descendantIds(folderId: String): Set<String> {
        val out = LinkedHashSet<String>()
        var frontier = listOf(folderId)
        while (frontier.isNotEmpty()) {
            val next = entries.filter { it.parentId in frontier }
            next.forEach { out.add(it.id) }
            frontier = next.map { it.id }
        }
        return out
    }

    /** 新增条目(自动分配 id/时间戳)。 */
    fun addEntry(
        name: String,
        kind: MediaKind,
        blobId: String? = null,
        sizeBytes: Long = 0,
        parentId: String? = null,
        mimeType: String? = null,
        hasThumb: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): VaultEntry {
        val entry = VaultEntry(
            id = UUID.randomUUID().toString(),
            name = name,
            kind = kind,
            blobId = blobId,
            sizeBytes = sizeBytes,
            parentId = parentId,
            createdAt = now,
            updatedAt = now,
            mimeType = mimeType,
            hasThumb = hasThumb,
        )
        entries.add(entry)
        return entry
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromJson(text: String): VaultIndex = json.decodeFromString(serializer(), text)
    }
}
