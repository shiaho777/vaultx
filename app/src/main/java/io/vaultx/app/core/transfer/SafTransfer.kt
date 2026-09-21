package io.vaultx.app.core.transfer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.InputStream
import java.io.OutputStream

/**
 * SAF 适配层——把 Storage Access Framework 的 Uri 世界接到 [TransferEngine] 的纯流端口上。
 *
 * - 导入:文件 uri / 目录树 uri 都包成 [TransferEngine.ImportSource];
 *   目录遍历用 `DocumentsContract` 一次批量 query(绕开 `DocumentFile.listFiles`
 *   逐项 IPC 的性能坑)。
 * - 导出:`createDocument` 建目标文件,记住返回的确切 docUri——半成品清理只删
 *   本次创建的文档,不误伤同名旧文件。
 */
class SafTransfer(private val context: Context) {

    // ---------------- 导入源 ----------------

    /** 单文件 uri → ImportSource。 */
    fun fileSource(uri: Uri): TransferEngine.ImportSource {
        val (name, size, mime) = queryMeta(uri)
        return object : TransferEngine.ImportSource {
            override val name = name ?: uri.lastPathSegment ?: "unnamed"
            override val sizeBytes = size
            override val mimeType = mime ?: context.contentResolver.getType(uri)
            override val isDirectory = false
            override fun open(): InputStream =
                context.contentResolver.openInputStream(uri)
                    ?: throw java.io.FileNotFoundException(uri.toString())
            override fun children(): List<TransferEngine.ImportSource> = emptyList()
        }
    }

    /** 目录树 uri(ACTION_OPEN_DOCUMENT_TREE 的返回)→ ImportSource。 */
    fun treeSource(treeUri: Uri): TransferEngine.ImportSource {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        return dirSource(docUri, displayNameOf(docUri) ?: "folder")
    }

    private fun dirSource(docUri: Uri, name: String): TransferEngine.ImportSource =
        object : TransferEngine.ImportSource {
            override val name = name
            override val sizeBytes = 0L
            override val mimeType = DocumentsContract.Document.MIME_TYPE_DIR
            override val isDirectory = true
            override fun open(): InputStream = throw UnsupportedOperationException("directory has no stream")
            override fun children(): List<TransferEngine.ImportSource> = listChildren(docUri)
        }

    /** 一次批量 query 列目录(单 IPC 拿全部子项的 id/name/size/mime)。 */
    private fun listChildren(dirDocUri: Uri): List<TransferEngine.ImportSource> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            dirDocUri,
            DocumentsContract.getDocumentId(dirDocUri),
        )
        val out = mutableListOf<TransferEngine.ImportSource>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val childDocUri = DocumentsContract.buildDocumentUriUsingTree(dirDocUri, c.getString(0))
                val childName = c.getString(1) ?: "unnamed"
                val childSize = c.getLong(2)
                val childMime = c.getString(3)
                out += if (childMime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    dirSource(childDocUri, childName)
                } else {
                    fileLikeSource(childDocUri, childName, childSize, childMime)
                }
            }
        }
        return out
    }

    private fun fileLikeSource(docUri: Uri, name: String, size: Long, mime: String?) =
        object : TransferEngine.ImportSource {
            override val name = name
            override val sizeBytes = size
            override val mimeType = mime
            override val isDirectory = false
            override fun open(): InputStream =
                context.contentResolver.openInputStream(docUri)
                    ?: throw java.io.FileNotFoundException(docUri.toString())
            override fun children(): List<TransferEngine.ImportSource> = emptyList()
        }

    private fun displayNameOf(docUri: Uri): String? =
        queryMeta(docUri).first

    private fun queryMeta(uri: Uri): Triple<String?, Long, String?> {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    Triple(c.getString(0), c.getLong(1), c.getString(2))
                } else Triple(null, 0L, null)
            } ?: Triple(null, 0L, null)
        }.getOrDefault(Triple(null, 0L, null))
    }

    // ---------------- 导出目的端 ----------------

    /** 往用户选的目录树导出;按 relPath 逐级建目录。 */
    fun exportSinkFactory(treeUri: Uri): TransferEngine.ExportSinkFactory =
        SafExportSinkFactory(context, treeUri)

    private class SafExportSinkFactory(
        private val context: Context,
        private val treeUri: Uri,
    ) : TransferEngine.ExportSinkFactory {

        private val dirCache = HashMap<String, Uri>()

        override fun create(relPath: String, mimeType: String?): TransferEngine.ExportSink {
            val parts = relPath.split('/')
            val fileName = parts.last()
            val dirUri = ensureDir(parts.dropLast(1))
            val docUri = DocumentsContract.createDocument(
                context.contentResolver,
                dirUri,
                mimeType ?: "application/octet-stream",
                fileName,
            ) ?: throw java.io.IOException("createDocument failed: $relPath")
            return object : TransferEngine.ExportSink {
                override val output: OutputStream =
                    context.contentResolver.openOutputStream(docUri)
                        ?: throw java.io.IOException("openOutputStream failed: $docUri")

                override fun commit() {}

                /** 只删本次 create 返回的确切 docUri——不同名旧文件兜底删。 */
                override fun abort() {
                    runCatching {
                        DocumentsContract.deleteDocument(context.contentResolver, docUri)
                    }
                }
            }
        }

        /** 逐级建目录(已存在则复用),缓存路径 → docUri。 */
        private fun ensureDir(parts: List<String>): Uri {
            var docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
            var path = ""
            for (part in parts) {
                path = if (path.isEmpty()) part else "$path/$part"
                docUri = dirCache.getOrPut(path) { findOrCreateDir(docUri, part) }
            }
            return docUri
        }

        private fun findOrCreateDir(parentDocUri: Uri, name: String): Uri {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                parentDocUri,
                DocumentsContract.getDocumentId(parentDocUri),
            )
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR && c.getString(1) == name) {
                        return DocumentsContract.buildDocumentUriUsingTree(parentDocUri, c.getString(0))
                    }
                }
            }
            return DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name,
            ) ?: throw java.io.IOException("create dir failed: $name")
        }
    }
}
