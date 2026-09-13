# Verification

Last verified 13 September 2026. Use the prerequisites in the [README](../README.md#build). Passing these checks does not establish production readiness or a clean dependency audit.

## Run the checks

```sh
(cd backend && npm ci && npm run check && npm test)
./scripts/build-mac.sh
./scripts/check-mac-crypto.sh
./scripts/check-mac-clipboard.sh
./scripts/check-mac-store.sh
(cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug)
```

Backend integration tests require loopback networking for the local Cloudflare runtime. Mac checks require macOS; the clipboard check uses an isolated test pasteboard and needs access to pasteboard services. Storage checks use temporary synthetic data.

## Automated coverage

| Component | Coverage | Latest result |
| --- | --- | --- |
| Backend | Setup authorization, enrollment retries and concurrency, changed/revoked enrollment rejection, one-use invitations, uploads and retries, retrieval, cursors, deletion, expiration, device revocation, vault reset, and malformed/oversized requests. | Type-check and both tests pass. |
| Mac | Shared encryption vectors, key wrapping, tamper rejection, clipboard image capture, pairing-code filtering, persisted enrollment state, encrypted storage, caching, retention, damaged-record isolation, repair, and explicit removal. | Build and crypto, clipboard, and store checks pass. |
| Android | Shared encryption vectors, tamper rejection, sync-request coalescing, persisted enrollment state, missing/corrupt payload isolation and repair, and pairing-code filtering. | Five unit tests, debug assembly, and lint pass; 10 lint warnings remain. |

Earlier manual checks exercised native text/image capture and sync in both directions against a local backend, deletion propagation, Mac direct paste, Android restart persistence, and basic light/dark layouts. These are not automated regression coverage.

## Verification limits

- Enrollment tests verify persisted requests and retry behavior. Actual Keychain/Keystore write failures and native process termination during live enrollment have not been fault-injected.
- Record tests cover individual payload damage. They do not establish recovery from a damaged database file or lost identity keys.
- Native disconnect and 401 recovery have not been verified against a deployed Worker. Multi-megabyte uploads through the current streaming path still need validation against real R2 storage.
- Large-history memory use, battery/OEM behavior, interrupted Android share imports, minimum-OS hardware, Intel Mac builds, login startup, screen-reader traversal, and tablet/foldable layouts remain unverified.
- Release signing/notarization, installation updates preserving history and keys, and production failure recovery remain unverified. Dependency advisory review is incomplete.

See [production readiness](production-readiness.md) for release priorities and security assumptions.
