# Zap

Private clipboard history for Mac and Android. Native apps, ten days of history by default, and end-to-end encrypted sync through your own Cloudflare account.

Currently intended for personal testing. Read the [production readiness review](docs/production-readiness.md) before distributing or relying on it for important history.

## Daily use

On Mac, copy text or an image as usual. Open Zap with **⌥Space (Option+Space)**, search, and press **Enter** to paste. Arrows select; Space previews when search is empty; ⌘C copies. Use the menu-bar icon for settings and startup options. Direct paste needs macOS Accessibility permission.

On Android, **share text or an image to Zap**, or open Zap and choose **Add from clipboard**. Tap an item to preview, copy, share, or delete it. Android does not allow ordinary apps to watch the clipboard in the background. New items enter history without overwriting your current clipboard.

Both apps work locally without a server. Android refreshes when opened; background refresh is opportunistic. Pending shares retry through WorkManager. Mac stays connected while running. Settings control retention from 1 to 365 days, default 10.

## Build

Requirements: Node 22+, a working Swift 5.9+ macOS SDK (full Xcode recommended), JDK 17, and Android SDK 35. No accounts or API keys are baked into the apps.

Both apps use the identifier `dev.midplane.zap`. Android's Kotlin package matches it; the Mac keeps its local database under `~/Library/Application Support/dev.midplane.zap` and its keys in Keychain.

```sh
./scripts/build-mac.sh
open dist/Zap.app

cd android
# Set ANDROID_HOME to your SDK, or set sdk.dir in local.properties.
./gradlew assembleDebug
```

Install `android/app/build/outputs/apk/debug/app-debug.apk` on your phone. Keep the debug signing key if you want to update that installation without losing local data. For distribution, use your own Android release signing configuration and a Developer ID signature/notarization on Mac. Set `ZAP_SIGN_IDENTITY` for the Mac signing identity; the default is local ad-hoc signing.

The Mac scripts compile directly with the active Xcode/Command Line Tools compiler and SDK, keeping the compiler cache in `mac/.build`. If a Command Line Tools upgrade left duplicate SwiftBridging module definitions, the scripts hide the duplicate with a local compiler overlay. System files are unchanged; SwiftPM is not required.

## Deploy and pair

```sh
cd backend
npm ci
npx wrangler login
cd ..
./scripts/deploy.sh
```

The script provisions `zap-content` in R2, deploys the Worker/Durable Object, and prints a random setup token. R2 must be enabled in your Cloudflare account. Choose a different Worker/bucket name in `backend/wrangler.jsonc` and the deployment script if these names already belong to another project.

1. Open Mac Settings, enter the deployed HTTPS Worker URL and setup token, and connect.
2. Choose **Pair Android**. On Android, open Settings → **Scan pairing code**.
3. Confirm the connection on the phone. QR invitations expire after five minutes and can be used once. The code contains encryption keys; keep it private. Manual code entry is available if scanning is inconvenient.

An existing phone can create a code with **Pair another device**. On a replacement Mac, use **Join existing history**. **Disconnect** forgets the server connection while retaining local history; that history uploads when you connect to another server.

One deployment is one personal device group. No login service, public sharing links, push provider, or paid Zap subscription. Cloudflare usage is billed to your account.

## Verification

```sh
cd backend
npm run check
npm test
cd ..
./scripts/check-mac-crypto.sh
cd android
./gradlew testDebugUnitTest assembleDebug
```

The backend integration test runs a local Cloudflare runtime and covers pairing, idempotent uploads, deletion, expiration, and revocation. Native crypto checks use the same cross-platform vector, including Unicode, key wrapping, and tamper rejection. Runtime verification is tracked in [verification.md](docs/verification.md).

## Implementation

- `mac`: SwiftUI/AppKit panel, pasteboard capture, SQLite, Keychain, CryptoKit.
- `android`: Compose, Room metadata, encrypted content files, Keystore, WorkManager, OkHttp.
- `backend`: TypeScript Worker, one SQLite Durable Object, R2 ciphertext.
- `protocol`: the current wire contract and shared crypto vector.

All content is AES-256-GCM encrypted before upload. P-256 ECDH envelopes deliver keys to paired devices. The server stores device names, identifiers, timestamps, sizes, and ciphertext. Clients currently trust the server's device roster during key rotation; protection against a malicious server substituting device keys is not implemented. Search runs locally. Plaintext image files exist temporarily in Android's private cache when you explicitly copy or share an image.

Device removal revokes access and rotates encryption for future captures. It cannot erase content already downloaded to another device. An existing device can enroll a replacement; losing every paired device means starting with a fresh deployment/history. Do not delete encryption keys while keeping a local database you want to read.

Retention removes items from active history and schedules blob cleanup. It does not guarantee forensic erasure from OS or Cloudflare backups. There is no special filtering of passwords or other sensitive clipboard content.

Plain text and static images only: 1 MiB text, 20 MiB PNG, 40 megapixels. No rich text, files, pins, OCR, analytics, or compatibility layers. Apps and backend ship together.
