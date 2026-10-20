import { describe, it, expect, vi } from 'vitest';

// SCORE-46 T-19, kept by SCORE-51 (SCORE-50a): the statements bomLineageRepository
// runs on a transaction client, recorded in order on a fake TxClient, with no
// database, and the lineage key (SCORE-46 T-7). The repository keys and locks a
// lineage and stores nothing.
const runQuery = vi.fn(async (..._a: any[]) => ({ rows: [] }));
vi.mock('../../src/db', () => ({
    runQuery: (...a: any[]) => runQuery.apply(null, a as any)
}));

import * as BomLineageRepository from '../../src/bomLineageRepository';
import { canonicalSerialNumber, lockLineage, latestStoredVersion } from '../../src/bomLineageRepository';

const ORG = '00000000-0000-0000-0000-000000000000';
const S = 'urn:uuid:11111111-2222-3333-4444-555555555555';
const S_BARE = '11111111-2222-3333-4444-555555555555';
const LOCK = 'SELECT pg_advisory_xact_lock(hashtext($1::uuid::text), hashtext($2))';

type Recorded = { text: string, params: any[] };

function fakeClient(bomRows: any[] = []) {
    const statements: Recorded[] = [];
    const client = {
        query: async (text: string, params: any[]) => {
            statements.push({ text, params });
            if (/FROM rebom\.boms/.test(text)) return { rows: bomRows };
            return { rows: [] };
        }
    };
    return { client, statements };
}

describe('canonicalSerialNumber', () => {
    it('T-7: keeps the prefixed form', () => {
        expect(canonicalSerialNumber(S)).toBe(S);
    });

    it('T-7: adds the prefix to the bare form', () => {
        expect(canonicalSerialNumber(S_BARE)).toBe(S);
    });

    it('T-7: trims before prefixing', () => {
        expect(canonicalSerialNumber('  ' + S_BARE + ' ')).toBe(S);
    });

    it('T-7: does not fold case', () => {
        // An upper-case uuid under the prefix is returned unchanged: a different
        // lineage from the lower-case one, as it is a different row today.
        const lower = 'urn:uuid:abcdef12-3456-4789-8abc-def012345678';
        const upper = 'urn:uuid:ABCDEF12-3456-4789-8ABC-DEF012345678';
        expect(canonicalSerialNumber(upper)).toBe(upper);
        expect(canonicalSerialNumber(upper)).not.toBe(canonicalSerialNumber(lower));
    });

    it('T-7: blank values give the empty string', () => {
        expect(canonicalSerialNumber('')).toBe('');
        expect(canonicalSerialNumber('   ')).toBe('');
    });
});

describe('lockLineage', () => {
    it('T-19: one advisory lock statement on (org, key), on the client only', async () => {
        const { client, statements } = fakeClient();

        await lockLineage(client, ORG, S);

        expect(statements).toStrictEqual([{ text: LOCK, params: [ORG, S] }]);
        expect(runQuery).not.toHaveBeenCalled();
    });

    // The database cases (bom-lineage-org-spelling.test.ts) pin that every
    // spelling of the organization waits on the one lock.
    it('T-19: the lock is keyed on the organization as a uuid value', async () => {
        const { client, statements } = fakeClient();
        await lockLineage(client, '{ABCDEF00-0000-0000-0000-000000000000}', S);
        expect(statements[0].text).toBe(LOCK);
        expect(statements[0].params[0]).toBe('{ABCDEF00-0000-0000-0000-000000000000}');
    });
});

describe('latestStoredVersion', () => {
    it('T-19 e: one statement over rebom.boms, both serialNumber forms, newest first', async () => {
        const { client, statements } = fakeClient([{ uuid: 'row-1', bom_version: '3' }]);

        expect(await latestStoredVersion(client, ORG, S)).toStrictEqual({ uuid: 'row-1', bom_version: '3' });
        expect(statements).toHaveLength(1);
        expect(statements[0].text).toMatch(/FROM rebom\.boms/);
        expect(statements[0].text).toMatch(/ORDER BY \(meta->>'bomVersion'\)::numeric DESC/);
        expect(statements[0].text).toMatch(/LIMIT 1/);
        expect(statements[0].params).toStrictEqual([S, S_BARE, ORG]);
        expect(runQuery).not.toHaveBeenCalled();
    });

    it('T-19 e: null when the lineage has no row', async () => {
        const { client } = fakeClient([]);
        expect(await latestStoredVersion(client, ORG, S)).toBeNull();
    });
});

describe('the module', () => {
    it('SCORE-51: exports only the key, the lock and the latest-version read', () => {
        expect(Object.keys(BomLineageRepository).sort())
            .toStrictEqual(['canonicalSerialNumber', 'latestStoredVersion', 'lockLineage']);
    });
});
