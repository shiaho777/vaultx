package io.vaultx.app.core.crypto

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
        assertEquals("弱", PasswordStrength.label("abc"))
        assertEquals("很强", PasswordStrength.label("Abcdefgh1234!@"))
    }
}
