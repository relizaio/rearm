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

    async function merge(ids: string[], overrides: any = {}): Promise<any> {
        const stored = await BomService.mergeAndStoreBoms(
            ids,
            createTestRebomOptions({ serialNumber: generateSerialNumber(), name: 'merged', group: 'Example', version: '1.0.0', ...overrides }),
            TEST_ORG_UUID,
            BomService.addBom
        );
        createdUuids.push(stored.uuid);
        return BomService.findBomObjectById(stored.uuid, TEST_ORG_UUID);
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

        // the six records, by rule: 12 dev-marked nodes, 2 excluded typings; personal data 18 (glob: 5 names,
        // the deprecation notice, the author edit; code-frame: 5 names, the author e-mail; @org/pkg: 5 names);
        // local paths 3 (the three LocalNodeModulesPath values); credentials 2 (the token ResolvedUrl, the vcs URL)
        expect(Object.fromEntries(MERGE_COUNT_PROPERTIES.map(n => [n, record(bom, n)]))).toStrictEqual({
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
    });
});
