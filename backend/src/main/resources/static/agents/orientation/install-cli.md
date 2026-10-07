<!-- orientation section: install-cli · core 2026-09-28 -->
### 1.2 Install the CLI — `26.09.1` exactly

The CLI is the only sanctioned programmatic surface (raw HTTP is
WAF-blocked in many deployments). Use **`26.09.1`** — older versions
lack the `rearm agent session ...` / `rearm agent enrollkey`
subcommands, the release vulnerability/violation output, and the
feature-set release targeting (`listfeaturesets` release fields,
`switchfeatureset --release` / `--follow`, §10) this doc relies on.

The source of truth for a release is its GitHub release page,
<https://github.com/relizaio/rearm-cli/releases/tag/26.09.1>, which
links the per-platform zips and the `sha256sums.txt` on the download
CDN (`https://cdn.rearmhq.com/rearm-download/26.09.1/`). Pick the
asset for your platform:

| OS      | Arch            | Asset                              |
|---------|-----------------|------------------------------------|
| Linux   | x86_64 / amd64  | `rearm-26.09.1-linux-amd64.zip`    |
| Linux   | aarch64 / arm64 | `rearm-26.09.1-linux-arm64.zip`    |
| Linux   | arm (32-bit)    | `rearm-26.09.1-linux-arm.zip`      |
| Linux   | i386            | `rearm-26.09.1-linux-386.zip`      |
| macOS   | Apple silicon   | `rearm-26.09.1-darwin-arm64.zip`   |
| macOS   | Intel           | `rearm-26.09.1-darwin-amd64.zip`   |
| Windows | x86_64          | `rearm-26.09.1-windows-amd64.zip`  |
| Windows | i386            | `rearm-26.09.1-windows-386.zip`    |
| FreeBSD | amd64 / i386 / arm | `rearm-26.09.1-freebsd-{amd64,386,arm}.zip` |
| OpenBSD | amd64 / i386    | `rearm-26.09.1-openbsd-{amd64,386}.zip` |
| Solaris | amd64           | `rearm-26.09.1-solaris-amd64.zip`  |

```bash
VERSION=26.09.1
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

Hashes for `26.09.1` (verbatim from the `sha256sums.txt` linked on the
GitHub release, `https://cdn.rearmhq.com/rearm-download/26.09.1/sha256sums.txt`):

```
6bc307fcf01bdccf900a6cc90716f29d004b83250ac8c70b3b1386d9ab46353b  rearm-26.09.1-darwin-amd64.zip
296c41599d739ccba6d5ea595f5a6c7a3e91a6f3b93f0c5519673c87bc1712fa  rearm-26.09.1-darwin-arm64.zip
3b9b1a77533c1a8260950d353a4b4d923156f0b9f250acb10da2659d2a1f6132  rearm-26.09.1-freebsd-386.zip
46b06ef8035658f7d123fc0eb5ba4d5cf14e5f0f86deba130b0623a3d4d171b6  rearm-26.09.1-freebsd-amd64.zip
23b8e1f971ea362643f8508c6f1aefaca36f92ab80125684bfd0b860e4cd38b7  rearm-26.09.1-freebsd-arm.zip
445b084eeb3c704624a085026fd3eb9965546cae23adba67d97fbddb94c200ac  rearm-26.09.1-linux-386.zip
9b44da897b80f9546bb005a2c1ca6a31bfda641c2b6cc86c21b129015df0446a  rearm-26.09.1-linux-amd64.zip
6624650d6c0fe5fae04b6fe4cc943071b3c1577c2e6ee9a0c056337230b422d1  rearm-26.09.1-linux-arm.zip
98dfbae123247634efcf9a62ec9f98c258c87d01e777094dfc013190a138cde1  rearm-26.09.1-linux-arm64.zip
ed3387b8e00334143cc2b23451d92d7101cbcc05b10a9d2f04385ad2b9f90912  rearm-26.09.1-openbsd-386.zip
88ec3e19074bf8cfb84fe194f2345a3cb21c60f8c0050154733768cc06bf4f16  rearm-26.09.1-openbsd-amd64.zip
b3870c2143718aa3f295d75fa23a0df6a581bf0b4371ad6742b04fa1fd10bba6  rearm-26.09.1-solaris-amd64.zip
258b153f59a3f1fd2f266ea21beeaa88f8cd60a9c19a60249b153266a63502aa  rearm-26.09.1-windows-386.zip
f78f03bacec640f9349beca7acf0c3dd7699daa91f23ba7b0c4d661e69eeebf8  rearm-26.09.1-windows-amd64.zip
```

Container alternative (same release, pinned by digest, from the GitHub
release page):

```
registry.relizahub.com/library/rearm-cli:26.09.1@sha256:1bac1b77ddcc9aba6f32c0ca81c72c33d04b1e6c039693abefe2e1bb3a00c298
```

If the hash doesn't match, **stop**. Don't run an unverified binary.
Re-fetch from the canonical URL or escalate to the operator.

Confirm the CLI is on your `PATH`:

```bash
rearm version
```

If the version reports older than `rearm_cli_min` (front-matter at
the top of this doc), stop and ask the operator to bump.

**The board verbs need CLI `26.10.1` or later.** `task verify`, `task
push`, `agent git commit` and `agent git merge`, `session open`, `session
close --final` and `session current`, `task signoff --pr`, the brief's
`next <TYPE>: <path>` lines and the checks in `doc publish --json`, which
the board sections name, are not in `26.09.1`. If `rearm agent task
--help` does not list `verify`, your CLI predates them: tell the operator
what `rearm version` prints.
