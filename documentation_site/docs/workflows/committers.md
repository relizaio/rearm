# Committers & Commit Signing

::: tip Available in ReARM CE and ReARM Pro
Committers, enrolled signing keys, and commit signature verification are part of the shared ReARM codebase and work on **both ReARM CE and ReARM Pro**. (CEL *policy enforcement* on top of the verdict — blocking a release on an unverified commit — is ReARM Pro.)
:::

## What is a committer?

A **committer** is a commit author that ReARM resolves signed commits back to — a natural person, or an external bot such as GitHub's web-flow signer. Each committer record holds:

- **Name** and **Email** — the primary identity shown on commits and in the UI.
- **Aliases** — additional/historical email addresses. A commit whose author/committer email matches the primary email *or any alias* resolves to this committer, so renames and alternate addresses still attribute correctly.
- **Linked ReARM user** *(optional)* — ties the committer to a ReARM user account; leave it standalone for bots/keys that aren't ReARM users.
- **Signing keys** — one or more enrolled public keys (see below).

> Agents are different: an AI agent owns its **own** signing keys and is resolved via the `ReARM-Agent` commit trailer, not as a committer — see [Bootstrap an AI Agent](./agentic).

## How signature verification works

ReARM verifies each commit's cryptographic signature against the public keys you have **enrolled** — matched by fingerprint. It never trusts the key embedded in the signature itself. For ordinary (non-agent) commits the verifier checks the organization's enrolled **committer** keys.

The result is recorded on the Source Code Entry and exposed to CEL as `commit.signature.state`:

| State | Meaning |
| --- | --- |
| `VERIFIED` | Signature is valid and matches an enrolled (non-revoked) key. |
| `UNSIGNED` | No signature on the commit. |
| `UNKNOWN_KEY` | Signed, but no enrolled key matches — *enrol the key to fix this*. |
| `INVALID_SIGNATURE` | Signature does not validate against the signed content. |
| `KEY_REVOKED` | Matched a key that has since been revoked. |
| `ERRORED` | Verification could not complete (e.g. malformed input). |

## Create a committer

In the ReARM UI go to **Organization Settings → Committers → New committer**:

| Field | Notes |
| --- | --- |
| Linked ReARM user | Optional — pick a user, or leave *standalone* for bots/external signers. |
| **Name** | Display name (e.g. `Alex Doe`). Required. |
| **Email** | Primary commit email. Required. |
| Aliases | Comma-separated alternate/historical emails. |

## Enrol a signing key

Open the committer, then under **Signing keys** click **+ Enrol key**:

- **SSH** — paste the single-line public key (`ssh-ed25519 AAAA… user@host`). The allowed-signers principal defaults to the committer's email.
- **GPG** — paste the full ASCII-armoured block (`-----BEGIN PGP PUBLIC KEY BLOCK-----` … `-----END … -----`).

A committer can hold several keys (e.g. an SSH and a GPG key, or a rotated pair). Revoking a key keeps it valid for **historical** verdicts but stops it binding **new** commits.

## Gating on signatures with CEL

Once committers and keys are enrolled, component-level or global rules can require verified commits. For example, block a release if any commit isn't verified:

```
release.commits.exists(c, c.signature.state != "VERIFIED")
```

## GitHub web-flow signing

Commits you make through GitHub's **web UI** are signed by GitHub itself, not by you:

- editing a file in the browser and committing,
- the **Merge pull request** / **Squash and merge** / **Rebase and merge** buttons,
- web-based reverts, and committing a suggested change in a review.

GitHub signs each of these with its own GPG key — *"GitHub (web-flow commit signing)"* — and records the committer as `GitHub <noreply@github.com>` (and, for some web-UI commits, `web-flow@github.com`). These show a green **Verified** badge on GitHub, but ReARM leaves them `UNKNOWN_KEY` until you enrol GitHub's web-flow key on a committer.

To set it up:

1. **Fetch GitHub's web-flow public key** (ASCII-armoured; one key signs every repo's web-UI commits):

   ```bash
   curl https://github.com/web-flow.gpg
   ```

   On GitHub Enterprise Server, fetch it from your instance instead: `curl https://<your-ghes-host>/web-flow.gpg`.

2. **Create the committer** (Organization Settings → Committers → New committer):

   | Field | Value |
   | --- | --- |
   | Linked ReARM user | *None — standalone* |
   | **Name** | `GitHub` |
   | **Email** | `noreply@github.com` |
   | Aliases | `web-flow@github.com` |

   The alias is needed because GitHub stamps some web-UI commits with the `web-flow@github.com` committer address instead of `noreply@github.com`; the alias resolves both back to this one committer.

3. **Enrol the web-flow key** — on the committer, **Signing keys → + Enrol key → Format: GPG**, and paste the block from step 1.

From then on, commits authored through the GitHub web UI verify as `VERIFIED` and attribute to the `GitHub` committer.
