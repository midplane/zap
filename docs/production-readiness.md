# Production readiness

Updated 13 September 2026. **Suitable for personal testing; not ready for broad distribution.** Build and test results are documented in [verification.md](verification.md). This is not a security certification or a load test.

## Security assumptions

- The self-hosted server is trusted for device membership. Clients wrap rotation keys for public keys from its unsigned device roster; a malicious server could substitute a key and obtain a future group key. Membership authentication is required before claiming protection against that attack.
- Pairing codes contain encryption keys that remain sensitive after the enrollment invitation expires. Capture filters prevent new text captures containing pairing codes; previously saved codes require explicit deletion. Android's sensitive-clipboard hint hides supported previews but does not restrict clipboard access.
- Removing a device revokes access and rotates keys for future captures. It cannot erase already-downloaded content. Losing every paired device means starting with a fresh deployment/history.
- Retention does not guarantee forensic erasure from backups. Damaged local records are retained for recovery until repaired or explicitly discarded. There is no general password filtering.

## Open findings

P1 findings should be resolved before broad release. P2 findings concern distribution and operation.

| Priority | Finding | Follow-up |
| --- | --- | --- |
| P1 | Initial history loading decrypts all retained payloads, and large images remain in memory. Mac decrypts on the main actor; Android holds its repository mutex during blocking network calls. | Load lightweight metadata separately, decrypt images on demand with a bounded cache, move networking outside local mutation locks, and measure large-history startup and memory use. |
| P1 | Android share ingestion runs in the Activity lifecycle and can be cancelled before content is durable. | Stage incoming content while its access grant is valid, transfer ownership to durable work, and test Back, rotation, and process death during import. |
| P1 | Key rotation trusts the server's unsigned device roster. | Authenticate membership changes before supporting an actively malicious server threat model. |
| P2 | There are no application quotas, failed-enrollment rate limits, cleanup-lag metrics, or alerting. Sync returns an unpaginated metadata snapshot; `/health` checks liveness only. | Add practical limits and monitoring without logging clipboard content, exercise R2 failure/recovery, and paginate large histories. |
| P2 | Packages use Android debug signing and default Mac ad-hoc signing. CI is configured, with its first hosted run pending; there is no release signing/notarization pipeline. | Confirm hosted checks pass, configure persistent release signing, and verify installation and updates preserve history and keys. |
| P2 | The Android build toolchain has 14 dependency coordinates with advisory matches, including Kotlin build-cache deserialization. | Upgrade AGP/Kotlin/Gradle together, rescan the full graph, and verify builds and device behavior before release signing. See the dependency audit for scope and mitigations. |

## Dependency and deployment checks

The [dependency audit](dependency-audit.md) reports zero npm findings after an
Undici patch, no OSV matches across the 114 Android runtime coordinates, and open
matches in 14 Android build-tool coordinates. The deployed Worker has no runtime
npm dependencies. These scans do not establish that unmatched code is safe.

Deploy the updated backend before enrolling with the current clients. Enrollment now requires client-generated credentials and supports recovery of interrupted registration. Local tests do not replace verification of native enrollment, revocation, streaming uploads, and recovery against a deployed Worker.
