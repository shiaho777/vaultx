package io.vaultx.app.ui.components

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 可内联预览的文本扩展名;两类界面(库/临时空间)共用。 */
val TEXT_EXTS = setOf(
    "txt", "md", "log", "json", "xml", "csv", "yaml", "yml", "ini", "conf",
    "html", "htm", "kt", "java", "py", "js", "ts", "c", "cpp", "h", "sh",
)

const val TEXT_PREVIEW_MAX_BYTES: Long = 512 * 1024

/** 文件名+大小是否适合内嵌文本预览。 */
fun isTextFileName(name: String, sizeBytes: Long): Boolean =
    name.substringAfterLast('.', "").lowercase() in TEXT_EXTS &&
        sizeBytes in 0..TEXT_PREVIEW_MAX_BYTES

/** 严格 UTF-8 解码(去 BOM);失败回退 GBK——中文 Windows 记事本默认存 GBK。 */
fun decodePreviewText(bytes: ByteArray): String {
    val text = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("GBK"))
    }
    return text.removePrefix("﻿")
}
