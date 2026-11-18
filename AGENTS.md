# AGENTS.md — VaultX

> Project conventions for coding agents. Living document — update it when conventions change. CLAUDE.md points here.

## Overview

VaultX: fully offline encrypted file vault for Android. Kotlin + Jetpack Compose (Material 3 Expressive) + Navigation 3. Single Gradle module `:app`. minSdk 26 / targetSdk 36 / compileSdk 37, JDK 17 syntax (JDK 21 toolchain works). GPLv3.

## Architecture map

```
app/src/main/java/io/vaultx/app/
├── core/            # pure-JVM engines, NO Android UI imports
│   ├── crypto/      # Argon2idKdf (native libsodium + BC fallback), KeyWrap, TinkStreaming, VaultCrypto, PortableCipher (.vlt)
│   ├── vault/       # VaultManager (lifecycle/index/blobs), VaultMeta, VaultIndex, VaultArchive (.fvault)
│   ├── transfer/    # TransferEngine (import/export), SafTransfer (SAF adapter)
│   ├── media/       # Thumbnailer, VaultImageFetcher (Coil), VaultDataSource (ExoPlayer)
│   ├── session/     # SessionManager (lock-free burn-on-exit space)
│   ├── state/       # VaultSessionHolder (key custody/auto-lock), AppSettings (DataStore)
│   └── security/    # BiometricKeystore
├── ui/              # Compose screens; ViewModels own all disk/crypto calls (Dispatchers.IO)
├── AppContainer.kt  # manual DI; per-vault indexMutexFor() shared by VM + settings
└── MainActivity.kt  # singleTask; share-intent + shortcut routing
```

Dependency direction: `ui → core`, never `core → ui`. `core/crypto` and `core/vault` must stay unit-testable on plain JVM (no android.* imports except where unavoidable in media/security).

## Commands

```bash
./gradlew :app:testDebugUnitTest        # unit tests — MUST be green before any commit
./gradlew :app:lintDebug                # lint — zero errors required
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease          # R8 + resource shrink; signed if keystore.properties exists, else unsigned
./gradlew :app:connectedDebugAndroidTest # instrumented — needs device/emulator; CI runs it (job android-test)
```

Gotchas:
- Gradle daemon occasionally dies mid-build (observed on this repo). If "daemon disappeared", retry the same command once before diagnosing.
- `keystore.properties` uses a module-relative `storeFile` path (`release.keystore`), resolved against `app/`. Absolute paths with non-ASCII characters break Properties encoding — do not reintroduce them.
- Instrumented tests cannot run locally without a device; say so honestly, don't claim they passed.

## Hard rules (Do / Don't)

- **Zero network is the product.** Never add `INTERNET` permission or any network call. About screen, licenses, privacy text are all bundled offline. Update checks are external links only.
- **Compatibility red line:** old vaults, old `.fvault`, `.vlt` must remain readable forever. `meta.vault.chunkSize` absent → 4096; new vaults → 256KB. Never migrate destructively; never delete unverified data. Format changes need a version anchor + legacy-read regression test.
- **KDF changes:** `Argon2idKdf` native (libsodium) and BouncyCastle paths must produce byte-identical output. Any change needs a cross-implementation test. Parameters only upgrade, never downgrade (`KdfParams.isAtLeast`).
- **Key hygiene:** every `UnlockedVault` session must be zeroized on lock; index mutations go through `indexMutexFor(vaultId)`; `loadIndex` failures surface to the error flow, never crash viewModelScope.
- **Decoy invariant:** real/decoy chains stay independent; unlock runs both KDF chains when a decoy exists (constant-time); decoy sessions must never see decoy-management UI.
- **No silent swallowing:** `runCatching` is for best-effort enhancements (thumbnails) only. Data-path errors must reach the user or the error flow.
- **Secrets never in git:** `keystore.properties`, `*.keystore`, `*.jks`, `local.properties` are ignored. The 0.1.0 demo key is revoked — never reuse it.
- **Strings:** every user-facing string goes to `res/values/` (English) AND `res/values-zh-rCN/` (Chinese), keys 1:1. No hardcoded UI text.

## Output language

Default English for README, repo description/topics, docs, commit messages, PR/Issue titles and bodies. Chinese only for the zh-CN i18n files and when the user explicitly asks.

## Change recipes

### Add a vault entry field
1. `VaultEntry` (VaultIndex.kt): add with default value — format version stays.
2. If semantics change (not additive): bump `FORMAT_VERSION`, implement migration, add legacy-read test.
3. UI + both string files.

### Add a setting
1. `AppSettings.kt`: key + StateFlow + setter (default must preserve current behavior).
2. `AppSettingsScreen.kt` UI; strings in both locales.
3. If it affects crypto/locking: wire in `AppContainer`/`VaultSessionHolder`, add unit test.

### Touch crypto or vault format
1. Read SECURITY.md threat model first.
2. Add regression test BEFORE the change (legacy data must still open).
3. Run full `testDebugUnitTest`; update SECURITY.md + CHANGELOG.md.

## Workflow & Git safety

- Do not commit/push/open PRs/issues unless the user explicitly asks to deliver.
- Stage explicit paths only; never `git add .` / `-A`. Keep unrelated worktree changes. No force-push to main.
- User overrides of these defaults are valid for that turn only; record the override in the PR/Issue.

## Delivery loop (Issue → PR → main → CI → merge)

Issue open → PR (base main, `Fixes #N`) → CI green → merge → Issue closes.
- CI is the merge gate; it must never auto-close Issues. Required checks (real job ids in `.github/workflows/ci.yml`): `test`, `android-test`.
- No merge rights? Open the PR, link it in the Issue, keep the Issue open, hand off to the maintainer.

## Security / privacy

- Never log passwords, keys, or plaintext file content.
- `FLAG_SECURE` default on in release; don't weaken.
- Backup exfiltration stays blocked (`allowBackup=false` + dataExtractionRules exclude-all).

## Verification

- Risk-matched: crypto/format changes → full unit suite + legacy-read tests; UI changes → compile + lint + manual smoke on device when possible.
- Green CI ≠ device-correct. Report actual commands and results; mark unverified items explicitly.