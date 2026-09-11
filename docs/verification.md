# Incremental verification

## Backend

- Passed TypeScript check.
- Passed local Cloudflare integration: setup authorization, one-use pairing, idempotent blob upload, cross-device retrieval, unchanged cursor, delete/retry rejection, expiry on retention change, credential revocation, and old-key rejection.

## Android

- Native build and shared crypto vector check in progress.
- Emulator runtime checks pending build completion.

## Mac

- Implementation committed; native compilation in progress.
- This machine's Command Line Tools have mismatched PackageDescription symbols and duplicate SwiftBridging module definitions. No system files have been changed. A temporary compiler overlay is being tried for verification.
- Capture, keyboard focus, Accessibility-assisted paste, and visual checks require a running build.

## Deployment

- No external resources have been deployed. No personal clipboard data has been captured for tests.
