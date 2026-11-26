import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';
import { runningTeaGroups } from '../../src/services/tea/teaCliRunner';
import {
    API_ROOT, cleanupDirs, configure, field, filesUnder, gql, Http, MARKERS, MARK_KEY, MARK_PG, MARK_TOKEN, Q, sleep, startHttp,
    Stub, TOKEN, UUID_A, workDirOf
} from './teaTestKit';
import { SAMPLE, TeaFixtureServer } from './teaFixtureServer';

/**
 * The environment of the child and the credential (TPF-180 design 3.5; rows T-22, T-23, T-25).
 * T-24 is asserted in every row that runs with marker credentials (teaOutcomes, teaReads, teaFetch).
 */

const BEARER = { kind: 'BEARER_TOKEN', authRoot: API_ROOT, token: MARK_TOKEN };
const API_KEY = { kind: 'API_KEY', authRoot: API_ROOT, keyId: MARK_KEY, keySecret: `${MARK_KEY}-secret` };

describe('TEA child environment and credential', () => {
    let http: Http;
    let stub: Stub | undefined;
    const saved: Record<string, string | undefined> = {};
    const planted: Record<string, string> = {
        POSTGRES_PASSWORD: MARK_PG,
        ENCRYPTION_PASSWORD: `${MARK_PG}-enc`,
        REBOM_TEA_CALLER_TOKEN: TOKEN,
        HTTPS_PROXY: 'http://proxy.example.com:3128',
        REARM_APIKEY: `${MARK_PG}-rearm`
    };

    beforeAll(async () => {
        http = await startHttp();
    });

    afterAll(async () => {
        await http.server.stop();
    });

    afterEach(() => {
        stub?.restore();
        stub = undefined;
        for (const [k, v] of Object.entries(saved)) {
            if (v === undefined) delete process.env[k];
            else process.env[k] = v;
        }
        cleanupDirs();
    });

    function plant(): void {
        for (const [k, v] of Object.entries(planted)) {
            saved[k] = process.env[k];
            process.env[k] = v;
        }
    }

    it('T-22 the child gets exactly the allow-list for each credential kind, nothing of rebom\'s own', async () => {
        plant();
        stub = new Stub();
        stub.behave({ stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT"}\n' });
        const root = configure();
        const work = workDirOf(root);
        const base = ['PATH', 'HOME', 'TMPDIR'];
        if (process.env.SSL_CERT_FILE !== undefined) base.push('SSL_CERT_FILE');
        if (process.env.SSL_CERT_DIR !== undefined) base.push('SSL_CERT_DIR');
        const kinds: Array<[unknown, string[]]> = [
            [{ kind: 'NONE' }, []],
            [BEARER, ['REARM_TEA_AUTH_ROOT', 'REARM_TEA_TOKEN']],
            [API_KEY, ['REARM_TEA_AUTH_ROOT', 'REARM_TEA_APIKEYID', 'REARM_TEA_APIKEY']]
        ];
        for (const [credential, extra] of kinds) {
            stub.clearRuns();
            expect(field(await gql(http.url, Q.product, { input: { apiRoot: API_ROOT, uuid: UUID_A, credential } })).outcome).toBe('OK');
            const env = stub.env();
            // the shell may add PWD, SHLVL and _ of its own
            const names = Object.keys(env).filter((n) => !['PWD', 'SHLVL', '_', 'OLDPWD'].includes(n)).sort();
            expect(names, JSON.stringify(credential)).toEqual([...base, ...extra].sort());
            const text = JSON.stringify(env);
            for (const value of Object.values(planted)) expect(text).not.toContain(value);
            // the per-call directory, already removed
            expect(env.HOME.startsWith(`${work}${path.sep}f-`)).toBe(true);
            expect(env.TMPDIR).toBe(env.HOME);
        }
    });

    it('T-23 real: the credential is in the child\'s environment and not in its command line; the fixture receives it', async () => {
        if (process.platform !== 'linux') return;
        const fixture = new TeaFixtureServer();
        await fixture.start();
        try {
            configure({ REBOM_TEA_LOCAL_TESTING: 'ON' });
            const product = fixture.productJson();
            const path_ = fixture.api(`/product/${SAMPLE.product}`);
            for (const credential of [
                { kind: 'BEARER_TOKEN', authRoot: fixture.root, token: MARK_TOKEN },
                { kind: 'API_KEY', authRoot: fixture.root, keyId: MARK_KEY, keySecret: `${MARK_KEY}-secret` }
            ]) {
                fixture.reset();
                fixture.apiKey = credential.kind === 'API_KEY' ? { id: MARK_KEY, secret: `${MARK_KEY}-secret`, token: `${MARK_TOKEN}-issued` } : null;
                fixture.requireBearer = credential.kind === 'BEARER_TOKEN' ? MARK_TOKEN : null;
                const release = fixture.hold(path_, product);
                const call = gql(http.url, Q.product, { input: { apiRoot: fixture.root, uuid: SAMPLE.product, credential } });
                for (let i = 0; i < 200 && fixture.count(path_) === 0; i++) await sleep(25);
                const pid = runningTeaGroups()[0];
                expect(pid, credential.kind).toBeDefined();
                const cmdline = fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8');
                const environ = fs.readFileSync(`/proc/${pid}/environ`, 'utf8');
                for (const marker of MARKERS) expect(cmdline).not.toContain(marker);
                expect(environ).toContain(credential.kind === 'API_KEY' ? MARK_KEY : MARK_TOKEN);
                release();
                const result = field(await call);
                expect(result.outcome, credential.kind).toBe('OK');
                const seen = fixture.requests.filter((r) => r.path === path_);
                expect(seen.length).toBeGreaterThan(0);
                expect(seen[seen.length - 1].authorization).toBe(credential.kind === 'API_KEY' ? `Bearer ${MARK_TOKEN}-issued` : `Bearer ${MARK_TOKEN}`);
            }
        } finally {
            await fixture.stop();
        }
    });

    it('T-25 no file under the scratch root holds a credential, while the run sleeps and after', async () => {
        stub = new Stub();
        stub.behave({ sleep: 2, stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT"}\n' });
        const root = configure();
        const call = gql(http.url, Q.product, { input: { apiRoot: API_ROOT, uuid: UUID_A, credential: API_KEY } });
        for (let i = 0; i < 100 && stub.runs().length === 0; i++) await sleep(20);
        const scan = () => {
            for (const file of filesUnder(root)) {
                const content = fs.readFileSync(file, 'utf8');
                for (const marker of MARKERS) expect(content, file).not.toContain(marker);
            }
        };
        expect(filesUnder(root).length + fs.readdirSync(workDirOf(root)).length).toBeGreaterThan(0);
        scan();
        expect(field(await call).outcome).toBe('OK');
        scan();
        expect(Object.keys(process.env).filter((name) => name.startsWith('REARM_TEA_'))).toEqual([]);
    });
});
