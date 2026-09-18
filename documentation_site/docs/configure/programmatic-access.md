---
sidebarDepth: 2
---

# Programmatic Access

::: warning Preview — not released yet
Everything on this page is **preview** functionality. It is merged but has **not shipped in a
released version of ReARM yet**, so it is not available on released instances and the details
below may still change before release.
:::

::: tip Available in both ReARM Community Edition and ReARM Pro
Programmatic access — API keys, access tokens, CLI browser login and federated identity — is part
of the **shared ReARM codebase**. It behaves the same on **ReARM CE** and **ReARM Pro**; nothing on this page is
edition-specific.
:::

ReARM has always authenticated machine callers with an **API key id and secret**. That still
works everywhere it used to. This page covers what is new alongside it:

- **[Access tokens](#access-tokens)** — trade the key secret once for a short-lived bearer token
  instead of sending the secret on every call.
- **[Personal keys and key management](#managing-keys-in-the-ui)** — a reworked Programmatic
  Access area, personal (user) keys, and secret expiry.
- **[Two secrets per key](#rotating-a-secret-without-downtime)** — rotate a secret with no
  downtime, and deactivate a key as a kill switch.
- **[CLI browser login](#signing-the-cli-in-from-your-browser)** — sign the CLI in from your
  browser, with no secret to copy or store.
- **[GitHub Actions without a secret](#github-actions-without-a-secret)** — let a workflow
  authenticate with the identity token GitHub issues to the job, so CI holds no key at all.

## Managing keys in the UI

Keys for an organization live under **Organization Settings → Programmatic Access**, which is
split into four tabs:

| Tab | What it holds |
|---|---|
| **Free Form Keys** | Organization keys whose permissions you compose yourself. Used by CI, integrations and agents. **Key Requests** — requests from members for a Free Form key, for an admin to approve — sit at the top of this tab. |
| **User Keys** | Personal keys belonging to members of the organization. |
| **Scoped Keys** | Keys tied to a specific object (for example a component), carrying that object's access. |
| **Federated Identities** | Trust rules that let CI authenticate with its own identity token instead of a stored secret, plus the per-repository identities those rules have created. See [GitHub Actions without a secret](#github-actions-without-a-secret). |

Your own keys are also on your profile, under **Your API Keys**. From there you can
**Create key** for yourself, or **Request a Free Form key** if you need one an administrator has
to grant.

::: tip A personal key never holds more than its owner
Whatever is set on a personal key — by its owner or by an administrator — is stored **reduced to
what the owner holds** in the organization at that moment, and every call is checked against the
owner's permissions again. A personal key cannot be a way to act above the person it belongs to.
:::

::: tip The secret is shown once
Whenever a secret is generated, the plaintext value is displayed **only at that moment**. Copy it
into your secret store immediately — ReARM stores only a hash and cannot show it again.
:::

## Access tokens

Rather than sending the key id and secret on every request, exchange them **once** for a
short-lived bearer token and use that token for subsequent calls. The secret then travels over the
wire a single time per hour instead of on every call.

Exchange the key for a token with the OAuth 2.0 `client_credentials` grant. The key id and secret
go in as HTTP Basic credentials:

```bash
curl -s -u "$REARM_APIKEYID:$REARM_APIKEY" \
  -X POST "$REARM_URI/api/programmatic/token" \
  -d "grant_type=client_credentials"
```

```json
{
  "access_token": "eyJhbGciOi...",
  "token_type": "Bearer",
  "expires_in": 3600
}
```

Then call the programmatic API with the token:

```bash
curl -s "$REARM_URI/api/programmatic/graphql" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"query":"query { organizations { uuid name } }"}'
```

Things worth knowing:

- **Tokens last one hour** (`expires_in` is `3600`). Exchange again when the token expires; there
  is no refresh for the `client_credentials` grant.
- **A token is bound to the secret it came from.** Retiring or deactivating that secret
  invalidates its outstanding tokens immediately — you do not have to wait for the hour to run
  out.
- The key id and secret may also be sent as `client_id` and `client_secret` form parameters if
  HTTP Basic is awkward for your client.
- The token carries exactly the key's permissions. It is not an elevation of any kind.
- A programmatic call with **no credential at all** is answered `401` with a
  `WWW-Authenticate: Bearer` challenge, as is a call with an invalid or expired token. Artifact and
  signature downloads are served `Cache-Control: private, no-store`, so nothing access-controlled
  lands in a shared cache.

## Rotating a secret without downtime

A key can hold **two secrets at once**. That is what makes a rotation possible without a window
where nothing can authenticate:

1. On the key, choose **Add secret**. The new plaintext secret is shown once — capture it.
2. Roll the new secret out to whatever uses the key, while the old secret is still valid.
3. Once everything is on the new secret, delete the old one.

You can also give a secret an **Expiry** so it stops working on a date of your choosing, and
**Clear expiry** to remove it again.

To cut a key off entirely, **Deactivate** it. Deactivation is the kill switch: the key stops
authenticating at once and any access tokens issued from it stop working, but the key and its
history stay on record. **Delete** removes it outright.

## Signing the CLI in from your browser

The CLI can now sign in through your browser, so there is no secret to copy onto the machine at
all. Run `rearm login` with **no key flags**:

```bash
rearm login -u https://rearm.example.com
```

The CLI prints a short code and a link, and opens the link in your browser:

```
Open this link in your browser and approve the sign-in:
  https://rearm.example.com/cli-login?user_code=WDJB-MJHT
Code: WDJB-MJHT
```

Signed in to ReARM in that browser, you will see the request and choose **what the CLI should
act as**:

- **Create a personal key for this session** — a key that exists only for this session and is
  deleted again when you sign out. You set **what it may do** on the approval form: an
  organization-wide level, functions, and per-object grants. Whatever you set is stored **reduced
  to your own permissions** in the organization, and every call is checked against them again — the
  session key can never exceed you.
- **Use one of my keys** — one of your existing **personal keys**, or a **Free Form key you hold**.
  The session's permissions are exactly that key's permissions.

Either way, approving a login never grants more than you already have.

Before approving, check the request is the one you started. The page shows two groups of details
and labels them by how much to trust them:

- **Reported by the CLI** — host name, operating system, time zone and client version. The CLI
  sends these, so the requester controls them.
- **Observed by the server** — the address the request actually came from.

Approve it, and the CLI is signed in.

Check what the CLI is currently acting as:

```bash
rearm whoami
```

And sign out when you are done — this ends the session on the server, deletes the key if it was
created for that session, and clears the local credentials file:

```bash
rearm logout
```

::: tip Signing in with a key still works
`rearm login -u <uri> -i <api-key-id> -k <api-key-secret>` behaves exactly as before. The browser
flow is what happens when you omit `--apikeyid`/`--apikey`; it is an addition, not a replacement.
:::

### What to know about a browser session

- **It refreshes itself.** The CLI holds a refresh token and renews its access token as needed, so
  a signed-in CLI keeps working between commands without you doing anything.
- **Sessions expire.** A session that goes unused eventually expires and you sign in again.
- **The credentials file is local.** It is written to your home directory with owner-only
  permissions. Treat it like any other credential: on a shared or throwaway machine, run
  `rearm logout` when you are finished rather than leaving the session behind.
- **You can see and revoke your sessions.** Your profile lists them under **CLI sessions**, with
  the device details each one reported and the address the server observed. Revoking one signs
  that CLI out immediately, and a key that was created for the session is deleted with it. An
  administrator can also see the sessions riding a particular key from that key's row in
  *Organization Settings → Programmatic Access*.
- The key a session was created for shows up among your **User Keys**, like any other personal
  key.

## GitHub Actions without a secret

A GitHub Actions job can authenticate with the **identity token GitHub issues to the job itself**.
There is then no ReARM key and no secret stored in the repository at all — nothing to rotate and
nothing to leak. An organization administrator sets the trust up once, and every repository it
covers authenticates without further configuration.

### Set up the trust rule

In *Organization Settings → Programmatic Access → **Federated Identities***, add a **Trust Rule**.
A rule has two halves: who is trusted, and what they may do.

**Who is trusted.** The **Owner on the provider** — your GitHub organization or user — is required.
Narrow it from there:

| Field | Purpose |
|---|---|
| **Repositories** | Globs over the name or `owner/name`. Empty means every repository of the owner. |
| **Excluded repositories** | Carved back out of the above. |
| **Refs** | `main`, `release/*`, `refs/tags/v*`. A bare pattern matches both branches and tags. |
| **Environments** | Restrict to a GitHub deployment environment. |
| **Events** | Restrict to `push`, `release`, `workflow_dispatch` and so on. |
| **Subject globs** | Advanced: match the raw `sub` claim, e.g. `repo:myorg/*:environment:prod`. |
| **Expires** | Optional. After this time the rule stops matching. |

::: warning `*` spans separators
In repository and subject globs `*` matches across `/` and `:` too. That cuts both ways — write
inclusions no broader than you mean, and check that an exclusion really covers what you think.
:::

**What it may do.** Either a scope that is evaluated per token, or one fixed key:

- **Scope by repository** (the usual choice). A level on **the calling repository's own**
  components, branches and releases, optionally the right to **create** components for that
  repository, and any extra static permissions a repository cannot express by itself. Each job gets
  only what its own repository maps to. The **organization-wide read** level is optional and
  defaults to **none** — leave it there for an ordinary build: `getversion`, `addrelease` and the
  rest authorize against the component they resolve, which the repository level already covers.
  An organization-wide level allows reads on *every* object of the organization, so grant it only
  to a workflow that genuinely lists or reads beyond its own repository.
- **Act as a Free Form key**. The job takes that key's permissions and attribution exactly.

**GitHub Actions** is the only provider today. The **Issuer** field is only for GitHub Enterprise
Server (`https://HOST/_services/token`) — leave it empty for github.com.

### Use it in a workflow

Grant the job `id-token: write` and let the CLI do the rest:

```yaml
permissions:
  id-token: write
  contents: read
steps:
  - run: rearm getversion --vcsuri "https://github.com/${{ github.repository }}" -b "${{ github.ref_name }}" -u https://rearm.example.com
    env:
      REARM_AUTH: github-oidc
```

The CLI requests the identity token with your ReARM URL as its audience, exchanges it at the token
endpoint for the usual one-hour access token, and renews it on its own as needed. In a job that has
`id-token: write` and no other credentials present, this mode is selected **automatically** —
`REARM_AUTH: github-oidc` (or `--auth github-oidc`) simply makes the choice explicit. `--auth` also
takes `key` and `session`.

Pass `--org <organization uuid>` (or `REARM_ORG`) only when **several** organizations trust the
same repository, which would otherwise be ambiguous.

`rearm whoami --auth github-oidc` reports which key and repository the job is acting as. A refused
exchange names the reason: no trust rule matches, several organizations match, or the repository
was renamed since its identity was pinned.

### Identities, pinning and revocation

The first successful exchange for a repository records a **federated identity** under the same tab —
one row per repository, holding no secret. Each row shows when it was **last used** and the
**pinned ids** it is bound to, which together tell you which repositories are actually relying on
the rule.

- **To cut access off**, disable or delete the **trust rule**. Tokens minted through it stop working
  at once. (Deleting an identity row does not revoke anything on its own — a job the rule still
  matches simply re-creates it on its next run.)
- **Pinning** protects against a repository being renamed or recreated to impersonate an earlier
  one: the identity is bound to the provider's numeric owner and repository ids on first use, and a
  mismatch is refused. When a rename is legitimate, an administrator **resets the pin** to accept
  the new ids.

## Which method should I use?

| Situation | Use |
|---|---|
| A person working from a laptop or a remote shell | **CLI browser login** — nothing to copy or store |
| A GitHub Actions workflow | **Federated identity** — `id-token: write` and a trust rule, no secret in the repository |
| Other CI, an integration, or any unattended caller | **API key + access token** — exchange the secret for a bearer token |
| An existing pipeline already using key id and secret | **No change required** — it keeps working as-is |
