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
