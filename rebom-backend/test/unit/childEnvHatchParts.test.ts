import { afterEach, describe, expect, it, vi } from 'vitest';

const log = vi.hoisted(() => ({
    info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn(), trace: vi.fn(), fatal: vi.fn()
}));
vi.mock('../../src/logger', () => ({ logger: log }));

import { configureChildEnv, holdsOwnName, parseChildEnvPassthrough } from '../../src/childEnv';

/**
 * The refusals and the syntax of REBOM_CHILD_ENV_PASSTHROUGH (TPF-256 design 3.3, rows T-1192 and
 * T-1193, round 2 of the mutation hop): every refused part has names of its own that hold no other
 * part, so that narrowing or dropping one part shows; near misses of each part, of each prefix and
 * of the two refused names are accepted; and the characters a name may and may not hold.
 */
afterEach(() => {
    configureChildEnv({});
});

const PARTS = ['PASS', 'PWD', 'SECRET', 'TOKEN', 'KEY', 'SALT', 'CREDENTIAL', 'AUTH'];
const PREFIXES = ['POSTGRES_', 'ENCRYPTION_', 'REBOM_', 'REARM_', 'LD_', 'CORECLR_'];

// each name holds the one part and none of the seven others, at the start, the middle and the end
const BY_PART: Record<string, string[]> = {
    PASS: ['PASSPHRASE', 'DB_PASSWD', 'MY_PASS', 'GPG_PASSPHRASE', 'XPASSX'],
    PWD: ['DB_PWD', 'PWD_FILE', 'XPWDX', 'my_pwd'],
    SECRET: ['CLIENT_SECRET', 'SECRETS_DIR', 'XSECRETX', 'my_secret'],
    TOKEN: ['MY_TOKEN', 'TOKENS', 'XTOKENX', 'my_token'],
    KEY: ['SOME_KEYRING', 'KEYFILE', 'MONKEY', 'my_key'],
    SALT: ['MY_SALT', 'SALTED', 'XSALTX', 'my_salt'],
    CREDENTIAL: ['MY_CREDENTIAL', 'CREDENTIALS_FILE', 'XCREDENTIALX', 'my_credential'],
    AUTH: ['MY_AUTH', 'AUTHORITY', 'XAUTHX', 'my_auth']
};

describe('every refused part of a name has names of its own', () => {
    it('the names of the table hold the one part each (the table itself is checked)', () => {
        for (const [part, names] of Object.entries(BY_PART)) {
            for (const name of names) {
                const upper = name.toUpperCase();
                expect(PARTS.filter((p) => upper.includes(p)), name).toStrictEqual([part]);
                expect(PREFIXES.some((p) => upper.startsWith(p)), name).toBe(false);
            }
        }
    });

    it.each(Object.entries(BY_PART))('%s', (_part, names) => {
        const result = parseChildEnvPassthrough(names.join(','));
        expect(result.refused).toStrictEqual(names);
        expect(result.accepted).toStrictEqual([]);
        expect(result.malformed).toBe(0);
    });

    it('the part PASS refuses what is not PASSWORD: PASSPHRASE, DB_PASSWD and MY_PASS together', () => {
        const result = parseChildEnvPassthrough('PASSPHRASE,DB_PASSWD,MY_PASS');
        expect(result.refused).toStrictEqual(['PASSPHRASE', 'DB_PASSWD', 'MY_PASS']);
        expect(result.accepted).toStrictEqual([]);
    });
});

describe('near misses are accepted', () => {
    it('a name one character short of a part, or that breaks it in two, is accepted', () => {
        const names = ['PAS_X', 'PW_X', 'SECRE_X', 'TOKE_X', 'KE_X', 'KE_Y', 'SAL_X', 'CREDENTIA_X', 'AUT_X',
            'PAS_S', 'SEC_RET', 'TOK_EN', 'CRED_ENTIAL', 'AU_TH'];
        const result = parseChildEnvPassthrough(names.join(','));
        expect(result.accepted).toStrictEqual(names);
        expect(result.refused).toStrictEqual([]);
    });

    it('a name that holds a prefix without its underscore, or an unrelated library name, is accepted', () => {
        const names = ['LDAP_URL', 'LDX', 'REARMX', 'REBOMX', 'POSTGRESX', 'ENCRYPTIONX', 'CORECLRX', 'OCI_BASE', 'XDG_CONFIG_HOME', 'TZ'];
        const result = parseChildEnvPassthrough(names.join(','));
        expect(result.accepted).toStrictEqual(names);
        expect(result.refused).toStrictEqual([]);
    });

    it('a name that holds the prefix later is accepted; the guard of rebom\'s own names agrees', () => {
        const result = parseChildEnvPassthrough('MY_REBOM_X,A_LD_X');
        expect(result.accepted).toStrictEqual(['MY_REBOM_X', 'A_LD_X']);
        expect(holdsOwnName({ OCI_BASE: '1', LDAP_URL: '1', REARM_X: '1', XDG_CONFIG_HOME: '1' })).toBe(false);
    });

    it('ordinary names that hold none of the refused parts are accepted', () => {
        const names = ['PRIVATE_DIR', 'LOG_LEVEL', 'NO_COLOR', 'LANGUAGE', 'GODEBUG', 'TERM', 'DEBUG', 'a', 'x1_y2'];
        const result = parseChildEnvPassthrough(names.join(','));
        expect(result.accepted).toStrictEqual(names);
        expect(result.refused).toStrictEqual([]);
    });

    it('the two refused names are refused whole, not by a part of them', () => {
        const result = parseChildEnvPassthrough('NODE_OPTIONS,node_options,DOTNET_STARTUP_HOOKS,NODE,DOTNET_STARTUP,NODE_OPTIONS_X,DOTNET_STARTUP_HOOKS_X');
        expect(result.refused).toStrictEqual(['NODE_OPTIONS', 'node_options', 'DOTNET_STARTUP_HOOKS']);
        expect(result.accepted).toStrictEqual(['NODE', 'DOTNET_STARTUP', 'NODE_OPTIONS_X', 'DOTNET_STARTUP_HOOKS_X']);
    });
});

describe('the syntax of the list', () => {
    it('a dot, a hyphen and a semicolon are not part of a name and do not separate names', () => {
        const result = parseChildEnvPassthrough('A.B,A-B,X;Y,.A,Z_1');
        expect(result.accepted).toStrictEqual(['Z_1']);
        expect(result.refused).toStrictEqual([]);
        expect(result.malformed).toBe(4);
    });

    it('a piece that starts with a hash is malformed; spaces around a name are trimmed on both sides', () => {
        const result = parseChildEnvPassthrough('#A, B ,C ');
        expect(result.accepted).toStrictEqual(['B', 'C']);
        expect(result.malformed).toBe(1);
    });

    it('a piece with a trailing or a leading space only, and tabs and new lines around names, are trimmed', () => {
        const result = parseChildEnvPassthrough('\tA\t,\nB\n, C,D ');
        expect(result.accepted).toStrictEqual(['A', 'B', 'C', 'D']);
        expect(result.malformed).toBe(0);
    });

    it('a new line or a tab between two names is not a separator: the piece is malformed', () => {
        const result = parseChildEnvPassthrough('A\nB,C\tD,E');
        expect(result.accepted).toStrictEqual(['E']);
        expect(result.malformed).toBe(2);
    });

    it('other punctuation, a space inside, a non-ASCII letter and an equals sign are malformed', () => {
        const result = parseChildEnvPassthrough('A:B,A/B,A B,CAF\u00c9,A=1,A$,(A),A\\B');
        expect(result.accepted).toStrictEqual([]);
        expect(result.malformed).toBe(8);
    });
});
