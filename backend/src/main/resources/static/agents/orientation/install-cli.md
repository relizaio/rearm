<!-- orientation section: install-cli · core 2026-11-10 -->
### 1.2 Install the CLI — `26.11.5` exactly

The CLI is the only sanctioned programmatic surface (raw HTTP is
WAF-blocked in many deployments). Use **`26.11.5`**: it is both
`rearm_cli_min` and `rearm_cli_recommended` in the front-matter, and
the oldest CLI this doc supports. Older CLIs lack some of what this doc
relies on: the board verbs (below), the `rearm agent session ...` /
`rearm agent enrollkey` subcommands, the release vulnerability/violation
output, or the feature-set release targeting (`listfeaturesets` release
fields, `switchfeatureset --release` / `--follow`, §10).

The source of truth for a release is its GitHub release page,
<https://github.com/relizaio/rearm-cli/releases/tag/26.11.5>, which
links the per-platform zips and the `sha256sums.txt` on the download
CDN (`https://cdn.rearmhq.com/rearm-download/26.11.5/`). Pick the
asset for your platform:

| OS      | Arch            | Asset                              |
|---------|-----------------|------------------------------------|
| Linux   | x86_64 / amd64  | `rearm-26.11.5-linux-amd64.zip`    |
| Linux   | aarch64 / arm64 | `rearm-26.11.5-linux-arm64.zip`    |
| Linux   | arm (32-bit)    | `rearm-26.11.5-linux-arm.zip`      |
| Linux   | i386            | `rearm-26.11.5-linux-386.zip`      |
| macOS   | Apple silicon   | `rearm-26.11.5-darwin-arm64.zip`   |
| macOS   | Intel           | `rearm-26.11.5-darwin-amd64.zip`   |
| Windows | x86_64          | `rearm-26.11.5-windows-amd64.zip`  |
| Windows | i386            | `rearm-26.11.5-windows-386.zip`    |
| FreeBSD | amd64 / i386 / arm | `rearm-26.11.5-freebsd-{amd64,386,arm}.zip` |
| OpenBSD | amd64 / i386    | `rearm-26.11.5-openbsd-{amd64,386}.zip` |
| Solaris | amd64           | `rearm-26.11.5-solaris-amd64.zip`  |

```bash
VERSION=26.11.5
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

Hashes for `26.11.5` (verbatim from the `sha256sums.txt` linked on the
GitHub release, `https://cdn.rearmhq.com/rearm-download/26.11.5/sha256sums.txt`):

```
b09d962cad1b2fa5fc9fc1f70f7315a70a6ca2e660bd01c1dfca46b8f83c8287  rearm-26.11.5-darwin-amd64.zip
e64c8880ab756edc38084af3394301b7b2ae50f279e5ebdc975031dc16fcd679  rearm-26.11.5-darwin-arm64.zip
510a6253d980a66e21c59445e9aa8a9bda89279d6dfd967e1bcb1211af269637  rearm-26.11.5-freebsd-386.zip
6e545729a38422ac8b58a72cec41bd24cec456b5a6b609e129f1b0635ed53ec9  rearm-26.11.5-freebsd-amd64.zip
d2e6be5e1b90614a365d6823c245eb848af776182cfb2125b3d5fff219e77812  rearm-26.11.5-freebsd-arm.zip
6f828cb04e6e8750b1b7c3b6a5b7a3087f83ef0e478e05dcdaaaf33ea1ab3ffa  rearm-26.11.5-linux-386.zip
d5d6fcf764249cfc4eeb1bd8675f7e838a64dda79050e40b560a4322251bd628  rearm-26.11.5-linux-amd64.zip
647d00be254977959487a2116c1c750432d4517be1dca9409938e0c2e46bd163  rearm-26.11.5-linux-arm.zip
9b6f2a6c9060376c863d0e85446c5fb37c59b5182385ae9c4f5f4e51c5ebcc81  rearm-26.11.5-linux-arm64.zip
b5953533abd73d44bb0e2ab5e79e938f94e52e1ea49db5732ad2267d1652394d  rearm-26.11.5-openbsd-386.zip
0044f40227343da3af7ca1f50e69317c40d352b32af24fc05ee6a866706ec0e6  rearm-26.11.5-openbsd-amd64.zip
dbe01261005cf229c0ef839fd553bfe73019fa178edab105d687f6c252b41dd5  rearm-26.11.5-solaris-amd64.zip
f9d2da3fdedb1d4c28e1d3501d4cecf55bea7bd6e6a804f44d3b5f6d50357548  rearm-26.11.5-windows-386.zip
5c98076b70ec5bdf5304b3ef3261e6dd583e7861065fa89b6a37d801c1eacbe1  rearm-26.11.5-windows-amd64.zip
```

Container alternative (same release, pinned by digest, from the GitHub
release page):

```
registry.relizahub.com/library/rearm-cli:26.11.5@sha256:7414a3af377f6db6bd258379a674651147d952491a03a7b3eaaaab2ba49ef1b2
```

If the hash doesn't match, **stop**. Don't run an unverified binary.
Re-fetch from the canonical URL or escalate to the operator.

Confirm the CLI is on your `PATH`:

```bash
rearm version
```

If the version reports older than `rearm_cli_min` (front-matter at
the top of this doc), stop and ask the operator to bump.

**The board verbs need CLI `26.11.5` or later.** `task verify`, `task
push`, `agent git commit` and `agent git merge`, `session open`, `session
close --final` and `session current`, `task signoff --pr`, the brief's
`next <TYPE>: <path>` lines and the checks in `doc publish --json`, which
the board sections name, are not in older CLIs. If `rearm agent task
--help` does not list `verify`, your CLI predates them: tell the operator
what `rearm version` prints.
