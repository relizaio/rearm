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
import { CREDENTIALS_STRIPPED_PROPERTY, FOLDED_ROOT_PROPERTY } from '../../src/services/bom/bomProcessingService';
import { TEST_ORG_UUID, createTestRebomOptions, generateSerialNumber } from '../helpers';

/**
 * FDAW-6 T13, T14 (architecture 3.5, B12): the manufacturer's own roots of a GlucoView-shaped
 * release and product carry the supplier, hashes and licence ReARM declares, and nothing is invented
 * where ReARM holds none. Also review F-13: a RELEASE-attached root whose purl carries a credential in
 * a URL qualifier still folds into the release node. Real `rearm bomutils merge-boms`.
 */

const FIXTURES = path.join(__dirname, '..', 'fixtures', 'merge');
const DIGEST_WEB = '1'.repeat(64);
const DIGEST_API = '2'.repeat(64);
const WEB_NODE = 'pkg:generic/Acme/glucoview-web@2.4.0';
const API_NODE = 'pkg:generic/Acme/glucoview-api@2.4.0';
const PRODUCT_NODE = 'pkg:generic/Acme/GlucoView@2.4.0';

function fixture(name: string): any {
    const bom = JSON.parse(fs.readFileSync(path.join(FIXTURES, name), 'utf8'));
    bom.serialNumber = generateSerialNumber();
    return bom;
}

function facts(digest: string, licenseExpression?: string): any {
    return { supplierName: 'Acme', hashes: [{ alg: 'SHA-256', content: digest }], ...(licenseExpression ? { licenseExpression } : {}) };
}

describe('the GlucoView roots carry the declared facts (FDAW-6 T13, T14)', () => {
    const created: string[] = [];

    beforeEach(() => {
        clearMockOciStorage();
    });

    afterEach(async () => {
        if (created.length > 0) {
            await pool.query('DELETE FROM rebom.boms WHERE uuid = ANY($1::uuid[])', [created]);
            created.length = 0;
        }
    });

    async function upload(bom: any, overrides: any = {}): Promise<string> {
        const stored = await BomService.addBom({
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({ serialNumber: bom.serialNumber, name: bom.metadata.component.name, group: 'Acme', version: '2.4.0', ...overrides })
            }
        });
        created.push(stored.uuid);
        return stored.uuid;
    }

    async function merge(ids: string[], overrides: any, extra: any = {}): Promise<{ uuid: string, bom: any }> {
        const stored = await BomService.mergeAndStoreBoms(ids, {
            ...createTestRebomOptions({ serialNumber: generateSerialNumber(), group: 'Acme', version: '2.4.0', ...overrides }),
            ...extra
        }, TEST_ORG_UUID, BomService.addBom);
        created.push(stored.uuid);
        return { uuid: stored.uuid, bom: await BomService.findBomObjectById(stored.uuid, TEST_ORG_UUID) };
    }

    /** The component release merge as rearm-saas asks for it: the upload attached to the release, listed. */
    async function componentRow(file: string, name: string, releaseNode: any, rootEdit?: (bom: any) => void) {
        const bom = fixture(file);
        if (rootEdit) rootEdit(bom);
        const id = await upload(bom, { belongsTo: 'RELEASE' });
        return merge([id], { name, rootComponentMergeMode: 'FLATTEN_UNDER_NEW_ROOT' }, { releaseRootIds: [id], ...(releaseNode ? { releaseNode } : {}) });
    }

    it('T13: the release node of a bare upload gets supplier, hashes and licence from the facts', async () => {
        const { bom } = await componentRow('glucoview-api.input.cdx.json', 'glucoview-api', facts(DIGEST_API, 'Apache-2.0'));
        const root = bom.metadata.component;
        expect(root['bom-ref']).toBe(API_NODE);
        expect(root.supplier).toStrictEqual({ name: 'Acme' });
        expect(root.hashes).toStrictEqual([{ alg: 'SHA-256', content: DIGEST_API }]);
        expect(root.licenses).toStrictEqual([{ license: { id: 'Apache-2.0' } }]);
        // the uploaded root folded into it
        expect((root.properties || []).some((p: any) => p.name === FOLDED_ROOT_PROPERTY)).toBe(true);
    });

    it('T13: a RELEASE-attached root carrying its own licence keeps the upload licence, the hashes are the facts', async () => {
        const { bom } = await componentRow('glucoview-web.input.cdx.json', 'glucoview-web', facts(DIGEST_WEB, 'Apache-2.0'), (b) => {
            b.metadata.component.licenses = [{ license: { name: 'LicenseRef-GlucoView-Proprietary' } }];
        });
        const root = bom.metadata.component;
        expect(root.licenses).toStrictEqual([{ license: { name: 'LicenseRef-GlucoView-Proprietary' } }]);
        expect(root.hashes).toStrictEqual([{ alg: 'SHA-256', content: DIGEST_WEB }]);
        expect(root.supplier).toStrictEqual({ name: 'Acme' });
    });

    it('F-13: a RELEASE-attached root whose purl carries a credential in vcs_url still folds into the release node', async () => {
        const credentialed = 'pkg:npm/acme/glucoview-web@2.4.0?vcs_url=https%3A%2F%2Fu%3Ap%40h.example%2Fr.git';
        const { bom } = await componentRow('glucoview-web.input.cdx.json', 'glucoview-web', facts(DIGEST_WEB), (b) => {
            const old = b.metadata.component['bom-ref'];
            b.metadata.component.purl = credentialed;
            b.metadata.component['bom-ref'] = credentialed;
            for (const d of b.dependencies) if (d.ref === old) d.ref = credentialed;
        });
        expect(bom.metadata.component['bom-ref']).toBe(WEB_NODE);
        const folded = (bom.metadata.component.properties || []).filter((p: any) => p.name === FOLDED_ROOT_PROPERTY).map((p: any) => p.value);
        expect(folded).toStrictEqual(['pkg:npm/acme/glucoview-web@2.4.0?belongsto=RELEASE&vcs_url=https%3A%2F%2Fh.example%2Fr.git']);
        expect(bom.components.some((c: any) => c.name === 'glucoview-web')).toBe(false);
        expect(JSON.stringify(bom)).not.toContain('u%3Ap%40');
        // the fixture's two URL credentials and the root's purl
        expect((bom.metadata.properties || []).find((p: any) => p.name === CREDENTIALS_STRIPPED_PROPERTY).value).toBe('3');
    });

    it('T14: a product merge sends the product facts only; each component root keeps what its own row has; nothing is invented', async () => {
        const web = await componentRow('glucoview-web.input.cdx.json', 'glucoview-web', facts(DIGEST_WEB, 'LicenseRef-GlucoView-Proprietary'));
        const api = await componentRow('glucoview-api.input.cdx.json', 'glucoview-api', null);

        const product = await merge([web.uuid, api.uuid], { name: 'GlucoView', rootComponentMergeMode: 'PRESERVE_UNDER_NEW_ROOT' },
            { releaseNode: { supplierName: 'Acme', licenseExpression: 'LicenseRef-GlucoView-Proprietary' } });
        const node = (ref: string) => product.bom.components.find((c: any) => c['bom-ref'] === ref);

        expect(product.bom.metadata.component['bom-ref']).toBe(PRODUCT_NODE);
        expect(product.bom.metadata.component.supplier).toStrictEqual({ name: 'Acme' });
        expect(product.bom.metadata.component).not.toHaveProperty('hashes');
        expect(product.bom.metadata.component.licenses).toStrictEqual(web.bom.metadata.component.licenses);

        expect(node(WEB_NODE).supplier).toStrictEqual({ name: 'Acme' });
        expect(node(WEB_NODE).hashes).toStrictEqual([{ alg: 'SHA-256', content: DIGEST_WEB }]);
        expect(node(WEB_NODE).licenses).toStrictEqual(web.bom.metadata.component.licenses);

        // the api row was built without facts: no supplier, no hashes, no licence on its node
        expect(node(API_NODE)).toBeDefined();
        expect(node(API_NODE)).not.toHaveProperty('hashes');
        expect(node(API_NODE)).not.toHaveProperty('licenses');
        expect(node(API_NODE)).not.toHaveProperty('supplier');
    });
});
