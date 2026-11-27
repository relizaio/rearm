import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const log = vi.hoisted(() => ({
    info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn(), trace: vi.fn(), fatal: vi.fn()
}));
vi.mock('../../src/logger', () => ({ logger: log }));

import {
    buildChildEnv, ChildTool, configureChildEnv, DEFAULT_CHILD_PATH, parseChildEnvPassthrough
} from '../../src/childEnv';

/**
 * Edges of the builder and of the hatch of childEnv.ts (TPF-256 design 3.1 and 3.3, rows T-1190,
 * T-1192, T-1194 and T-1195): the shortest and the odd names, values that are defined but empty,
 * the list the caller gets back, and the level of the start line for each single cause.
 */
afterEach(() => {
    configureChildEnv({});
});

describe('the shape of a name', () => {
    it('a name of one character is a name, and so is one that starts with an underscore', () => {
        const result = parseChildEnvPassthrough('X,_,_FOO,a,Z9');
        expect(result.accepted).toStrictEqual(['X', '_', '_FOO', 'a', 'Z9']);
        expect(result.malformed).toBe(0);
    });

    it('a name that starts with a digit or a dash is malformed, also when it is one character long', () => {
        const result = parseChildEnvPassthrough('9,-,-A,9A');
        expect(result.accepted).toStrictEqual([]);
        expect(result.malformed).toBe(4);
    });
});

describe('values that are defined but empty are values', () => {
    const parent = (): NodeJS.ProcessEnv => ({ PATH: '/p', LANG: '', LC_ALL: '', TZ: '' });

    it('an inherited name with an empty value is passed with the empty value', () => {
        const env = buildChildEnv(ChildTool.BASE, parent());
        expect(env).toStrictEqual({ PATH: '/p', LANG: '', LC_ALL: '' });
        expect(Object.prototype.hasOwnProperty.call(env, 'LANG')).toBe(true);
    });

    it('an accepted extra name with an empty value is passed with the empty value', () => {
        configureChildEnv({ REBOM_CHILD_ENV_PASSTHROUGH: 'TZ' });
        expect(buildChildEnv(ChildTool.REARM_BOM, parent())).toStrictEqual({ PATH: '/p', LANG: '', LC_ALL: '', TZ: '', HOME: '/nonexistent' });
    });

    it('a PATH that is defined but empty stays empty; only a PATH that is not defined gets the default', () => {
        expect(buildChildEnv(ChildTool.BASE, { PATH: '' })).toStrictEqual({ PATH: '' });
        expect(buildChildEnv(ChildTool.BASE, {}).PATH).toBe(DEFAULT_CHILD_PATH);
        expect(buildChildEnv(ChildTool.BASE, { PATH: undefined }).PATH).toBe(DEFAULT_CHILD_PATH);
    });
});

describe('what configureChildEnv hands back is not its state', () => {
    it('changing the returned lists afterwards changes no environment', () => {
        const result = configureChildEnv({ REBOM_CHILD_ENV_PASSTHROUGH: 'TZ' });
        result.accepted.push('LATER');
        result.accepted[0] = 'OTHER';
        const parent = { PATH: '/p', TZ: 'UTC', LATER: 'x', OTHER: 'y' };
        expect(buildChildEnv(ChildTool.BASE, parent)).toStrictEqual({ PATH: '/p', TZ: 'UTC' });
    });

    it('a refused name that the caller pushes into the returned lists reaches no environment', () => {
        const result = configureChildEnv({ REBOM_CHILD_ENV_PASSTHROUGH: 'POSTGRES_HOST' });
        expect(result.accepted).toStrictEqual([]);
        result.refused.push('TZ');
        expect(buildChildEnv(ChildTool.BASE, { PATH: '/p', TZ: 'UTC', POSTGRES_HOST: 'db' })).toStrictEqual({ PATH: '/p' });
    });
});

describe('the level of the start line, one cause at a time', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('one refused name and nothing malformed is a warning', () => {
        configureChildEnv({ REBOM_CHILD_ENV_PASSTHROUGH: 'API_KEY' });
        expect(log.warn).toHaveBeenCalledTimes(1);
        expect(log.info).not.toHaveBeenCalled();
        expect(log.warn.mock.calls[0][0]).toStrictEqual({ accepted: [], refused: ['API_KEY'], malformed: 0, withheldFromEnrichment: [] });
    });

    it('one malformed piece and nothing refused is a warning', () => {
        configureChildEnv({ REBOM_CHILD_ENV_PASSTHROUGH: 'A B' });
        expect(log.warn).toHaveBeenCalledTimes(1);
        expect(log.info).not.toHaveBeenCalled();
        expect(log.warn.mock.calls[0][0]).toStrictEqual({ accepted: [], refused: [], malformed: 1, withheldFromEnrichment: [] });
    });

    it('two refused names, and two malformed pieces, are each one warning line', () => {
        configureChildEnv({ REBOM_CHILD_ENV_PASSTHROUGH: 'API_KEY,MY_TOKEN' });
        configureChildEnv({ REBOM_CHILD_ENV_PASSTHROUGH: 'A B,C D' });
        expect(log.warn).toHaveBeenCalledTimes(2);
        expect(log.info).not.toHaveBeenCalled();
    });

    it('a withheld name alone stays an info line and the line carries the four fields as given', () => {
        configureChildEnv({ REBOM_CHILD_ENV_PASSTHROUGH: 'GOMAXPROCS,TZ' });
        expect(log.warn).not.toHaveBeenCalled();
        expect(log.info).toHaveBeenCalledTimes(1);
        expect(log.info.mock.calls[0]).toStrictEqual([
            { accepted: ['GOMAXPROCS', 'TZ'], refused: [], malformed: 0, withheldFromEnrichment: ['GOMAXPROCS'] },
            'child environment passthrough'
        ]);
    });
});
