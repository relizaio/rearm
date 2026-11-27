import { describe, it, expect } from 'vitest';

import {
    BEAR_API_KEY_NUL_MESSAGE, BEAR_ENV_API_KEY, buildEnrichmentChildEnv
} from '../../src/services/bom/enrichmentChildEnv';

/**
 * The environment of one enrichment child (TPF-254 design 3.2 and 3.4; row T-1000): a copy of the
 * parent, the key name removed, then set to the key when the key is not empty. The parent is never
 * written to.
 */
describe('buildEnrichmentChildEnv', () => {
    const MARK = 'markerkey';
    const parent = () => ({ PATH: '/usr/bin:/bin', FOO: 'bar', REARM_BEARAPIKEY: 'outside' });

    it('T-1000 names the variable the rearm CLI reads for --bearApiKey', () => {
        expect(BEAR_ENV_API_KEY).toBe('REARM_BEARAPIKEY');
    });

    it('T-1000 a key: the parent copied with the name set to the key, the parent unchanged', () => {
        const p = parent();
        const env = buildEnrichmentChildEnv(MARK, p);
        expect(env).toStrictEqual({ PATH: '/usr/bin:/bin', FOO: 'bar', REARM_BEARAPIKEY: MARK });
        expect(env).not.toBe(p);
        expect(p).toStrictEqual(parent());
    });

    it('T-1000 an empty key: the name is not a property at all, the inherited value is dropped, the parent unchanged', () => {
        const p = parent();
        const env = buildEnrichmentChildEnv('', p);
        expect(env).toStrictEqual({ PATH: '/usr/bin:/bin', FOO: 'bar' });
        expect(Object.prototype.hasOwnProperty.call(env, 'REARM_BEARAPIKEY')).toBe(false);
        expect(env).not.toBe(p);
        expect(p).toStrictEqual(parent());
    });

    it('T-1000 a parent without the name and a key: only the name is added', () => {
        const p = { PATH: '/usr/bin:/bin' };
        expect(buildEnrichmentChildEnv(MARK, p)).toStrictEqual({ PATH: '/usr/bin:/bin', REARM_BEARAPIKEY: MARK });
        expect(p).toStrictEqual({ PATH: '/usr/bin:/bin' });
    });

    it('T-1000 a key holding a NUL byte is refused with a constant message that does not hold the key', () => {
        const p = parent();
        const key = MARK + String.fromCharCode(0) + 'tail';
        let thrown: unknown;
        try {
            buildEnrichmentChildEnv(key, p);
        } catch (e) {
            thrown = e;
        }
        expect(thrown).toBeInstanceOf(Error);
        expect((thrown as Error).message).toBe(BEAR_API_KEY_NUL_MESSAGE);
        expect((thrown as Error).message).toBe('BEAR API key holds a NUL byte');
        expect(String(thrown)).not.toContain(MARK);
        expect(p).toStrictEqual(parent());
    });

    it('T-1000 the default parent is process.env, which is not written to', () => {
        const saved = process.env.REARM_BEARAPIKEY;
        delete process.env.REARM_BEARAPIKEY;
        try {
            const env = buildEnrichmentChildEnv(MARK);
            expect(env.REARM_BEARAPIKEY).toBe(MARK);
            expect(env).not.toBe(process.env);
            expect(env.PATH).toBe(process.env.PATH);
            expect(process.env.REARM_BEARAPIKEY).toBeUndefined();
        } finally {
            if (saved === undefined) delete process.env.REARM_BEARAPIKEY;
            else process.env.REARM_BEARAPIKEY = saved;
        }
    });
});
