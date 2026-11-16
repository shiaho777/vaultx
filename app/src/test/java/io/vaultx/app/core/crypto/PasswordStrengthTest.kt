package io.vaultx.app.core.crypto

import io.vaultx.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordStrengthTest {

    @Test
    fun emptyAndWeak() {
        assertEquals(0, PasswordStrength.score(""))
        assertEquals(1, PasswordStrength.score("abc"))
        assertEquals(1, PasswordStrength.score("12345"))
    }

    @Test
    fun mediumAndStrong() {
        assertEquals(2, PasswordStrength.score("abcd1234"))
        assertEquals(3, PasswordStrength.score("Abcd1234"))
        assertEquals(4, PasswordStrength.score("Abcd1234!xyzW"))
    }

    @Test
    fun scoreIsMonotonicWithLength() {
        assertTrue(PasswordStrength.score("Ab1!efgh2345ijkl") >= PasswordStrength.score("Ab1!efgh"))
    }

    @Test
    fun labels() {
        assertEquals(0, PasswordStrength.labelRes(PasswordStrength.score("")))
        assertEquals(R.string.pw_weak, PasswordStrength.labelRes(PasswordStrength.score("abc")))
        assertEquals(R.string.pw_medium, PasswordStrength.labelRes(2))
        assertEquals(R.string.pw_strong, PasswordStrength.labelRes(3))
        assertEquals(R.string.pw_very_strong, PasswordStrength.labelRes(PasswordStrength.score("Abcdefgh1234!@")))
    }
}
