<!-- orientation section: signing-key · core 2026-10-08 -->
### 2.4 Generate and enrol your signing key (first run, once per agent host)

You sign commits with an SSH or GPG key. ReARM matches the signature
against keys enrolled under your agent uuid; if no key is enrolled,
commits land as `signature.state=UNSIGNED` (or `UNKNOWN_KEY` if a
signature is present but the verifier has no matching enrolled key)
and the component-level signed-commits gate will reject the release.

You **generate** the key yourself on the agent host and **self-enrol**
the public half — operators don't provision keys ahead of time. The
FREEFORM `AGENT` key carries an intentional carve-out that lets it
attach a public key to its own bound-agent identity.

**Step 0 — Bootstrap host-local signing material.** The remaining
steps assume `~/.ssh/agent_signing_key` exists and git is configured
to sign with it. On a fresh host neither is true, so do this first
(idempotent — skip any line whose state is already what you want):

```bash
# Generate an ed25519 keypair (no passphrase — must sign non-interactively).
# The comment is informational; the verifier doesn't read it.
ssh-keygen -t ed25519 -f ~/.ssh/agent_signing_key -N "" \
  -C '<your.email@your-vendor.example>'

# allowed_signers maps a principal email to the pubkey for SSH-signature
# verification. The principal here MUST match the --identity passed to
# `rearm agent enrollkey` below, and SHOULD match git's user.email so
# `git log --show-signature` validates locally.
mkdir -p ~/.config/git
echo "<your.email@your-vendor.example> $(cat ~/.ssh/agent_signing_key.pub)" \
  > ~/.config/git/allowed_signers

# git globals: sign every commit by default with the ed25519 key.
git config --global gpg.format ssh
git config --global user.signingkey ~/.ssh/agent_signing_key.pub
git config --global gpg.ssh.allowedSignersFile ~/.config/git/allowed_signers
git config --global commit.gpgsign true
```

Pick **one** identity (typically a vendor-scoped email) and use it
consistently across `allowed_signers`, git's `user.email`, and the
`--identity` flag on `enrollkey`. Drift between them is the most
common reason a freshly-bootstrapped host still lands commits as
`UNKNOWN_KEY`.

**Step 1 — Enrol the pubkey under your agent uuid**:

```bash
rearm agent enrollkey \
  --agent '<agent uuid from init response>' \
  --format SSH \
  --pubkey-file ~/.ssh/agent_signing_key.pub \
  --identity '<the same email you used in allowed_signers and git user.email>'
```

There is no `--org` flag: the org is always the one your FREEFORM key
resolves to, so it's taken from the key automatically. The fingerprint
is auto-derived locally via `ssh-keygen -lf` (or
`gpg --with-colons --show-keys` for `--format GPG`). Pass
`--fingerprint` explicitly if the local tool isn't available.

**Ownership rule.** `enrollkey` attaches the pubkey to exactly the
agent named by `--agent`. The only constraint is that this agent must
be owned by your calling FREEFORM key's identity — any agent the key
created (including a fresh row from a new `(name, model,
model-version, vendor)` tuple) can be enrolled. A key can never enrol
onto an agent owned by a *different* identity. Drifting tuples still
create extra Agent rows, so **pick the tuple once and reuse it on
every session** to avoid sprawl; ask the operator to delete any
duplicate row via the controlling-ReARM admin UI.

If a key is already enrolled and you're rotating it, enrol the new
one and then revoke the old via the operator (CLI doesn't yet have
a self-revoke; that's an operator JWT path).

