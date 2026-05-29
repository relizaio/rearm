# Verifying GitHub Web-Flow Commits

::: tip Available in ReARM CE and ReARM Pro
Commit signature verification — committers, enrolled signing keys, and the resulting `VERIFIED` verdict — is part of the shared ReARM codebase and works on **both ReARM CE and ReARM Pro**.
:::

## What is GitHub web-flow?

Commits you make through GitHub's **web UI** are signed by GitHub itself, not by you:

- editing a file in the browser and committing,
- the **Merge pull request** / **Squash and merge** / **Rebase and merge** buttons,
- web-based reverts, and committing a suggested change in a review.

GitHub signs each of these with its own GPG key — *"GitHub (web-flow commit signing)"* — and records the committer as:

```
GitHub <noreply@github.com>
```

These commits show a green **Verified** badge on GitHub, but ReARM will leave them `UNKNOWN_KEY` until you tell ReARM to trust GitHub's web-flow key. ReARM never trusts the key embedded in a signature — it only matches against keys you have **enrolled** on a [Committer](../concepts/). For non-agent commits the verifier checks the org's enrolled **committer** GPG keys, so the fix is to enroll GitHub's public web-flow key under a committer.

## Set up a GitHub web-flow committer

### 1. Fetch GitHub's web-flow public key

GitHub publishes the web-flow public key. Download the ASCII-armoured block:

```bash
curl https://github.com/web-flow.gpg
```

This key is global to `github.com` — one key signs the web-UI commits of every repository. (On GitHub Enterprise Server, fetch it from your instance instead: `curl https://<your-ghes-host>/web-flow.gpg`.)

### 2. Create the committer

In the ReARM UI go to **Organization Settings → Committers → New committer** and fill in:

| Field            | Value                                  |
| ---------------- | -------------------------------------- |
| Linked ReARM user | *None — standalone* (GitHub is not a ReARM user) |
| **Name**         | `GitHub`                               |
| **Email**        | `noreply@github.com`                   |
| Aliases          | `web-flow@github.com`                  |

The name/email are for clean attribution on the commit and in the UI — verification itself matches on the enrolled key, not the email. Add `web-flow@github.com` as an alias because GitHub stamps some web-UI commits with that committer address instead of `noreply@github.com`; the alias lets ReARM resolve both back to this one committer.

### 3. Enrol the web-flow key

Open the committer you just created. Under **Signing keys** click **+ Enrol key**:

- **Format:** `GPG`
- **Public key:** paste the full ASCII-armoured block from step 1 (the `-----BEGIN PGP PUBLIC KEY BLOCK-----` … `-----END PGP PUBLIC KEY BLOCK-----` text).

Click **Enrol**. The key is now active for the organization.

## Result

From now on, commits authored through the GitHub web UI verify as **`VERIFIED`** in ReARM and attribute to the `GitHub` committer — visible on the Source Code Entry signature badge, the [Pull Request](./pull-requests) view, and anywhere CEL policies read `commit.signature.state`. For example, a component or global rule can now require:

```
release.commits.exists(c, c.signature.state != "VERIFIED")
```

and merge commits created with the GitHub **Merge** button will satisfy it.

::: tip
The web-flow key is just one committer. Enrol your developers' own GPG/SSH public keys on their committers the same way so their locally-signed commits verify too.
:::
