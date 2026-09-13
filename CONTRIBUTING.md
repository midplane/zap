# Contributing to Zap

Zap is experimental software for a personal Mac/Android device group. Start with
the [README](README.md), [design](DESIGN.md), [protocol](protocol/README.md), and
[known production gaps](docs/production-readiness.md). Open an issue before a large
feature or protocol change so we can agree on scope. Small fixes can go straight
to a pull request.

Report vulnerabilities privately using [SECURITY.md](SECURITY.md).

## Set up and check a change

Use Node 22+, JDK 17, Android SDK 35 with Build Tools 34.0.0, and macOS 14+ with
Xcode or Command Line Tools for Mac work. Set `ANDROID_HOME` to your Android SDK,
or put `sdk.dir` in the ignored `android/local.properties` file.

From the repository root:

```sh
cd backend
npm ci
npm run check
npm test
npx wrangler deploy --dry-run
cd ..

./scripts/check-mac-crypto.sh
./scripts/check-mac-store.sh
./scripts/check-mac-clipboard.sh
./scripts/build-mac.sh

cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Run the checks for components you change; CI runs all three components on fresh
GitHub-hosted runners. Backend tests start a local Cloudflare runtime and need
loopback networking, but no Cloudflare login. Mac clipboard checks use a dedicated
test pasteboard. Android checks above need no emulator. These commands do not
deploy a Worker or require production credentials.

Use a separate test deployment and synthetic clipboard content for manual pairing,
revocation, retention, and recovery checks. See [verification](docs/verification.md)
for coverage and limitations. Never attach real clipboard databases, pairing codes,
keys, setup tokens, or unsanitized device logs to an issue or pull request.

## Pull requests

- Describe the problem, resulting behavior, and checks you ran. Include screenshots
  for UI changes and explain anything you could not test.
- Keep changes focused. Add regression coverage for behavioral fixes where useful.
- Update the protocol and both clients together when changing the wire format;
  identify migration or compatibility consequences explicitly.
- Keep generated builds, local SDK paths, signing material, and credentials out of
  Git. Do not add deployment steps or secrets to pull-request CI.
- For dependency changes, rerun the [dependency audit](docs/dependency-audit.md)
  and update the bundled third-party notices when the runtime graph changes.

Contributions are provided under the project's [MIT license](LICENSE). Submit only
code and artwork you have the right to contribute. Preserve upstream attribution
and licenses when bringing in third-party material.
