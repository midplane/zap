# Verification

Last updated 11 September 2026. The current identifiers are `dev.midplane.zap` on both platforms.

## Checks run

| Component | Evidence |
| --- | --- |
| Backend | TypeScript check and local Cloudflare integration tests pass with the updated toolchain. Tests cover setup authorization, one-use pairing, upload retries/concurrency, retrieval, cursors, deletion, expiry, revocation by a peer, self-revocation on disconnect, the last device resetting the deployment, and malformed/oversized requests. |
| Deployment | Wrangler dry-run bundles successfully with one Durable Object and the intended R2 binding. This review did not deploy backend changes. |
| Mac | The documented build script compiles with Swift 6.1.2/macOS 15.5 SDK, targets macOS 14, and produces an arm64 app that passes signature verification. Shared crypto vectors and tamper rejection pass. Option+Space opens and closes history. |
| Android | Unit tests (including the shared crypto vectors, which also cover reading the curve parameters directly) and lint pass. Debug assembly, installation alongside the earlier development app on a connected Pixel 10 Pro, and that identity passing pairing and a synthetic text upload against the local backend on the emulator are from the earlier pass. |
| Native UI | Mac history and compact pairing sheet, Android empty/populated history and settings, launcher artwork, and light/dark appearance were inspected. Android larger text was checked on the emulator. |

## Earlier integration coverage

Actual native clients passed text and PNG capture/share, encrypted pairing and sync in both directions against a loopback Cloudflare runtime, and Android deletion propagating to Mac. Mac Enter-to-paste succeeded in a disposable TextEdit document. Android history survived force-stop/relaunch. The test clients were disconnected and synthetic history removed after those integration checks.

The Mac build script now bypasses the broken SwiftPM manifest linker on this machine and detects duplicate SwiftBridging module maps. Its workaround is project-local; no system toolchain files were changed.

## Repeatable commands

```sh
(cd backend && npm run check && npm test)
./scripts/build-mac.sh
./scripts/check-mac-crypto.sh
./scripts/check-mac-clipboard.sh
./scripts/check-mac-store.sh
(cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug)
```

Android lint warnings include available dependency upgrades, the annotation processor, backup configuration, and synchronous preference writes on IO threads. The advisory recheck is pending: internal-registry authentication and automatic approval review prevented completing it. Do not interpret passing builds as a clean dependency audit.

Mac clipboard checks use a separate pasteboard to verify Finder image-file copying (the image rather than its file icon), direct PNG/TIFF data, and ignoring icons on non-image files. They require access to macOS pasteboard services.

Store checks verify cached payload replacement, pending-state updates, removal, expiry, and reopening encrypted storage. In one local run with eight synthetic 1 MiB payloads, a cold load took 31.91 ms and cached metadata reloads averaged 0.01 ms. This is a focused work-reduction check, not a battery measurement. Android's sync-request test verifies that a burst during a running sync produces one follow-up without dropping a later request.

Idle sync now uses Mac live notifications with a five-minute fallback, bounded reconnect delays on both apps, and Android periodic jobs only while paired and battery is not low. User-triggered sends still request immediate work. Device battery impact and cold-start memory use remain unmeasured.

The updated Mac app reached “Up to date” from `/Applications`. The updated Android app launched on the emulator in local-only mode with zero registered Zap background jobs. No physical phone was connected for this pass.

## Not established

`DELETE /v1/devices/me` has only been exercised by the local integration test. Neither native client's disconnect path, nor the 401 re-pairing path, has been run against a deployed Worker, and no backend changes were deployed. Uploads now stream into R2 through a fixed-length stream; a real multi-megabyte upload against R2 has not been re-run.

Large-history performance, lost enrollment responses, interrupted share imports, storage corruption recovery, physical-phone battery/OEM behavior, Android 10 hardware, Intel Mac builds, actual login startup, release signing/notarization, OS screen-reader traversal, tablets/foldables, and production failure recovery remain unverified. See [production-readiness.md](production-readiness.md) for prioritized findings.
