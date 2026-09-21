# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | ✅        |

## Security Design

- **KDF**: Argon2id (memory-hard, OWASP-recommended)
- **Key structure**: Random 256-bit VMK, wrapped by password-derived KEK via AES-256-GCM
- **File encryption**: Google Tink Streaming AEAD (AES-256-GCM-HKDF, 4KB segments)
- **Password verification**: Attempting to unwrap the VMK IS the verification — no separate hash to tamper with
- **Storage**: All data in app-private internal storage (`getFilesDir()`), invisible to other apps without root
- **Biometric unlock (opt-in)**: The VMK is additionally wrapped by a non-exportable AES-256 key inside the Android Keystore, usable only after live biometric authentication. Deleting biometrics or a new fingerprint enrollment invalidates the Keystore key; the app then falls back to password unlock. The password always remains the root of trust
- **Decoy vault (opt-in)**: A second password opens a separate compartment with its own VMK and index. Unlock attempts are indistinguishable between wrong-password and decoy paths. Enabling a decoy forces the master gate (index encryption) on, because a plaintext index would leak the real entry count. The decoy management UI is hidden during decoy sessions, and removing a decoy requires the real password. Note: the decoy password can also delete the whole vault — under coercion this is plausible deniability, not a bug
- **Memory hygiene**: VMK is zeroized on lock; auto-lock fires by timer while the app is backgrounded (not only on return to foreground); decrypted indexes are dropped from the process heap when a vault locks. JVM cannot guarantee wiping every copy (e.g. inside Tink's internal keyset) — this is a platform limitation we document rather than hide

## Threat Model (Honest Disclosure)

This is what VaultX **does and does not** protect against:

### ✅ Protected Against
- **Other apps on the device**: Files are in UID-sandboxed private storage
- **File manager / gallery browsing**: No media scanning of private storage
- **Source code analysis**: Even with full source code and encrypted files, you cannot decrypt without the password
- **Random access to files**: Tink AEAD chunks bind ciphertext to position, preventing reorder/append attacks

### ⚠️ Limited Protection
- **Weak passwords**: Argon2id slows brute-force but cannot make weak passwords strong. We enforce ≥4 characters and show strength indicators
- **Device theft with unlocked screen**: If the device is unlocked, an attacker can access unlocked vaults in memory

### ❌ Not Protected Against
- **Root / bootloader unlock**: Root users can read private storage and memory dumps. In particular, `meta.vault` is plaintext: with root, an attacker can see that a decoy is configured (two wrapped VMKs), even though they still cannot decrypt either without a password
- **Coerced biometric**: If an adversary can physically operate your finger/face, biometric unlock opens the real vault regardless of any decoy. With a decoy configured, consider leaving biometric off
- **Data loss**: There is NO password recovery. Forgetting your password means losing your data forever (forgetting only the decoy password is harmless — the real vault is unaffected)
- **Factory reset / app uninstall**: This destroys all encrypted data
- **Government-level forensic tools**: 超出无 root 应用的能力边界

## Reporting a Vulnerability

If you discover a security issue, please report it privately via GitHub Issues (labeled "security").
We will respond within 72 hours and provide a fix before public disclosure.

## Password Recovery

**There is none.** This is a deliberate design choice:

- The password is the sole root of all secrets
- We have no servers, no accounts, no way to verify identity
- This means: **back up your password** or accept data loss

## Changelog

- **v0.1.0** (2026-09): Initial release with dual-mode vault, streaming AEAD encryption, portable `.vlt` format
