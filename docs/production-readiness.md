# Production readiness review

Reviewed 11 September 2026. **Suitable for continued personal testing; not ready for broad distribution.** This review included source, local backend regression tests, native builds, Android lint, and a bounded native visual pass. It is not a security certification or a load test.

## Fixes completed

- Serialized uploads for the same item ID and coordinated uploads with R2 cleanup, preventing concurrent retries from replacing committed ciphertext. Added a concurrent-upload regression check.
- Bounded JSON and upload request reads before allocating the complete body; reject non-object JSON. Added malformed/oversized request checks.
- Prevented Android pairing requests from carrying existing device credentials to a different server. Both clients require disconnecting before joining another history.
- Replaced Mac force-unwraps of remote keys and invitations with recoverable errors. Preserved retention edits made during a sync, and fixed recapturing a previously deleted item.
- Removed unused Android DAO/crypto methods and an unused Mac dismissal callback. Serialized Android startup reads with repository writes and preserved coroutine cancellation during sync.
- Updated Wrangler, Miniflare, worker types, and the test bundler; pinned `sharp` to 0.35.4 for the identified transitive advisories. No internal registry URLs remain in the lockfile.
- Added the published Gradle distribution checksum. Disabled Wrangler's automatic config mutation when creating the already-declared R2 bucket.
- Added Mac and adaptive/themed Android app icons, a branded Android header, native settings action rows, persistent Android sync feedback, compact Mac dates, a compact pairing sheet, and accurate local-only clear confirmations. Android image byte preparation now runs off the UI thread.
- Changed the default Mac shortcut to Option+Space and verified panel opening/closing.

## Open findings

| Priority | Finding and evidence | Required follow-up |
| --- | --- | --- |
| P1 | **History memory and cold-start work are unbounded.** Both clients now reuse decrypted payloads across refreshes, but the first load still decrypts every retained payload and large base64 images stay resident. Mac loads on the main actor; Android also holds its repository mutex during blocking network calls. | Load lightweight list records separately from payloads, decrypt images on demand with a bounded cache, and keep networking outside local mutation locks. Measure startup, capture latency, and peak memory with image-heavy history. |
| P1 | **Enrollment is not retry-safe after a lost response.** `/v1/bootstrap` commits its only device before returning its token; `/v1/pair` consumes the invitation before returning. A dropped response or failed local key save can leave enrollment committed only on the server. | Persist a client-generated enrollment identity before sending, and make retry return the same enrollment result. Test response loss and key-storage failure. A lost first bootstrap currently requires a fresh vault; a lost pairing response requires a new invitation. |
| P1 | **One damaged record can block all history.** Mac `Store.all()` throws for any malformed/decryption-failing row; Android `refresh()` throws for any missing/corrupt payload file. Mac can fail at launch; Android cannot complete reconciliation. | Quarantine/report individual bad records, redownload previously synced content, and retain unsent damaged records for explicit recovery. Test missing files, failed disk writes, and keychain/Keystore lock or loss. |
| P1 | **Android share ingestion depends on the Activity lifecycle.** `MainActivity.receive()` launches in `lifecycleScope`; leaving during a large share can cancel the operation before it is durable. | Copy incoming content into private staging while the grant is valid, hand ownership to durable work, and show saving/completion state. Test Back, rotation, and process death during multi-image import. |
| P1 | **The encryption trust boundary needs to be explicit.** Key rotation wraps the new key for public keys supplied by the server's unsigned device roster. A malicious server could substitute a public key and receive a future rotation key. | Treat the self-hosted server as trusted for device membership. Authenticate membership changes before claiming protection against an actively malicious server. Encryption currently protects stored payloads and ordinary transit, not this attack. |
| P2 | **Operations and abuse controls are incomplete.** `/health` is a liveness response. There are no application quotas, failed-enrollment rate limits, cleanup-lag metrics, or alerting. Sync returns a full metadata snapshot without pagination. | Add practical per-vault limits, monitor cleanup failures and retry rates without logging clipboard data, and exercise R2 failure/recovery. Add pagination before supporting very large histories. |
| P2 | **Distribution has no release gate.** Current artifacts use Android debug signing and Mac ad-hoc signing. There is no CI workflow, release signing/notarization pipeline, or repository license. | Choose a license before public distribution, configure persistent release signing, and automate the existing lean checks. Exercise clean installation and an update preserving history/keys. |

P1 means fix before broad release; P2 means resolve as part of operating/distributing the app. No P0 issue was established by the checks performed.

## Dependency audit boundary

The initial npm audit reported four high-severity affected packages in development tooling: Wrangler, Miniflare, sharp, and ws. These tools are not bundled into the deployed Worker. The identified paths were updated; the Worker still has no runtime npm dependencies.

Package downloads succeeded through the supplied internal registry. Its audit endpoint required authentication. Automatic approval review blocked the public-registry recheck because it would disclose this private project's dependency metadata. **A clean final advisory audit has not been established.** Android dependency advisories were not comprehensively audited.

## Native finish assessment

The apps use SwiftUI/AppKit and Jetpack Compose Material 3 controls. Android settings actions are full-width `ListItem` rows with icons and descriptions, rather than a stack of low-emphasis text buttons. The paired-paper brand mark uses ink, warm white, and lime; normal controls keep their native platform palette.

| Dimension | Score | Basis |
| --- | --- | --- |
| Accessibility | 2/4 | Labels and native controls are present; larger Android text was inspected. TalkBack and VoiceOver traversal were not exercised. |
| Performance | 1/4 | Image preparation was improved, but whole-history loading remains the major issue above. |
| Appearance | 3/4 | Native light/dark screens and launcher artwork were reviewed; controls and supporting text retain readable hierarchy. |
| Platform conformance | 3/4 | Native search, lists, sheets/dialogs, Back, clipboard import, and share entry points. Some lifecycle recovery remains open. |
| Adaptivity | 2/4 | Mac panel and phone layouts were checked; landscape, tablet, split-screen, and foldable coverage remain incomplete. |
| Total | 11/20 | The visual finish is ahead of the reliability and coverage work. |

This was a source audit and an inline visual review, not an independent accessibility or performance audit. Keep the compact history layout and standard platform controls while addressing the remaining work.

## Verification boundaries

See [verification.md](verification.md) for commands and results. No backend changes were deployed by this review. A Pixel installation and emulator captures do not establish battery/OEM reliability, minimum-OS support, or production recovery. Test those explicitly before release.
