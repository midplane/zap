# Product

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

Native apps on two platforms: a SwiftUI/AppKit menu-bar panel on macOS and a Jetpack Compose app on Android. Each follows its own platform's design language.

## Users

One person, the developer, who uses it daily across one or two Macs and an Android phone (a Pixel). Polish is for that person's own daily use, not for an audience yet.

## Product Purpose

Private clipboard history across Mac and Android. Everything copied on the Mac, and anything shared or added on the phone, is kept, searchable, and quick to copy again, on every paired device. Success: finding and re-copying something takes seconds, and the history is just there on each device without thinking about sync.

## Positioning

Private history on your own Cloudflare server, end-to-end encrypted before it leaves a device, working offline with no account, native on both Mac and Android.

## Operating Context

- Mac: copying happens all day in other apps; Zap watches the pasteboard and opens with ⌥Space as a keyboard-driven panel. Return pastes, Space previews.
- Android: ordinary apps cannot watch the clipboard in the background, so items arrive by sharing to Zap, by "Add from clipboard", or by syncing from the Mac. The phone is mostly for retrieving: open, find, copy.
- History is mixed and dense: URLs, shell commands, file paths, single words, paragraphs, and images, often hundreds of items within the retention window.

## Capabilities and Constraints

- Text and image items; search; content filters (All, Text, Images); preview; copy, share, delete; pin (pinned items outlive retention).
- Retention from 1 to 365 days, default 10, shared across devices.
- Pairing by QR code; device list with removal (which rotates the encryption key).
- Background sync on Android is best-effort (WorkManager); live updates over a WebSocket while the app is open.
- Android minimum SDK 29; Compose with Material 3 components available.

## Brand Commitments

- Name: Zap.
- The mark: two offset paper slips, warm white behind lime on ink. Used in the launcher icon and in-app. Keep it.

## Evidence on Hand

- Screenshots: `docs/screenshots/android-clipboard.png`, `docs/screenshots/mac-clipboard.png` (demo history).
- No users, testimonials, or metrics; none should be invented.

## Product Principles

1. Retrieval speed first: the thing you copied is findable and copyable in one or two actions.
2. Content is the interface: what an item is (link, command, image, text) should be legible at a glance.
3. Private by default, and quiet about it: security is structural, not a banner.
4. Feel crafted: considered motion and feedback for the actions done constantly (copy, pin, delete), without slowing them.
