# Rebom Backend

## Encryption Service Setup

The encryption service uses AES-256-GCM for encrypting sensitive data with support for key rotation.

### Required Environment Variables

```bash
ENCRYPTION_PASSWORD=<your-password>
ENCRYPTION_SALT=<hex-encoded-salt>
```

### Optional (for key rotation)

```bash
ENCRYPTION_OLD_PASSWORD=<previous-password>
ENCRYPTION_OLD_SALT=<previous-hex-salt>
```

### Generating Secure Credentials with OpenSSL

**Generate a strong password (44 characters, base64-encoded):**
```bash
openssl rand -base64 32
```

**Generate a salt (32 hex characters = 16 bytes):**
```bash
openssl rand -hex 16
```

**Example output:**
```bash
# Password
$ openssl rand -base64 32
AT8P34WD8P1sHmB9Pz9pPD9pID8P2VVPz8cMj8NCg4=

# Salt
$ openssl rand -hex 16
a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6
```

**Set environment variables:**
```bash
export ENCRYPTION_PASSWORD="AT8P34WD8P1sHmB9Pz9pPD9pID8P2VVPz8cMj8NCg4="
export ENCRYPTION_SALT="a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"
```

### Key Rotation

To rotate encryption keys without breaking existing encrypted data:

1. Generate new credentials using the commands above
2. Set the new credentials as `ENCRYPTION_PASSWORD` and `ENCRYPTION_SALT`
3. Move the old credentials to `ENCRYPTION_OLD_PASSWORD` and `ENCRYPTION_OLD_SALT`
4. The service will automatically decrypt old data with the old key and encrypt new data with the new key

**Example:**
```bash
# New keys
export ENCRYPTION_PASSWORD="<new-password-from-openssl>"
export ENCRYPTION_SALT="<new-salt-from-openssl>"

# Old keys (for backward compatibility)
export ENCRYPTION_OLD_PASSWORD="<previous-password>"
export ENCRYPTION_OLD_SALT="<previous-salt>"
```

---

## Enrichment Configuration

BEAR enrichment is configured via the `setBearIntegration` GraphQL mutation, which stores the BEAR URI and API key in the database.

### Optional Environment Variable

```bash
ENRICHMENT_PENDING_IF_NOT_CONFIGURED=true
```

When set to `true`, incoming BOMs are marked with `PENDING` enrichment status even if no BEAR integration is configured yet. This is useful for new environments where BOMs are arriving before enrichment has been set up — once the BEAR integration is configured, the scheduler will pick up and enrich these BOMs automatically.

Default: `false` (BOMs are marked `SKIPPED` if no BEAR integration is configured).

---

## TEA operations

rebom fetches documents from Transparency Exchange API (TEA) servers for the ReARM backend. The
GraphQL operations `teaStatus`, `teaResolve`, `teaProduct`, `teaComponent`, `teaReleases`,
`teaRelease`, `teaFetchCollection`, `teaFetchedFileChunk`, `teaStoreFetchedSbom` and
`teaReleaseFetch` run the machine mode of the bundled rearm CLI (`rearm tea machine <verb>`) as a
child process under the ceilings below. Outcomes are data in every result (`outcome`, `error`),
never GraphQL errors; `teaApiVersion` is 1.

### The caller token

Every TEA operation needs the header `X-Rebom-Caller-Token` with the value of
`REBOM_TEA_CALLER_TOKEN` (32 to 256 printable ASCII characters without spaces). Without a valid
token in rebom's environment every TEA operation answers `NOT_CONFIGURED`; a missing or wrong
header is `CALLER_REFUSED`. Generate one with `openssl rand -hex 32` and give the ReARM backend
the same value. To rotate, replace it on both sides; a call in the window is refused and retried
at the next sync. rebom's other operations do not read the header.

### Settings

Read once at start. An invalid value does not fall back to the default: the TEA operations then
answer `NOT_CONFIGURED` with reason `SETTING` and the variable's name. A caller may lower a
per-call limit, never raise it.

| Setting | Default | Range |
| --- | --- | --- |
| `REBOM_TEA_READ_DEADLINE_SECONDS` | 60 | 1 to 600 |
| `REBOM_TEA_DOWNLOAD_DEADLINE_SECONDS` | 600 | 1 to 3600 |
| `REBOM_TEA_CONNECT_TIMEOUT_SECONDS` | 5 | 1 to 60 |
| `REBOM_TEA_MAX_DOCUMENT_BYTES` | 16777216 | 1024 to 268435456 |
| `REBOM_TEA_MAX_OUTPUT_BYTES` | 8388608 | 65536 to 25165824 |
| `REBOM_TEA_MAX_PAGES` | 10 | 1 to 1000 |
| `REBOM_TEA_MAX_ARTIFACT_BYTES` | 52428800 | 1 to 2147483647 |
| `REBOM_TEA_MAX_TOTAL_BYTES` | 268435456 | 1 to 2147483647 |
| `REBOM_TEA_MAX_FILES` | 200 | 1 to 100000 |
| `REBOM_TEA_MAX_DISK_BYTES` | 1073741824 | 1048576 to 68719476736 |
| `REBOM_TEA_HOLD_SECONDS` | 600 | 10 to 3600 |
| `REBOM_TEA_MAX_CHUNK_BYTES` | 8388608 | 65536 to 33554432 |
| `REBOM_TEA_MAX_CONCURRENT_READS` | 4 | 1 to 64 |
| `REBOM_TEA_MAX_CONCURRENT_DOWNLOADS` | 2 | 1 to 16 |
| `REBOM_TEA_MAX_CONCURRENT_RESULTS` | 2 | 1 to 16 |
| `REBOM_TEA_MAX_QUEUED` | 16 | 0 to 1024 (per pool; 0: no waiting) |
| `REBOM_TEA_QUEUE_WAIT_SECONDS` | 20 | 1 to 300 |
| `REBOM_TEA_TMP_ROOT` | `<os tmpdir>/rebom-tea` | an absolute path |

`teaStatus` reports the ceilings (its `deadlineSeconds` is the download deadline).

### Scratch directory, memory and disk

Runs and held fetches live under `<REBOM_TEA_TMP_ROOT>/work`, which must be a directory owned by
rebom's user with no group or other permission bits (rebom makes it so when it creates it, and
refuses one it did not make, with reason `TMP_ROOT`). Leftovers are removed at start; a stop
kills the running processes and removes the directory. `REBOM_TEA_MAX_DISK_BYTES` bounds running
downloads and held files together; a mounted volume with a size limit bounds it from outside.

A store parses up to 50 MB of JSON in rebom's own memory, several copies of it. A pod with a
memory limit below 2 GiB should set `REBOM_TEA_MAX_CONCURRENT_RESULTS` to 1.

### Local testing

`REBOM_TEA_LOCAL_TESTING=ON` (the exact string; anything else is `OFF`) lets the CLI connect to
loopback and to this host's own addresses and accepts plain `http` roots (and
`localPlainHttpPort` on `teaResolve`). It is for a developer machine only, never in production,
and never together with a token on a network others share. `teaStatus` reports it and rebom logs
a warning at start.

### The caller's timeout

A call is synchronous. The caller's HTTP timeout for a TEA call must be at least the effective
deadline plus 60 seconds (queue wait, kill margin, slack). A rebom without these operations
answers HTTP 400 with `GRAPHQL_VALIDATION_FAILED`.

### The CLI

The image pins the CLI release in the Dockerfile and checks at build time that it has the machine
mode. A rebom started with another `rearm` on its `PATH` checks the same on the first TEA call and
answers `NOT_AVAILABLE` (`CLI_MISSING`, `CLI_TOO_OLD`, `CLI_FORMAT`) when it lacks it.

---

## Database Migrations

Flyway command:

```bash
docker run --rm -v ./migrations:/flyway/sql flyway/flyway -url=jdbc:postgresql://host.docker.internal:5438/postgres -user=postgres -password=password -defaultSchema=rebom -schemas='rebom' migrate
```

---

## Testing

Test SARIF parser:

```bash
npx ts-node test/test-sarif.ts
```

Run all tests:

```bash
npm test
```

Run specific test suite:

```bash
npm test encryptionService
```
