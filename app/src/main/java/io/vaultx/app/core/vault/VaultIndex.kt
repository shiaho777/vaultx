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
    /**
     * 回收站标记:非 null = 已删除(值为删除时刻 epoch ms),条目与 blob 保留至
     * 恢复/彻底删除/保留期(30 天)到期。浏览/搜索一律排除回收站条目。
     * 带默认值的字段新增,格式版本不变(旧端 ignoreUnknownKeys 可读)。
     */
    val trashedAt: Long? = null,
    /** 收藏(星标):跨目录快捷访问,不改变所在目录。 */
    val starred: Boolean = false,
    /** 用户标签(小写归一、去重):跨目录组织与筛选的轻量手段。 */
    val tags: List<String> = emptyList(),
) {
    val isFolder: Boolean get() = kind == MediaKind.FOLDER
    val isTrashed: Boolean get() = trashedAt != null
}

/**
 * `index.dat`(明文)或 `index.enc`(密文)的内容:条目表 + 更新时间。
 * 只含元数据;真正的文件内容永远是密文 blob。
 */
@Serializable
data class VaultIndex(
    val entries: MutableList<VaultEntry> = mutableListOf(),
    val updatedAt: Long = 0,
    /** 格式版本号:旧索引无此字段 → 默认 1;高于本端支持必须拒绝(见 [fromJson])。 */
    val formatVersion: Int = FORMAT_VERSION,
) {

    fun find(id: String): VaultEntry? = entries.firstOrNull { it.id == id }

    /** 某文件夹的直接子项(含子文件夹);null = 根目录。回收站条目不出现。 */
    fun childrenOf(folderId: String?): List<VaultEntry> =
        entries.filter { it.parentId == folderId && !it.isTrashed }

    /** 全局搜索:跨文件夹/分类,忽略大小写;文件名或标签命中均可;回收站条目不命中。 */
    fun search(query: String): List<VaultEntry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return entries.filter {
            !it.isTrashed && (
                it.name.contains(q, ignoreCase = true) ||
                    it.tags.any { tag -> tag.contains(q, ignoreCase = true) }
                )
        }
    }

    /** 全部收藏条目(跨目录,不含回收站)。 */
    fun starredEntries(): List<VaultEntry> = entries.filter { it.starred && !it.isTrashed }

    /** 含指定标签的条目(跨目录,不含回收站;标签已归一为小写)。 */
    fun entriesWithTag(tag: String): List<VaultEntry> =
        entries.filter { !it.isTrashed && tag.lowercase() in it.tags }

    /** 库内全部标签(去重、字典序),供筛选列表展示。 */
    fun allTags(): List<String> =
        entries.asSequence().filter { !it.isTrashed }.flatMap { it.tags }.distinct().sorted().toList()

    /**
     * 回收站顶层条目:被标记删除、且父级不在回收站(或父级已不存在)。
     * 文件夹被删时子孙同批标记,只展示顶层避免重复计数。
     */
    fun trashedRoots(): List<VaultEntry> =
        entries.filter { it.isTrashed && (it.parentId == null || find(it.parentId)?.isTrashed != true) }

    /** 文件夹的完整子孙条目 id 集(删除/移动时级联用)。父链成环的损坏索引下也保证终止。 */
    fun descendantIds(folderId: String): Set<String> {
        val out = LinkedHashSet<String>()
        var frontier = listOf(folderId)
        while (frontier.isNotEmpty()) {
            // 已收录的 id 不再扩展——否则 A→B→A 环会无限扩散
            val next = entries.filter { it.parentId in frontier && it.id !in out }
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
        /** 当前索引格式版本。带默认值的字段新增不升版本;语义变更才递增。 */
        const val FORMAT_VERSION = 1

        /** 回收站保留期:到期自动彻底删除(进库时惰性清扫)。 */
        const val TRASH_RETENTION_MS = 30L * 24 * 3600 * 1000

        /** 单条目标签上限与单标签长度上限(防索引膨胀/界面溢出)。 */
        const val MAX_TAGS_PER_ENTRY = 20
        const val MAX_TAG_LENGTH = 32

        /** 标签归一:去首尾空白、压缩内部空白、小写、截断;空串返回 null。 */
        fun normalizeTag(raw: String): String? {
            val t = raw.trim().replace(Regex("\\s+"), " ").lowercase()
            if (t.isEmpty()) return null
            return t.take(MAX_TAG_LENGTH)
        }

        // encodeDefaults:formatVersion 等默认值字段必须落盘,否则版本锚点形同虚设
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun fromJson(text: String): VaultIndex {
            val index = json.decodeFromString(serializer(), text)
            require(index.formatVersion <= FORMAT_VERSION) {
                "unsupported index format version ${index.formatVersion} (max $FORMAT_VERSION); upgrade the app"
            }
            return index
        }
    }
}
