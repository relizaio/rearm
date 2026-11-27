import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const log = vi.hoisted(() => ({
    info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn(), trace: vi.fn(), fatal: vi.fn()
}));
vi.mock('../../src/logger', () => ({ logger: log }));

import {
    CHILD_ENV_PASSTHROUGH_LINE, CHILD_ENV_PASSTHROUGH_VARIABLE, configureChildEnv, ENRICH_WITHHELD_NAMES,
    ENRICH_WITHHELD_PREFIXES, isWithheldFromEnrichment, parseChildEnvPassthrough
} from '../../src/childEnv';

/**
 * The escape hatch REBOM_CHILD_ENV_PASSTHROUGH (TPF-256 design 3.3 and 3.10; rows T-1192, T-1193,
 * T-1195 and the predicate of T-1234).
 */

// every name of the table of design 3.1
const MANAGED = [
    'PATH', 'LANG', 'LC_ALL', 'HOME', 'TMPDIR', 'GNUPGHOME', 'SSL_CERT_FILE', 'SSL_CERT_DIR',
    'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY', 'http_proxy', 'https_proxy', 'all_proxy', 'no_proxy',
    'DOTNET_SYSTEM_GLOBALIZATION_INVARIANT', 'DOTNET_BUNDLE_EXTRACT_BASE_DIR', 'REARM_RESILIENTDNS'
];

afterEach(() => {
    configureChildEnv({});
});

describe('T-1192 parsing the list', () => {
    it('undefined and an empty string give an empty result', () => {
        const empty = { accepted: [], refused: [], malformed: 0, withheldFromEnrichment: [] };
        expect(parseChildEnvPassthrough(undefined)).toStrictEqual(empty);
        expect(parseChildEnvPassthrough('')).toStrictEqual(empty);
        expect(parseChildEnvPassthrough(' , ,')).toStrictEqual(empty);
    });

    it('trimmed, empty pieces dropped, first-seen order without repeats', () => {
        const result = parseChildEnvPassthrough('TZ, GODEBUG,,TZ,');
        expect(result.accepted).toStrictEqual(['TZ', 'GODEBUG']);
        expect(result.refused).toStrictEqual([]);
        expect(result.malformed).toBe(0);
    });

    it('malformed pieces are counted and never kept or echoed; a 128-character name is accepted', () => {
        const long128 = 'A' + 'B'.repeat(127);
        const long129 = 'C' + 'D'.repeat(128);
        expect(long128).toHaveLength(128);
        expect(long129).toHaveLength(129);
        const result = parseChildEnvPassthrough(`FOO=bar,A B,1A,${long129},${long128},*`);
        expect(result.accepted).toStrictEqual([long128]);
        expect(result.refused).toStrictEqual([]);
        expect(result.malformed).toBe(5);
        const text = JSON.stringify(result);
        for (const fragment of ['FOO', 'bar', 'A B', '1A', long129, '*']) {
            expect(text).not.toContain(fragment);
        }
    });

    it('repeats of a refused name are reported once', () => {
        expect(parseChildEnvPassthrough('API_KEY,API_KEY').refused).toStrictEqual(['API_KEY']);
    });
});

describe('T-1193 the refused names', () => {
    const REFUSED = [
        'POSTGRES_PASSWORD', 'postgres_host', 'ENCRYPTION_OLD_SALT', 'REBOM_TEA_CALLER_TOKEN', 'REARM_DEBUG', 'REARM_TEA_TOKEN',
        'MY_Password', 'DB_PWD', 'API_KEY', 'SSLKEYLOGFILE', 'SSH_AUTH_SOCK', 'LD_PRELOAD', 'CORECLR_PROFILER', 'NODE_OPTIONS',
        'DOTNET_STARTUP_HOOKS', 'REARM_BEARAPIKEY', 'REARM_APIKEY', 'MY_SECRET', 'GH_TOKEN', 'SOME_SALT', 'A_CREDENTIAL',
        ...MANAGED
    ];
    const ACCEPTED = ['TZ', 'GODEBUG', 'GOMAXPROCS', 'LC_CTYPE', 'DOTNET_GCHeapHardLimit'];

    it('each name of the first group is refused, in upper and in lower case; the second group is accepted', () => {
        for (const name of REFUSED) {
            for (const spelled of [name.toUpperCase(), name.toLowerCase()]) {
                const result = parseChildEnvPassthrough(spelled);
                expect(result.refused, spelled).toStrictEqual([spelled]);
                expect(result.accepted, spelled).toStrictEqual([]);
            }
        }
        const result = parseChildEnvPassthrough([...REFUSED, ...ACCEPTED].join(','));
        expect(result.accepted).toStrictEqual(ACCEPTED);
        expect(result.refused).toStrictEqual(REFUSED);
        expect(result.malformed).toBe(0);
    });

    it('one rule at a time: a prefix is a prefix, a part is anywhere in the name', () => {
        expect(parseChildEnvPassthrough('MY_POSTGRES_X').accepted).toStrictEqual(['MY_POSTGRES_X']);
        expect(parseChildEnvPassthrough('MY_REARM_X').accepted).toStrictEqual(['MY_REARM_X']);
        expect(parseChildEnvPassthrough('MY_LD_X').accepted).toStrictEqual(['MY_LD_X']);
        expect(parseChildEnvPassthrough('NODE_OPTIONS_X').accepted).toStrictEqual(['NODE_OPTIONS_X']);
        expect(parseChildEnvPassthrough('XAUTHORITY').refused).toStrictEqual(['XAUTHORITY']);
        expect(parseChildEnvPassthrough('PATHEXT').accepted).toStrictEqual(['PATHEXT']);
    });
});

describe('T-1195 the one start-up line', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    function lines() {
        return Object.entries(log).flatMap(([level, fn]) => fn.mock.calls.map((args) => ({ level, args })));
    }

    it('nothing set: one info line with three empty arrays and 0', () => {
        const result = configureChildEnv({ PATH: '/x', POSTGRES_PASSWORD: 'pg-value' });
        expect(result).toStrictEqual({ accepted: [], refused: [], malformed: 0, withheldFromEnrichment: [] });
        expect(lines()).toStrictEqual([{
            level: 'info',
            args: [{ accepted: [], refused: [], malformed: 0, withheldFromEnrichment: [] }, CHILD_ENV_PASSTHROUGH_LINE]
        }]);
        expect(CHILD_ENV_PASSTHROUGH_LINE).toBe('child environment passthrough');
        expect(CHILD_ENV_PASSTHROUGH_VARIABLE).toBe('REBOM_CHILD_ENV_PASSTHROUGH');
    });

    it('accepted names only: info, GODEBUG withheld from enrichment, no value logged', () => {
        configureChildEnv({ [CHILD_ENV_PASSTHROUGH_VARIABLE]: 'TZ,GODEBUG', TZ: 'tz-value', GODEBUG: 'godebug-value' });
        expect(lines()).toStrictEqual([{
            level: 'info',
            args: [{ accepted: ['TZ', 'GODEBUG'], refused: [], malformed: 0, withheldFromEnrichment: ['GODEBUG'] }, CHILD_ENV_PASSTHROUGH_LINE]
        }]);
        const text = JSON.stringify(lines());
        expect(text).not.toContain('tz-value');
        expect(text).not.toContain('godebug-value');
    });

    it('a refused name and a malformed entry: warn, names and the count only', () => {
        const env = {
            [CHILD_ENV_PASSTHROUGH_VARIABLE]: 'TZ,POSTGRES_PASSWORD,SOME=value-text',
            TZ: 'tz-value', POSTGRES_PASSWORD: 'pg-value'
        };
        let result: unknown;
        expect(() => { result = configureChildEnv(env); }).not.toThrow();
        expect(result).toStrictEqual({ accepted: ['TZ'], refused: ['POSTGRES_PASSWORD'], malformed: 1, withheldFromEnrichment: [] });
        expect(lines()).toStrictEqual([{
            level: 'warn',
            args: [{ accepted: ['TZ'], refused: ['POSTGRES_PASSWORD'], malformed: 1, withheldFromEnrichment: [] }, CHILD_ENV_PASSTHROUGH_LINE]
        }]);
        const text = JSON.stringify(lines());
        for (const value of ['value-text', 'SOME', 'tz-value', 'pg-value']) expect(text).not.toContain(value);
    });

    it('a malformed entry alone is a warning too', () => {
        configureChildEnv({ [CHILD_ENV_PASSTHROUGH_VARIABLE]: 'TZ,=x' });
        expect(lines().map((l) => l.level)).toStrictEqual(['warn']);
    });

    it('a logger that throws does not stop the start, and the list still applies', () => {
        log.info.mockImplementationOnce(() => { throw new Error('logger down'); });
        let result: unknown;
        expect(() => { result = configureChildEnv({ [CHILD_ENV_PASSTHROUGH_VARIABLE]: 'TZ' }); }).not.toThrow();
        expect(result).toStrictEqual({ accepted: ['TZ'], refused: [], malformed: 0, withheldFromEnrichment: [] });
    });

    it('the default environment is process.env', () => {
        const saved = process.env[CHILD_ENV_PASSTHROUGH_VARIABLE];
        try {
            process.env[CHILD_ENV_PASSTHROUGH_VARIABLE] = 'TZ';
            expect(configureChildEnv().accepted).toStrictEqual(['TZ']);
        } finally {
            if (saved === undefined) delete process.env[CHILD_ENV_PASSTHROUGH_VARIABLE];
            else process.env[CHILD_ENV_PASSTHROUGH_VARIABLE] = saved;
        }
    });
});

describe('T-1234 the names the enrichment run never takes', () => {
    it('the two constants, name by name', () => {
        expect(ENRICH_WITHHELD_PREFIXES).toStrictEqual(['GO']);
        expect(ENRICH_WITHHELD_NAMES).toStrictEqual(['DEBUG_HTTP2_GOROUTINES', 'SSLKEYLOGFILE']);
    });

    it('the predicate: a prefix on the upper-cased name or one of the two names, not a substring', () => {
        for (const name of ['GODEBUG', 'godebug', 'GOTRACEBACK', 'GOGC', 'GOMEMLIMIT', 'GOMAXPROCS', 'GOROOT', 'GOCOVERDIR',
            'GORACE', 'DEBUG_HTTP2_GOROUTINES', 'debug_http2_goroutines', 'SSLKEYLOGFILE']) {
            expect(isWithheldFromEnrichment(name), name).toBe(true);
        }
        for (const name of ['TZ', 'LC_CTYPE', 'XDG_CONFIG_HOME', 'DOTNET_GCHeapHardLimit', 'MY_GODEBUG',
            'DEBUG_HTTP2_GOROUTINES_X', 'X_SSLKEYLOGFILE']) {
            expect(isWithheldFromEnrichment(name), name).toBe(false);
        }
    });

    it('withheld names stay accepted and are listed in the order of accepted', () => {
        const result = parseChildEnvPassthrough('TZ,GOTRACEBACK,LC_CTYPE,godebug,DEBUG_HTTP2_GOROUTINES');
        expect(result.accepted).toStrictEqual(['TZ', 'GOTRACEBACK', 'LC_CTYPE', 'godebug', 'DEBUG_HTTP2_GOROUTINES']);
        expect(result.withheldFromEnrichment).toStrictEqual(['GOTRACEBACK', 'godebug', 'DEBUG_HTTP2_GOROUTINES']);
        // SSLKEYLOGFILE is refused for every tool by the KEY pattern before the enrichment rule
        const ssl = parseChildEnvPassthrough('SSLKEYLOGFILE');
        expect(ssl.refused).toStrictEqual(['SSLKEYLOGFILE']);
        expect(ssl.withheldFromEnrichment).toStrictEqual([]);
    });
});
