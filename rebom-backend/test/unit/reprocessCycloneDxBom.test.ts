import { describe, it, expect, vi, beforeEach } from 'vitest';

// The forced re-enrichment rebuilds a processed document from the row's raw copy; the raw read is
// replaced so the test controls what the raw copy holds.
const rawCopy = vi.hoisted(() => ({ bom: null as any }));
vi.mock('../../src/services/oci', async (importOriginal) => {
    const actual: any = await importOriginal();
    return { ...actual, resolveAndFetchRawBom: vi.fn(async () => rawCopy.bom) };
});

import { reprocessCycloneDxBom } from '../../src/services/bom/bomProcessingService';
import { createTestRebomOptions } from '../helpers';

/**
 * SCORE-60 (T-27, ADR-9 (f)): rearm-saas always forces a re-enrichment, which rebuilds the processed
 * document from the raw copy, the uploaded document untouched by contract. A row rebom did not merge
 * is re-stored without `reliza:export:releaseNode`, as at ingest; a merge output keeps its own.
 */

const STAMP = { name: 'reliza:export:releaseNode', value: 'true' };
const KEEP = { name: 'acme:keep', value: 'yes' };

function raw(withStamps = true): any {
    const stamp = withStamps ? [STAMP] : [];
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        serialNumber: 'urn:uuid:5d0b7f6e-0a5c-4b8e-9d1e-0000000000aa',
        version: 1,
        metadata: { component: { type: 'application', name: 'uploaded', version: '0.0.1', 'bom-ref': 'root', properties: [KEEP, ...stamp] } },
        components: [{
            type: 'library', name: 'lib', version: '1.0.0', purl: 'pkg:npm/lib@1.0.0', 'bom-ref': 'pkg:npm/lib@1.0.0', properties: [...stamp],
            components: [{ type: 'library', name: 'nested', version: '1.0.0', purl: 'pkg:npm/nested@1.0.0', 'bom-ref': 'pkg:npm/nested@1.0.0', properties: [...stamp, KEEP] }]
        }]
    };
}

function row(mod: string): any {
    return { uuid: 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee', meta: createTestRebomOptions({ name: 'svc', group: 'com.acme', version: '2.0.0', mod }), bom: {} };
}

function namesEverywhere(node: any, out: string[] = []): string[] {
    if (Array.isArray(node)) { node.forEach((n) => namesEverywhere(n, out)); return out; }
    if (node && typeof node === 'object') {
        if (Array.isArray(node.properties)) node.properties.forEach((p: any) => out.push(p?.name));
        Object.values(node).forEach((v) => namesEverywhere(v, out));
    }
    return out;
}

describe('reprocessCycloneDxBom: release node stamps', () => {
    beforeEach(() => { rawCopy.bom = raw(); });

    it('re-augments an upload without any release node stamp', async () => {
        const out = await reprocessCycloneDxBom(row('raw'));

        expect(out.metadata.component.name).toBe('svc');
        expect(out.metadata.component.version).toBe('2.0.0');
        expect(out.metadata.component.group).toBe('com.acme');
        expect(namesEverywhere(out)).not.toContain(STAMP.name);
        expect(out.metadata.component.properties).toContainEqual(KEEP);
        expect(out.components[0].components[0].properties).toStrictEqual([KEEP]);
    });

    it('keeps a merge output its stamps, on the root and the nested nodes', async () => {
        const out = await reprocessCycloneDxBom(row('rebom'));

        expect(out.metadata.component.properties).toContainEqual(STAMP);
        expect(out.components[0].properties).toStrictEqual([STAMP]);
        expect(out.components[0].components[0].properties).toStrictEqual([STAMP, KEEP]);
    });

    it('re-augments a raw copy without the property and leaves the rest as it was', async () => {
        rawCopy.bom = raw(false);

        const out = await reprocessCycloneDxBom(row('raw'));

        expect(out.metadata.component.name).toBe('svc');
        expect(out.components[0].properties).toStrictEqual([]);
        expect(out.components[0].components[0].properties).toStrictEqual([KEEP]);
    });

    it('does not strip the raw document it was handed in place', async () => {
        const handed = rawCopy.bom;
        const before = structuredClone(handed);

        await reprocessCycloneDxBom(row('raw'));

        expect(handed.components[0].properties).toStrictEqual(before.components[0].properties);
        expect(handed.components[0].components[0].properties).toStrictEqual(before.components[0].components[0].properties);
    });
});
