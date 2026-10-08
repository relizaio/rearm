<!-- orientation section: install-cli · core 2026-10-08 -->
### 1.2 Install the CLI — `26.10.2` exactly

The CLI is the only sanctioned programmatic surface (raw HTTP is
WAF-blocked in many deployments). Use **`26.10.2`**: it is both
`rearm_cli_min` and `rearm_cli_recommended` in the front-matter, and
the oldest CLI this doc supports. Older CLIs lack some of what this doc
relies on: the board verbs (below), the `rearm agent session ...` /
`rearm agent enrollkey` subcommands, the release vulnerability/violation
output, or the feature-set release targeting (`listfeaturesets` release
fields, `switchfeatureset --release` / `--follow`, §10).

The source of truth for a release is its GitHub release page,
<https://github.com/relizaio/rearm-cli/releases/tag/26.10.2>, which
links the per-platform zips and the `sha256sums.txt` on the download
CDN (`https://cdn.rearmhq.com/rearm-download/26.10.2/`). Pick the
asset for your platform:

| OS      | Arch            | Asset                              |
|---------|-----------------|------------------------------------|
| Linux   | x86_64 / amd64  | `rearm-26.10.2-linux-amd64.zip`    |
| Linux   | aarch64 / arm64 | `rearm-26.10.2-linux-arm64.zip`    |
| Linux   | arm (32-bit)    | `rearm-26.10.2-linux-arm.zip`      |
| Linux   | i386            | `rearm-26.10.2-linux-386.zip`      |
| macOS   | Apple silicon   | `rearm-26.10.2-darwin-arm64.zip`   |
| macOS   | Intel           | `rearm-26.10.2-darwin-amd64.zip`   |
| Windows | x86_64          | `rearm-26.10.2-windows-amd64.zip`  |
| Windows | i386            | `rearm-26.10.2-windows-386.zip`    |
| FreeBSD | amd64 / i386 / arm | `rearm-26.10.2-freebsd-{amd64,386,arm}.zip` |
| OpenBSD | amd64 / i386    | `rearm-26.10.2-openbsd-{amd64,386}.zip` |
| Solaris | amd64           | `rearm-26.10.2-solaris-amd64.zip`  |

```bash
VERSION=26.10.2
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

Hashes for `26.10.2` (verbatim from the `sha256sums.txt` linked on the
GitHub release, `https://cdn.rearmhq.com/rearm-download/26.10.2/sha256sums.txt`):

```
88963d5b2bbf3334dce8785f318868c42efeaee38f1d257b506c13614fb505a7  rearm-26.10.2-darwin-amd64.zip
d09cbe6efe0176097f468cdb1809c6aaaae30c25fc22b666f697b16a18a0fb3f  rearm-26.10.2-darwin-arm64.zip
60d398ebef5aabe9ee10526ce531d4d74285b80fc399b54a284a1a1719699e60  rearm-26.10.2-freebsd-386.zip
c5b93a3b86eae55af6273d6fdca803041a3a22a48b59c0a84c37639aa6a6b7d0  rearm-26.10.2-freebsd-amd64.zip
cfaaa043ccb10054458cf867c73670ce0e42a45ccbe68e711e18dcc2bb1e47db  rearm-26.10.2-freebsd-arm.zip
188ef7160e317980c0ea323d73e6bf48ebeab4a661a396e5758ec749ee7ced72  rearm-26.10.2-linux-386.zip
d405c3f6f748925ea1a965cfa4e757c4a35186e4e1134ad86ad38d02d889a16f  rearm-26.10.2-linux-amd64.zip
4c0e895be8520aca7f967d7371483f787b03c759fc363639f9056196bf634be2  rearm-26.10.2-linux-arm.zip
1bb1e03c91fabf0acf3a11420b5ab529127e6085086c677dee90845e62347717  rearm-26.10.2-linux-arm64.zip
943d1a694a7c52443d6e0b0af9874d13e2d6151efa84073c2129ecca0615c3c2  rearm-26.10.2-openbsd-386.zip
8cdf49b47a754d6259a974fe4654c7caacc2119ec83300c2fa7b0c73819779da  rearm-26.10.2-openbsd-amd64.zip
8a28ec72436dac4a7329a1ef62bf4586fdc114696644b8ac020ba4912ef6f79e  rearm-26.10.2-solaris-amd64.zip
49f5c9bf899dde60805ac445662e188763cd9ed2abef16a4faf94f6b4dd7b317  rearm-26.10.2-windows-386.zip
0ecc1f47fb53b0498541d08aeaea9fb310c8a5e26419133f66737ccff3ebad99  rearm-26.10.2-windows-amd64.zip
```

Container alternative (same release, pinned by digest, from the GitHub
release page):

```
registry.relizahub.com/library/rearm-cli:26.10.2@sha256:a53101d54b0c50208532a444c4a5be8856acf325fb9d07ff0c480aa0153241e4
```

If the hash doesn't match, **stop**. Don't run an unverified binary.
Re-fetch from the canonical URL or escalate to the operator.

Confirm the CLI is on your `PATH`:

```bash
rearm version
```

If the version reports older than `rearm_cli_min` (front-matter at
the top of this doc), stop and ask the operator to bump.

**The board verbs need CLI `26.10.2` or later.** `task verify`, `task
push`, `agent git commit` and `agent git merge`, `session open`, `session
close --final` and `session current`, `task signoff --pr`, the brief's
`next <TYPE>: <path>` lines and the checks in `doc publish --json`, which
the board sections name, are not in older CLIs. If `rearm agent task
--help` does not list `verify`, your CLI predates them: tell the operator
what `rearm version` prints.
