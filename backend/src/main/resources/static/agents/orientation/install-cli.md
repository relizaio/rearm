<!-- orientation section: install-cli · core 2026-10-25 -->
### 1.2 Install the CLI — `26.10.17` exactly

The CLI is the only sanctioned programmatic surface (raw HTTP is
WAF-blocked in many deployments). Use **`26.10.17`**: it is both
`rearm_cli_min` and `rearm_cli_recommended` in the front-matter, and
the oldest CLI this doc supports. Older CLIs lack some of what this doc
relies on: the board verbs (below), the `rearm agent session ...` /
`rearm agent enrollkey` subcommands, the release vulnerability/violation
output, or the feature-set release targeting (`listfeaturesets` release
fields, `switchfeatureset --release` / `--follow`, §10).

The source of truth for a release is its GitHub release page,
<https://github.com/relizaio/rearm-cli/releases/tag/26.10.17>, which
links the per-platform zips and the `sha256sums.txt` on the download
CDN (`https://cdn.rearmhq.com/rearm-download/26.10.17/`). Pick the
asset for your platform:

| OS      | Arch            | Asset                              |
|---------|-----------------|------------------------------------|
| Linux   | x86_64 / amd64  | `rearm-26.10.17-linux-amd64.zip`   |
| Linux   | aarch64 / arm64 | `rearm-26.10.17-linux-arm64.zip`   |
| Linux   | arm (32-bit)    | `rearm-26.10.17-linux-arm.zip`     |
| Linux   | i386            | `rearm-26.10.17-linux-386.zip`     |
| macOS   | Apple silicon   | `rearm-26.10.17-darwin-arm64.zip`  |
| macOS   | Intel           | `rearm-26.10.17-darwin-amd64.zip`  |
| Windows | x86_64          | `rearm-26.10.17-windows-amd64.zip` |
| Windows | i386            | `rearm-26.10.17-windows-386.zip`   |
| FreeBSD | amd64 / i386 / arm | `rearm-26.10.17-freebsd-{amd64,386,arm}.zip` |
| OpenBSD | amd64 / i386    | `rearm-26.10.17-openbsd-{amd64,386}.zip` |
| Solaris | amd64           | `rearm-26.10.17-solaris-amd64.zip` |

```bash
VERSION=26.10.17
# Linux / macOS: derive the asset name from uname; on Windows pick it from the table.
OS=$(uname -s | tr '[:upper:]' '[:lower:]')          # linux | darwin | freebsd | openbsd
ARCH=$(uname -m); case "$ARCH" in x86_64) ARCH=amd64;; aarch64|arm64) ARCH=arm64;; i?86) ARCH=386;; armv*) ARCH=arm;; esac
ASSET=rearm-${VERSION}-${OS}-${ARCH}.zip
curl -fsSL -O https://cdn.rearmhq.com/rearm-download/${VERSION}/${ASSET}

# Verify the SHA-256 of the downloaded zip BEFORE unpacking.
# Hashes are for the install zip, NOT the binary inside.
sha256sum "${ASSET}"     # macOS: shasum -a 256 "${ASSET}"
# Compare against the line for your asset in the table below.
```

Hashes for `26.10.17` (verbatim from the `sha256sums.txt` linked on the
GitHub release, `https://cdn.rearmhq.com/rearm-download/26.10.17/sha256sums.txt`):

```
eb939725fdd98ee794ff4984aecdc9767ea38be5f0aabe1d4e37e0a706bd3deb  rearm-26.10.17-darwin-amd64.zip
b5940a73d14b31fcda4755e5e5ccdc045d6821d2af5d4ba3707865212e061036  rearm-26.10.17-darwin-arm64.zip
b29e025bae84fa5612229a0682734cdf6e18a1eb4b57012fdd356cc0356004ba  rearm-26.10.17-freebsd-386.zip
3542c43306b94931443e491655e3e99c4d29af6a996c1f7524ed28392d708baa  rearm-26.10.17-freebsd-amd64.zip
9803ad76485144be1b1aee88ae7ff64bbef9987fdf0c7e7618ff96ed209f6f0b  rearm-26.10.17-freebsd-arm.zip
76f690a909bfa6f026c78f24b42d12c0506219305acb048d03e23164599b8421  rearm-26.10.17-linux-386.zip
c7990f604381dd994c6aae57022aa21d1cbcc5b25f0b09b5d51348f3af790315  rearm-26.10.17-linux-amd64.zip
0c93d4f53b10c2e731b0c2974eef1cfcbd39febb5058fcebe7b8f6e31d292562  rearm-26.10.17-linux-arm.zip
3d6352f166df9787230da567d8129973bb44d586a46d201013693f44b7589287  rearm-26.10.17-linux-arm64.zip
966f0bbae303c6ed1e3e7caabef82ac7611e147bc22934db1c1f01db0aa2b50b  rearm-26.10.17-openbsd-386.zip
76fff57d97e0355e628694eaa3f788e928770205cb1ba224af00f458747f2db4  rearm-26.10.17-openbsd-amd64.zip
46b82545afea8551a3079079b5e9dea9cb9b58e862df9660517616dd750af6b6  rearm-26.10.17-solaris-amd64.zip
61b2861e85aa9ee9ce1bc19c7d4eded1f3e7f45ded6c264ed7f0f5f4155abbb1  rearm-26.10.17-windows-386.zip
2cb722abd233ef70d9a17fe769990a95001612bc170a5dfb0c5db9a43825cf69  rearm-26.10.17-windows-amd64.zip
```

Container alternative (same release, pinned by digest, from the GitHub
release page):

```
registry.relizahub.com/library/rearm-cli:26.10.17@sha256:561bb9b77ab43c0b132cad74e1ec0bcf05516f4feafae054495203498b0f585b
```

If the hash doesn't match, **stop**. Don't run an unverified binary.
Re-fetch from the canonical URL or escalate to the operator.

Confirm the CLI is on your `PATH`:

```bash
rearm version
```

If the version reports older than `rearm_cli_min` (front-matter at
the top of this doc), stop and ask the operator to bump.

**The board verbs need CLI `26.10.17` or later.** `task verify`, `task
push`, `agent git commit` and `agent git merge`, `session open`, `session
close --final` and `session current`, `task signoff --pr`, the brief's
`next <TYPE>: <path>` lines and the checks in `doc publish --json`, which
the board sections name, are not in older CLIs. If `rearm agent task
--help` does not list `verify`, your CLI predates them: tell the operator
what `rearm version` prints.
