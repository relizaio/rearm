import { describe, it, expect, vi } from 'vitest';

// SCORE-46 T-19: the statements bomLineageRepository runs on a transaction
// client, recorded in order on a fake TxClient, with no database. The lineage
// lock is the first statement of every lineage transaction (ADR-13).
const runQuery = vi.fn(async (..._a: any[]) => ({ rows: [] }));
vi.mock('../../src/db', () => ({
    runQuery: (...a: any[]) => runQuery.apply(null, a as any)
}));
vi.mock('../../src/logger', () => ({
    logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() }
}));

import { claimInTransaction, claimIfUnowned, reassign, latestStoredVersion } from '../../src/bomLineageRepository';

const ORG = '00000000-0000-0000-0000-000000000000';
const S = 'urn:uuid:11111111-2222-3333-4444-555555555555';
const S_BARE = '11111111-2222-3333-4444-555555555555';
const A = 'aaaaaaaa-0000-4000-8000-000000000001';
const B = 'bbbbbbbb-0000-4000-8000-000000000002';
const LOCK = 'SELECT pg_advisory_xact_lock(hashtext($1::uuid::text), hashtext($2))';

type Recorded = { text: string, params: any[] };

function fakeClient(lineageRows: any[] = [], bomRows: any[] = []) {
    const statements: Recorded[] = [];
    const client = {
        query: async (text: string, params: any[]) => {
            statements.push({ text, params });
            if (/FOR UPDATE|SELECT owner_component/.test(text)) return { rows: lineageRows };
            if (/FROM rebom\.boms/.test(text)) return { rows: bomRows };
            return { rows: [] };
        }
    };
    return { client, statements };
}

const kinds = (statements: Recorded[]) => statements.map(s => {
    if (s.text === LOCK) return 'lock';
    if (s.text.startsWith('INSERT INTO rebom.bom_lineage')) return 'insert';
    if (s.text.includes('FOR UPDATE')) return 'select';
    if (s.text.startsWith('UPDATE rebom.bom_lineage')) return 'update';
    return s.text;
});

describe('claimInTransaction', () => {
    it('T-19 a: an owner on a new lineage: lock, insert, locked read, claim', async () => {
        const { client, statements } = fakeClient([{ owner_component: null }]);

        const owner = await claimInTransaction(client, ORG, S, A);

        expect(owner).toBe(A);
        expect(kinds(statements)).toStrictEqual(['lock', 'insert', 'select', 'update']);
        expect(statements[0].params).toStrictEqual([ORG, S]);
        expect(statements[1].text).toMatch(/ON CONFLICT .* DO NOTHING/);
        expect(statements[3].text).toMatch(/owner_component IS NULL/);
        expect(statements[3].params).toContain(A);
        expect(statements[3].params).toContain('UPLOAD');
        expect(runQuery).not.toHaveBeenCalled();
    });

    it('T-19 b: a null caller takes the lock, then only the locked read', async () => {
        const none = fakeClient([]);
        expect(await claimInTransaction(none.client, ORG, S, null)).toBeNull();
        expect(kinds(none.statements)).toStrictEqual(['lock', 'select']);
        expect(none.statements[0].params).toStrictEqual([ORG, S]);

        const owned = fakeClient([{ owner_component: B }]);
        expect(await claimInTransaction(owned.client, ORG, S, null)).toBe(B);
        expect(kinds(owned.statements)).toStrictEqual(['lock', 'select']);
    });

    it('T-19 c: an owner on a lineage another component owns: no update, answers that owner', async () => {
        const { client, statements } = fakeClient([{ owner_component: B }]);

        expect(await claimInTransaction(client, ORG, S, A)).toBe(B);
        expect(kinds(statements)).toStrictEqual(['lock', 'insert', 'select']);
    });

    // The database cases (bom-lineage-org-spelling.test.ts) pin that every
    // spelling of the organization waits on the one lock.
    it('T-19: the lock is keyed on the organization as a uuid value', async () => {
        const { client, statements } = fakeClient([]);
        await claimInTransaction(client, '{ABCDEF00-0000-0000-0000-000000000000}', S, null);
        expect(statements[0].text).toBe(LOCK);
        expect(statements[0].params[0]).toBe('{ABCDEF00-0000-0000-0000-000000000000}');
    });
});

describe('the mutations', () => {
    it('T-19 d: claimIfUnowned takes the lock first', async () => {
        const { client, statements } = fakeClient([{ owner_component: null }]);

        await claimIfUnowned(client, ORG, S, B, 'backfill');

        expect(kinds(statements)[0]).toBe('lock');
        expect(statements[0].params).toStrictEqual([ORG, S]);
        expect(kinds(statements)).toStrictEqual(['lock', 'insert', 'select', 'update']);
        expect(statements[3].params).toContain('BACKFILL');
    });

    it('T-19 d: reassign takes the lock first and updates without the IS NULL guard', async () => {
        const { client, statements } = fakeClient([{ owner_component: B }]);

        await reassign(client, ORG, S, A, 'user-1');

        expect(kinds(statements)).toStrictEqual(['lock', 'insert', 'select', 'update']);
        expect(statements[0].params).toStrictEqual([ORG, S]);
        expect(statements[3].text).not.toMatch(/IS NULL/);
        expect(statements[3].params).toContain('REASSIGN');
        expect(statements[3].params).toContain(A);
    });
});

describe('latestStoredVersion', () => {
    it('T-19 e: one statement over rebom.boms, both serialNumber forms, newest first', async () => {
        const { client, statements } = fakeClient([], [{ uuid: 'row-1', bom_version: '3' }]);

        expect(await latestStoredVersion(client, ORG, S)).toStrictEqual({ uuid: 'row-1', bom_version: '3' });
        expect(statements).toHaveLength(1);
        expect(statements[0].text).toMatch(/FROM rebom\.boms/);
        expect(statements[0].text).toMatch(/ORDER BY \(meta->>'bomVersion'\)::numeric DESC/);
        expect(statements[0].text).toMatch(/LIMIT 1/);
        expect(statements[0].params).toStrictEqual([S, S_BARE, ORG]);
        expect(runQuery).not.toHaveBeenCalled();
    });

    it('T-19 e: null when the lineage has no row', async () => {
        const { client } = fakeClient([], []);
        expect(await latestStoredVersion(client, ORG, S)).toBeNull();
    });
});
