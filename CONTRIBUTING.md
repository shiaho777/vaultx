# Contributing to VaultX

Thanks for your interest in VaultX — a local-only encrypted vault for Android (GPLv3).
Contributions of every kind are welcome: code, docs, translations, design, and bug reports.

**Security vulnerability?** Do not open a public issue or PR. Report it privately per
[SECURITY.md](SECURITY.md) (GitHub Security Advisories).

## Development environment

- **JDK 17+** (CI builds with Temurin 17)
- **Android SDK**: platform 36 and 37 installed (`compileSdk 37`, `targetSdk 36`, `minSdk 26`)
- Android Studio (recent stable) or `sdkmanager` CLI
- A real device or emulator running Android 8.0+ (API 26+) for manual verification

```bash
git clone https://github.com/shiaho777/vaultx.git
cd vaultx
```

`local.properties` is generated locally (SDK path) and must never be committed.

## Build & test commands

```bash
# Unit tests
./gradlew :app:testDebugUnitTest

# Lint (errors fail the build)
./gradlew :app:lintDebug

# Debug APK
./gradlew :app:assembleDebug

# Release APK (unsigned unless you configure your own keystore; verifies R8/resource shrinking)
./gradlew :app:assembleRelease

# Instrumented tests (device/emulator connected; CI runs these on an API 34 emulator)
./gradlew :app:connectedDebugAndroidTest

# Install debug build
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Code style

- **Kotlin official style** (`kotlin.code.style=official`): 4-space indent, no wildcard imports,
  trailing commas where they help diffs.
- **UI is Jetpack Compose + Material 3 (Expressive)**: use M3 components and theme tokens
  (`MaterialTheme.colorScheme` / typography / spacing) — no hardcoded colors or dimensions in screens.
- Keep the architecture boundaries: crypto/vault logic in `core/`, UI in `ui/`; no disk I/O on the
  main thread; user-facing strings go through resources.
- Match the surrounding code. Small, focused diffs beat sweeping reformatting.

## Delivery workflow (Issue → PR → CI → merge → close)

1. **Issue first.** Open an issue describing the bug or feature before writing code, so scope and
   approach can be agreed. Trivial fixes (typos, small docs) may go straight to a PR.
2. **Branch and PR.** Fork, create a topic branch, and open a PR **against `main`**. Reference the
   issue with `Fixes #N` in the PR body so it closes automatically on merge.
3. **CI must be fully green before merge.** Required checks are the real CI jobs:
   - `test` — unit tests, lint, debug and release builds
   - `android-test` — instrumented tests on an API 34 emulator (includes lock-clears-cache security regressions)
4. **Real-device verification.** Green CI ≠ correct on hardware. Exercise the affected flows on a
   real device and record what you tested in the PR's Test plan.
5. **Close the issue only after merge.** Never close an issue while the fix is unmerged; if you
   cannot merge, leave the PR and issue open, link them, and hand off to a maintainer.

Commit messages and PR titles/bodies in English, imperative mood (e.g. "Fix decoy vault residue cleanup").

## No code? You can still contribute

- **Bug reports**: use the bug report form — version, device, reproduction steps, expected vs actual.
- **Documentation**: fix README/docs errors, improve clarity, add examples.
- **Translations**: improve `README.zh-CN.md` or propose new languages / app string translations.
- **Design**: UI/UX suggestions, mockups, icon and accessibility improvements.
- **Triage**: reproduce reported bugs, confirm duplicates, test fixes on devices we don't own.

## AI-assisted contributions policy

AI tools may be used, but responsibility is never delegated:

- **You must read, understand, and verify every line you submit** — run the tests, exercise the
  change on a device, and take full responsibility for it.
- Disclosing AI assistance in the PR is optional but encouraged; **human review is mandatory**
  (the PR template contains the declaration you must check).
- **Unreviewed generated code and blind generate→fail→regenerate loops are rejected.** A PR that
  shows no evidence of human understanding (cannot explain its own diff, tests copied without
  comprehension) will be closed.
- Never paste vault passwords, private user data, or signing material into external AI services.

## Security boundaries (non-negotiable)

- **Never commit secrets**: keystores (`*.keystore`, `*.jks`), `keystore.properties`,
  `local.properties`, or any signing credentials. They are gitignored — do not force-add them.
  (The 0.1.0 demo keystore leak is why 0.2.0 rotated signing keys; see CHANGELOG.)
- **Crypto-core changes** (`core/crypto/`, `core/security/`, key handling, vault format) **must
  include**:
  1. Regression tests covering the change (unit and, where relevant, instrumented).
  2. A threat-model note in the PR: which guarantee in [SECURITY.md](SECURITY.md) is affected and
     why the change preserves or improves it.
- No network permissions, no telemetry, no plaintext outside app-private storage — PRs violating
  these product invariants will not be accepted.
- Backward compatibility: legacy vaults (4KB segments, old KDF params, short legacy passwords)
  must keep opening. Never mix segment sizes within a vault.

## License

By contributing, you agree your contributions are licensed under the
[GNU GPL v3.0](LICENSE), the project's license.
