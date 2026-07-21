# ReARM CE via Docker Compose

Single-file deployment of ReARM Community Edition: backend, UI,
Keycloak (SSO), three PostgreSQL instances, rebom (BOM service) and the
OCI artifact service, plus an optional TLS front for non-localhost
deployments.

All configuration is optional and lives in a single gitignored `.env`
file next to `docker-compose.yml` -- see [`.env.example`](./.env.example)
for every knob. With no `.env` at all you get a working localhost
deployment.

## Scenario 1: localhost (default)

```bash
docker compose up -d
```

Open http://localhost:8092 -- no TLS, no configuration. Localhost is a
[secure context](https://developer.mozilla.org/en-US/docs/Web/Security/Secure_Contexts),
so the login flow works over plain http.

## Scenario 2: remote host by IP (eval)

Browsers only expose the Web Crypto API -- which the PKCE login flow
requires -- in secure contexts (https or localhost). A deployment
reached by IP or hostname therefore **must** run the TLS profile;
plain http will fail at login with `Web Crypto API is not available`.

```bash
cp .env.example .env
# in .env:
#   REARM_HOST=203.0.113.7:8443     # what users type in the browser
#   REARM_PROTOCOL=https
#   REARM_TLS_HOST=203.0.113.7      # bare host/IP, no port
#   REARM_TLS_PORT=8443             # omit both port lines to use 443
#   COMPOSE_PROFILES=tls
docker compose up -d
```

Open https://203.0.113.7:8443 -- traefik serves a self-signed
certificate, so the browser shows a warning once (Advanced -> Proceed).
The connection is still a secure context, so login works.

## Scenario 3: real domain with Let's Encrypt

Requires a public DNS record for the host and ports 443/80 reachable
from the internet (the ACME challenge and the http->https redirect use
port 80).

```bash
cp .env.example .env
# in .env:
#   REARM_HOST=rearm.example.com
#   REARM_PROTOCOL=https
#   REARM_TLS_HOST=rearm.example.com
#   REARM_ACME_EMAIL=you@example.com
#   COMPOSE_PROFILES=tls
docker compose up -d
```

Certificates are obtained and renewed automatically; no browser
warnings.

> Setting `REARM_ACME_EMAIL` is what switches traefik from the
> self-signed default certificate to Let's Encrypt. Custom
> `REARM_TLS_PORT`/`REARM_TLS_HTTP_PORT` values break the ACME
> challenge -- use the defaults for this scenario.

## First login

Self-registration is disabled in the shipped realm. Create the first
user through the Keycloak admin console:

1. Open `<your origin>/kauth/admin/` (e.g.
   http://localhost:8092/kauth/admin/) and log in with the Keycloak
   admin credentials (`admin` / `admin` by default -- change them for
   anything internet-facing).
2. Switch the realm selector from **master** to **Reliza**.
3. Users -> Create new user: set *Email* (used as the username), mark
   it verified, then set a password under Credentials (temporary: off).
4. Log in at your ReARM origin with that email and password.

Command-line alternative (no browser needed):

```bash
docker compose exec keycloak bash -c '
/opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:9080/kauth --realm master --user admin --password admin
/opt/keycloak/bin/kcadm.sh create users -r Reliza -s username=you@example.com -s email=you@example.com -s enabled=true -s emailVerified=true
U=$(/opt/keycloak/bin/kcadm.sh get users -r Reliza -q email=you@example.com --fields id --format csv --noquotes | head -1)
/opt/keycloak/bin/kcadm.sh set-password -r Reliza --userid "$U" --new-password "ChangeMe12345!"'
```

## OCI artifact storage (optional)

BOMs and artifacts can be stored in an OCI registry, either external or
bundled.

**External registry:** set the four `OCI_*` variables in `.env` (see
`.env.example`); the registry namespace is declared once and used by
both consumers.

**Bundled local registry (zot):** add the `docker-compose.zot.yml`
overlay to run a single-container [zot](https://zotregistry.dev)
registry on the compose network and point artifact storage at it:

```sh
docker compose -f docker-compose.yml -f docker-compose.zot.yml up -d
```

No `.env` changes are required -- credentials default to
`rearm` / `zotRegistryPass` and can be overridden with
`OCI_REGISTRY_USERNAME` / `OCI_REGISTRY_TOKEN` (the htpasswd file is
regenerated from these on every start). The registry publishes no host
ports: it is reachable only by the other compose services. Artifacts
persist in the `rearm-zot-data` volume. The storage profile matches
ReARM's access pattern (push once, fetch by digest, no deletes):
deduplication and garbage collection are off, and the minimal zot image
ships no UI/search extensions.

Note: like traefik (see the comment in `docker-compose.yml`), zot needs
an inotify instance at startup; on hosts with exhausted inotify
instances it exits with "couldn't initialize inotify: too many open
files" -- raise `fs.inotify.max_user_instances` (e.g.
`sysctl -w fs.inotify.max_user_instances=512`).

The legacy per-service files (`core.env`, `oci.env`, `rebom.env`) are
still honored for backward compatibility, but new deployments should
use `.env`.

## Passwords and secrets

- Each PostgreSQL instance has its own password variable --
  `REARM_PG_PASS`, `KEYCLOAK_PG_PASS`, `REBOM_PG_PASS` (all default to
  a well-known value; change them for anything beyond localhost).
- `.env` holds credentials in plain text: it is gitignored -- keep it
  that way, and consider `chmod 600 .env`.
- The main PostgreSQL convenience port (5440) binds to loopback by
  default; `REARM_PG_BIND=0.0.0.0` exposes it, which you should only do
  after changing `REARM_PG_PASS`.

## Things to know

- **`REARM_HOST` is baked in at first boot.** The Keycloak realm import
  (login-app redirect URIs) only runs when the realm does not exist
  yet. To change the host later: `docker compose down -v` (destroys all
  data, including users) and boot fresh, or edit the `login-app` client
  under the Reliza realm in the Keycloak admin console.
- **Ports published by default**: 8092 (UI, the single external
  origin), 9080 (Keycloak direct -- its console redirects to the main
  origin anyway), 5440 (PostgreSQL, loopback-only). The `tls` profile
  adds `REARM_TLS_PORT`/`REARM_TLS_HTTP_PORT` (443/80 by default).
- **Data lives in named volumes** (`rearm-postgres-data`,
  `rearm-keycloak-compose-postgres-data`, `rebom-postgres-data`,
  `traefik-data`). `docker compose down` keeps them; `down -v` deletes
  them.
- **Upgrades**: pull a compose file tagged to a newer ReARM release
  (image digests are pinned per release), then `docker compose up -d`.
- **Logs**: `docker compose logs -f rearm-core` (or `rearm-ui`,
  `keycloak`, `traefik`, ...).
