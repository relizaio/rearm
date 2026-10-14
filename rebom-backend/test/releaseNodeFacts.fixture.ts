import * as BomService from '../src/bomService';
import { TEST_ORG_UUID, createTestRebomOptions, generateSerialNumber } from './helpers';

/**
 * SCORE-30 (T-8, T-10): the documents of a product export as ReARM builds them. One component
 * release with two uploads, one attached to its source code entry and one to its deliverable,
 * merged with the release's facts; another release with one upload attached to the release; and
 * a release without an SBOM, as a placeholder. Every component the uploads list is complete
 * (supplier, hash, licence), so what fails a score is a node ReARM wrote or an uploaded root.
 */

export const ORG = 'Facts Org';
export const COMMIT = 'a94a8fe5ccb19ba61c4c0873d391e987982fbbd3';
/** The digest the deliverable-attached upload was made under. */
export const UPLOAD_DIGEST = '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08';
/** The digest record of the component release's one deliverable (its facts). */
export const RELEASE_DIGEST = '60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752';
/** The digest record of the SBOM-less release's one deliverable. */
export const PLACEHOLDER_DIGEST = 'fd61a03af4f77d870fc21e05e7e80678095c92d808cfb3b5c279ee04c74aca13';
export const PLACEHOLDER_UUID = '0b0f4a52-6c55-4d1f-9a8e-000000000301';
export const PLACEHOLDER_REF = `urn:rearm:release:${PLACEHOLDER_UUID}`;

// packageurl-js lower-cases an npm namespace
export const SCE_ROOT = `pkg:npm/facts%20org/api@1.0.0?belongsto=SCE&hash=${COMMIT}`;
export const DELIVERABLE_ROOT = `pkg:oci/api-image@1.0.0?belongsto=DELIVERABLE&hash=sha256%3A${UPLOAD_DIGEST}`;
export const RELEASE_ROOT = 'pkg:oci/web@2.0.0?belongsto=RELEASE';
export const RELEASE_NODE = 'pkg:generic/Facts%20Org/facts-api@1.0.0';
export const PRODUCT_ROOT = 'pkg:generic/Facts%20Org/facts-product@3.0.0';

/** A component complete on every per-component check: supplier, hash, licence, version, purl. */
function complete(name: string, digit: string): any {
    const purl = `pkg:npm/${name}@1.0.0`;
    return {
        type: 'library', name, version: '1.0.0', purl, 'bom-ref': purl,
        supplier: { name: `${name} maintainers` },
        hashes: [{ alg: 'SHA-256', content: digit.repeat(64) }],
        licenses: [{ license: { id: 'MIT' } }]
    };
}

/** An upload whose root carries no supplier, hash or licence, as most generators write it. */
function uploadDoc(root: any, components: any[]): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        serialNumber: generateSerialNumber(),
        version: 1,
        metadata: { timestamp: '2026-10-13T00:00:00Z', component: root },
        components,
        dependencies: [
            { ref: root['bom-ref'], dependsOn: components.map(c => c['bom-ref']) },
            ...components.map(c => ({ ref: c['bom-ref'], dependsOn: [] }))
        ]
    };
}

export function sceUpload(): any {
    return uploadDoc({ type: 'application', name: 'api', version: '1.0.0', purl: 'pkg:npm/api@1.0.0', 'bom-ref': 'pkg:npm/api@1.0.0' },
        [complete('lib-a', 'a'), complete('lib-b', 'b')]);
}

export function deliverableUpload(): any {
    return uploadDoc({ type: 'container', name: 'api-image', version: '1.0.0', purl: 'pkg:oci/api-image@1.0.0', 'bom-ref': 'pkg:oci/api-image@1.0.0' },
        [complete('lib-c', 'c')]);
}

export function releaseUpload(): any {
    return uploadDoc({ type: 'container', name: 'web', version: '2.0.0', purl: 'pkg:oci/web@2.0.0', 'bom-ref': 'pkg:oci/web@2.0.0' },
        [complete('lib-d', 'd'), complete('lib-e', 'e')]);
}

export function placeholder(withHashes: boolean): any {
    return {
        releaseUuid: PLACEHOLDER_UUID, name: 'worker', version: '4.0.0', type: 'library',
        supplierName: ORG, group: ORG, purl: null,
        ...(withHashes ? { hashes: [{ alg: 'SHA-256', content: PLACEHOLDER_DIGEST }] } : {}),
        reason: 'NO_SBOM_ARTIFACT'
    };
}

export type Built = { componentMerge: any, product: any, ids: { sce: string, deliverable: string, release: string, componentMerge: string, product: string } };

/**
 * Uploads the three SBOMs as ReARM attaches them, merges the component release (FLATTEN) and the
 * product (PRESERVE) the way rearm-saas asks, and returns both merged documents as served.
 * `withFacts` false sends the requests of the pre-task shape: no releaseNode, no placeholder hashes.
 */
export async function buildProductExport(created: string[], withFacts: boolean): Promise<Built> {
    async function upload(bom: any, overrides: any): Promise<string> {
        const record = await BomService.addBom({
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({ serialNumber: bom.serialNumber, group: ORG, ...overrides })
            }
        });
        created.push(record.uuid);
        return record.uuid;
    }

    async function merge(ids: string[], overrides: any, extra: any): Promise<string> {
        const record = await BomService.mergeAndStoreBoms(ids, {
            ...createTestRebomOptions({ serialNumber: generateSerialNumber(), group: ORG, mergeVersion: 6, ...overrides }),
            ...extra
        }, TEST_ORG_UUID, BomService.addBom);
        created.push(record.uuid);
        return record.uuid;
    }

    const sce = await upload(sceUpload(), { name: 'facts-api', version: '1.0.0', belongsTo: 'SCE', hash: COMMIT });
    const deliverable = await upload(deliverableUpload(), { name: 'facts-api', version: '1.0.0', belongsTo: 'DELIVERABLE', hash: 'sha256:' + UPLOAD_DIGEST });
    const release = await upload(releaseUpload(), { name: 'web', version: '2.0.0', belongsTo: 'RELEASE' });

    const componentMerge = await merge([sce, deliverable],
        { name: 'facts-api', version: '1.0.0', rootComponentMergeMode: 'FLATTEN_UNDER_NEW_ROOT' },
        withFacts ? { releaseNode: { supplierName: ORG, hashes: [{ alg: 'SHA-256', content: RELEASE_DIGEST }] } } : {});
    const product = await merge([componentMerge, release],
        { name: 'facts-product', version: '3.0.0', rootComponentMergeMode: 'PRESERVE_UNDER_NEW_ROOT' },
        withFacts
            ? { releaseNode: { supplierName: ORG }, missingSbomComponents: [placeholder(true)] }
            : { missingSbomComponents: [placeholder(false)] });

    return {
        componentMerge: await BomService.findBomObjectById(componentMerge, TEST_ORG_UUID),
        product: await BomService.findBomObjectById(product, TEST_ORG_UUID),
        ids: { sce, deliverable, release, componentMerge, product }
    };
}

/** The component with bom-ref `ref` at the top level of `bom`, or undefined. */
export function node(bom: any, ref: string): any {
    return (bom.components || []).find((c: any) => c['bom-ref'] === ref);
}
