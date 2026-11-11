import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

// The integration table may not exist in the test database (as in the other store rows).
vi.mock('../../src/integrationRepository', () => ({
    findIntegrationByTypeAndOrg: vi.fn().mockResolvedValue(null),
    upsertIntegration: vi.fn(),
    createSecret: vi.fn(),
    findSecretById: vi.fn(),
    updateSecret: vi.fn(),
    deleteSecret: vi.fn(),
    deleteIntegration: vi.fn(),
}));

import { v4 as uuidv4 } from 'uuid';
import * as BomRepository from '../../src/bomRepository';
import { SpdxService } from '../../src/services/spdx';
import { getMockStorageState } from '../../src/test-utils/mockOciService';
import { pool } from '../../src/utils';
import { cleanupTestBoms, generateSerialNumber, loadFixture, TEST_ORG_UUID } from '../helpers';
import { cleanupDirs, configure, field, gql, Http, Q, startHttp } from './teaTestKit';
import { SAMPLE, TeaFixtureArtifact, TeaFixtureServer } from './teaFixtureServer';

/**
 * Storing a held file as an SBOM through the existing add path (TPF-180 design 3.6.3, 3.6.4;
 * rows T-60 to T-63 and T-65 to T-69; T-64 is in teaLineageUnderLock.test.ts). Real CLI against the
 * fixture, the migrated database, the mocked registry.
 */

const created: string[] = [];
const ART = (n: number) => `77777777-7777-4777-8777-77777777777${n}`;

function serial(): string {
    const sn = generateSerialNumber();
    created.push(sn);
    return sn;
}

function cdx(serialNumber: string, version = 1, description?: string): any {
    const doc = loadFixture('cyclonedx-simple.json');
    doc.serialNumber = serialNumber;
    doc.version = version;
    if (description) doc.metadata.component.description = description;
    return doc;
}

const bytes = (doc: unknown) => Buffer.from(JSON.stringify(doc));
const artifact = (n: number, body: Buffer, type = 'BOM', mediaType = 'application/vnd.cyclonedx+json'): TeaFixtureArtifact => ({ uuid: ART(n), type, mediaType, body });
const rebomOptions = () => ({ name: 'tea-component', group: 'com.example', version: '1.0.0', belongsTo: '', structure: 'FLAT' });
const pushes = () => getMockStorageState().storage.size;

describe('TEA SBOM store', () => {
    let gqlHttp: Http;
    let fixture: TeaFixtureServer;

    beforeAll(async () => {
        gqlHttp = await startHttp();
    });

    afterAll(async () => {
        await gqlHttp.server.stop();
        await cleanupTestBoms(pool, created);
    });

    afterEach(async () => {
        await fixture?.stop();
        cleanupDirs();
    });

    /** Serves the artifacts as one collection and fetches it; the fetch result. */
    async function fetchHeld(artifacts: TeaFixtureArtifact[], options: { sbomMaxBytes?: number } = {}): Promise<any> {
        await fixture?.stop();
        fixture = new TeaFixtureServer();
        await fixture.start();
        fixture.serveComponentCollection(artifacts);
        configure({ REBOM_TEA_LOCAL_TESTING: 'ON' }, options);
        return fetchAgain();
    }

    async function fetchAgain(): Promise<any> {
        const result = field(await gql(gqlHttp.url, Q.fetch, { input: {
            apiRoot: fixture.root, releaseKind: 'COMPONENT_RELEASE', releaseUuid: SAMPLE.componentRelease, collectionVersion: 1,
            documentClasses: ['SBOM', 'VEX', 'OTHER'], credential: { kind: 'NONE' }
        } }));
        expect(['OK', 'PARTIAL'], JSON.stringify(result.error)).toContain(result.outcome);
        return result;
    }

    async function store(fetchId: string, number: number, continueLineage?: string): Promise<any> {
        return field(await gql(gqlHttp.url, Q.store, { input: {
            fetchId, number, org: TEST_ORG_UUID, rebomOptions: rebomOptions(), ...(continueLineage ? { continueLineage } : {})
        } }));
    }

    async function chunkOk(fetchId: string, number: number): Promise<boolean> {
        return field(await gql(gqlHttp.url, Q.chunk, { fetchId, number, offset: 0, length: 16 })).outcome === 'OK';
    }

    async function rowsOf(serialNumber: string): Promise<any[]> {
        return BomRepository.allBomsBySerialNumber(serialNumber, TEST_ORG_UUID);
    }

    it('T-60 store, store again, fetch again and store: CREATED, then EXISTING with the same row, one row, no further push', async () => {
        const sn = serial();
        const held = await fetchHeld([artifact(1, bytes(cdx(sn)))]);
        const first = await store(held.fetchId, 1);
        expect(first.outcome, JSON.stringify(first)).toBe('OK');
        expect(first.stored).toBe('CREATED');
        expect(first.format).toBe('CYCLONEDX');
        const afterFirst = pushes();
        const second = await store(held.fetchId, 1);
        expect(second.stored).toBe('EXISTING');
        expect(second.bom.uuid).toBe(first.bom.uuid);
        const again = await fetchAgain();
        const third = await store(again.fetchId, 1);
        expect(third.stored).toBe('EXISTING');
        expect(third.bom.uuid).toBe(first.bom.uuid);
        expect(await rowsOf(sn)).toHaveLength(1);
        expect(pushes()).toBe(afterFirst);
    });

    it('T-61 the same bytes uploaded with addBom first, then fetched and stored: EXISTING with the upload\'s row', async () => {
        const sn = serial();
        const doc = cdx(sn);
        const upload = await gql(gqlHttp.url, 'mutation($bomInput: BomInput!) { addBom(bomInput: $bomInput) { uuid } }',
            { bomInput: { format: 'CYCLONEDX', bom: doc, org: TEST_ORG_UUID, rebomOptions: rebomOptions() } });
        const uploaded = upload.json.data.addBom.uuid;
        const held = await fetchHeld([artifact(1, bytes(doc))]);
        const stored = await store(held.fetchId, 1);
        expect(stored.stored).toBe('EXISTING');
        expect(stored.bom.uuid).toBe(uploaded);
    });

    it('T-62 a fetched document may not continue a stored lineage the caller did not name', async () => {
        const sn = serial();
        const upload = await gql(gqlHttp.url, 'mutation($bomInput: BomInput!) { addBom(bomInput: $bomInput) { uuid } }',
            { bomInput: { format: 'CYCLONEDX', bom: cdx(sn, 1), org: TEST_ORG_UUID, rebomOptions: rebomOptions() } });
        const v1 = upload.json.data.addBom.uuid;
        const held = await fetchHeld([artifact(1, bytes(cdx(sn, 2, 'version two')))]);
        const before = pushes();
        for (const named of [undefined, serial()]) {
            const refused = await store(held.fetchId, 1, named);
            expect(refused.stored, String(named)).toBe('LINEAGE_EXISTS');
            expect(refused.conflict.serialNumber).toBe(sn);
            expect(refused.conflict.storedVersion).toBe('1');
            expect(refused.conflict.documentVersion).toBe('2');
            expect(refused.conflict.existingUuid).toBe(v1);
        }
        expect(pushes()).toBe(before);
        expect(await rowsOf(sn)).toHaveLength(1);
        const read = await BomRepository.bomBySerialNumber(sn, TEST_ORG_UUID);
        expect(String(read[0].meta.bomVersion)).toBe('1');
        const named = await store(held.fetchId, 1, sn);
        expect(named.stored).toBe('CREATED');
        expect(String(named.bom.meta.bomVersion)).toBe('2');
    });

    it('T-63 a named lineage with a document at the stored version is VERSION_CONFLICT', async () => {
        const sn = serial();
        await gql(gqlHttp.url, 'mutation($bomInput: BomInput!) { addBom(bomInput: $bomInput) { uuid } }',
            { bomInput: { format: 'CYCLONEDX', bom: cdx(sn, 1), org: TEST_ORG_UUID, rebomOptions: rebomOptions() } });
        const held = await fetchHeld([artifact(1, bytes(cdx(sn, 1, 'other content')))]);
        const conflict = await store(held.fetchId, 1, sn);
        expect(conflict.stored).toBe('VERSION_CONFLICT');
        expect(conflict.conflict.serialNumber).toBe(sn);
        expect(conflict.conflict.storedVersion).toBe('1');
    });

    it('T-65 two stores of one new document at once: one CREATED, one EXISTING, the same row', async () => {
        const sn = serial();
        const held = await fetchHeld([artifact(1, bytes(cdx(sn)))]);
        const results = await Promise.all([store(held.fetchId, 1), store(held.fetchId, 1)]);
        expect(results.map((r) => r.stored).sort()).toEqual(['CREATED', 'EXISTING']);
        expect(results[0].bom.uuid).toBe(results[1].bom.uuid);
        expect(await rowsOf(sn)).toHaveLength(1);
    });

    it('T-66 SPDX: CREATED, EXISTING, LINEAGE_EXISTS for the namespace with other content; named, the next version needs a namespace of its own', async () => {
        const convert = vi.spyOn(SpdxService, 'convertSpdxToCycloneDx');
        try {
            const sn = serial();
            const ns = `https://spdx.example.test/tea/${uuidv4()}`;
            const spdx = (name: string, namespace = ns) => ({ SPDXID: 'SPDXRef-DOCUMENT', spdxVersion: 'SPDX-2.3', dataLicense: 'CC0-1.0', name, documentNamespace: namespace, packages: [] });
            const convertsTo = () => convert.mockResolvedValueOnce({ success: true, convertedBom: cdx(sn, 1) } as any);
            const held = await fetchHeld([
                artifact(1, bytes(spdx('first')), 'BOM', 'application/spdx+json'),
                artifact(2, bytes(spdx('second')), 'BOM', 'application/spdx+json'),
                artifact(3, bytes(spdx('third', `${ns}/v2`)), 'BOM', 'application/spdx+json')
            ]);
            convertsTo();
            const first = await store(held.fetchId, 1);
            expect(first.stored, JSON.stringify(first)).toBe('CREATED');
            expect(first.format).toBe('SPDX');
            const again = await store(held.fetchId, 1);
            expect(again.stored).toBe('EXISTING');
            expect(again.bom.uuid).toBe(first.bom.uuid);
            const other = await store(held.fetchId, 2);
            expect(other.stored).toBe('LINEAGE_EXISTS');
            expect(other.conflict.serialNumber).toBe(sn);
            expect(other.conflict.storedVersion).toBe('1');
            expect(other.conflict.existingUuid).toBe(first.bom.uuid);
            // departure from the design's last step (implementation notes): one namespace holds one
            // SPDX document per organization, so the same namespace cannot become version 2
            const sameNamespace = await store(held.fetchId, 2, other.conflict.serialNumber);
            expect(sameNamespace.stored).toBe('INVALID');
            expect(sameNamespace.storeMessage).toContain('documentNamespace');
            expect(await rowsOf(sn)).toHaveLength(1);
            convertsTo();
            const named = await store(held.fetchId, 3, other.conflict.serialNumber);
            expect(named.stored, JSON.stringify(named)).toBe('CREATED');
            expect(String(named.bom.meta.bomVersion)).toBe('2');
            const idempotent = await store(held.fetchId, 3, other.conflict.serialNumber);
            expect(idempotent.stored).toBe('EXISTING');
            expect(idempotent.bom.uuid).toBe(named.bom.uuid);
            expect(await rowsOf(sn)).toHaveLength(2);
        } finally {
            convert.mockRestore();
        }
    });

    it('T-67 the content decides: XML, a non-SBOM object, an array, invalid UTF-8, a byte order mark, no serialNumber, deep nesting', async () => {
        const sn = serial();
        let deep = '';
        for (let i = 0; i < 200000; i++) deep += '[';
        for (let i = 0; i < 200000; i++) deep += ']';
        const files: Array<[Buffer, string[]]> = [
            [Buffer.from('<?xml version="1.0"?><bom xmlns="http://cyclonedx.org/schema/bom/1.6"/>'), ['NOT_PARSEABLE']],
            [Buffer.from('{"a":1}'), ['NOT_AN_SBOM']],
            [Buffer.from('[1,2,3]'), ['NOT_AN_SBOM']],
            [Buffer.from([0x7b, 0x22, 0xff, 0xfe, 0x22, 0x7d]), ['NOT_PARSEABLE']],
            [Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), bytes(cdx(sn))]), ['CREATED']],
            [bytes({ ...cdx(sn), serialNumber: undefined }), ['INVALID']],
            [Buffer.from(deep), ['NOT_PARSEABLE', 'NOT_AN_SBOM', 'INVALID']]
        ];
        const held = await fetchHeld(files.map(([body], i) => artifact(i + 1, body)));
        for (let i = 0; i < files.length; i++) {
            const result = await store(held.fetchId, i + 1);
            expect(result.outcome, String(i + 1)).toBe('OK');
            expect(files[i][1], `${i + 1}: ${result.stored}`).toContain(result.stored);
            if (result.stored === 'INVALID') expect(result.storeMessage.length).toBeGreaterThan(0);
            expect(await chunkOk(held.fetchId, i + 1), String(i + 1)).toBe(true);
        }
    });

    it('T-68 a file typed LICENSE that holds a CycloneDX document is class OTHER and stores', async () => {
        const sn = serial();
        const held = await fetchHeld([artifact(1, bytes(cdx(sn)), 'LICENSE', 'application/json')]);
        expect(held.files[0].documentClass).toBe('OTHER');
        const stored = await store(held.fetchId, 1);
        expect(stored.stored).toBe('CREATED');
    });

    it('T-69 an SBOM above the lowered ceiling is TOO_LARGE and stays readable by chunks', async () => {
        const sn = serial();
        const held = await fetchHeld([artifact(1, bytes(cdx(sn)))], { sbomMaxBytes: 100 });
        const result = await store(held.fetchId, 1);
        expect(result.stored).toBe('TOO_LARGE');
        expect(await chunkOk(held.fetchId, 1)).toBe(true);
        expect(await rowsOf(sn)).toHaveLength(0);
    });
});

