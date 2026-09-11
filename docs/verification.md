# Verification

Last updated 11 September 2026. The current identifiers are `dev.midplane.zap` on both platforms.

## Checks run

| Component | Evidence |
| --- | --- |
| Backend | TypeScript check and local Cloudflare integration tests pass with the updated toolchain. Tests cover setup authorization, one-use pairing, upload retries/concurrency, retrieval, cursors, deletion, expiry, revocation, and malformed/oversized requests. |
| Deployment | Wrangler dry-run bundles successfully with one Durable Object and the intended R2 binding. This review did not deploy backend changes. |
| Mac | The documented build script compiles with Swift 6.1.2/macOS 15.5 SDK, targets macOS 14, and produces an arm64 app that passes signature verification. Shared crypto vectors and tamper rejection pass. Option+Space opens and closes history. |
| Android | Debug assembly, JVM crypto checks, and lint pass. The renamed app installs alongside the earlier development app on the connected Pixel 10 Pro. Its new identity also passed pairing and a synthetic text upload against the local backend on the emulator. |
| Native UI | Mac history and compact pairing sheet, Android empty/populated history and settings, launcher artwork, and light/dark appearance were inspected. Android larger text was checked on the emulator. |

## Earlier integration coverage

Actual native clients passed text and PNG capture/share, encrypted pairing and sync in both directions against a loopback Cloudflare runtime, and Android deletion propagating to Mac. Mac Enter-to-paste succeeded in a disposable TextEdit document. Android history survived force-stop/relaunch. The test clients were disconnected and synthetic history removed after those integration checks.

The Mac build script now bypasses the broken SwiftPM manifest linker on this machine and detects duplicate SwiftBridging module maps. Its workaround is project-local; no system toolchain files were changed.

## Repeatable commands

```sh
(cd backend && npm run check && npm test)
./scripts/build-mac.sh
./scripts/check-mac-crypto.sh
(cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug)
```

Android lint warnings include available dependency upgrades, the annotation processor, backup configuration, and synchronous preference writes on IO threads. The advisory recheck is pending: internal-registry authentication and automatic approval review prevented completing it. Do not interpret passing builds as a clean dependency audit.

## Not established

Large-history performance, lost enrollment responses, interrupted share imports, storage corruption recovery, physical-phone battery/OEM behavior, Android 10 hardware, Intel Mac builds, actual login startup, release signing/notarization, OS screen-reader traversal, tablets/foldables, and production failure recovery remain unverified. See [production-readiness.md](production-readiness.md) for prioritized findings.
