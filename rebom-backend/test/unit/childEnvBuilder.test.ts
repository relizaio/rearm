import { afterEach, describe, expect, it, vi } from 'vitest';

const log = vi.hoisted(() => ({
    info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn(), trace: vi.fn(), fatal: vi.fn()
}));
vi.mock('../../src/logger', () => ({ logger: log }));

import {
    buildChildEnv, CHILD_ENV_PASSTHROUGH_VARIABLE, CHILD_HOME_NONE, ChildTool, configureChildEnv, DEFAULT_CHILD_PATH
} from '../../src/childEnv';
import { buildEnrichmentChildEnv } from '../../src/services/bom/enrichmentChildEnv';
import { buildTeaChildEnv } from '../../src/services/tea/teaChildEnv';
import { proxyVariableNames } from '../../src/services/tea/teaOutcome';
import { TeaCredential, TeaCredentialKind } from '../../src/services/tea/teaRequest';

/**
 * The allow-list builder of every child of rebom (TPF-256 design 3.1 and 3.10; rows T-1190, T-1191,
 * T-1194 and the environments of T-1234).
 */

const TEA_CREDENTIAL: TeaCredential = { kind: TeaCredentialKind.BEARER_TOKEN, authRoot: 'https://tea.invalid', token: 't', keyId: null, keySecret: null };

const PROXY_NAMES = ['HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY', 'http_proxy', 'https_proxy', 'all_proxy', 'no_proxy'];

// A made-up parent: every name with a value no other name has.
function parent(): NodeJS.ProcessEnv {
    const p: NodeJS.ProcessEnv = {
        PATH: '/parent/bin', LANG: 'parent-lang', LC_ALL: 'parent-lc-all',
        CHILD_ENV_CANARY: 'canary-value',
        POSTGRES_PASSWORD: 'pg-password-value',
        ENCRYPTION_PASSWORD: 'enc-password-value', ENCRYPTION_SALT: 'enc-salt-value',
        ENCRYPTION_OLD_PASSWORD: 'enc-old-password-value', ENCRYPTION_OLD_SALT: 'enc-old-salt-value',
        REBOM_TEA_CALLER_TOKEN: 'tea-caller-token-value',
        REARM_BEARAPIKEY: 'bear-key-value', REARM_DEBUG: 'rearm-debug-value', REARM_RESILIENTDNS: 'resilient-value',
        HOME: '/parent/home', TMPDIR: '/parent/tmp', GNUPGHOME: '/parent/gnupg',
        NODE_OPTIONS: 'node-options-value',
        SSL_CERT_FILE: '/parent/cert-file', SSL_CERT_DIR: '/parent/cert-dir',
        DOTNET_SYSTEM_GLOBALIZATION_INVARIANT: 'dotnet-invariant-value', DOTNET_BUNDLE_EXTRACT_BASE_DIR: '/parent/dotnet-extract'
    };
    for (const name of PROXY_NAMES) p[name] = `proxy-value-${name}`;
    return p;
}

const BASE = ['LANG', 'LC_ALL', 'PATH'];
const ROWS: Record<ChildTool, string[]> = {
    [ChildTool.BASE]: BASE,
    [ChildTool.REARM_BOM]: [...BASE, 'HOME'],
    [ChildTool.REARM_ENRICH]: [...BASE, 'HOME', 'SSL_CERT_FILE', 'SSL_CERT_DIR', ...PROXY_NAMES, 'REARM_RESILIENTDNS'],
    [ChildTool.CYCLONEDX_CLI]: [...BASE, 'DOTNET_SYSTEM_GLOBALIZATION_INVARIANT', 'DOTNET_BUNDLE_EXTRACT_BASE_DIR', 'HOME', 'TMPDIR'],
    [ChildTool.SSH_KEYGEN]: BASE,
    [ChildTool.GPG]: BASE
};

const sorted = (names: string[]) => [...names].sort();

afterEach(() => {
    // the list of extras is module state: back to the state before any configure call
    configureChildEnv({});
});

describe('T-1190 the base list', () => {
    it('exactly PATH, LANG, LC_ALL with the parent\'s values; a new object each call; the parent unchanged', () => {
        const p = parent();
        const env = buildChildEnv(ChildTool.BASE, p);
        expect(env).toStrictEqual({ PATH: '/parent/bin', LANG: 'parent-lang', LC_ALL: 'parent-lc-all' });
        const again = buildChildEnv(ChildTool.BASE, p);
        expect(again).toStrictEqual(env);
        expect(again).not.toBe(env);
        expect(env).not.toBe(p);
        expect(p).toStrictEqual(parent());
    });

    it('a parent without LANG: the name is absent, not undefined', () => {
        const p = parent();
        delete p.LANG;
        const env = buildChildEnv(ChildTool.BASE, p);
        expect(env).toStrictEqual({ PATH: '/parent/bin', LC_ALL: 'parent-lc-all' });
        expect(Object.prototype.hasOwnProperty.call(env, 'LANG')).toBe(false);
    });

    it('a parent without PATH: PATH is DEFAULT_CHILD_PATH, the value of the image', () => {
        const p = parent();
        delete p.PATH;
        expect(DEFAULT_CHILD_PATH).toBe('/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin');
        for (const tool of Object.values(ChildTool)) {
            expect(buildChildEnv(tool, p).PATH).toBe(DEFAULT_CHILD_PATH);
        }
        expect(p.PATH).toBeUndefined();
    });

    it('an empty parent: PATH alone', () => {
        expect(buildChildEnv(ChildTool.BASE, {})).toStrictEqual({ PATH: DEFAULT_CHILD_PATH });
    });

    it('the default parent is process.env, read at every call', () => {
        const saved = process.env.LANG;
        try {
            process.env.LANG = 'first-lang';
            expect(buildChildEnv(ChildTool.BASE).LANG).toBe('first-lang');
            process.env.LANG = 'second-lang';
            expect(buildChildEnv(ChildTool.BASE).LANG).toBe('second-lang');
        } finally {
            if (saved === undefined) delete process.env.LANG;
            else process.env.LANG = saved;
        }
    });
});

describe('T-1191 the row of each tool', () => {
    it('the names are exactly those of the tool\'s row; no key name, no GNUPGHOME, nothing of rebom', () => {
        const p = parent();
        for (const tool of Object.values(ChildTool)) {
            const env = buildChildEnv(tool, p);
            expect(sorted(Object.keys(env)), tool).toStrictEqual(sorted(ROWS[tool]));
            expect(env.REARM_BEARAPIKEY, tool).toBeUndefined();
            expect(env.GNUPGHOME, tool).toBeUndefined();
            for (const name of Object.keys(env)) {
                if (name !== 'HOME') expect(env[name], `${tool} ${name}`).toBe(p[name]);
            }
            for (const value of Object.values(env)) {
                expect(value).not.toBe('canary-value');
                expect(value).not.toBe('pg-password-value');
            }
        }
        expect(p).toStrictEqual(parent());
    });

    it('HOME: CHILD_HOME_NONE for the two rearm tools, the parent\'s for cyclonedx-cli, absent for the others', () => {
        const p = parent();
        expect(CHILD_HOME_NONE).toBe('/nonexistent');
        expect(buildChildEnv(ChildTool.REARM_BOM, p).HOME).toBe(CHILD_HOME_NONE);
        expect(buildChildEnv(ChildTool.REARM_ENRICH, p).HOME).toBe(CHILD_HOME_NONE);
        expect(buildChildEnv(ChildTool.CYCLONEDX_CLI, p).HOME).toBe('/parent/home');
        for (const tool of [ChildTool.BASE, ChildTool.SSH_KEYGEN, ChildTool.GPG]) {
            expect(Object.prototype.hasOwnProperty.call(buildChildEnv(tool, p), 'HOME')).toBe(false);
        }
        // CHILD_HOME_NONE is set even when the parent has no HOME
        const noHome = parent();
        delete noHome.HOME;
        expect(buildChildEnv(ChildTool.REARM_BOM, noHome).HOME).toBe(CHILD_HOME_NONE);
        expect(Object.prototype.hasOwnProperty.call(buildChildEnv(ChildTool.CYCLONEDX_CLI, noHome), 'HOME')).toBe(false);
    });

    it('proxyVariableNames of teaOutcome.ts finds the eight proxy names in the enrichment environment and none in the others', () => {
        const p = parent();
        expect(sorted(proxyVariableNames(buildChildEnv(ChildTool.REARM_ENRICH, p)))).toStrictEqual(sorted(PROXY_NAMES));
        for (const tool of Object.values(ChildTool).filter((t) => t !== ChildTool.REARM_ENRICH)) {
            expect(proxyVariableNames(buildChildEnv(tool, p)), tool).toStrictEqual([]);
        }
    });
});

describe('T-1194 the accepted extra names reach every tool but the enrichment run takes no Go name', () => {
    const LIST = 'TZ,GODEBUG,GOTRACEBACK,DEBUG_HTTP2_GOROUTINES,HOME,POSTGRES_PASSWORD';
    const withExtras = () => ({
        ...parent(), TZ: 'tz-value', GODEBUG: 'http2debug=1', GOTRACEBACK: 'crash', DEBUG_HTTP2_GOROUTINES: '1'
    });

    it('before any configure call the tools get no extra name', () => {
        const p = withExtras();
        for (const tool of Object.values(ChildTool)) {
            expect(sorted(Object.keys(buildChildEnv(tool, p))), tool).toStrictEqual(sorted(ROWS[tool]));
        }
    });

    it('five tools get TZ and the three Go names with the parent\'s values; REARM_ENRICH gets TZ alone; HOME and POSTGRES_PASSWORD as without a list', () => {
        const p = withExtras();
        const teaBefore = buildTeaChildEnv(TEA_CREDENTIAL, '/call/dir', p);
        configureChildEnv({ [CHILD_ENV_PASSTHROUGH_VARIABLE]: LIST });

        for (const tool of [ChildTool.BASE, ChildTool.REARM_BOM, ChildTool.CYCLONEDX_CLI, ChildTool.SSH_KEYGEN, ChildTool.GPG]) {
            const env = buildChildEnv(tool, p);
            expect(sorted(Object.keys(env)), tool).toStrictEqual(sorted([...ROWS[tool], 'TZ', 'GODEBUG', 'GOTRACEBACK', 'DEBUG_HTTP2_GOROUTINES']));
            expect(env.TZ).toBe('tz-value');
            expect(env.GODEBUG).toBe('http2debug=1');
            expect(env.GOTRACEBACK).toBe('crash');
            expect(env.DEBUG_HTTP2_GOROUTINES).toBe('1');
            expect(env.POSTGRES_PASSWORD).toBeUndefined();
        }
        const enrich = buildChildEnv(ChildTool.REARM_ENRICH, p);
        expect(sorted(Object.keys(enrich))).toStrictEqual(sorted([...ROWS[ChildTool.REARM_ENRICH], 'TZ']));
        expect(enrich.TZ).toBe('tz-value');
        for (const name of ['GODEBUG', 'GOTRACEBACK', 'DEBUG_HTTP2_GOROUTINES', 'POSTGRES_PASSWORD']) {
            expect(Object.prototype.hasOwnProperty.call(enrich, name), name).toBe(false);
        }
        expect(buildChildEnv(ChildTool.REARM_BOM, p).HOME).toBe(CHILD_HOME_NONE);
        expect(enrich.HOME).toBe(CHILD_HOME_NONE);
        expect(buildChildEnv(ChildTool.CYCLONEDX_CLI, p).HOME).toBe('/parent/home');

        const teaAfter = buildTeaChildEnv(TEA_CREDENTIAL, '/call/dir', p);
        expect(teaAfter).toStrictEqual(teaBefore);
    });

    it('an accepted name the parent does not define is not set', () => {
        configureChildEnv({ [CHILD_ENV_PASSTHROUGH_VARIABLE]: 'TZ' });
        const env = buildChildEnv(ChildTool.REARM_BOM, parent());
        expect(Object.prototype.hasOwnProperty.call(env, 'TZ')).toBe(false);
    });

    it('a later configure call replaces the list', () => {
        const p = withExtras();
        configureChildEnv({ [CHILD_ENV_PASSTHROUGH_VARIABLE]: 'TZ' });
        expect(buildChildEnv(ChildTool.BASE, p).TZ).toBe('tz-value');
        configureChildEnv({});
        expect(buildChildEnv(ChildTool.BASE, p).TZ).toBeUndefined();
    });
});

describe('T-1234 the enrichment environments with Go names listed', () => {
    it('both enrichment environments hold TZ and none of the four other names; the second holds the key', () => {
        const p = {
            ...parent(), GODEBUG: 'inittrace=1', GOTRACEBACK: 'crash', GOMEMLIMIT: '1GiB', DEBUG_HTTP2_GOROUTINES: '1', TZ: 'UTC'
        };
        const result = configureChildEnv({ [CHILD_ENV_PASSTHROUGH_VARIABLE]: 'GODEBUG,GOTRACEBACK,GOMEMLIMIT,DEBUG_HTTP2_GOROUTINES,TZ' });
        expect(result.withheldFromEnrichment).toStrictEqual(['GODEBUG', 'GOTRACEBACK', 'GOMEMLIMIT', 'DEBUG_HTTP2_GOROUTINES']);
        expect(result.accepted).toStrictEqual(['GODEBUG', 'GOTRACEBACK', 'GOMEMLIMIT', 'DEBUG_HTTP2_GOROUTINES', 'TZ']);

        const row = buildChildEnv(ChildTool.REARM_ENRICH, p);
        const withKey = buildEnrichmentChildEnv('markerkey', p);
        for (const env of [row, withKey]) {
            expect(env.TZ).toBe('UTC');
            for (const name of ['GODEBUG', 'GOTRACEBACK', 'GOMEMLIMIT', 'DEBUG_HTTP2_GOROUTINES']) {
                expect(Object.prototype.hasOwnProperty.call(env, name), name).toBe(false);
            }
        }
        expect(row.REARM_BEARAPIKEY).toBeUndefined();
        expect(withKey.REARM_BEARAPIKEY).toBe('markerkey');
        const { REARM_BEARAPIKEY: _key, ...rest } = withKey;
        expect(rest).toStrictEqual(row);
        // the merge row gets every one of them
        const merge = buildChildEnv(ChildTool.REARM_BOM, p);
        for (const name of ['GODEBUG', 'GOTRACEBACK', 'GOMEMLIMIT', 'DEBUG_HTTP2_GOROUTINES', 'TZ']) {
            expect(merge[name], name).toBe(p[name as keyof typeof p]);
        }
    });
});
