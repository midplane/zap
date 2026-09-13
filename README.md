# Zap

Private clipboard history for Mac and Android. Native apps, ten days of history by default, and end-to-end encrypted sync through your own Cloudflare account.

Currently intended for personal testing. Read the [production readiness review](docs/production-readiness.md) before distributing or relying on it for important history.

Licensed under the [MIT License](LICENSE), copyright 2026 Nikhil Bafna. This covers Zap's original code, documentation, and artwork. Third-party components, including the Gradle wrapper and application dependencies, retain their own licenses and notices.

## Daily use

On Mac, copy text or an image as usual. Open Zap with **⌥Space (Option+Space)**, search, and press **Enter** to paste. Arrows select; Space previews when search is empty; ⌘C copies. Use the menu-bar icon for settings and startup options. Direct paste needs macOS Accessibility permission.

On Android, **share text or an image to Zap**, or open Zap and choose **Add from clipboard**. Tap an item to preview, copy, share, or delete it. Android does not allow ordinary apps to watch the clipboard in the background. New items enter history without overwriting your current clipboard.

Both apps work locally without a server. Android refreshes when opened; while connected, it requests background refresh every 15 minutes when the network is available and battery is not low. Android may delay that work. Pending shares retry through WorkManager. Mac uses live change notifications with a five-minute fallback check. Both apps back off failed live connections and reuse loaded history during sync. Settings control retention from 1 to 365 days, default 10.

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

## Package and install

```sh
./scripts/package.sh
```

This builds both apps and produces `dist/Zap-macOS-arm64.dmg` (or `x86_64` when built on an Intel Mac) and `dist/Zap-android-debug.apk`.

On Mac, open the DMG, drag Zap into Applications, eject the disk, and launch `/Applications/Zap.app`. Quit Zap before replacing an existing copy. On Android, open the APK and allow installation from that source when prompted, or use `adb install -r dist/Zap-android-debug.apk` with a connected phone.

These are personal-testing packages: the APK uses debug signing, and the DMG contains the locally signed Mac app. Packaging does not add release signing or notarization. Keep the same app identifiers and signing keys for future updates.

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
2. Choose **Pair another device**. On Android, open Settings → **Scan pairing code**.
3. Confirm the connection on the phone. QR invitations expire after five minutes and can be used once. Only enrollment permission expires: the embedded encryption keys can still decrypt matching ciphertext afterward. Keep the entire code private, even after expiry. Manual code entry is available if scanning is inconvenient.

Any connected device can create a code with **Pair another device**. To add a second or replacement Mac, copy the code from the pairing sheet, then paste it into **Join existing history** in Settings on the new Mac — the same code works for a Mac or a phone. **Disconnect** forgets the server connection while retaining local history; that history uploads when you connect to another server.

Both apps securely save enrollment before contacting the server and resume it after a restart or lost response. Retry connecting to finish a pending attempt.

The setup token initializes a server once. If you see “This deployment is already initialized,” join using a pairing code from a connected device. This also applies after changing the app identifier; the previous app can still create a pairing code.

One deployment is one personal device group. No login service, public sharing links, push provider, or paid Zap subscription. Cloudflare usage is billed to your account.

## Verification

```sh
cd backend
npm run check
npm test
cd ..
./scripts/check-mac-crypto.sh
./scripts/check-mac-store.sh
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

Unreadable records are isolated so healthy history remains usable. Settings shows recovery status, retries, and an explicit **Discard damaged items** action. Synced records are downloaded again automatically. Unsent damaged records remain encrypted in local storage, including past their retention date, until repaired or explicitly discarded. Discarding while connected also queues deletion on paired devices; **Clear history** includes damaged records.

Retention removes items from active history and schedules blob cleanup. It does not guarantee forensic erasure from OS or Cloudflare backups. Both apps reject captured text containing Zap pairing codes. Android masks manual pairing input and marks copied pairing codes as sensitive so supported system clipboard previews hide them. This does not prevent another app with clipboard access from reading a copied code. Previously saved codes are not removed automatically; delete any such entries from history. There is no general filtering of passwords or other sensitive clipboard content.

Plain text and static images only: 1 MiB text, 20 MiB PNG, 40 megapixels. No rich text, files, pins, OCR, analytics, or compatibility layers. Apps and backend ship together.
