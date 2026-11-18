## Summary

<!-- What does this PR change, and why? Keep it short; link related discussion if any. -->

## Related issue

Fixes #<!-- issue number — every PR should start from an issue (see CONTRIBUTING.md) -->

## Test plan

<!-- Green CI is necessary but not sufficient. List what you actually ran and verified. -->

**Automated (must pass):**
- [ ] `./gradlew :app:testDebugUnitTest` — unit tests pass locally
- [ ] `./gradlew :app:lintDebug` — lint clean
- [ ] CI required checks green: `test` and `android-test`

**Real-device verification (check what applies, note device + Android version):**
- [ ] Affected flows exercised on a real device: <!-- device, Android version, what you did -->
- [ ] Vault create / unlock / lock cycle still works (if crypto, storage, or session code changed)
- [ ] No plaintext or decrypted content leaks outside private storage (if import/export/media changed)
- [ ] New regression tests added for any bug fix or crypto-core change

## AI-assistance disclosure (optional, honesty required)

<!-- If AI tools helped produce this PR, say so briefly (e.g. "drafted with an LLM, reviewed line by line"). Optional to disclose tooling — NOT optional to review. -->

**Required declaration:**
- [ ] I have read and understood every line of this change, verified it on my own (tests + real device where applicable), and take full responsibility for it. Unreviewed generated code and blind generate-fix loops are not accepted.

## Security checklist (if crypto / key handling / storage is touched)

- [ ] No keystores, signing material, or `local.properties` committed
- [ ] Regression tests covering the change are included
- [ ] Threat-model impact described in this PR (what changes for the guarantees in SECURITY.md)
