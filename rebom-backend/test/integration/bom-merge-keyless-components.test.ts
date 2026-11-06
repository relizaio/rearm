import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';

// Mock integrationRepository before importing bomService (table may not exist in test DB)
vi.mock('../../src/integrationRepository', () => ({
    findIntegrationByTypeAndOrg: vi.fn().mockResolvedValue(null),
    upsertIntegration: vi.fn(),
    createSecret: vi.fn(),
    findSecretById: vi.fn(),
    updateSecret: vi.fn(),
    deleteSecret: vi.fn(),
    deleteIntegration: vi.fn(),
}));

import * as BomService from '../../src/bomService';
import { pool } from '../../src/utils';
import { clearMockOciStorage } from '../../src/services/oci';
import { scoreBom } from '../../src/services/bom/bomScoreService';
import {
    MERGE_COUNT_PROPERTIES,
    DEV_COMPONENTS_EXCLUDED_PROPERTY,
    EXCLUDED_SCOPE_DROPPED_PROPERTY,
    PERSONAL_DATA_STRIPPED_PROPERTY,
    LOCAL_PATHS_STRIPPED_PROPERTY,
    CREDENTIALS_STRIPPED_PROPERTY,
    ORPHAN_COMPONENTS_LINKED_PROPERTY,
    DANGLING_REFS_DROPPED_PROPERTY,
    countOf
} from '../../src/services/bom/bomProcessingService';
import { TEST_ORG_UUID, createTestRebomOptions, generateSerialNumber } from '../helpers';

/**
 * FDAW-6 T10, T11 (architecture 3.4): components without a purl stay in the merged graph, keyless
 * nodes get a bom-ref and fold by name and version, and a product merge inherits the six counts. Runs
 * the real `rearm bomutils merge-boms` and `rearm bomutils score`.
 */

const FIXTURES = path.join(__dirname, '..', 'fixtures', 'merge');
const PYPI = ['black', 'cryptography', 'fastapi', 'numpy', 'pydantic', 'pytest', 'python-jose', 'six', 'sqlalchemy', 'uvicorn'];
const PRODUCT_PURL = 'pkg:generic/Example/GlucoView@2.4.0';

function fixture(name: string): any {
    const bom = JSON.parse(fs.readFileSync(path.join(FIXTURES, name), 'utf8'));
    bom.serialNumber = generateSerialNumber();
    return bom;
}

function allComponents(components: any[] = []): any[] {
    return components.flatMap((c: any) => [c, ...allComponents(c.components)]);
}

function record(bom: any, name: string): string | undefined {
    return (bom.metadata.properties || []).find((p: any) => p.name === name)?.value;
}

function rootEntry(bom: any): any {
    return bom.dependencies.find((d: any) => d.ref === bom.metadata.component['bom-ref']);
}

describe('default merge keeps keyless components in the graph (FDAW-6 T10, T11)', () => {
    const createdUuids: string[] = [];

    beforeEach(() => {
        clearMockOciStorage();
    });

    afterEach(async () => {
        if (createdUuids.length > 0) {
            await pool.query('DELETE FROM rebom.boms WHERE uuid = ANY($1::uuid[])', [createdUuids]);
            createdUuids.length = 0;
        }
    });

    async function upload(bom: any): Promise<string> {
        const stored = await BomService.addBom({
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({ serialNumber: bom.serialNumber, name: bom.metadata.component.name, version: bom.metadata.component.version })
            }
        });
        createdUuids.push(stored.uuid);
        return stored.uuid;
    }

    async function merge(ids: string[], overrides: any): Promise<{ uuid: string, bom: any }> {
        const stored = await BomService.mergeAndStoreBoms(
            ids,
            createTestRebomOptions({ serialNumber: generateSerialNumber(), version: '2.4.0', group: 'Acme', ...overrides }),
            TEST_ORG_UUID,
            BomService.addBom
        );
        createdUuids.push(stored.uuid);
        return { uuid: stored.uuid, bom: await BomService.findBomObjectById(stored.uuid, TEST_ORG_UUID) };
    }

    it('T10: the api input alone: keyless nodes stay and are in the graph, the strip reaches the apk publisher and the service', async () => {
        const { bom } = await merge([await upload(fixture('glucoview-api.input.cdx.json'))], { name: 'glucoview-api-release' });
        const top = bom.components;
        const refs = top.map((c: any) => c['bom-ref']);
        const dependsOn: string[] = rootEntry(bom).dependsOn;

        const lib = top.filter((c: any) => c.name === 'libdosecalc');
        expect(lib).toHaveLength(1);
        expect(lib[0]['bom-ref']).toBe('libdosecalc@3.1');
        expect(lib[0]).not.toHaveProperty('purl');
        expect(dependsOn).toContain('libdosecalc@3.1');

        // the two keyless copies folded into one, the first copy's group, with the assigned ref
        const tables = allComponents(top).filter((c: any) => c.name === 'dosetables');
        expect(tables).toHaveLength(1);
        expect(tables[0]).toMatchObject({ 'bom-ref': 'dosetables@1.0', group: 'vendor-a' });
        expect(dependsOn).toContain('dosetables@1.0');
        expect(JSON.stringify(bom)).not.toContain('#2');

        for (const name of PYPI) expect(dependsOn).toContain(top.find((c: any) => c.name.toLowerCase() === name)['bom-ref']);
        expect(dependsOn).toContain('pkg:apk/alpine/musl@1.2.4?distro=alpine-3.19');
        expect(refs).toContain('pkg:generic/Acme/glucoview-api@2.4.0');

        // the strip: the apk publisher, the service contact and endpoint, the file: reference, the author names
        const musl = top.find((c: any) => c.name === 'musl');
        expect(musl.publisher).toBe('Natanael Copa');
        const service = bom.services.find((s: any) => s.name === 'dose-api');
        expect(service.provider).toStrictEqual({ name: 'Example Corp', contact: [{ name: 'Ops' }] });
        expect(service.endpoints).toStrictEqual(['https://api.example.com/v1', 'https://api.example.com/v2']);
        const numpy = top.find((c: any) => c.name === 'numpy');
        expect(numpy.externalReferences.some((e: any) => String(e.url).startsWith('file:'))).toBe(false);
        expect(top.find((c: any) => c.name === 'black').authors).toStrictEqual([{ name: '\u0141ukasz Langa' }]);

        // the six records, by rule: orphans 13 (the 10 PyPI nodes, libdosecalc, dosetables, musl: the input
        // has no dependencies section; the uploaded root is linked by linkUnfoldedRoots, not counted);
        // personal data 22 (the musl publisher edit, the provider contact e-mail and phone, 19 author
        // names carrying an address); local paths 1 (the file: reference); credentials 1 (the endpoint)
        expect(Object.fromEntries(MERGE_COUNT_PROPERTIES.map(n => [n, record(bom, n)]))).toStrictEqual({
            [DEV_COMPONENTS_EXCLUDED_PROPERTY]: '0',
            [EXCLUDED_SCOPE_DROPPED_PROPERTY]: '0',
            [PERSONAL_DATA_STRIPPED_PROPERTY]: '22',
            [LOCAL_PATHS_STRIPPED_PROPERTY]: '1',
            [CREDENTIALS_STRIPPED_PROPERTY]: '1',
            [ORPHAN_COMPONENTS_LINKED_PROPERTY]: '13'
        });
        expect(record(bom, DANGLING_REFS_DROPPED_PROPERTY) ?? '0').toBe('0');

        const report = JSON.parse(await scoreBom(JSON.stringify(bom), ['fda']));
        const noOrphans = report.structure.checks.find((c: any) => c.id === 'structure.no-orphans');
        expect(noOrphans.status).toBe('PASS');
    });

    it('T11: a product merge of the two component rows keeps the vendored node, drops no more, and sums the counts', async () => {
        const web = await merge([await upload(fixture('glucoview-web.input.cdx.json'))], { name: 'glucoview-web-release' });
        const api = await merge([await upload(fixture('glucoview-api.input.cdx.json'))], { name: 'glucoview-api-release' });

        const product = await merge([web.uuid, api.uuid], {
            name: 'GlucoView', group: 'Example', purl: PRODUCT_PURL, rootComponentMergeMode: 'PRESERVE_UNDER_NEW_ROOT'
        });
        const all = allComponents(product.bom.components);
        const lib = all.filter((c: any) => c.name === 'libdosecalc');
        expect(lib).toHaveLength(1);
        // under glucoview-api's node: the api row's root names it
        const apiRoot = product.bom.components.find((c: any) => c.name === 'glucoview-api-release');
        expect(apiRoot).toBeDefined();
        const apiEntry = product.bom.dependencies.find((d: any) => d.ref === apiRoot['bom-ref']);
        expect(apiEntry.dependsOn).toContain(lib[0]['bom-ref']);
        expect(all.some((c: any) => c.scope === 'excluded' || (c.properties || []).some((p: any) => p.name === 'cdx:npm:package:development' && p.value === 'true'))).toBe(false);

        // the product merge itself drops, strips and links nothing: every count is the two rows' sum
        expect(Object.fromEntries(MERGE_COUNT_PROPERTIES.map(n => [n, record(product.bom, n)]))).toStrictEqual(
            Object.fromEntries(MERGE_COUNT_PROPERTIES.map(n => [n, String(countOf(web.bom, n) + countOf(api.bom, n))])));
        expect(record(product.bom, DEV_COMPONENTS_EXCLUDED_PROPERTY)).toBe('12');
        expect(record(product.bom, EXCLUDED_SCOPE_DROPPED_PROPERTY)).toBe('2');
    });
});
