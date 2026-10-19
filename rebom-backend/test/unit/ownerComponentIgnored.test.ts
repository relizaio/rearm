import { describe, it, expect, vi, beforeEach } from 'vitest';

// SCORE-51 (SCORE-50a) T-1 and the CycloneDX half of T-3: rebom keeps no lineage
// owner. rebomOptions.ownerComponent is accepted whatever its value and ignored,
// and an insert's transaction is the lineage lock, the latest-version read and
// the insert. runQuery is also the transaction client, so it records every
// statement in order.
const LOCK = 'SELECT pg_advisory_xact_lock(hashtext($1::uuid::text), hashtext($2))';
const runQuery = vi.fn(async (text: string, _params?: any[]) =>
    (/^INSERT/.test(text) ? { rows: [{ uuid: 'inserted' }] } : { rows: [] as any[] }));
const pushToOci = vi.fn(async (tag: string, _doc?: any, _repo?: string) => ({
    ociRepositoryName: 'rebom-artifacts-2026-10',
    fileSHA256Digest: `digest-of-${tag}`,
    originalSize: 10
}));

vi.mock('../../src/utils', () => ({ runQuery: (...a: any[]) => runQuery.apply(null, a as any), pool: {},
    withTransaction: async (fn: any) => fn({ query: (...a: any[]) => runQuery.apply(null, a as any) }) }));
vi.mock('../../src/logger', () => ({
    logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() }
}));
vi.mock('../../src/bomRepository', () => ({
    allBomsBySerialNumber: vi.fn(async () => [] as any[]),
    bomById: vi.fn(async () => [])
}));
vi.mock('../../src/services/oci', async (importOriginal) => {
    const actual: any = await importOriginal();
    return {
        ...actual,
        pushToOci: (...a: any[]) => pushToOci.apply(null, a as any),
        getMonthlyRepositoryName: () => 'rebom-artifacts-2026-10'
    };
});
vi.mock('../../src/services/bom/bomProcessingService', async (importOriginal) => {
    const actual: any = await importOriginal();
    return {
        ...actual,
        getInitialEnrichmentStatus: vi.fn(async () => 'SKIPPED'),
        enrichBomAsync: vi.fn(async () => undefined)
    };
});

import { addBom } from '../../src/services/bom/bomAddService';
import { ERROR_CODES } from '../../src/types/errors';

const SERIAL = 'urn:uuid:11111111-2222-3333-4444-555555555555';
const ORG = '00000000-0000-0000-0000-000000000000';
const A = 'aaaaaaaa-0000-4000-8000-000000000001';

function bom(version: number) {
    return {
        bomFormat: 'CycloneDX', specVersion: '1.5', serialNumber: SERIAL, version,
        metadata: { timestamp: '2026-10-19T01:00:00Z' },
        components: [{ type: 'library', 'bom-ref': 'c1', name: 'left-pad', version: '1.3.0',
            purl: 'pkg:npm/left-pad@1.3.0' }]
    };
}

// Augmentation needs the component context ReARM sends with every upload.
function rebomOptions(owner: string | undefined): any {
    const options: any = {
        name: 'demo-component', group: 'io.reliza', version: '1.0.0',
        belongsTo: 'COMPONENT', structure: 'FLAT', bomState: 'RAW', stripBom: 'false',
        tldOnly: false, notes: '', storage: 'OCI', mod: 'raw'
    };
    if (owner !== undefined) options.ownerComponent = owner;
    return options;
}

const kinds = () => runQuery.mock.calls.map(c => {
    const text = String(c[0]);
    if (text === LOCK) return 'lock';
    if (text.startsWith("SELECT uuid, meta->>'bomVersion' AS bom_version FROM rebom.boms")) return 'latest';
    if (text.startsWith('INSERT INTO rebom.boms')) return 'insert';
    return text;
});

beforeEach(() => vi.clearAllMocks());

describe('T-1: CycloneDX upload with rebomOptions.ownerComponent', () => {
    it.each([
        ['a uuid', A],
        ['a string that is not a uuid', 'not-a-uuid'],
        ['absent', undefined]
    ])('%s: accepted, stored without it, and the caller\'s options untouched', async (_label, owner) => {
        const options = rebomOptions(owner);
        const sent = structuredClone(options);

        const created: any = await addBom({
            bomInput: { format: 'CYCLONEDX', org: ORG, bom: bom(1), tags: [], rebomOptions: options }
        } as any);

        expect(created.uuid).toBe('inserted');
        expect(pushToOci).toHaveBeenCalledTimes(2);
        const insert = runQuery.mock.calls.find(c => String(c[0]).startsWith('INSERT INTO rebom.boms'))!;
        const meta = (insert[1] as any[])[1];
        expect(meta.serialNumber).toBe(SERIAL);
        expect(meta).not.toHaveProperty('ownerComponent');
        // A copy, not a mutation: the caller's object is exactly what it sent.
        expect(options).toStrictEqual(sent);
        for (const call of runQuery.mock.calls) {
            expect(String(call[0])).not.toContain('bom_lineage');
        }
    });
});

describe('T-3: the CycloneDX insert transaction', () => {
    it('is the lock on (org, canonical key), the latest-version read and the insert, nothing else', async () => {
        await addBom({
            bomInput: { format: 'CYCLONEDX', org: ORG, bom: bom(1), tags: [], rebomOptions: rebomOptions(A) }
        } as any);

        expect(kinds()).toStrictEqual(['lock', 'latest', 'insert']);
        expect(runQuery.mock.calls[0][1]).toStrictEqual([ORG, SERIAL]);
        expect(runQuery.mock.calls[1][1]).toStrictEqual([SERIAL, SERIAL.replace('urn:uuid:', ''), ORG]);
    });
});

describe('T-6: the error codes', () => {
    it('has no BOM_LINEAGE_OWNER_MISMATCH', () => {
        expect(Object.keys(ERROR_CODES)).not.toContain('BOM_LINEAGE_OWNER_MISMATCH');
        expect(Object.values(ERROR_CODES)).not.toContain('BOM_LINEAGE_OWNER_MISMATCH');
    });
});
