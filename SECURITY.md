# Security policy

Zap is experimental and has no stable supported release yet. Security fixes target
the current default branch; there is no backport or response-time guarantee.
Review the [production readiness notes](docs/production-readiness.md) before relying
on it for sensitive clipboard history.

## Report privately

Email **zodvik@gmail.com** with the subject `Zap security report`. If GitHub shows
the repository's **Security → Report a vulnerability** button, you may use that
private reporting form instead. Do not open a public issue or pull request that
exposes an unfixed vulnerability.

Include the affected commit or version, platform, a description of the impact,
and minimal reproduction steps using synthetic data. Never send real clipboard
history, live pairing codes, encryption keys, device tokens, setup tokens, or
Cloudflare credentials. An initial description is enough to arrange any further
details privately. Email is not an end-to-end encrypted reporting channel.

Please coordinate public disclosure with the maintainer while a fix is prepared.
If you receive no reply, follow up using the same private channel.

## Scope and expectations

Reports concerning the apps, backend, pairing protocol, key handling, local storage,
and build/dependency supply chain are welcome. Test only deployments and devices
you own or have explicit permission to assess.

Encryption does not protect plaintext on an unlocked or compromised endpoint.
The current clients trust the server's device roster during key rotation. Revoking
a device cannot erase content it already downloaded, and retention is not secure
erasure from backups. A pairing code contains encryption material that remains
sensitive after its enrollment invitation expires. These limitations are described
in the README and are not claims of protection offered by Zap.

## Maintainer setup

Before opening the repository, enable **Settings → Security → Advanced Security →
Private vulnerability reporting** (the location can vary with GitHub's UI). The
policy file alone does not enable that repository setting. Keep the email contact
working even when GitHub reporting is available. See GitHub's
[private reporting documentation](https://docs.github.com/en/code-security/how-tos/report-and-fix-vulnerabilities/configure-vulnerability-reporting).
