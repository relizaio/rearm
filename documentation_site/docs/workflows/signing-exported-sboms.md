---
sidebarDepth: 2
---

# Signing exported SBOMs

An organization that holds an SBOM signing key signs every merged CycloneDX JSON SBOM ReARM exports
for its releases. A consumer who holds the organization's public key can then check that the file
came from that organization and was not changed after the export.

See also [Exporting Compliance Artifacts](./exporting-compliance-artifacts) and
[Scoring an SBOM for CISA 2026 and FDA Readiness](./sbom-scoring).

## What a signed export is

Every merged CycloneDX JSON export of a release is signed: the one the **Export Release BOM**
dialog downloads, the one `rearm exportreleasebom` writes, and the document the release **Score**
reads. The signature is a root `signature` object in the CycloneDX JSON Signature Format (JSF),
over the whole document:

- algorithm `Ed25519`;
- the document is canonicalised with RFC 8785 (JCS: keys sorted, no whitespace, ECMAScript number
  form) with the `signature` in place and only its `value` left out, so indentation and key order
  do not matter;
- the signature carries the key's `keyId` and its public key as a JWK (`publicKey`, `kty` `OKP`,
  `crv` `Ed25519`).

Every merged JSON export also says, at the end of `metadata.properties`, whether it is signed.
The values are strings, as every CycloneDX property value is:

| Property | Value | Meaning |
|---|---|---|
| `reliza:export:signed` | `true` | The document carries a root `signature` made with the organization's current key |
| `reliza:export:signed` | `false` | The document is not signed; `reliza:export:unsignedReason` says why |
| `reliza:export:signatureKeyId` | the key id | On a signed document: the `keyId` of the key that signed it, the key id the **SBOM export signing** section shows for that key |
| `reliza:export:unsignedReason` | `NO_KEY` | The organization has no current SBOM signing key |
| `reliza:export:unsignedReason` | `SIGNING_ERROR` | Signing failed on the server; the server log has the cause |

These properties are inside the signed content, so they cannot be changed without breaking the
signature. They stay in the document whatever the **Include internal metadata** switch says: they
describe the file you hold.

The CISA 2026 check `sbom-author-signature` passes on a signed export.

## Configure

An organization admin manages the key in **Organization Settings**, **Admin Settings**,
**SBOM export signing**:

1. Press **Generate key**. ReARM creates an Ed25519 key for the organization.
2. The section shows the current key: its key id, algorithm, source (generated or imported), when
   it was created, and its public key as a JWK and as PEM, each with a copy button. Below it is a
   note that publishing the keys at a public URL is not available yet (quoted under
   [How a consumer verifies](#how-a-consumer-verifies)), and a table of earlier keys (retired and
   revoked) with their key id, status, source and dates. The table does not show their public keys.

Exports are signed from the next request on. There is no per-export switch: while the organization
has a current key, every merged JSON export is signed; without one, every export says
`reliza:export:unsignedReason` `NO_KEY`.

The private key is stored encrypted at rest with this installation's encryption settings (the
`relizaprops.encryption` password and salt, which every stored ReARM secret uses). An installation
that still runs on the default values of those settings protects the key with values anyone can
look up, so anyone who can read the database could recover it. Change them before you generate
or import a key (with the Helm chart: `useDefaultSecrets: false` and the `reliza-props` secret's
`encryption-pass` and `encryption-salt`).

SBOM export signing works the same on ReARM Community Edition and ReARM Pro. On a server from
before SBOM export signing (an older version) the section says "This server does not offer SBOM
export signing yet."

## Rotate and revoke

**Rotate.** With a current key, the button reads **Rotate key**, and it asks first:

> The current key is retired and new exports sign with the new key. Documents signed with the retired key keep verifying with its public key. Copy that public key before you rotate: this section shows the public key of the current key only.

The retired key moves to the table of earlier keys with the status `RETIRED`. That table does not
show a public key, and ReARM has no other supported way to read the public key of a retired key
today, so copy the current key's public key (PEM or JWK) and note its key id before you rotate. A
consumer who holds that public key keeps verifying documents signed before the rotation; a
consumer who holds only the new key gets `KEY_MISMATCH` on them.

**Revoke.** Revoke is offered on the current key and on every retired key, and asks first:

> The key is marked REVOKED. Consumers are not told: a copy of its public key still verifies documents signed with it, so tell them to stop trusting it. If it is the current key, exports are unsigned until a new key exists.

A revoked key is listed in the table of earlier keys with the status `REVOKED`. Revoking changes
nothing in a public key a consumer already holds: until ReARM publishes the keys with their status,
a consumer learns of a revocation only from you, and a document signed with the revoked key still
verifies with a copy of its public key. Tell the consumers who hold that key to stop trusting it.
Revoking the current key leaves the organization without one: every export from then on says
`reliza:export:unsignedReason` `NO_KEY` until you generate or import a new key.

## Import

**Import key** takes a private key you already hold: an Ed25519 private key as PKCS#8 PEM (a block
that starts with `-----BEGIN PRIVATE KEY-----`). Any other form, such as an RSA, EC or OpenSSH
key, is refused with:

```
SBOM signing key import expects an Ed25519 private key as PKCS#8 PEM (BEGIN PRIVATE KEY)
```

An imported key becomes the current key exactly as a generated one does: with a current key, the
import rotates it, and the earlier key is retired. Its source reads `IMPORTED`.

To make such a key with OpenSSL:

```bash
openssl genpkey -algorithm ed25519 -out org-sbom-signing.pem
```

## How a consumer verifies

ReARM does not publish an organization's public keys today, at any URL. In the place of a public
URL, the settings section says:

> Experimental: Publishing the organization's public signing keys at a public URL (JWKS), so consumers can verify signed exports without an account, is upcoming experimental functionality and is not available yet.

Until then the organization hands its public key to each consumer itself. Copy the current key's
public key from the settings section, as PEM or as a JWK, save it to a file (`pub.pem` below) and
give it to the consumer together with its key id. The consumer must be sure that the file comes
from the organization: whoever can swap the key on the way can sign documents that then verify.
Send it over a channel the consumer already trusts for the organization, or let the consumer
compare the key with the organization over a second channel.

The consumer verifies with the ReARM CLI:

```bash
rearm bomutils verify-signature -f release.cdx.json --key pub.pem
```

The CLI checks the signature with that key, and the `publicKey` embedded in the signature must
equal it. A file with one key verifies only the documents signed with that key. A signed document
names its key in `reliza:export:signatureKeyId`; a document signed with another key, for example
an earlier key of the organization, reads `KEY_MISMATCH`. The document can also come straight from
an export:

```bash
rearm exportreleasebom --releaseid <release uuid> | rearm bomutils verify-signature --key pub.pem
```

`--key` takes a PEM public key, a single JWK, or a JWKS file with several keys, of which the CLI
uses the one whose `kid` equals the signature's `keyId`. ReARM does not hand out a JWKS today.

To make the signature part of an SBOM readiness gate:

```bash
rearm bomutils score -f release.cdx.json --signature-key pub.pem --fail-on-not-ready
```

With a key given, the signature check passes only on `VERIFIED`: a signature that does not verify,
or that the CLI cannot verify, makes the `cisa-2026` profile NOT READY.

**The embedded key proves integrity, not authorship.** Without `--key`, `verify-signature` checks
the signature against the `publicKey` it embeds. That proves the document was not changed since it
was signed under that key, but anyone can sign a document with a key of their own and embed it.
Only a check against a public key you received from the organization says who signed it.

The outcome, `input.signature.verification` in the CLI's JSON output (from the
[rearm-cli bomutils documentation](https://github.com/relizaio/rearm-cli/blob/main/docs/bomutils.md),
"Signature verification"):

| Verification | Meaning |
|---|---|
| `NOT_REQUESTED` | no key was given (`score` without `--signature-key`) |
| `VERIFIED` | the signature verifies with the given key |
| `FAILED` | it does not verify (a changed document), the signature is malformed, or there is none |
| `KEY_MISMATCH` | the embedded `publicKey` is not the given key, or the key file holds no key for the signature's `keyId` |
| `KEY_REVOKED` | the selected key carries `rearm:status` `REVOKED` in the key file |
| `UNSUPPORTED_ALGORITHM` | an algorithm the CLI does not verify (it verifies `Ed25519`, `ES256`, `ES384`, `ES512`) |
| `UNSUPPORTED_FORM` | `signers` with two or more entries, `chain`, `excludes`, `certificatePath`, or an XML signature |
| `NO_KEY` | `verify-signature` without `--key` on a signature that names only a `keyId` and embeds no key |

A retired key still verifies. The CLI learns a key's status only from a `rearm:status` member of a
JWK or of a JWKS entry: with it, the detail of a `RETIRED` key says so and a `REVOKED` key reads
`KEY_REVOKED`. The PEM and the JWK the settings section shows carry none, so with them a document
signed with a retired or a revoked key reads `VERIFIED`.

## What the UI shows

**Export Release BOM dialog.** After an export, one line under the **Export** and **Score** buttons
says what the file you just received says about itself:

- `Signed with organization key <key id>`
- `Not signed: no organization SBOM signing key is configured (Organization settings, SBOM export signing)`
- `Not signed: signing failed on the server (SIGNING_ERROR)`

A CSV or Excel export shows `Signing applies to CycloneDX JSON exports only; this file carries no
signature.` Before an export, and after an export from a server that does not stamp its exports,
the dialog shows no line.

**Score report.** The report shows a signature line under the format line, read from the engine's
`input.signature`. A score from ReARM is made without a key, so a signed export reads
`Signature: Ed25519, key <key id>, present, not verified (no key given)`; a report from the CLI
made with `--signature-key` reads `verified` or the verification value with its detail.

**CLI.** `rearm exportreleasebom` prints the same sentences on stderr after a JSON export, starting
with a lower-case letter (`signed with organization key <key id>`).

## Limitations

- CSV and Excel exports carry no signature.
- Per-artifact downloads of uploaded SBOMs are served as uploaded and never re-signed.
- VDR and VEX exports are not signed.
- The merge drops the signatures of uploaded supplier SBOMs: the merged document carries only
  ReARM's signature.
- SPDX documents are not signed.
- The server scores without a key: in ReARM the score checks only that a signature is present and
  well-formed. Verify with the CLI and the organization's public key.
- The organization's public keys are not published: there is no public URL for them yet, so each
  consumer needs the key from the organization. The settings section shows the public key of the
  current key only, and nothing tells a consumer that a key was retired or revoked.
