// Node probe of the trust-store gate: the client that ships is the client that
// is tested. Run with the image's node:
//
//   node /gate/trust-gate.mjs
//
// Node has no offline chain-validation API, so with both hosts unreachable
// the decision is the openssl offline check of trust-gate.sh against the same
// bundle. Identical in every repository that carries ci/trust-gate/.

import { existsSync } from 'node:fs';

const HOSTS = [
  'dv-g3b-emudhra.we-have-a-problem.com',
  'dv-g3b-aaa-emudhra.we-have-a-problem.com',
];

// The first Node release whose bundled store no longer carries the two roots.
// Empty until that release exists; until then the image must run Node on the
// system bundle (--use-openssl-ca with SSL_CERT_FILE).
const FIXED_NODE = '';

const PASS_CODES = new Set([
  'UNABLE_TO_GET_ISSUER_CERT_LOCALLY',
  'UNABLE_TO_VERIFY_LEAF_SIGNATURE',
  'SELF_SIGNED_CERT_IN_CHAIN',
  'CERT_UNTRUSTED',
  'CERT_REVOKED',
  'ERR_TLS_CERT_ALTNAME_INVALID',
]);
const UNREACHABLE_CODES = new Set(['ENOTFOUND', 'ECONNREFUSED', 'ETIMEDOUT', 'UND_ERR_CONNECT_TIMEOUT']);

let failures = 0;
const say = (msg) => console.log(`probe: ${msg}`);
const fail = (msg) => {
  say(`${msg}: FAIL`);
  failures++;
};

function versionAtLeast(have, want) {
  const parse = (v) => v.replace(/^v/, '').split('.').map((n) => parseInt(n, 10) || 0);
  const a = parse(have);
  const b = parse(want);
  for (let i = 0; i < Math.max(a.length, b.length); i++) {
    if ((a[i] || 0) !== (b[i] || 0)) return (a[i] || 0) > (b[i] || 0);
  }
  return true;
}

function codeOf(err) {
  for (let cur = err; cur; cur = cur.cause) {
    if (cur.code) return cur.code;
  }
  return undefined;
}

say(`node ${process.versions.node}`);
say(`NODE_OPTIONS ${process.env.NODE_OPTIONS ?? '(unset)'}`);
say(`SSL_CERT_FILE ${process.env.SSL_CERT_FILE ?? '(unset)'}`);

if (FIXED_NODE === '') {
  if (!(process.env.NODE_OPTIONS ?? '').split(/\s+/).includes('--use-openssl-ca')) {
    fail('NODE_OPTIONS does not contain --use-openssl-ca');
  }
  const certFile = process.env.SSL_CERT_FILE;
  if (!certFile || !existsSync(certFile)) {
    fail(`SSL_CERT_FILE does not name an existing file (${certFile ?? 'unset'})`);
  }
} else if (!versionAtLeast(process.versions.node, FIXED_NODE)) {
  fail(`node ${process.versions.node} is older than ${FIXED_NODE}`);
}

for (const host of HOSTS) {
  try {
    const res = await fetch(`https://${host}/`, { signal: AbortSignal.timeout(20000) });
    fail(`${host}: TLS handshake succeeded, HTTP ${res.status} (chain trusted)`);
  } catch (err) {
    const code = codeOf(err);
    if (PASS_CODES.has(code)) {
      say(`${host}: pass (${code})`);
    } else if (UNREACHABLE_CODES.has(code) || err?.name === 'TimeoutError') {
      say(`${host}: unreachable (${code ?? err.name})`);
    } else {
      fail(`${host}: ${code ?? err} (not a certificate verification error)`);
    }
  }
}

if (failures === 0) {
  say('PASS');
} else {
  say(`FAIL (${failures} findings)`);
  process.exit(1);
}
