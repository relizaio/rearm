import { afterEach, describe, expect, it, vi } from 'vitest';

const log = vi.hoisted(() => ({
    info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn(), trace: vi.fn(), fatal: vi.fn()
}));
vi.mock('../../src/logger', () => ({ logger: log }));

import { buildChildEnv, ChildTool, configureChildEnv } from '../../src/childEnv';

/**
 * The rows of the allow-list read against a parent that holds EVERY name (TPF-256 design 3.1, rows
 * T-1190 and T-1191, round 2 of the mutation hop). A parent with a fixed set of names cannot show a
 * name that a row wrongly inherits and the parent does not hold; this one answers for any name it
 * is asked, with a value that carries the name, so a name that reaches a child is seen with the
 * value it came with. The names of the six rows are written out here, not read from the module.
 */
const everyName = new Proxy({} as NodeJS.ProcessEnv, {
    get: (_target, key) => (typeof key === 'string' ? `from-parent:${key}` : undefined)
});

const fromParent = (names: string[]): Record<string, string> =>
    Object.fromEntries(names.map((name) => [name, `from-parent:${name}`]));

const BASE = ['PATH', 'LANG', 'LC_ALL'];
const PROXIES = ['HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY', 'http_proxy', 'https_proxy', 'all_proxy', 'no_proxy'];

const EXPECTED: [ChildTool, Record<string, string>][] = [
    [ChildTool.BASE, fromParent(BASE)],
    [ChildTool.REARM_BOM, { ...fromParent(BASE), HOME: '/nonexistent' }],
    [ChildTool.REARM_ENRICH, {
        ...fromParent([...BASE, 'SSL_CERT_FILE', 'SSL_CERT_DIR', ...PROXIES, 'REARM_RESILIENTDNS']),
        HOME: '/nonexistent'
    }],
    [ChildTool.CYCLONEDX_CLI, fromParent([
        ...BASE, 'DOTNET_SYSTEM_GLOBALIZATION_INVARIANT', 'DOTNET_BUNDLE_EXTRACT_BASE_DIR', 'HOME', 'TMPDIR'
    ])],
    [ChildTool.SSH_KEYGEN, fromParent(BASE)],
    [ChildTool.GPG, fromParent(BASE)]
];

afterEach(() => {
    configureChildEnv({});
});

describe('each row holds exactly its names when the parent holds every name', () => {
    it.each(EXPECTED)('%s', (tool, expected) => {
        configureChildEnv({});
        const env = buildChildEnv(tool, everyName);
        expect(Object.keys(env).sort()).toStrictEqual(Object.keys(expected).sort());
        expect(env).toStrictEqual(expected);
    });

    it('with extra names accepted: the five tools hold the row and the extra names, the enrichment run no Go name', () => {
        configureChildEnv({ REBOM_CHILD_ENV_PASSTHROUGH: 'TZ,GODEBUG,godebug,GOMAXPROCS,DEBUG_HTTP2_GOROUTINES' });
        for (const [tool, expected] of EXPECTED) {
            const env = buildChildEnv(tool, everyName);
            const extra = tool === ChildTool.REARM_ENRICH
                ? ['TZ']
                : ['TZ', 'GODEBUG', 'godebug', 'GOMAXPROCS', 'DEBUG_HTTP2_GOROUTINES'];
            expect(env, tool).toStrictEqual({ ...fromParent(extra), ...expected });
        }
    });

    it('an unknown tool is an error, never a fall back to the row of another tool', () => {
        expect(() => buildChildEnv('NOT_A_TOOL' as ChildTool, everyName)).toThrow();
    });
});

describe('the parent is the one that is given', () => {
    const PLANT = ['PLANTED_BY_TEST', 'LANG', 'LC_ALL', 'HOME'] as const;
    const saved: Record<string, string | undefined> = {};

    const plant = (): void => {
        for (const name of PLANT) saved[name] = process.env[name];
        for (const name of PLANT) process.env[name] = `from-process:${name}`;
    };
    const unplant = (): void => {
        for (const name of PLANT) {
            if (saved[name] === undefined) delete process.env[name];
            else process.env[name] = saved[name];
        }
    };

    it('a name the given parent does not hold is absent even when rebom\'s own process holds it', () => {
        plant();
        try {
            configureChildEnv({ REBOM_CHILD_ENV_PASSTHROUGH: 'PLANTED_BY_TEST' });
            expect(buildChildEnv(ChildTool.BASE, { PATH: '/p' })).toStrictEqual({ PATH: '/p' });
            expect(buildChildEnv(ChildTool.CYCLONEDX_CLI, { PATH: '/p' })).toStrictEqual({ PATH: '/p' });
        } finally {
            unplant();
        }
    });
});

describe('many accepted names', () => {
    it('twenty-five accepted names all reach the child with their values', () => {
        const names = Array.from({ length: 25 }, (_, i) => `EXTRA_${String(i + 1).padStart(2, '0')}`);
        configureChildEnv({ REBOM_CHILD_ENV_PASSTHROUGH: names.join(',') });
        const env = buildChildEnv(ChildTool.GPG, everyName);
        expect(Object.keys(env)).toHaveLength(28);
        expect(env).toStrictEqual({ ...fromParent(names), ...fromParent(BASE) });
    });
});
