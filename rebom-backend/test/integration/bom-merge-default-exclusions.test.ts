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
import validateBom from '../../src/validateBom';
import { BomMapper } from '../../src/services/bom/bomMapper';
import { findBomObjectWithMetaById } from '../../src/services/bom/bomCrudService';
import {
    ADDRESS_PATTERN,
    PERSONAL_DATA_PROPERTY_NAMES,
    LOCAL_PATH_PROPERTY_NAMES,
    MERGE_COUNT_PROPERTIES,
    DEV_COMPONENTS_EXCLUDED_PROPERTY,
    EXCLUDED_SCOPE_DROPPED_PROPERTY,
    PERSONAL_DATA_STRIPPED_PROPERTY,
    LOCAL_PATHS_STRIPPED_PROPERTY,
    CREDENTIALS_STRIPPED_PROPERTY,
    ORPHAN_COMPONENTS_LINKED_PROPERTY,
    OPTIONAL_SCOPE_DROPPED_PROPERTY,
    FILE_COMPONENTS_EXCLUDED_PROPERTY,
    carriesUrlCredential,
    isRearmToolEntry
} from '../../src/services/bom/bomProcessingService';
import { TEST_ORG_UUID, createTestRebomOptions, generateSerialNumber } from '../helpers';

/**
 * FDAW-6 T8, T9 (architecture 3.1 to 3.3): the default merge of the GlucoView web input drops the
 * generator-marked dev components and the excluded scope, strips personal data, local paths and URL
 * credentials, and records the six counts. Runs the real `rearm bomutils merge-boms`.
 */

const FIXTURES = path.join(__dirname, '..', 'fixtures', 'merge');
const DEV = 'cdx:npm:package:development';
const RUNTIME = ['pkg:npm/glob@7.2.3', 'pkg:npm/@babel/code-frame@7.29.7', 'pkg:npm/@org/pkg@1.0.0'];
const WEB_ROOT = 'pkg:generic/Acme/glucoview-web@2.4.0';
/** The FDAW-6 records; the seventh is written only under ignoreDev. */
const SIX_RECORDS = MERGE_COUNT_PROPERTIES.filter(n => n !== OPTIONAL_SCOPE_DROPPED_PROPERTY);
const OPTIONAL_RUNTIME = 'optional-runtime.input.cdx.json';
const OPTIONAL_REF = 'pkg:npm/optional-plugin@3.0.0';
const NESTED_REF = 'pkg:npm/optional-plugin-core@3.0.0';
const EXCLUDED_REF = 'pkg:npm/excluded-typings@4.0.0';
const MAINTAINED_REF = 'pkg:npm/maintained@5.0.0';
const CREDENTIAL_FIELDS = 5;

function fixture(name: string): any {
    const bom = JSON.parse(fs.readFileSync(path.join(FIXTURES, name), 'utf8'));
    bom.serialNumber = generateSerialNumber();
    return bom;
}

function allComponents(components: any[] = []): any[] {
    return components.flatMap((c: any) => [c, ...allComponents(c.components)]);
}

function allServices(services: any[] = []): any[] {
    return services.flatMap((s: any) => [s, ...allServices(s.services)]);
}

function record(bom: any, name: string): string | undefined {
    return (bom.metadata.properties || []).find((p: any) => p.name === name)?.value;
}

/** Every node the strip walks: the root, the components at any depth, the tools but ReARM's own entry. */
function strippedNodes(bom: any): any[] {
    const tools = (bom.metadata.tools?.components || []).filter((t: any) => !isRearmToolEntry(t));
    return [bom.metadata.component, ...allComponents(bom.components), ...tools];
}

describe('default merge: dev tools, excluded scope, personal data, paths and credentials out (FDAW-6 T8, T9)', () => {
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
        const record = await BomService.addBom({
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({ serialNumber: bom.serialNumber, name: bom.metadata.component.name, version: bom.metadata.component.version })
            }
        });
        createdUuids.push(record.uuid);
        return record.uuid;
    }

    async function mergeRow(ids: string[], overrides: any = {}): Promise<{ row: any, bom: any }> {
        const row = await BomService.mergeAndStoreBoms(
            ids,
            createTestRebomOptions({ serialNumber: generateSerialNumber(), name: 'merged', group: 'Example', version: '1.0.0', ...overrides }),
            TEST_ORG_UUID,
            BomService.addBom
        );
        createdUuids.push(row.uuid);
        return { row, bom: await BomService.findBomObjectById(row.uuid, TEST_ORG_UUID) };
    }

    async function merge(ids: string[], overrides: any = {}): Promise<any> {
        return (await mergeRow(ids, overrides)).bom;
    }

    it('T8: the default merge of the web input', async () => {
        const bom = await merge([await upload(fixture('glucoview-web.input.cdx.json'))]);
        const components = allComponents(bom.components);

        // B6, B10
        expect(components.some((c: any) => (c.properties || []).some((p: any) => p.name === DEV && p.value === 'true'))).toBe(false);
        expect(components.some((c: any) => c.scope === 'excluded')).toBe(false);
        // the three runtime components and the uploaded root, which survives the merge as a top-level node
        expect(bom.components.map((c: any) => c['bom-ref']).sort()).toStrictEqual([...RUNTIME, WEB_ROOT].sort());

        // B9: names, address shape, credentials
        const nodes = strippedNodes(bom);
        const props = nodes.flatMap((n: any) => n.properties || []);
        expect(props.filter((p: any) => PERSONAL_DATA_PROPERTY_NAMES.has(p.name) || LOCAL_PATH_PROPERTY_NAMES.has(p.name))).toStrictEqual([]);
        expect(props.filter((p: any) => ADDRESS_PATTERN.test(String(p.value)))).toStrictEqual([]);
        for (const n of nodes) {
            for (const field of ['author', 'publisher']) if (n[field]) expect(ADDRESS_PATTERN.test(n[field])).toBe(false);
            for (const a of n.authors || []) expect(a).not.toHaveProperty('email');
            for (const c of n.supplier?.contact || []) expect(c).not.toHaveProperty('email');
        }
        for (const s of allServices(bom.services)) for (const c of s.provider?.contact || []) expect(c).not.toHaveProperty('email');
        const urls = [
            ...nodes.flatMap((n: any) => (n.externalReferences || []).map((e: any) => e.url)),
            ...props.map((p: any) => p.value),
            ...allServices(bom.services).flatMap((s: any) => s.endpoints || [])
        ];
        expect(urls.filter(carriesUrlCredential)).toStrictEqual([]);

        const byRef = (ref: string) => bom.components.find((c: any) => c['bom-ref'] === ref);
        const value = (ref: string, name: string) => (byRef(ref).properties || []).filter((p: any) => p.name === name).map((p: any) => p.value);
        expect(value('pkg:npm/@org/pkg@1.0.0', 'ResolvedUrl')).toStrictEqual(['https://npm.pkg.github.com/@org/pkg/-/pkg-1.0.0.tgz']);
        expect(byRef('pkg:npm/@babel/code-frame@7.29.7').externalReferences.filter((e: any) => e.type === 'vcs').map((e: any) => e.url))
            .toStrictEqual(['https://git.example.com/org/repo.git']);
        expect(value('pkg:npm/glob@7.2.3', 'cdx:npm:deprecated')).toStrictEqual([]);
        expect(value('pkg:npm/@babel/code-frame@7.29.7', 'ResolvedUrl')).toStrictEqual(['https://registry.npmjs.org/@babel/code-frame/-/code-frame-7.29.7.tgz']);
        expect(value('pkg:npm/glob@7.2.3', 'cdx:foo:repository')).toStrictEqual(['git+ssh://git@github.com/org/repo.git']);
        expect(value('pkg:npm/glob@7.2.3', 'SrcFile')).toStrictEqual(['package-lock.json']);
        expect(byRef('pkg:npm/glob@7.2.3').author).toBe('Sindre Sorhus (https://sindresorhus.com)');
        expect(byRef('pkg:npm/@babel/code-frame@7.29.7').authors).toStrictEqual([{ name: 'The Babel Team' }]);

        // the six FDAW-6 records, by rule: 12 dev-marked nodes, 2 excluded typings; personal data 18 (glob: 5 names,
        // the deprecation notice, the author edit; code-frame: 5 names, the author e-mail; @org/pkg: 5 names);
        // local paths 3 (the three LocalNodeModulesPath values); credentials 2 (the token ResolvedUrl, the vcs URL)
        expect(record(bom, OPTIONAL_SCOPE_DROPPED_PROPERTY)).toBeUndefined();
        expect(Object.fromEntries(SIX_RECORDS.map(n => [n, record(bom, n)]))).toStrictEqual({
            [DEV_COMPONENTS_EXCLUDED_PROPERTY]: '12',
            [EXCLUDED_SCOPE_DROPPED_PROPERTY]: '2',
            [PERSONAL_DATA_STRIPPED_PROPERTY]: '18',
            [LOCAL_PATHS_STRIPPED_PROPERTY]: '3',
            [CREDENTIALS_STRIPPED_PROPERTY]: '2',
            [ORPHAN_COMPONENTS_LINKED_PROPERTY]: '0'
        });
        await expect(validateBom(bom)).resolves.toBeTruthy();
    });

    it('T9: under ignoreDev the component set is the same (the fixture has no optional node without a dev marker)', async () => {
        const id = await upload(fixture('glucoview-web.input.cdx.json'));
        const byDefault = await merge([id]);
        const ignoreDev = await merge([id], { ignoreDev: true });
        const refs = (b: any) => allComponents(b.components).map((c: any) => c['bom-ref']).sort();
        expect(refs(ignoreDev)).toStrictEqual(refs(byDefault));
        expect(record(ignoreDev, DEV_COMPONENTS_EXCLUDED_PROPERTY)).toBe('12');
        expect(record(ignoreDev, OPTIONAL_SCOPE_DROPPED_PROPERTY)).toBe('0');
    });

    describe('the keep options end to end (FDAW-19 row 5)', () => {
        const refs = (bom: any) => allComponents(bom.components).map((c: any) => c['bom-ref']);
        const dependencyRefs = (bom: any) => bom.dependencies.flatMap((d: any) => [d.ref, ...(d.dependsOn || []), ...(d.provides || [])]);
        const propertiesOf = (bom: any, ref: string) => allComponents(bom.components).find((c: any) => c['bom-ref'] === ref).properties || [];
        const uploadWith = (...entries: [string, string][]) => {
            const bom = fixture(OPTIONAL_RUNTIME);
            bom.metadata.properties = entries.map(([name, value]) => ({ name, value }));
            return upload(bom);
        };

        it('the default merge keeps the optional node, drops the excluded one and writes the six records', async () => {
            const bom = await merge([await upload(fixture(OPTIONAL_RUNTIME))]);
            expect(refs(bom)).toEqual(expect.arrayContaining([OPTIONAL_REF, NESTED_REF]));
            expect(refs(bom)).not.toContain(EXCLUDED_REF);
            expect(record(bom, OPTIONAL_SCOPE_DROPPED_PROPERTY)).toBeUndefined();
            for (const name of SIX_RECORDS) expect(record(bom, name)).toMatch(/^\d+$/);
            expect(record(bom, EXCLUDED_SCOPE_DROPPED_PROPERTY)).toBe('1');
        });

        it('ignoreDev takes the optional node and its nested component out and counts them, leaving no dangling ref', async () => {
            const bom = await merge([await upload(fixture(OPTIONAL_RUNTIME))], { ignoreDev: true });
            expect(refs(bom)).not.toContain(OPTIONAL_REF);
            expect(refs(bom)).not.toContain(NESTED_REF);
            expect(record(bom, OPTIONAL_SCOPE_DROPPED_PROPERTY)).toBe('2');
            expect(dependencyRefs(bom)).not.toContain(OPTIONAL_REF);
            expect(dependencyRefs(bom)).not.toContain(NESTED_REF);
            await expect(validateBom(bom)).resolves.toBeTruthy();
        });

        it('keepExcludedScope keeps the excluded node and writes no excludedScopeDropped', async () => {
            const { row, bom } = await mergeRow([await upload(fixture(OPTIONAL_RUNTIME))], { keepExcludedScope: true });
            expect(refs(bom)).toContain(EXCLUDED_REF);
            expect(record(bom, EXCLUDED_SCOPE_DROPPED_PROPERTY)).toBeUndefined();
            expect(row.meta.keepExcludedScope).toBe(true);
            expect(row.meta.keepPersonalData).toBeUndefined();
        });

        it('keepPersonalData and keepLocalPaths keep both groups, write neither record, and every S10 field loses its userinfo (F-9)', async () => {
            const { row, bom } = await mergeRow([await upload(fixture(OPTIONAL_RUNTIME))], { keepPersonalData: true, keepLocalPaths: true });

            const names = propertiesOf(bom, MAINTAINED_REF).map((p: any) => p.name);
            expect(names).toEqual(expect.arrayContaining(['cdx:npm:maintainerSet', 'cdx:npm:binPaths']));
            expect(allComponents(bom.components).find((c: any) => c['bom-ref'] === MAINTAINED_REF).author).toBe('Jane Doe <jane.doe@example.com>');
            expect(record(bom, PERSONAL_DATA_STRIPPED_PROPERTY)).toBeUndefined();
            expect(record(bom, LOCAL_PATHS_STRIPPED_PROPERTY)).toBeUndefined();

            const byRef = (ref: string) => allComponents(bom.components).find((c: any) => c['bom-ref'] === ref);
            const one = byRef('pkg:npm/required-one@1.0.0');
            const two = byRef('pkg:npm/required-two@2.0.0');
            expect(one.properties.find((p: any) => p.name === 'ResolvedUrl').value).toBe('https://registry.example.com/required-one-1.0.0.tgz');
            expect(one.externalReferences[0].url).toBe('https://git.example.com/required-one.git');
            expect(two.licenses[0].license.url).toBe('https://licences.example.com/mit');
            expect(two.supplier.url).toStrictEqual(['https://supplier.example.com/']);
            expect(bom.services.find((s: any) => s.name === 'dose-api').endpoints).toStrictEqual(['https://api.example.com/v1']);
            expect(JSON.stringify(bom)).not.toContain('fixture-token');
            expect(Number(record(bom, CREDENTIALS_STRIPPED_PROPERTY))).toBeGreaterThanOrEqual(CREDENTIAL_FIELDS);

            expect(row.meta).toMatchObject({ keepPersonalData: true, keepLocalPaths: true });
            const dto = BomMapper.toMetaDto(row);
            expect([dto.keepExcludedScope, dto.keepPersonalData, dto.keepLocalPaths]).toStrictEqual([undefined, true, true]);
        });

        it('the stored row echoes the three booleans, and a merge without them stores none', async () => {
            const id = await upload(fixture(OPTIONAL_RUNTIME));
            const all = await mergeRow([id], { keepExcludedScope: true, keepPersonalData: true, keepLocalPaths: false });
            const stored = await findBomObjectWithMetaById(all.row.uuid, TEST_ORG_UUID);
            expect(stored.meta).toMatchObject({ keepExcludedScope: true, keepPersonalData: true, keepLocalPaths: false });
            const dto = BomMapper.toMetaDto({ ...all.row, meta: stored.meta });
            expect([dto.keepExcludedScope, dto.keepPersonalData, dto.keepLocalPaths]).toStrictEqual([true, true, false]);

            const none = await mergeRow([id]);
            const storedNone = await findBomObjectWithMetaById(none.row.uuid, TEST_ORG_UUID);
            for (const key of ['keepExcludedScope', 'keepPersonalData', 'keepLocalPaths']) expect(storedNone.meta).not.toHaveProperty(key);
        });

        it('an upload cannot set the counts: the merge writes its own, the stored row keeps no forged value (F-10)', async () => {
            const baseline = await merge([await upload(fixture(OPTIONAL_RUNTIME))], { ignoreDev: true });
            const id = await uploadWith([OPTIONAL_SCOPE_DROPPED_PROPERTY, '500'], [CREDENTIALS_STRIPPED_PROPERTY, '7']);

            const { row, bom } = await mergeRow([id], { ignoreDev: true });

            expect(record(bom, OPTIONAL_SCOPE_DROPPED_PROPERTY)).toBe('2');
            expect(record(bom, CREDENTIALS_STRIPPED_PROPERTY)).toBe(record(baseline, CREDENTIALS_STRIPPED_PROPERTY));
            const stored: any = await BomService.findBomObjectById(row.uuid, TEST_ORG_UUID);
            expect(JSON.stringify(stored.metadata.properties)).not.toContain('"500"');
            expect(stored.metadata.properties.filter((p: any) => p.name === OPTIONAL_SCOPE_DROPPED_PROPERTY)).toHaveLength(1);
        });

        it('a product merge over merge rows sums the rows values (F-10)', async () => {
            const forged: [string, string][] = [[OPTIONAL_SCOPE_DROPPED_PROPERTY, '500'], [CREDENTIALS_STRIPPED_PROPERTY, '7']];
            const one = await mergeRow([await uploadWith(...forged)], { name: 'row-one', ignoreDev: true });
            const two = await mergeRow([await upload(fixture(OPTIONAL_RUNTIME))], { name: 'row-two', ignoreDev: true });

            const product = await merge([one.row.uuid, two.row.uuid], { name: 'product', ignoreDev: true, rootComponentMergeMode: 'PRESERVE_UNDER_NEW_ROOT' });

            expect(record(product, OPTIONAL_SCOPE_DROPPED_PROPERTY)).toBe('4');
            expect(record(product, CREDENTIALS_STRIPPED_PROPERTY))
                .toBe(String(Number(record(one.bom, CREDENTIALS_STRIPPED_PROPERTY)) + Number(record(two.bom, CREDENTIALS_STRIPPED_PROPERTY))));
        });

        it('an upload carrying fileComponentsExcluded "9" records only what this merge removed (F-10)', async () => {
            const bom = fixture(OPTIONAL_RUNTIME);
            bom.metadata.properties = [{ name: FILE_COMPONENTS_EXCLUDED_PROPERTY, value: '9' }];
            bom.components.push({ type: 'file', name: 'conf', 'bom-ref': 'file-conf' });

            const merged = await merge([await upload(bom)], { excludeFileComponents: true });

            expect(merged.metadata.properties.filter((p: any) => p.name === FILE_COMPONENTS_EXCLUDED_PROPERTY))
                .toStrictEqual([{ name: FILE_COMPONENTS_EXCLUDED_PROPERTY, value: '1' }]);
        });
    });
});
