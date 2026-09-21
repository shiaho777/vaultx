package io.vaultx.app.core.session

import io.vaultx.app.core.crypto.KdfParams
import io.vaultx.app.core.crypto.PortableFormatException
import io.vaultx.app.core.crypto.WrongPasswordException
import io.vaultx.app.core.vault.MediaKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var session: SessionManager

    @Before
    fun setUp() {
        session = SessionManager(tmp.root)
    }

    @Test
    fun importListAndKindClassification() {
        val f = session.importFile("pic.PNG", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        assertEquals("pic.PNG", f.storedName)
        assertEquals(MediaKind.IMAGE, f.kind)
        assertEquals(3, f.sizeBytes)
        assertEquals(listOf("pic.PNG"), session.listFiles().map { it.displayName })
        assertTrue(session.hasFiles())
    }

    @Test
    fun importDuplicateNameGetsSuffix() {
        session.importFile("a.txt", ByteArrayInputStream(byteArrayOf(1)))
        val second = session.importFile("a.txt", ByteArrayInputStream(byteArrayOf(2)))
        assertEquals("a (2).txt", second.storedName)
        val third = session.importFile("a.txt", ByteArrayInputStream(byteArrayOf(3)))
        assertEquals("a (3).txt", third.storedName)
        assertEquals(3, session.listFiles().size)
    }

    @Test
    fun renameAndDelete() {
        session.importFile("old.bin", ByteArrayInputStream(byteArrayOf(9)))
        val renamed = session.rename("old.bin", "new.bin")!!
        assertEquals("new.bin", renamed.storedName)
        // 重命名到已占名 → 自动消解
        session.importFile("taken.bin", ByteArrayInputStream(byteArrayOf(1)))
        val r2 = session.rename("new.bin", "taken.bin")!!
        assertEquals("taken (2).bin", r2.storedName)
        session.delete("taken.bin")
        assertEquals(listOf("taken (2).bin"), session.listFiles().map { it.storedName })
    }

    @Test
    fun vltExportImportRoundtrip() {
        val data = "portable secret".toByteArray()
        session.importFile("note.txt", ByteArrayInputStream(data))
        val out = ByteArrayOutputStream()
        session.exportAsVlt("note.txt", "oncepw".toCharArray(), out, KdfParams.TEST)
        val vltBytes = out.toByteArray()

        session.destroy()
        assertFalse(session.hasFiles())

        val back = session.importVlt("note.txt.vlt", ByteArrayInputStream(vltBytes), "oncepw".toCharArray())
        assertEquals("note.txt", back.storedName)
        assertArrayEquals(data, session.file(back.storedName).readBytes())
    }

    @Test
    fun vltImportWrongPasswordRejected() {
        session.importFile("s.txt", ByteArrayInputStream("x".toByteArray()))
        val out = ByteArrayOutputStream()
        session.exportAsVlt("s.txt", "right".toCharArray(), out, KdfParams.TEST)
        try {
            session.importVlt("s.vlt", ByteArrayInputStream(out.toByteArray()), "wrong".toCharArray())
            fail()
        } catch (_: WrongPasswordException) {
        }
        // 失败不留半截文件
        assertEquals(listOf("s.txt"), session.listFiles().map { it.storedName })
    }

    @Test
    fun vltImportNonPortableRejected() {
        try {
            session.importVlt("x.vlt", ByteArrayInputStream("not a vlt".toByteArray()), "pw".toCharArray())
            fail()
        } catch (e: Exception) {
            assertTrue(e is PortableFormatException || e is WrongPasswordException || e is java.io.IOException)
        }
    }

    @Test
    fun destroyClearsEverything() {
        session.importFile("a", ByteArrayInputStream(byteArrayOf(1)))
        session.importFile("b", ByteArrayInputStream(byteArrayOf(2)))
        session.destroy()
        assertFalse(session.hasFiles())
        assertTrue(session.listFiles().isEmpty())
    }
}
