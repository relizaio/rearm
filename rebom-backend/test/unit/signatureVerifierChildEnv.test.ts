import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

// childProcess.ts captures spawn at load, so it is patched before any import runs. ssh-keygen and
// gpg get a stand-in child (an event emitter with stdin, stdout and stderr and no process) that
// answers with what the case's responder returns: data on stdout and stderr, then exit and close
// with the same code. Every stand-in spawn is recorded, with whether the GNUPGHOME it was given was
// a directory at that moment.
const fake = vi.hoisted(() => {
    const cp = (process as any).getBuiltinModule('node:child_process');
    const fsMod = (process as any).getBuiltinModule('node:fs');
    const { EventEmitter } = (process as any).getBuiltinModule('node:events');
    const original = cp.spawn;
    const STAND_INS = ['ssh-keygen', 'gpg'];
    type Answer = { stdout?: string, stderr?: string, code: number };
    const state = {
        cp,
        original,
        calls: [] as { cmd: string, args: string[], options: any, stdin: Buffer[], gnupgHomeWasDir: boolean }[],
        respond: (_cmd: string, _args: string[]): Answer => ({ code: 0 })
    };
    cp.spawn = (cmd: string, args: string[], options: any) => {
        if (!STAND_INS.includes(cmd)) return original(cmd, args, options);
        const gnupgHome = options?.env?.GNUPGHOME;
        const call = {
            cmd,
            args: [...args],
            options,
            stdin: [] as Buffer[],
            gnupgHomeWasDir: typeof gnupgHome === 'string' && fsMod.existsSync(gnupgHome) && fsMod.statSync(gnupgHome).isDirectory()
        };
        state.calls.push(call);
        const child: any = new EventEmitter();
        child.stdin = { write: (buf: Buffer) => { call.stdin.push(Buffer.from(buf)); return true; }, end: () => undefined };
        child.stdout = new EventEmitter();
        child.stderr = new EventEmitter();
        child.kill = () => true;
        const answer = state.respond(cmd, call.args);
        setImmediate(() => {
            if (answer.stdout) child.stdout.emit('data', Buffer.from(answer.stdout));
            if (answer.stderr) child.stderr.emit('data', Buffer.from(answer.stderr));
            child.emit('exit', answer.code, null);
            child.emit('close', answer.code, null);
        });
        return child;
    };
    return state;
});
const log = vi.hoisted(() => ({
    info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn(), trace: vi.fn(), fatal: vi.fn()
}));
vi.mock('../../src/logger', () => ({ logger: log }));

import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { verifySignature } from '../../src/services/signatureVerifier';
import { buildChildEnv, CHILD_ENV_PASSTHROUGH_VARIABLE, ChildTool, configureChildEnv } from '../../src/childEnv';

/**
 * The four children of the signature verifier get the environment of their tool (TPF-256 design
 * 3.1 and the child (b) table of 3.6; row T-1210): ssh-keygen the base list, gpg the base list and
 * the GNUPGHOME made for the one call. rebom's environment is the made-up parent of design section
 * 4, planted in process.env, so whatever of it reaches a child is seen with its value. The expected
 * environments are written out here, not read from the module.
 */

const CANARY = 'CHILD_ENV_CANARY';
const PROXIES = ['HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY', 'http_proxy', 'https_proxy', 'all_proxy', 'no_proxy'];
const SSH_PAYLOAD = Buffer.from('payload signed over ssh\n');
const ALLOWED_SIGNERS = 'dev@example.invalid ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIStandInKeyMaterialOnly\n';
const STAND_IN_FINGERPRINT = 'SHA256:standInFingerprintOfTheAllowedKey';
const GPG_KEY_ID = '0123456789ABCDEF';

const b64 = (text: string | Buffer) => Buffer.from(text).toString('base64');

let tmpRoot = '';
let parentPath = '';
let savedEnv: NodeJS.ProcessEnv = {};

/** The parent of design section 4: each name with a value no other name has. */
function plantParent(): void {
    const parent: Record<string, string> = {
        PATH: parentPath,
        LANG: 'parent-LANG',
        LC_ALL: 'parent-LC_ALL',
        [CANARY]: 'canary-value',
        POSTGRES_PASSWORD: 'parent-POSTGRES_PASSWORD',
        ENCRYPTION_PASSWORD: 'parent-ENCRYPTION_PASSWORD',
        ENCRYPTION_SALT: 'parent-ENCRYPTION_SALT',
        ENCRYPTION_OLD_PASSWORD: 'parent-ENCRYPTION_OLD_PASSWORD',
        ENCRYPTION_OLD_SALT: 'parent-ENCRYPTION_OLD_SALT',
        REBOM_TEA_CALLER_TOKEN: 'parent-REBOM_TEA_CALLER_TOKEN',
        REARM_BEARAPIKEY: 'parent-REARM_BEARAPIKEY',
        REARM_DEBUG: 'parent-REARM_DEBUG',
        HOME: '/parent-HOME',
        // A real directory: the verifier makes its per-call directories under os.tmpdir().
        TMPDIR: tmpRoot,
        GNUPGHOME: '/parent-GNUPGHOME',
        NODE_OPTIONS: '--parent-NODE_OPTIONS',
        SSL_CERT_FILE: '/parent-SSL_CERT_FILE',
        SSL_CERT_DIR: '/parent-SSL_CERT_DIR',
        ...Object.fromEntries(PROXIES.map((name) => [name, `http://parent-${name}.invalid:3128`]))
    };
    Object.assign(process.env, parent);
}

const BASE_FROM_PARENT = () => ({ PATH: parentPath, LANG: 'parent-LANG', LC_ALL: 'parent-LC_ALL' });

function expectNoParentSecret(env: NodeJS.ProcessEnv): void {
    for (const name of [CANARY, 'POSTGRES_PASSWORD', 'ENCRYPTION_PASSWORD', 'REBOM_TEA_CALLER_TOKEN', 'REARM_BEARAPIKEY', 'NODE_OPTIONS', 'HOME', 'TMPDIR']) {
        expect(Object.prototype.hasOwnProperty.call(env, name)).toBe(false);
    }
}

function isInside(dir: string, prefix: string): boolean {
    return path.dirname(dir) === tmpRoot && path.basename(dir).startsWith(prefix);
}

describe('T-1210 the signature verifier children, spawn recorded, canary planted', () => {
    beforeAll(() => {
        tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'tpf268-verifier-'));
        parentPath = process.env.PATH ?? '/usr/bin:/bin';
    });

    beforeEach(() => {
        fake.calls.length = 0;
        savedEnv = { ...process.env };
        plantParent();
    });

    afterEach(() => {
        configureChildEnv({});
        for (const name of Object.keys(process.env)) {
            if (!(name in savedEnv)) delete process.env[name];
        }
        Object.assign(process.env, savedEnv);
        fake.respond = () => ({ code: 0 });
    });

    afterAll(() => {
        fake.cp.spawn = fake.original;
        fs.rmSync(tmpRoot, { recursive: true, force: true });
    });

    it('ssh-keygen -Y verify and -lf: commands, arguments and options as before, each environment exactly the base list', async () => {
        fake.respond = (_cmd, args) => (args[0] === '-lf'
            ? { stdout: `256 ${STAND_IN_FINGERPRINT} dev (ED25519)\n`, code: 0 }
            : { stdout: 'Good "git" signature for dev@example.invalid\n', code: 0 });

        const result = await verifySignature({
            format: 'SSH', signatureB64: b64('stand-in signature'), payloadB64: b64(SSH_PAYLOAD), trustStoreB64: b64(ALLOWED_SIGNERS)
        });

        expect(result.verdict).toBe('VERIFIED');
        expect(result.matchedFingerprint).toBe(STAND_IN_FINGERPRINT);
        expect(fake.calls.map((c) => c.cmd)).toStrictEqual(['ssh-keygen', 'ssh-keygen']);

        const [verify, fingerprint] = fake.calls;
        const verifyDir = path.dirname(verify.args[3]);
        expect(isInside(verifyDir, 'verify-ssh-')).toBe(true);
        expect(verify.args).toStrictEqual([
            '-Y', 'verify',
            '-f', path.join(verifyDir, 'allowed_signers'),
            '-I', 'dev@example.invalid',
            '-n', 'git',
            '-s', path.join(verifyDir, 'signature.sig')
        ]);
        expect(Object.keys(verify.options).sort()).toStrictEqual(['env', 'stdio']);
        expect(verify.options.stdio).toStrictEqual(['pipe', 'pipe', 'pipe']);
        expect(verify.options.env).toStrictEqual(BASE_FROM_PARENT());
        expect(Buffer.concat(verify.stdin).equals(SSH_PAYLOAD)).toBe(true);

        const keyDir = path.dirname(fingerprint.args[1]);
        expect(isInside(keyDir, 'sshfp-')).toBe(true);
        expect(fingerprint.args).toStrictEqual(['-lf', path.join(keyDir, 'k.pub')]);
        expect(Object.keys(fingerprint.options)).toStrictEqual(['env']);
        expect(fingerprint.options.env).toStrictEqual(BASE_FROM_PARENT());

        for (const call of fake.calls) {
            expect(call.options.env).toStrictEqual(buildChildEnv(ChildTool.SSH_KEYGEN));
            expect(call.options.env).not.toBe(process.env);
            expectNoParentSecret(call.options.env);
            expect(Object.prototype.hasOwnProperty.call(call.options.env, 'GNUPGHOME')).toBe(false);
        }
    });

    it('gpg --import and --verify: the base list plus the GNUPGHOME of the call, the same for both, a new one per verification', async () => {
        fake.respond = (_cmd, args) => (args.includes('--verify')
            ? { stdout: `[GNUPG:] NEWSIG\n[GNUPG:] GOODSIG ${GPG_KEY_ID} Stand In <dev@example.invalid>\n`, code: 0 }
            : { stderr: 'gpg: key imported\n', code: 0 });
        const input = {
            format: 'GPG' as const, signatureB64: b64('stand-in signature'), payloadB64: b64('payload'), trustStoreB64: b64('stand-in armoured key')
        };

        const first = await verifySignature(input);
        const second = await verifySignature(input);

        expect(first).toMatchObject({ verdict: 'VERIFIED', matchedFingerprint: GPG_KEY_ID });
        expect(second).toMatchObject({ verdict: 'VERIFIED', matchedFingerprint: GPG_KEY_ID });
        expect(fake.calls.map((c) => c.cmd)).toStrictEqual(['gpg', 'gpg', 'gpg', 'gpg']);

        const homes: string[] = [];
        for (const [importCall, verifyCall] of [[fake.calls[0], fake.calls[1]], [fake.calls[2], fake.calls[3]]]) {
            const gnupgHome = importCall.options.env.GNUPGHOME;
            const callDir = path.dirname(gnupgHome);
            expect(path.basename(gnupgHome)).toBe('gnupg');
            expect(isInside(callDir, 'verify-gpg-')).toBe(true);
            expect(importCall.args).toStrictEqual(['--batch', '--import', path.join(callDir, 'pubkeys.asc')]);
            expect(verifyCall.args).toStrictEqual([
                '--batch', '--status-fd', '1', '--verify', path.join(callDir, 'signature.sig'), path.join(callDir, 'payload.bin')
            ]);
            for (const call of [importCall, verifyCall]) {
                expect(Object.keys(call.options)).toStrictEqual(['env']);
                expect(call.options.env).toStrictEqual({ ...BASE_FROM_PARENT(), GNUPGHOME: gnupgHome });
                expect(call.options.env).toStrictEqual({ ...buildChildEnv(ChildTool.GPG), GNUPGHOME: gnupgHome });
                expect(call.gnupgHomeWasDir).toBe(true);
                expectNoParentSecret(call.options.env);
            }
            expect(verifyCall.options.env.GNUPGHOME).toBe(gnupgHome);
            expect(gnupgHome).not.toBe('/parent-GNUPGHOME');
            // The directory of the call is removed after it, as before.
            expect(fs.existsSync(callDir)).toBe(false);
            homes.push(gnupgHome);
        }
        expect(homes[0]).not.toBe(homes[1]);
    });

    it('names accepted in the hatch reach the verifier children like every other tool; a refused name does not', async () => {
        process.env.TZ = 'parent-TZ';
        process.env.GODEBUG = 'parent-GODEBUG';
        configureChildEnv({ [CHILD_ENV_PASSTHROUGH_VARIABLE]: 'TZ,GODEBUG,POSTGRES_PASSWORD,GNUPGHOME' });
        fake.respond = (cmd, args) => {
            if (cmd === 'ssh-keygen') {
                return args[0] === '-lf' ? { stdout: `256 ${STAND_IN_FINGERPRINT} dev (ED25519)\n`, code: 0 } : { code: 0 };
            }
            return args.includes('--verify') ? { stdout: `[GNUPG:] GOODSIG ${GPG_KEY_ID} x\n`, code: 0 } : { code: 0 };
        };

        const ssh = await verifySignature({
            format: 'SSH', signatureB64: b64('sig'), payloadB64: b64(SSH_PAYLOAD), trustStoreB64: b64(ALLOWED_SIGNERS)
        });
        const gpg = await verifySignature({
            format: 'GPG', signatureB64: b64('sig'), payloadB64: b64('payload'), trustStoreB64: b64('key')
        });

        expect(ssh.verdict).toBe('VERIFIED');
        expect(gpg.verdict).toBe('VERIFIED');
        expect(fake.calls.map((c) => c.cmd)).toStrictEqual(['ssh-keygen', 'ssh-keygen', 'gpg', 'gpg']);
        const extras = { TZ: 'parent-TZ', GODEBUG: 'parent-GODEBUG' };
        expect(fake.calls[0].options.env).toStrictEqual({ ...BASE_FROM_PARENT(), ...extras });
        expect(fake.calls[1].options.env).toStrictEqual({ ...BASE_FROM_PARENT(), ...extras });
        const gnupgHome = fake.calls[2].options.env.GNUPGHOME;
        expect(isInside(path.dirname(gnupgHome), 'verify-gpg-')).toBe(true);
        expect(fake.calls[2].options.env).toStrictEqual({ ...BASE_FROM_PARENT(), ...extras, GNUPGHOME: gnupgHome });
        expect(fake.calls[3].options.env).toStrictEqual({ ...BASE_FROM_PARENT(), ...extras, GNUPGHOME: gnupgHome });
        for (const call of fake.calls) expectNoParentSecret(call.options.env);
    });
});
