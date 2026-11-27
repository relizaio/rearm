import { beforeEach, describe, expect, it, vi } from 'vitest';

// The builder of childEnv.ts is replaced by a recorder: what buildEnrichmentChildEnv itself does
// with the answer (the key name removed, then set) is looked at apart from what the builder returns.
const builder = vi.hoisted(() => ({
    answer: {} as Record<string, string>,
    calls: [] as { tool: unknown, parent: unknown }[]
}));
vi.mock('../../src/childEnv', async (importOriginal) => {
    const actual: any = await importOriginal();
    return {
        ...actual,
        buildChildEnv: (tool: unknown, parent: unknown) => {
            builder.calls.push({ tool, parent });
            return { ...builder.answer };
        }
    };
});

import { ChildTool } from '../../src/childEnv';
import { BEAR_ENV_API_KEY, buildEnrichmentChildEnv } from '../../src/services/bom/enrichmentChildEnv';

/**
 * The two layers that keep a stray REARM_BEARAPIKEY from reaching the enrichment child (TPF-256
 * design 3.1, rows T-1199 and T-1234): the builder never returns the name (the hatch refuses it),
 * and buildEnrichmentChildEnv removes it again before it sets the key. This file pins the second
 * layer with a builder that does return the name.
 */
describe('buildEnrichmentChildEnv on its own', () => {
    beforeEach(() => {
        builder.calls.length = 0;
        builder.answer = { PATH: '/usr/bin:/bin', HOME: '/nonexistent', REARM_BEARAPIKEY: 'stale-value-of-the-builder' };
    });

    it('asks the builder for the REARM_ENRICH row of the parent it was given, once', () => {
        const parent = { PATH: '/p' };
        buildEnrichmentChildEnv('a-key', parent);
        expect(builder.calls).toHaveLength(1);
        expect(builder.calls[0].tool).toBe(ChildTool.REARM_ENRICH);
        expect(builder.calls[0].parent).toBe(parent);
    });

    it('an empty key removes the name even when the builder returned it', () => {
        const env = buildEnrichmentChildEnv('', {});
        expect(env).toStrictEqual({ PATH: '/usr/bin:/bin', HOME: '/nonexistent' });
        expect(Object.prototype.hasOwnProperty.call(env, BEAR_ENV_API_KEY)).toBe(false);
    });

    it('a key replaces the value the builder returned, and nothing else of the answer changes', () => {
        const env = buildEnrichmentChildEnv('fresh-key', {});
        expect(env).toStrictEqual({ PATH: '/usr/bin:/bin', HOME: '/nonexistent', REARM_BEARAPIKEY: 'fresh-key' });
    });
});
