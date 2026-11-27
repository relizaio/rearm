import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// childProcess.ts captures spawn at load, so it is patched before any import runs: every spawn is
// recorded and then runs for real.
const rec = vi.hoisted(() => {
    const cp = (process as any).getBuiltinModule('node:child_process');
    const original = cp.spawn;
    const calls: { cmd: string, args: string[], options: any }[] = [];
    cp.spawn = (cmd: string, args: string[], options: any) => {
        calls.push({ cmd, args: [...args], options });
        return original(cmd, args, options);
    };
    return { cp, original, calls };
});

import * as fs from 'fs';
import * as path from 'path';
import { verifySignature } from '../../src/services/signatureVerifier';
import { buildChildEnv, ChildTool } from '../../src/childEnv';

/**
 * The real ssh-keygen and gpg with the short environment of their tool (TPF-256 design 3.1, 3.6
 * and 3.9; row T-1211). They run in the image's builder stage with both tools on PATH and do not
 * skip when one is missing. A canary is planted in process.env; POSTGRES_PASSWORD is set there by
 * the test configuration. The fixtures under test/fixtures/signature are public material only: a
 * payload, an allowed_signers line and a signature made with a throwaway ed25519 SSH key, and the
 * armoured public key and a detached signature of a throwaway ed25519 GPG key. Neither private key
 * was kept.
 */

const CANARY = 'CHILD_ENV_CANARY';
const FIXTURES = path.join(__dirname, '..', 'fixtures', 'signature');
const BASE_NAMES = ['LANG', 'LC_ALL', 'PATH'];

// ssh-keygen -lf of the fixture SSH key.
const SSH_FINGERPRINT = 'SHA256:Mf0kD/AWYWRjk0pj5bb4cUTRrQwFsLAuemFWKi6QEmY';
// The key id that GOODSIG names for the fixture GPG key (fingerprint 9DD0C79AE0E2DB3CFAEE070F53F2530DE932B07B).
const GPG_KEY_ID = '53F2530DE932B07B';

const fixture = (name: string) => fs.readFileSync(path.join(FIXTURES, name));
const b64 = (buf: Buffer) => buf.toString('base64');
const tampered = () => Buffer.concat([fixture('payload.txt'), Buffer.from('one line more\n')]);

function expectBaseList(env: NodeJS.ProcessEnv): void {
    expect(Object.keys(env).every((name) => BASE_NAMES.includes(name))).toBe(true);
    expect(typeof env.PATH).toBe('string');
    expect(Object.prototype.hasOwnProperty.call(env, CANARY)).toBe(false);
    expect(Object.prototype.hasOwnProperty.call(env, 'POSTGRES_PASSWORD')).toBe(false);
}

function expectSshEnvironments(): void {
    for (const call of rec.calls) {
        expect(call.cmd).toBe('ssh-keygen');
        expect(call.options.env).toStrictEqual(buildChildEnv(ChildTool.SSH_KEYGEN));
        expectBaseList(call.options.env);
    }
}

function expectGpgEnvironments(): void {
    expect(rec.calls.map((c) => c.cmd)).toStrictEqual(['gpg', 'gpg']);
    const gnupgHome = rec.calls[0].options.env.GNUPGHOME;
    expect(path.basename(gnupgHome)).toBe('gnupg');
    expect(path.dirname(rec.calls[0].args[2])).toBe(path.dirname(gnupgHome));
    for (const call of rec.calls) {
        expect(call.options.env).toStrictEqual({ ...buildChildEnv(ChildTool.GPG), GNUPGHOME: gnupgHome });
        const { GNUPGHOME: _home, ...rest } = call.options.env;
        expectBaseList(rest);
    }
}

describe('T-1211 the real ssh-keygen and gpg with the environment of their row', () => {
    let savedCanary: string | undefined;

    beforeEach(() => {
        rec.calls.length = 0;
        savedCanary = process.env[CANARY];
        process.env[CANARY] = 'canary-value';
    });

    afterEach(() => {
        if (savedCanary === undefined) delete process.env[CANARY];
        else process.env[CANARY] = savedCanary;
    });

    afterAll(() => {
        rec.cp.spawn = rec.original;
    });

    it('SSH, the signed payload: VERIFIED with the fingerprint of the fixture key', async () => {
        expect(process.env.POSTGRES_PASSWORD).toBeDefined();

        const result = await verifySignature({
            format: 'SSH', signatureB64: b64(fixture('ssh-payload.sig')), payloadB64: b64(fixture('payload.txt')),
            trustStoreB64: b64(fixture('ssh-allowed-signers'))
        });

        expect(result.verdict).toBe('VERIFIED');
        expect(result.matchedFingerprint).toBe(SSH_FINGERPRINT);
        expect(rec.calls.map((c) => c.args[0])).toStrictEqual(['-Y', '-lf']);
        expectSshEnvironments();
    });

    it('SSH, a tampered payload: INVALID_SIGNATURE', async () => {
        const result = await verifySignature({
            format: 'SSH', signatureB64: b64(fixture('ssh-payload.sig')), payloadB64: b64(tampered()),
            trustStoreB64: b64(fixture('ssh-allowed-signers'))
        });

        expect(result.verdict).toBe('INVALID_SIGNATURE');
        expect(rec.calls.map((c) => c.args[0])).toStrictEqual(['-Y']);
        expectSshEnvironments();
    });

    it('GPG, the signed payload: VERIFIED with the key of the fixture', async () => {
        const result = await verifySignature({
            format: 'GPG', signatureB64: b64(fixture('gpg-payload.asc')), payloadB64: b64(fixture('payload.txt')),
            trustStoreB64: b64(fixture('gpg-pubkey.asc'))
        });

        expect(result.verdict).toBe('VERIFIED');
        expect(result.matchedFingerprint).toBe(GPG_KEY_ID);
        expectGpgEnvironments();
    });

    it('GPG, a tampered payload: INVALID_SIGNATURE', async () => {
        const result = await verifySignature({
            format: 'GPG', signatureB64: b64(fixture('gpg-payload.asc')), payloadB64: b64(tampered()),
            trustStoreB64: b64(fixture('gpg-pubkey.asc'))
        });

        expect(result.verdict).toBe('INVALID_SIGNATURE');
        expectGpgEnvironments();
    });
});
