---
sidebarDepth: 2
---

# Programmatic Access

::: tip Available in both ReARM Community Edition and ReARM Pro
Programmatic access (API keys, access tokens, CLI browser login and federated identity) is part of
the shared ReARM codebase and works on both **ReARM CE** and **ReARM Pro**. The differences are in
what a permission can point at, see [Edition differences](#edition-differences).
:::

ReARM has always authenticated machine callers with an **API key id and secret**. That still
works everywhere it used to. This page covers what is new alongside it:

- **[Personal keys and key management](#managing-keys-in-the-ui)**: a reworked Programmatic
  Access area, personal (user) keys, and Free Form keys that members can request.
- **[Access tokens](#access-tokens)**: trade the key secret once for a short-lived bearer token
  instead of sending the secret on every call.
- **[Two secrets per key](#secrets-rotation-expiry-and-the-kill-switch)**: rotate a secret with no
  downtime, give each secret an expiry, and deactivate a key as a kill switch.
- **[Keys without a secret](#keys-without-a-secret)**: create a key id first, then bind it to a
  federated trust rule or mint its secret later, from the UI or with `rearm apikey mint`.
- **[CLI browser login](#signing-the-cli-in-from-your-browser)**: sign the CLI in from your
  browser (device authorization), with no secret to copy or store, and bound how long such a
  session may last.
- **[GitHub Actions without a secret](#github-actions-without-a-secret)**: let a workflow
  authenticate with the identity token GitHub issues to the job, so CI holds no key at all.

## Edition differences

Everything on this page behaves the same on both editions, except for the objects a permission can
point at:

| Where | ReARM Pro | ReARM CE |
|---|---|---|
| Permissions on Free Form and personal keys, and on the key a CLI login creates | Per-perspective permissions, instance and cluster permissions, and the DevOps Read / DevOps Write functions | Not offered: CE has no perspectives, instances or clusters to grant on, and does not show the DevOps functions |
| Federated trust rules, extra static permissions | Scopes `Component / Product`, `Perspective` and `Instance / Cluster` | `Instance / Cluster` is hidden. `Perspective` is listed, but there is nothing to select in it, because CE has no perspectives |
| Creating a component into a perspective (`perspective` input, `--perspective`) | Supported | Not applicable: CE has no perspectives |

## Managing keys in the UI

Keys for an organization live under **Organization Settings -> Programmatic Access**, which only
organization administrators see. It is split into four tabs:

| Tab | What it holds |
|---|---|
| **Free Form Keys** | Organization keys whose permissions you compose yourself. Used by CI, integrations and agents. **Key Requests**, requests from members for a Free Form key, sit at the top of this tab when there are any. |
| **User Keys** | Personal keys belonging to members of the organization. |
| **Scoped Keys** | Keys bound to one object (component, instance, cluster, organization-wide and approval keys), carrying that object's access. |
| **Federated Identities** | Trust rules that let CI authenticate with its own identity token instead of a stored secret, plus the per-repository identities those rules have created. See [GitHub Actions without a secret](#github-actions-without-a-secret). |

Your own keys are on your profile, in the **API Keys** tab under **Your API Keys**. From there you
can **Create key** for yourself, or **Request a Free Form key** if you need one an administrator has
to grant.

::: tip A personal key never holds more than its owner
Whatever is set on a personal key, by its owner or by an administrator, is stored **reduced to
what the owner holds** in the organization at that moment, and every call is checked against the
owner's permissions again. A personal key cannot be a way to act above the person it belongs to.
:::

::: tip The secret is shown once
Whenever a secret is generated, the plaintext value is displayed **only at that moment**. Copy it
into your secret store immediately: ReARM stores only a hash and cannot show it again.
:::

### Creating a Free Form key

On the **Free Form Keys** tab, click **Create Free Form Key** (the plus icon under the table). This
creates the **key id only, with no secret**. ReARM then offers to **Generate secret** right away;
choose **Later** to leave the key without one (see [Keys without a secret](#keys-without-a-secret)).
Set what the key may do with **Set Permissions For Key** in its **Manage** column.

A personal key made with **Create key** on your profile also starts with no secret and **no
permissions**: it is refused everywhere until you set its permissions from the **Manage** column,
and it needs a secret (or a [CLI login](#signing-the-cli-in-from-your-browser)) to be used.

### Key requests and holders

A member with write access in the organization can **Request a Free Form key** from their profile,
with a purpose and the permissions they propose. The request appears under **Key Requests** for the
administrators, who can **Review / Edit Permissions**, then **Approve** or **Deny** it (a denial
takes an optional reason, which the requester sees).

- An approved key becomes **active, still without a secret**. The requester is its **holder**, and
  only the holder can generate or regenerate its secrets: administrators do not see them.
- Administrators keep the other controls of a held key (permissions, retiring or deleting a secret,
  deactivating the key), and can reassign the holder, or clear it, with the **Holder (who mints the
  secrets)** icon. A new holder must be a member of the organization.
- On a key without a holder, secrets can be minted by anyone with write access on the
  organization; in the UI that happens on the Programmatic Access tab, which administrators see.
  On a personal key, only its owner mints secrets, administrators included.

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

## Secrets: rotation, expiry and the kill switch

A Free Form or personal key holds **up to two secrets at once**, in slots `#1` and `#2`. Both work
until one is retired, regenerated, deleted or expires. The **Secrets** column of the key tables
shows each slot as `active`, `retired` or `expired`, with its created, last-used and expiry dates
behind the info icon, and the actions below.

| Action | What it does |
|---|---|
| **Add secret** / **Add second secret (rotation)** | Mints a secret in the free slot and shows it once. You can give it an expiry as you add it. Refused when both slots are taken. |
| **Regenerate** | Replaces the secret in that slot with a new one, shown once. The old value, and every access token exchanged with it, stop working immediately; the other slot is unaffected. |
| **Retire** / **Enable** | Retiring keeps the secret on file but refuses it, and the access tokens exchanged with it, until it is enabled again. |
| **Delete** | Removes the secret for good and frees its slot. The secret and every access token exchanged with it stop working. |
| **Expiry** | Sets, changes or clears when the secret stops working. It must be in the future. Access tokens exchanged with the secret end at that time at the latest. |

Retiring or deleting the **last active secret** leaves the key unable to authenticate with a
secret until a new one is added; ReARM warns before doing it.

### Rotating without downtime

1. On the key, choose **Add second secret (rotation)**. The new secret is shown once; capture it.
2. Roll the new secret out to whatever uses the key, while the old one still works.
3. Once everything is on the new secret, **Retire** the old one, and **Delete** it when you are
   sure nothing needs it.

### The kill switch

To cut a key off entirely, **Deactivate** it in the **Status** column. Every secret and every access
token of that key id are refused until it is activated again; nothing is deleted. When an
organization administrator deactivates a key, the key is marked **disabled by an admin**: its owner
or holder can no longer re-activate it, only an administrator can. **Delete** removes the key
outright: the key id, its secrets and every access token stop working, and this cannot be undone.

## Keys without a secret

A Free Form key created on the **Free Form Keys** tab, approved from a key request, or created by
applying an `API_KEYS` file with `rearm apikey apply` starts as a **key id with no secret**. It
cannot authenticate with a secret until one is minted, but it can still be used in two ways.

### Bound to a federated trust rule

A [federated trust rule](#set-up-the-trust-rule) whose grant is **Act as a Free Form key** binds
that key: a GitHub Actions job the rule admits exchanges its identity token for an access token
**of that key**, with the key's permissions and attribution. The key needs no secret for this:
ReARM counts a Free Form key as able to authenticate when it is active and has either a usable
secret or a usable trust rule bound to it.

- The bound key must be an **active Free Form key of the same organization** as the rule.
- When several rules match one identity, the key-bound rules take precedence over template rules,
  and they must all bind the same key.
- Deactivating the key stops the exchange; disabling, editing or deleting the rule invalidates the
  access tokens minted through it at once.

### Minting a secret later

- **In the UI**: **Add secret** on the key's row, see
  [Secrets](#secrets-rotation-expiry-and-the-kill-switch). On a held key only its holder can do it.
- **With the CLI**: `rearm apikey mint` mints a secret for a **declared** key and prints it once:

  ```bash
  rearm apikey mint <key> --slot 1            # mint slot 1 if it is empty
  rearm apikey mint <key> --slot 2 --rotate   # replace the secret slot 2 holds
  ```

  `<key>` is the key's declared name, its key id or its uuid; `--slot` is `1` (the default) or
  `2`. A slot that already holds a secret is left alone and no value comes back (the CLI notes
  this on stderr), unless `--rotate`, which replaces it so the old value stops working at once. The secret's expiry follows the key's
  `secretExpiresDays` setting (no expiry when it is not set).

  The command needs CLI 26.10.2 or later and a caller with the **Configuration Write** function. It
  is refused for a key that no `API_KEYS` file declares (an organization administrator can declare
  a key made by hand with **Declare as…** on its row), for a key that a person holds (its holder
  mints on the keys page), and for a key with more permissions than the caller.

## Signing the CLI in from your browser

The CLI can sign in through your browser, so there is no secret to copy onto the machine at all.
This is the OAuth 2.0 device authorization flow: the CLI asks ReARM for a login request, and you
approve it in a browser that is signed in to ReARM, on the same machine or on any other. Run
`rearm login` with **no key flags**:

```bash
rearm login -u https://rearm.example.com
```

The CLI prints a short code and a link, and tries to open the link in your browser:

```
Open this link in your browser and approve the sign-in:
  https://rearm.example.com/cli-login?user_code=WDJB-MJHT
Code: WDJB-MJHT
```

On a machine without a browser, open the link (or `/cli-login` on your ReARM, and type the code)
anywhere else. The request waits **10 minutes** for approval; after that, run `rearm login` again.

### Approving the request

Signed in to ReARM, you see the request and choose **what the CLI should act as**:

- **Create a personal key for this session**: a new personal key in the organization you pick,
  with the notes you give it (by default `CLI session on <host>`). You set **what it may do** on
  the approval form: an organization-wide level, functions, and per-object grants. The form only
  offers what you hold yourself, and whatever is sent is stored **reduced to your own
  permissions** in that organization; every call is checked against them again, so the session
  key can never exceed you. You must be a member of the organization. The key is deleted when the
  session ends.
- **Use one of my keys**: one of your active **personal keys**, or an active **Free Form key you
  hold**. The session acts with exactly that key's permissions.

Either way, approving a login never grants more than you already have. **Deny** refuses the
request, and the CLI reports that the sign-in was denied.

Before approving, check the request is the one you started. The page shows two groups of details
and labels them by how much to trust them:

- **Reported by the CLI**: host name, operating system, time zone and client version. The CLI
  sends these, so the requester controls them.
- **Observed by the server**: the address the request came from (the first address of
  `X-Forwarded-For` when a proxy sets it).

The approval form also has an optional **Session lifetime** in minutes, see below. Approve, and
the CLI is signed in: it prints the key it acts as, the organization, and when the session ends.

::: tip Signing in with a key still works
`rearm login -u <uri> -i <api-key-id> -k <api-key-secret>` behaves as before and writes the key to
the credentials file. The browser flow is what happens when you omit `--apikeyid`/`--apikey`; it is
an addition, not a replacement.
:::

### Session lifetime and refresh

- **No secret is stored.** The CLI receives a refresh token, delivered once and kept by ReARM only
  as a hash, and trades it for the usual **one-hour access tokens** as it needs them. A signed-in
  CLI keeps working between commands without you doing anything.
- **Sliding expiry.** Each refresh extends the session to **30 days** from that moment, but never
  past **90 days** after approval. A CLI used at least once a month stays signed in until the
  90 days are up.
- **Rotating refresh tokens.** Every refresh replaces the refresh token. The previous one is still
  honoured for **2 minutes** (a retry, or a crash before the new one was saved); presented after
  that, it means two holders, and ReARM revokes the session.
- **A hard end per key: `sessionMaxMinutes`.** A personal or Free Form key can bound the
  device-login sessions approved on it, from **1 to 129600 minutes** (90 days); empty means no
  bound beyond the 90-day cap. A session approved on such a key ends that many minutes after
  approval, refreshes included. Set it on the key's permissions dialog, in the **Device Login**
  tab (**Device-login session limit**); the key's owner or holder, or an organization
  administrator, can change it. A change applies to sessions approved afterwards, not to open
  ones. It does not affect the key's secrets, which are bounded by their own expiry.
- **The approver can shorten it.** The **Session lifetime** on the approval page may be shorter
  than the key's limit, never longer; for a new personal key, which has no limit, it can be 1 to
  129600 minutes. A session of 60 minutes or less gets one access token and no refresh token.
- **What else ends a session:** the key behind it being deactivated or deleted (a refresh then
  fails, and the kill switch refuses its access tokens), the session being revoked, or
  `rearm logout`.

### `rearm whoami` and `rearm logout`

Check what the CLI is currently acting as:

```bash
rearm whoami
```

For a browser login it prints the mode (`browser-login session`), the key id, the organization and,
when known, `sessionExpiresAt` and `sessionHardExpiry`. With an API key it prints the key id; in a
GitHub Actions job with `--auth github-oidc` it prints the identity the job acts as.

Sign out when you are done. This revokes the session on the server, deletes the key if it was
created for that session, and clears the session from the local credentials file:

```bash
rearm logout
```

The credentials file is `$HOME/.rearm.env` (or the file given with `--config`), written with
owner-only permissions. Treat it like any other credential: on a shared or throwaway machine, run
`rearm logout` when you are finished rather than leaving the session behind.

### Seeing and revoking sessions

- **Your own sessions**: your profile, **API Keys** tab, **CLI sessions**. Each row shows the host,
  system and time zone the CLI reported, the address the server saw, the key it acts as, the
  organization, when it signed in, was last used and expires, and its hard end (**Ends (key
  limit)**) when the key bounds it. **Revoke** signs that CLI out at once.
- **Sessions on a key** (organization administrators): in **Organization Settings -> Programmatic
  Access**, the **CLI sessions on this key** icon on a Free Form or User key lists the active
  sessions riding it, and can revoke them.

Revoking a session ends it immediately, together with its access tokens. A key that was created
for the session is deleted with it, unless another active session still uses it. The key a session
was created for shows up among the **User Keys**, like any other personal key.

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
- **Act as a Free Form key**. The job takes that key's permissions and attribution exactly. The
  key needs no secret, see [Keys without a secret](#keys-without-a-secret).

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

For end-to-end sample pipelines with the ReARM GitHub Actions and a troubleshooting list, see
[Federated (Keyless) Login from GitHub Actions](../integrations/githubActionsFederated).

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
