package io.vaultx.app

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.vaultx.app.R

/**
 * 快捷设置磁贴:一键锁定全部库。
 * 下拉通知栏点一下 → 所有会话 VMK 清零 + 索引缓存丢弃 + 图片缓存清空,
 * 不用回到应用里找锁定按钮——"离开即锁"的肌肉记忆入口。
 */
class LockTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val container = (application as VaultXApp).container
        container.session.lockAll()
        container.clearImageMemoryCache()
        qsTile?.let { tile ->
            tile.state = Tile.STATE_INACTIVE
            tile.label = getString(R.string.tile_locked)
            tile.updateTile()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    private fun refresh() {
        val container = (application as VaultXApp).container
        val anyUnlocked = container.session.anyUnlocked()
        qsTile?.let { tile ->
            tile.state = if (anyUnlocked) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = getString(if (anyUnlocked) R.string.tile_lock_all else R.string.tile_locked)
            tile.updateTile()
        }
    }
}