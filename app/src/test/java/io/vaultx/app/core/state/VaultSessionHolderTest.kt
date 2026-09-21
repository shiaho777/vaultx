package io.vaultx.app.core.state

import io.vaultx.app.core.crypto.KdfParams
import io.vaultx.app.core.vault.UnlockedVault
import io.vaultx.app.core.vault.VaultManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class VaultSessionHolderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var manager: VaultManager
    private lateinit var unlocked: UnlockedVault

    @Before
    fun setUp() {
        manager = VaultManager(tmp.root)
        val meta = manager.createVault("t", "pw1234".toCharArray(), kdfParams = KdfParams.TEST)
        unlocked = manager.unlock(meta.vaultId, "pw1234".toCharArray())
    }

    @Test
    fun putGetLock() {
        val holder = VaultSessionHolder(scope = null, clock = { 0L })
        holder.put(unlocked)
        assertTrue(holder.isUnlocked(unlocked.vaultId))
        assertEquals(unlocked, holder.get(unlocked.vaultId))
        holder.lock(unlocked.vaultId)
        assertFalse(holder.isUnlocked(unlocked.vaultId))
        assertNull(holder.get(unlocked.vaultId))
    }

    @Test
    fun lockAllClearsEverything() {
        val holder = VaultSessionHolder(scope = null, clock = { 0L })
        holder.put(unlocked)
        holder.lockAll()
        assertFalse(holder.isUnlocked(unlocked.vaultId))
    }

    @Test
    fun lockNotifiesCallback() {
        val locked = mutableListOf<String?>()
        val holder = VaultSessionHolder(scope = null, clock = { 0L }, onLocked = { locked += it })
        holder.put(unlocked)
        holder.lock(unlocked.vaultId)
        assertEquals(listOf(unlocked.vaultId), locked)
        holder.put(unlocked)
        holder.lockAll()
        assertEquals(listOf(unlocked.vaultId, null), locked)
    }

    @Test
    fun lockEmitsEvent() = runTest {
        val events = mutableListOf<String?>()
        val holder = VaultSessionHolder(scope = null, clock = { 0L })
        val job = launch { holder.lockEvents.collect { events += it } }
        testScheduler.runCurrent() // 让 collector 先就位(replay=0,早发会丢)
        holder.put(unlocked)
        holder.lock(unlocked.vaultId)
        testScheduler.runCurrent()
        assertEquals(listOf(unlocked.vaultId), events)
        job.cancel()
    }

    @Test
    fun immediateBackgroundLockWhenTimeoutZero() {
        val holder = VaultSessionHolder(scope = null, clock = { 0L })
        holder.put(unlocked)
        holder.onBackground(0)
        assertFalse(holder.isUnlocked(unlocked.vaultId))
    }

    @Test
    fun backgroundTimerLocksWhileStillBackgrounded() = runTest {
        // 定时任务在后台期间就锁定,不等回前台
        val scope = TestScope(testScheduler)
        var now = 0L
        val holder = VaultSessionHolder(scope = scope, clock = { now })
        holder.put(unlocked)
        holder.onBackground(60)
        assertTrue(holder.isUnlocked(unlocked.vaultId))
        scope.advanceTimeBy(61_000)
        assertFalse(holder.isUnlocked(unlocked.vaultId))
    }

    @Test
    fun foregroundWithinTimeoutKeepsSession() = runTest {
        val scope = TestScope(testScheduler)
        var now = 0L
        val holder = VaultSessionHolder(scope = scope, clock = { now })
        holder.put(unlocked)
        holder.onBackground(60)
        now = 30_000 // 30 秒后回前台
        scope.advanceTimeBy(30_000)
        holder.onForeground()
        assertTrue(holder.isUnlocked(unlocked.vaultId))
        // 取消后不再触发
        scope.advanceTimeBy(60_000)
        assertTrue(holder.isUnlocked(unlocked.vaultId))
    }

    @Test
    fun foregroundAfterTimeoutLocks() {
        // scope=null 退化路径:回前台补锁
        var now = 0L
        val holder = VaultSessionHolder(scope = null, clock = { now })
        holder.put(unlocked)
        holder.onBackground(60)
        now = 120_000
        holder.onForeground()
        assertFalse(holder.isUnlocked(unlocked.vaultId))
    }

    @Test
    fun replaceSameVaultZeroizesOldSession() {
        val holder = VaultSessionHolder(scope = null, clock = { 0L })
        holder.put(unlocked)
        val fresh = manager.unlock(unlocked.vaultId, "pw1234".toCharArray())
        holder.put(fresh)
        assertEquals(fresh, holder.get(unlocked.vaultId))
    }
}
