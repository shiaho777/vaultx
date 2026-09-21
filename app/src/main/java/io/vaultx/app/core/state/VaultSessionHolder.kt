package io.vaultx.app.core.state

import android.os.SystemClock
import io.vaultx.app.core.vault.UnlockedVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 内存中的已解锁库登记表。锁定的含义:
 *  1. VMK 从内存清零(crypto.zeroize)
 *  2. 通知 UI 弹回锁定界面(lockEvents)
 *  3. 同步回调 [onLocked](如丢弃缓存里的明文索引)
 * 进程死亡天然等于锁定——密钥从不落盘。
 *
 * 后台超时用定时任务在**后台期间**就完成锁定,而不是回前台才补锁:
 * 挂起十分钟不锁的窗口不存在。时钟取 [SystemClock.elapsedRealtime](单调),
 * 不受用户改系统时间影响;单测注入假时钟与作用域。
 *
 * @param scope 定时锁定任务的作用域(传 null 则退化为"回前台补锁"的旧行为)
 * @param clock 单调时钟(毫秒),单测注入
 */
class VaultSessionHolder(
    private val scope: CoroutineScope? = null,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val onLocked: (vaultId: String?) -> Unit = {},
) {

    private val lock = Any()
    private val map = HashMap<String, UnlockedVault>()

    private val _revision = MutableStateFlow(0)
    /** 观察解锁状态变化的节拍器(值本身无意义)。 */
    val revision: StateFlow<Int> = _revision

    /** 锁定事件:element = 被锁定的 vaultId;null = 全部锁定。 */
    private val _lockEvents = MutableSharedFlow<String?>(extraBufferCapacity = 32)
    val lockEvents: SharedFlow<String?> = _lockEvents

    private var backgroundAt: Long? = null
    private var pendingTimeoutSec: Int = 0
    private var timeoutJob: Job? = null

    fun get(vaultId: String): UnlockedVault? = synchronized(lock) { map[vaultId] }

    fun put(vault: UnlockedVault) {
        // 同一 vaultId 的旧会话(如真库会话换诱骗会话)被顶替时,其 VMK 一并清零
        val replaced = synchronized(lock) {
            val prev = map[vault.vaultId]
            map[vault.vaultId] = vault
            _revision.value += 1
            prev
        }
        if (replaced != null && replaced !== vault) replaced.crypto.zeroize()
    }

    fun lock(vaultId: String) {
        val removed: UnlockedVault
        synchronized(lock) {
            val r = map.remove(vaultId) ?: return
            _revision.value += 1
            removed = r
        }
        removed.crypto.zeroize()
        onLocked(vaultId)
        _lockEvents.tryEmit(vaultId)
    }

    fun lockAll() {
        val victims = synchronized(lock) {
            if (map.isEmpty()) return
            val v = map.values.toList()
            map.clear()
            _revision.value += 1
            v
        }
        victims.forEach { it.crypto.zeroize() }
        onLocked(null)
        _lockEvents.tryEmit(null)
    }

    fun isUnlocked(vaultId: String): Boolean = synchronized(lock) { map.containsKey(vaultId) }

    /**
     * 进入后台:立即锁定或安排定时锁定。
     * [timeoutSeconds] <= 0 = 立即;否则到点即使仍在前台以外的任何地方,主动清零。
     */
    fun onBackground(timeoutSeconds: Int) {
        if (timeoutSeconds <= 0) {
            lockAll()
            return
        }
        synchronized(lock) {
            backgroundAt = clock()
            pendingTimeoutSec = timeoutSeconds
            timeoutJob?.cancel()
            timeoutJob = scope?.launch {
                delay(timeoutSeconds * 1000L)
                lockAll()
            }
        }
    }

    /** 回到前台:取消未到点的定时任务;已超时(定时器没跑成/进程被冻结)则补锁。 */
    fun onForeground() {
        val timedOut: Boolean
        synchronized(lock) {
            timeoutJob?.cancel()
            timeoutJob = null
            val at = backgroundAt ?: return
            backgroundAt = null
            timedOut = (clock() - at) >= pendingTimeoutSec * 1000L
        }
        if (timedOut) lockAll()
    }
}
