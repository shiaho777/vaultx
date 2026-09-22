<div align="center">

# 🔐 VaultX

**你的文件，只有你能打开。**

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![API](https://img.shields.io/badge/API-26%2B-brightgreen.svg)](https://developer.android.com/about/versions/oreo)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-blue.svg)](https://kotlinlang.org)

*开源 · 零权限 · 纯本地 · 不联网*

</div>

---

## ✨ 功能特性

### 🔒 有锁模式 — 持久加密保险库
- **多库架构**：创建多个独立库，每个库有自己的密码
- **五类文件管理**：图片 / 视频 / 音频 / 文件 / 文件夹
- **加密后不可见**：存入的文件在系统相册和文件管理器中完全消失
- **网格/列表双视图**：视图与排序偏好持久记忆；搜索跨目录命中并显示所在路径
- **媒体预览**：图片同目录翻页 + 双击/双指缩放，视频/音频同目录播放列表连播，文本文件（UTF-8/GBK）内嵌预览
- **文件操作**：导入（含整棵目录树）/ 重命名 / 移动 / 副本（文件夹递归复制）/ 删除 / 导出（解密后或加密 `.vlt`）
- **`.vlt` 双向互通**：库内文件可用一次性密码加密导出 `.vlt`；导入时遇到 `.vlt` 自动询问密码，解密流直接入库（明文不落地）
- **删除可撤销**：删除进 8 秒撤销窗，到期才抹除密文；任何新操作自动落实上一批
- **拖拽移动**：选中条目直接拖到文件夹格子上；多选计数、全选、批量导出
- **重复导入跳过**：同名同大小自动判重，不产 "(2)" 副本
- **改密码零重加密**：密钥移植技术，改密码后所有文件无需重加密
- **生物识别解锁（可选）**：指纹/面容代替密码。主密钥被 Android Keystore 不可导出密钥包裹，每次解锁需现场活体认证；密码永远是兜底
- **诱骗库（可选）**：一个库、两个密码。真密码开真库，诱骗密码打开一个界面完全一致的独立空库，无法分辨——应对被迫交出密码的场合
- **库导出备份**：导出为 `.fvault` 文件（密文搬运，免密码导出，逐文件 SHA-256 校验；导入流式校验 + 进度显示，不整段进内存）
- **总密码开关**：控制进入库时是否需要密码
- **紧急销毁**：一键销毁全部库（输入「销毁」二次确认）

### 🌀 无锁模式 — 临时空间
- **会话即焚**：退出或重启后所有文件自动销毁；支持散文件与整文件夹导入（拍平重名自动消解）
- **加密转换**：一次性密码生成 `.vlt` 便携加密文件
- **临时解密**：打开 `.vlt` 在会话内预览，关闭即焚

### 🛡️ 安全防护
- **FLAG_SECURE**：防止截图/录屏/最近任务预览（可配置）
- **自动锁定**：后台计时到点即锁定（不等回前台），清空内存密钥
- **锁定即清空**：VMK 清零 + 已解密索引同步丢弃，不留在堆里
- **防备份泄漏**：`allowBackup=false`

---

## 🔐 加密架构

```
密码 → Argon2id (memory-hard KDF) → KEK
                                        ↓
                          AES-256-GCM 包裹 → VMK (主密钥,随机生成)
                                                ↓
                    Tink Streaming AEAD (AES-256-GCM-HKDF, 4KB 分块)
                                                ↓
                              文件加密（可随机 seek、边下边播）

诱骗密码 → Argon2id (独立盐) → 诱骗 KEK → 包裹独立诱骗 VMK → 诱骗索引
（同一库的两个密码走完全独立的密钥链,失败表现一致,无法区分）
```

**关键设计**：
- **VMK = 随机生成**，不从密码直接派生
- **密码验证 = unwrap 成功**，不存在独立可篡改的校验位
- **改密码 = 新 KEK 重新包裹同一把 VMK**，零重加密，瞬时完成
- **开源安全**：即使拿到源码和加密文件，没有密码也无法解密

---

## 🏗️ 技术栈

| 模块 | 技术 |
|------|------|
| UI | Kotlin + Jetpack Compose + Material 3 Expressive |
| 导航 | Navigation 3 |
| KDF | Argon2id (BouncyCastle) |
| 文件加密 | Google Tink Streaming AEAD |
| 图片加载 | Coil 3 (自定义解密 Fetcher) |
| 音视频 | Media3 ExoPlayer (自定义解密 DataSource) |
| 缩略图 | MediaMetadataRetriever |
| 存储 | DataStore Preferences (设置) + 私有内部存储 (加密数据) |
| 导入导出 | Storage Access Framework (SAF) |

---

## 🚀 构建

### 前置要求
- Android Studio Hedgehog+ / `sdkmanager` CLI
- JDK 17+
- Android SDK 36 (compileSdk 37)

### 构建步骤

```bash
# 克隆项目
git clone https://github.com/your-username/VaultX.git
cd VaultX

# 构建 debug APK
./gradlew :app:assembleDebug

# 运行单元测试
./gradlew :app:testDebugUnitTest

# 安装到设备
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 📁 项目结构

```
app/src/main/java/io/vaultx/app/
├── core/
│   ├── crypto/          # 加密核心
│   │   ├── Argon2idKdf.kt      # 密码 → 密钥派生
│   │   ├── KeyWrap.kt          # AES-GCM 包裹/解包
│   │   ├── TinkStreaming.kt    # 流式 AEAD 加密引擎
│   │   ├── VaultCrypto.kt      # 库加密上下文
│   │   └── PortableCipher.kt   # .vlt 便携格式
│   ├── security/        # 平台安全
│   │   └── BiometricKeystore.kt # Keystore 密钥(生物识别解锁)
│   ├── vault/           # 库引擎
│   │   ├── VaultManager.kt     # 库生命周期/索引/blob 管理
│   │   ├── VaultArchive.kt     # .fvault 归档格式
│   │   ├── VaultMeta.kt        # 库元数据
│   │   └── VaultIndex.kt       # 文件索引
│   ├── transfer/        # 传输引擎
│   │   ├── TransferEngine.kt   # 导入/导出核心
│   │   └── SafTransfer.kt      # SAF 适配层
│   ├── media/           # 媒体桥
│   │   ├── Thumbnailer.kt      # 缩略图生成
│   │   ├── VaultImageFetcher.kt # Coil 加密图片源
│   │   └── VaultDataSource.kt   # ExoPlayer 加密视频源
│   ├── session/         # 无锁模式
│   │   └── SessionManager.kt   # 临时空间/加密转换/解密
│   └── state/           # 状态管理
│       ├── VaultSessionHolder.kt # 密钥持有/后台定时自动锁定
│       └── AppSettings.kt       # DataStore 设置
├── ui/                  # 界面层
│   ├── mode/            # 模式选择
│   ├── vault/           # 库列表/主页/设置/解锁门
│   ├── viewer/          # 图片查看器/播放器
│   ├── session/         # 无锁模式界面
│   ├── settings/        # 应用设置
│   ├── components/      # 共享组件(含生物识别解锁门面)
│   ├── nav/             # 导航
│   └── theme/           # M3 Expressive 主题
├── MainActivity.kt      # 主活动
└── VaultXApp.kt         # Application + Coil 注册
```

---

## 📖 安全说明

请阅读 [SECURITY.md](SECURITY.md) 了解完整的威胁模型和诚实披露。

**核心事实**：
- 无密码找回
- 密码丢失 = 数据丢失
- 无 root 保护 = 无 root 能力
- 开源 = 公开审计 + 社区守护

---

## 📜 License

本项目采用 [GNU General Public License v3.0](LICENSE)。

隐私工具采用 GPL 是业内惯例（GnuPG、KeePassXC、Cryptomator 均为 GPL），确保代码永远开源、永远免费。

---

## 🤝 贡献

欢迎贡献代码、报告 Bug、提出建议！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

---

<div align="center">

**VaultX** — 让隐私保护触手可及

</div>
