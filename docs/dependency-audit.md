# Dependency audit — 2026-09-13

The audit is complete; the Android build toolchain has unresolved advisory matches.
This is an inventory/advisory review, not a source-code security assessment or proof
that unmatched dependencies are safe.

## Results

| Scope | Result |
| --- | --- |
| Backend npm lockfile, including development and optional packages | Zero npm audit findings after the Undici patch below |
| Android release runtime graph: 114 Maven coordinates | No OSV advisory matches |
| Android full graph: 292 unique Maven coordinates, including debug/release runtime, unit tests, kapt, and plugin classpaths | 14 affected coordinates, all on the root buildscript classpath |
| Mac | No third-party Swift packages; OS frameworks and system SQLite are outside package-advisory scanning |

The full Android query result, coordinates, scopes, and advisory IDs are recorded
in [android-osv.json](dependencies/android-osv.json). Queries use the public
[OSV API](https://google.github.io/osv.dev/api/); npm uses the public npm registry's
advisory endpoint. They send public dependency names and versions, not source code
or application data. Advisory databases and results change over time.

### Fixed npm finding

The initial scan reported one high and two moderate affected packages: Undici and
its Miniflare/Wrangler dependents. Five Undici advisories affected version 7.28.0:
`GHSA-8xcm-r25x-g524`, `GHSA-4cwx-7wf7-3272`, `GHSA-m8rv-5g2x-5cg5`,
`GHSA-jr45-8vmc-qm54`, and `GHSA-v3r7-h72x-cjcm`.

An explicit npm override pins **Undici 7.29.0**, the patched version in the same
major series. See the [upstream release](https://github.com/nodejs/undici/releases/tag/v7.29.0)
and [cache advisory](https://github.com/nodejs/undici/security/advisories/GHSA-4cwx-7wf7-3272).
The lockfile points to public npm URLs. Type checking, both backend tests, and
Wrangler's dry-run bundle check passed after a clean install. Remove the override
when the selected Wrangler/Miniflare versions resolve a safe version themselves,
after rerunning the audit and tests.

### Open Android build-tool findings

| Resolved dependency | Version | Advisory count |
| --- | --- | ---: |
| protobuf-java | 3.22.3 | 1 |
| commons-io | 2.13.0 | 1 |
| netty-codec-http2 | 4.1.93.Final | 9 |
| netty-codec-http | 4.1.93.Final | 19 |
| netty-codec | 4.1.93.Final | 3 |
| netty-common | 4.1.93.Final | 2 |
| netty-handler-proxy | 4.1.93.Final | 1 |
| netty-handler | 4.1.93.Final | 7 |
| commons-compress | 1.21 | 2 |
| jose4j | 0.9.5 | 1 |
| bcpkix-jdk18on | 1.77 | 2 |
| bcprov-jdk18on | 1.77 | 7 |
| jdom2 | 2.0.6 | 1 |
| kotlin-gradle-plugin | 2.1.0 | 1 |

These dependencies are resolved through AGP 8.7.3 and Kotlin 2.1.0 tooling, and do
not appear in the app's release runtime graph. Some advisories describe server or
cryptographic operations whose reachability in this build has not been established;
they remain open, with no blanket suppression or claim of non-exploitability.
Advisory counts overlap across modules; see the snapshot for exact identifiers.

In particular, Kotlin's
[build-cache deserialization advisory](https://github.com/advisories/GHSA-r937-wjx7-w2jp)
is relevant to contributor/build environments. Bouncy Castle also has a
[critical advisory match](https://github.com/advisories/GHSA-574f-3g2m-x479).
Use fresh, isolated CI runners, keep credentials out of pull-request builds, and do
not import untrusted build caches. CI here neither restores nor publishes dependency
or build caches and has read-only repository permission. These measures reduce
exposure; they do not fix the affected libraries.

The next remediation is a coordinated AGP/Kotlin/Gradle upgrade, followed by a full
rescan, build/lint/unit checks, and device verification. This audit does not force
unrelated transitive build-tool versions or adopt prerelease compiler fixes.
Track these findings before release signing or distributing build environments.

## License review

All 114 resolved Android runtime modules declare Apache-2.0 in their Maven POMs
or parent POMs. The review also inspected cached runtime JAR/AAR license/notice
entries, including nested `classes.jar`. OkHttp's embedded Public Suffix List notice
adds MPL-2.0 obligations for that data. Full license texts, preserved notices, and
the corresponding rule source now accompany the APK under `assets/licenses/`.
See [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

The npm license snapshot records all non-root entries in `package-lock.json`,
including optional packages absent on this machine. Native development tools can
contain further components; their internal licenses are outside this runtime
distribution review. The Gradle wrapper retains its embedded Apache-2.0 text.

## Repeat the audit

Use Node 22+, Python 3.9+, JDK 17, and the Android SDK described in CONTRIBUTING.
An online run is required to refresh advisories and missing Maven license metadata.
From the repository root:

```sh
mkdir -p dist/audit
cd backend
npm ci --registry=https://registry.npmjs.org
npm audit --registry=https://registry.npmjs.org --json > ../dist/audit/npm-audit.json
cd ../android
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew --init-script ../scripts/android-dependencies.init.gradle ossDependencyInventory
cd ..
python3 scripts/audit-android.py
python3 scripts/audit-android.py --runtime-only
python3 scripts/android-notices.py --check
```

The all-scope scanner currently exits **1** for the documented matches. It exits
**0** for no matches and **2** for an incomplete scan. Run the two modes separately
even when the full scan reports findings. Inventory generation fails on unresolved
dependencies; regenerate it before each audit rather than reusing an old report.
The wrapper distribution, JDK, SDK, native binary internals, and OS components are
not covered by the Maven coordinate scan.

After reviewing dependency changes, run `python3 scripts/android-notices.py` to
regenerate the notices and MPL rule source, then inspect the diff. The generator
requires cached runtime binaries and retrieves missing POMs from Google Maven and
Maven Central. It stops for an unrecognized license so new terms get manual review.
Keep the full license texts in `licenses/android/` current when adding new licenses.
Refresh the dated audit snapshot and npm license metadata when dependencies change.

CI gates npm audit, Android runtime advisories, notice freshness, and the normal
build/test/lint checks. It does not claim the full Android build toolchain is free
of advisories. Dependabot checks npm, Gradle, and pinned GitHub Actions weekly;
the full Android scan remains a maintainer check before a release/toolchain change.
