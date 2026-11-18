# VaultX 发布指南

面向维护者的签名、构建与分发流程。普通用户无需阅读本文。

## 1. 版本策略

- 语义化版本 `MAJOR.MINOR.PATCH`（`app/build.gradle.kts` 的 `versionName`）。
- **格式版本独立于应用版本**：`VaultMeta.FORMAT_VERSION` / `VaultIndex.FORMAT_VERSION`。
  - 新增带默认值的字段：不升格式版本（旧端可读，`ignoreUnknownKeys`）。
  - 语义变更/需要迁移：递增格式版本，并在 `fromJson` 前实现迁移路径。
  - 旧版本应用读到更高格式版本会**拒绝打开**（防静默写坏），这是刻意设计。
- 每次发布递增 `versionCode`（整数，单调不减）。

## 2. 签名

Release 签名信息从 `keystore.properties`（根目录，勿提交真实口令到公开仓库）或环境变量读取：

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

或环境变量：`VAULTX_STORE_FILE` / `VAULTX_STORE_PASSWORD` / `VAULTX_KEY_ALIAS` / `VAULTX_KEY_PASSWORD`。

> ⚠️ **历史密钥已作废**：0.1.0 及之前仓库中曾误提交演示 keystore 与口令（现已移出跟踪并加入 `.gitignore`）。
> 该密钥应视为已泄漏，**不得再用于任何分发签名**；若曾用它签名分发过 APK，请通知用户卸载重装新签名版本。
> 正式分发请生成自己的密钥并妥善保管——**签名密钥丢失意味着无法再向老用户推送更新**。

## 3. 构建

```bash
# 单元测试（发布前必须全绿）
./gradlew :app:testDebugUnitTest

# 模糊/篡改测试包含在单元测试内（CryptoFuzzTest）

# Release APK（R8 混淆 + 资源收缩已启用）
./gradlew :app:assembleRelease

# App Bundle（Play 分发）
./gradlew :app:bundleRelease
```

产物：`app/build/outputs/apk/release/` 与 `app/build/outputs/bundle/release/`。

## 4. 可复现构建（F-Droid 方向）

F-Droid 要求第三方能独立重建出字节一致的 APK：

1. 固定依赖版本（`gradle/libs.versions.toml` 已集中管理；不要使用动态版本 `+`）。
2. 提交 Gradle Wrapper（`gradle/wrapper/gradle-wrapper.properties` 锁定分发 URL 与校验和）。
3. 构建环境记录：JDK 21（lazysodium 5.2.0 字节码为 Java 21）、Android SDK compileSdk 37 / targetSdk 36。
4. 去除构建产物中的时间戳/机器路径差异（AGP 默认已处理大部分；如校验失败用 `diffoscope` 定位）。
5. F-Droid 提交要点：
   - 元数据放 `metadata/`（Fastlane 结构：`en-US/` 与 `zh-CN/` 的 title/short_description/full_description + 截图）。
   - 应用无网络权限、无跟踪库——在描述中明确，这是审核加分项。
   - 加密出口合规：开源加密软件按当地法规自查（美国 EAR 开源豁免条款等）。

## 5. CI

`.github/workflows/ci.yml`：每次 push / PR 自动跑单元测试 + assembleDebug，失败上传测试报告。
发布前建议在 CI 增加 `assembleRelease`（需配置签名 secrets）与仪器测试（connectedDebugAndroidTest，需模拟器 runner）。

## 6. 发布检查清单

- [ ] 全部单元测试通过（含 CryptoFuzzTest）
- [ ] `versionCode` / `versionName` 已递增
- [ ] 格式版本变更（如有）附带迁移测试
- [ ] 中英文文案同步（`values/` 与 `values-zh-rCN/` 键一一对应）
- [ ] release 构建在真机冒烟：建库/导入/锁定/生物识别/诱骗库/备份导出导入
- [ ] SECURITY.md 威胁模型与本次改动一致
- [ ] 更新日志（CHANGELOG）条目