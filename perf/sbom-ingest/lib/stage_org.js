#!/usr/bin/env node
/**
 * Stage a fresh organization for the SBOM-ingest load test.
 *
 * Creates a throwaway Keycloak user, an organization, one component, and N
 * programmatic API keys. Prints identifiers to stdout and writes the secrets to
 * a separate file so they never land in a log or a transcript.
 *
 * Why N keys: the Bucket4j rate limiter allows 100 requests / 30s per key
 * (RateLimitingFilter.java:65-72), i.e. 12,000/hr. Programmatic keys are not
 * exempt; each gets its own bucket keyed api:<apiKeyId>. Driving 20,000
 * releases in under an hour therefore needs at least two keys and wants
 * headroom, so the limiter is never the thing being measured.
 *
 * Usage:
 *   node stage_org.js --keys 4 --secrets-out /path/to/creds.json
 */

const path = require('path');

const HARNESS = process.env.REARM_IT_DIR
  || '/home/reliza/claude_pojects/rearm-integration-tests';
const { buildConfig } = require(path.join(HARNESS, 'features/support/config.js'));
const { KeycloakAdmin } = require(path.join(HARNESS, 'features/support/keycloak.js'));
const { RearmClient } = require(path.join(HARNESS, 'features/support/rearm_client.js'));

function arg(name, dflt) {
  const i = process.argv.indexOf(`--${name}`);
  return i > -1 ? process.argv[i + 1] : dflt;
}

async function main() {
  const keyCount = parseInt(arg('keys', '4'), 10);
  const secretsOut = arg('secrets-out', '/tmp/loadtest-creds.json');

  const cfg = buildConfig();
  const stamp = Date.now();
  const email = `loadtest-${stamp}@${cfg.runOrg.userEmailDomain || 'example.com'}`;
  const password = 'Password123!';

  const kc = new KeycloakAdmin(cfg);
  await kc.init?.();
  const user = await kc.createUser({
    username: email, email, password,
    firstName: 'Load', lastName: 'Test',
  });
  const token = await kc.getUserAccessToken(email, password);

  const client = new RearmClient(cfg, token);

  // First authenticated call provisions the user server-side. It is also the
  // WAF smoke: a JWT-bearer GraphQL call from a generic HTTP client either
  // works here or the whole JMeter plan needs a different auth path.
  await client.getMyUser();

  const org = await client.createOrganization(`sbom-ingest-loadtest-${stamp}`);
  const orgUuid = org.uuid || org;

  const keys = [];
  for (let i = 0; i < keyCount; i++) {
    const k = await client.setOrgApiKey(orgUuid, 'ORGANIZATION_RW', String(i));
    keys.push({ order: String(i), id: k.id, secret: k.apiKey });
  }

  const out = {
    createdAt: new Date().toISOString(),
    baseUrl: cfg.baseUrl,
    orgUuid,
    keycloakUserId: user.id || user,
    keycloakEmail: email,
    keycloakPassword: password,
    keys,
  };
  require('fs').writeFileSync(secretsOut, JSON.stringify(out, null, 2), { mode: 0o600 });

  // Identifiers only. Secrets are in the file, never on stdout.
  console.log(JSON.stringify({
    orgUuid,
    keycloakEmail: email,
    keyCount: keys.length,
    keyIds: keys.map((k) => k.id),
    secretsFile: secretsOut,
  }, null, 2));
}

main().catch((e) => {
  console.error('stage failed:', e.message);
  process.exit(1);
});
