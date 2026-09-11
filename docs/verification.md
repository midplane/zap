# Incremental verification

## Backend

- Passed TypeScript check.
- Passed local Cloudflare integration: setup authorization, one-use pairing, idempotent blob upload, cross-device retrieval, unchanged cursor, delete/retry rejection, expiry on retention change, credential revocation, and old-key rejection.

## Android

- Passed debug APK build, Android lint (zero errors), and JVM shared crypto vector/key-wrapping/tamper check. Remaining lint warnings concern dependency updates, the annotation processor, and deliberate synchronous preference writes from IO work.
- Passed emulator text and PNG share ingestion and persistence after force-stop/relaunch.
- Passed real-client pairing with Mac against local Cloudflare runtime; Android decrypted Mac text and PNG captures, then uploaded its local history.
- Passed fresh Android-to-Mac live arrival and Android deletion propagating to Mac.
- Passed bounded phone visual review in normal light mode and dark mode at 1.3× font scale; no emulator crashes were reported.

## Mac

- Passed native compilation using the installed macOS 14.5 SDK and a temporary compiler overlay.
- Passed Swift shared crypto vector/key-wrapping/tamper check.
- Passed text and PNG clipboard capture, shortcut opening, search, and Enter-to-paste into a disposable TextEdit document.
- Passed native setup against local Cloudflare runtime, encrypted upload, and invitation creation.
- This machine's Command Line Tools have mismatched PackageDescription symbols and duplicate SwiftBridging module definitions. No system files have been changed. The temporary verification overlay maps the duplicate module map to an empty file; it is not required on a healthy Xcode installation.
- Passed bounded native Mac light/dark finish review after correcting secondary-text and search-prompt contrast; reviewer scored every identified issue resolved.
- Fixed startup failures found by runtime checks: SQLite WAL result handling and the utility lifecycle. Added the standard Edit menu for field shortcuts.

## Deployment

- No external resources have been deployed. A loopback-only Cloudflare runtime and synthetic text/PNG fixtures were used for integration checks.
- Android release builds continue to require HTTPS; only debug builds allow HTTP on localhost/127.0.0.1.
- Both test clients were disconnected, synthetic history removed, and the local verification server stopped after testing. Deletions made while disconnected stay local.

## Remaining limits

- Physical-phone battery/OEM behavior, Android 10 hardware, Intel Mac builds, launch-at-login behavior, notarization, and a production Cloudflare deployment were not exercised.
- History loading currently decrypts the retained payloads into memory. Large image-heavy histories have not been load-tested.
- The finish review covered native history screens, not every settings or permission state. OS screen readers were not separately exercised.
