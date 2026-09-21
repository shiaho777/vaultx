package io.vaultx.app.core.crypto

/**
 * 密码强度粗估(纯函数,UI 指示条用)。
 *
 * 评分维度:长度 ≥8 / ≥12 各一分;字符类(数字/小写/大写/符号)≥2 类一分、≥3 类一分。
 * 长度 <6 时封顶"弱"。返回 0..4:0 空,1 弱,2 中,3 强,4 很强。
 *
 * 注意:这只是启发式——它不查字典、不算熵,提示用途而非安全承诺。
 */
object PasswordStrength {

    fun score(pw: CharSequence): Int {
        if (pw.isEmpty()) return 0
        var s = 0
        if (pw.length >= 8) s++
        if (pw.length >= 12) s++
        val classes = listOf(
            pw.any { it.isDigit() },
            pw.any { it.isLowerCase() },
            pw.any { it.isUpperCase() },
            pw.any { !it.isLetterOrDigit() },
        ).count { it }
        if (classes >= 2) s++
        if (classes >= 3) s++
        if (pw.length < 6) s = minOf(s, 1)
        return s.coerceIn(1, 4)
    }

    fun label(pw: CharSequence): String = when (score(pw)) {
        0 -> ""
        1 -> "弱"
        2 -> "中"
        3 -> "强"
        else -> "很强"
    }
}
