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
Programmatic access — API keys, access tokens and CLI browser login — is part of the **shared
ReARM codebase**. It behaves the same on **ReARM CE** and **ReARM Pro**; nothing on this page is
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

## Managing keys in the UI

Keys for an organization live under **Organization Settings → Programmatic Access**, which is
split into four tabs:

| Tab | What it holds |
|---|---|
| **Free Form Keys** | Organization keys whose permissions you compose yourself. Used by CI, integrations and agents. |
| **Scoped Keys** | Keys tied to a specific object (for example a component), carrying that object's access. |
| **User Keys** | Personal keys belonging to members of the organization. |
| **Key Requests** | Requests from members for a Free Form key, for an admin to approve. |

Your own keys are also on your profile, under **Your API Keys**. From there you can
**Create key** for yourself, or **Request a Free Form key** if you need one an administrator has
to grant.

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

Signed in to ReARM in that browser, you will see the request — which host it came from and when —
and choose **what the CLI should act as**:

- a **personal key created for this session** (deleted again when you sign out),
- one of your **existing personal keys**, or
- a **Free Form key you hold**.

Approve it, and the CLI is signed in. The session's permissions are exactly that key's
permissions — approving a login never grants more than the key already had.

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
- The key a session was created for shows up among your **User Keys**, like any other personal
  key.

## Which method should I use?

| Situation | Use |
|---|---|
| A person working from a laptop or a remote shell | **CLI browser login** — nothing to copy or store |
| CI, an integration, or any unattended caller | **API key + access token** — exchange the secret for a bearer token |
| An existing pipeline already using key id and secret | **No change required** — it keeps working as-is |
