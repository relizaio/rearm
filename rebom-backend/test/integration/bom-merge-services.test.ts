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
import { DANGLING_REFS_DROPPED_PROPERTY } from '../../src/services/bom/bomProcessingService';
import { TEST_ORG_UUID, createTestRebomOptions, generateSerialNumber } from '../helpers';

/**
 * SCORE-14 (SCORE-9 T-4): an uploaded SBOM that lists sysvinit services and gives each a
 * dependencies[] entry (cdxgen's OS scan of an nginx image) reaches the stored merge with its
 * services, at the release level and again at the product level, so every dependency
 * reference resolves. Runs the real `rearm bomutils merge-boms`, which never reads services.
 */

const FIXTURES = path.join(__dirname, '..', 'fixtures', 'merge');
const NGINX = 'urn:service:sysvinit:pkg:apk-alpine-nginx-1.31.3-r1:nginx';
const NGINX_DEBUG = 'urn:service:sysvinit:pkg:apk-alpine-nginx-1.31.3-r1:nginx-debug';

function fixture(name: string): any {
    const bom = JSON.parse(fs.readFileSync(path.join(FIXTURES, name), 'utf8'));
    bom.serialNumber = generateSerialNumber();
    return bom;
}

function resolvable(bom: any): Set<string> {
    const refs = new Set<string>();
    const walk = (list: any[] = [], child: string) => list.forEach((c: any) => {
        if (c['bom-ref']) refs.add(c['bom-ref']);
        walk(c[child], child);
    });
    walk([bom.metadata.component], 'components');
    walk(bom.components, 'components');
    walk(bom.services, 'services');
    return refs;
}

function unresolved(bom: any): string[] {
    const refs = resolvable(bom);
    return (bom.dependencies || []).flatMap((d: any) => [d.ref, ...(d.dependsOn || []), ...(d.provides || [])])
        .filter((r: string) => !refs.has(r));
}

function serviceEntries(bom: any): string[] {
    return (bom.dependencies || []).map((d: any) => d.ref).filter((r: string) => r.startsWith('urn:service:'));
}

function property(bom: any, name: string): any[] {
    return (bom.metadata.properties || []).filter((p: any) => p.name === name);
}

describe('BOM merge with services the dependency entries name', () => {
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
                rebomOptions: createTestRebomOptions({
                    serialNumber: bom.serialNumber,
                    name: bom.metadata.component.name,
                    version: bom.metadata.component.version
                })
            }
        });
        createdUuids.push(record.uuid);
        return record.uuid;
    }

    async function merge(ids: string[], name: string, overrides: any = {}): Promise<{ id: string, bom: any }> {
        const record = await BomService.mergeAndStoreBoms(
            ids,
            createTestRebomOptions({ serialNumber: generateSerialNumber(), name, version: '1.0.0', mergeVersion: 3, ...overrides }),
            TEST_ORG_UUID,
            BomService.addBom
        );
        createdUuids.push(record.uuid);
        const bom = await BomService.findBomObjectById(record.uuid, TEST_ORG_UUID) as any;
        return { id: record.uuid, bom };
    }

    it('stores the services in the release merge and again in the product merge made of it', async () => {
        const nginx = fixture('nginx-services.input.cdx.json');
        const nginxId = await upload(nginx);
        const appId = await upload(fixture('app.input.cdx.json'));

        const release = await merge([nginxId, appId], 'release-merge');

        expect(serviceEntries(release.bom)).toStrictEqual([NGINX, NGINX_DEBUG]);
        expect(release.bom.services).toStrictEqual(nginx.services);
        expect(unresolved(release.bom)).toStrictEqual([]);
        expect(property(release.bom, DANGLING_REFS_DROPPED_PROPERTY)).toStrictEqual([]);

        const product = await merge([release.id], 'product-merge');

        expect(serviceEntries(product.bom)).toStrictEqual([NGINX, NGINX_DEBUG]);
        expect(product.bom.services).toStrictEqual(nginx.services);
        expect(unresolved(product.bom)).toStrictEqual([]);
        expect(property(product.bom, DANGLING_REFS_DROPPED_PROPERTY)).toStrictEqual([]);
    });

    it('keeps the top-level-only merge free of services and of unresolved refs', async () => {
        const nginxId = await upload(fixture('nginx-services.input.cdx.json'));
        const appId = await upload(fixture('app.input.cdx.json'));

        const { bom } = await merge([nginxId, appId], 'tld-merge', { tldOnly: true });

        expect(bom).not.toHaveProperty('services');
        expect(serviceEntries(bom)).toStrictEqual([]);
        expect(unresolved(bom)).toStrictEqual([]);
        expect(property(bom, DANGLING_REFS_DROPPED_PROPERTY)).toStrictEqual([]);
    });
});
