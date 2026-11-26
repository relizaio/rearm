import { afterEach, describe, expect, it, vi } from 'vitest';

// T-64: the lineage repository, the rows and the transaction are mocked, so the second check (under
// the lineage lock) is the only one that can see the stored row.
const txClient = vi.hoisted(() => ({ query: vi.fn() }));

vi.mock('../../src/integrationRepository', () => ({
    findIntegrationByTypeAndOrg: vi.fn().mockResolvedValue(null),
    upsertIntegration: vi.fn(),
    createSecret: vi.fn(),
    findSecretById: vi.fn(),
    updateSecret: vi.fn(),
    deleteSecret: vi.fn(),
    deleteIntegration: vi.fn(),
}));

vi.mock('../../src/bomRepository', async (importOriginal) => ({
    ...(await importOriginal<typeof import('../../src/bomRepository')>()),
    allBomsBySerialNumber: vi.fn().mockResolvedValue([])
}));

vi.mock('../../src/bomLineageRepository', async (importOriginal) => ({
    ...(await importOriginal<typeof import('../../src/bomLineageRepository')>()),
    lockLineage: vi.fn().mockResolvedValue(undefined),
    latestStoredVersion: vi.fn()
}));

vi.mock('../../src/utils', async (importOriginal) => ({
    ...(await importOriginal<typeof import('../../src/utils')>()),
    withTransaction: vi.fn(async (fn: (client: unknown) => unknown) => fn(txClient))
}));

vi.mock('../../src/services/bom/bomProcessingService', async (importOriginal) => ({
    ...(await importOriginal<typeof import('../../src/services/bom/bomProcessingService')>()),
    enrichBomAsync: vi.fn().mockResolvedValue(undefined)
}));

import * as BomLineageRepository from '../../src/bomLineageRepository';
import { addBomDetailed, ANY_LINEAGE, LineagePolicyKind } from '../../src/services/bom/bomAddService';
import { BomLineageNotPermittedError } from '../../src/types/errors';
import { generateSerialNumber, loadFixture, TEST_ORG_UUID } from '../helpers';

/**
 * The lineage rule is checked again under the lineage lock (TPF-180 design 3.6.4; row T-64).
 */

function input(serialNumber: string, version: number): any {
    const bom = loadFixture('cyclonedx-simple.json');
    bom.serialNumber = serialNumber;
    bom.version = version;
    return { bomInput: { format: 'CYCLONEDX', bom, org: TEST_ORG_UUID, rebomOptions: { name: 'c', group: 'g', version: '1' } } };
}

describe('TEA lineage rule under the lock', () => {
    afterEach(() => {
        vi.clearAllMocks();
    });

    it('T-64 no lineage at the first check, a row after the lock: refused under NEW_OR_NAMED, inserted as before under ANY', async () => {
        const sn = generateSerialNumber();
        const existing = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
        vi.mocked(BomLineageRepository.latestStoredVersion).mockResolvedValue({ uuid: existing, bom_version: '1' });

        const refused = await addBomDetailed(input(sn, 2), 'UPLOAD', { kind: LineagePolicyKind.NEW_OR_NAMED, named: null }).catch((e) => e);
        expect(refused).toBeInstanceOf(BomLineageNotPermittedError);
        expect(refused.serialNumber).toBe(sn);
        expect(refused.existingUuid).toBe(existing);
        expect(BomLineageRepository.lockLineage).toHaveBeenCalledTimes(1);
        expect(txClient.query).not.toHaveBeenCalled();

        txClient.query.mockResolvedValue({ rows: [{ uuid: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', meta: {} }] });
        const named = await addBomDetailed(input(sn, 2), 'UPLOAD', { kind: LineagePolicyKind.NEW_OR_NAMED, named: sn.replace('urn:uuid:', '') });
        expect(named.disposition).toBe('CREATED');

        txClient.query.mockClear();
        const any = await addBomDetailed(input(sn, 2), 'UPLOAD', ANY_LINEAGE);
        expect(any.disposition).toBe('CREATED');
        expect(txClient.query).toHaveBeenCalledTimes(1);
    });
});
