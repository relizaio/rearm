import * as BomService from '../src/bomService';
import { TEST_ORG_UUID, createTestRebomOptions, generateSerialNumber } from './helpers';

/**
 * SCORE-30 (T-8, T-10): the documents of a product export as ReARM builds them. One component
 * release with two uploads, one attached to its source code entry and one to its deliverable,
 * each stored with the supplier ReARM names (ADR-12), merged with the release's facts; another
 * release with one upload attached to the release and stored in the shape of a row from before
 * this task (no supplierName); and two releases without an SBOM, as placeholders: one of an
 * INTERNAL component with a hashed deliverable, one of an EXTERNAL component (no supplier, no
 * hash). Every component the uploads list is complete (supplier, hash, licence), so what fails a
 * score is a node ReARM wrote or an uploaded root.
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
/** The SBOM-less release of an EXTERNAL component: ReARM names no supplier for it (ADR-2). */
export const EXTERNAL_PLACEHOLDER_UUID = '0b0f4a52-6c55-4d1f-9a8e-000000000302';
export const EXTERNAL_PLACEHOLDER_REF = `urn:rearm:release:${EXTERNAL_PLACEHOLDER_UUID}`;

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

/** The placeholder of an EXTERNAL component's release, as rearm-saas sends it: no supplierName. */
export function externalPlaceholder(): any {
    return {
        releaseUuid: EXTERNAL_PLACEHOLDER_UUID, name: 'vendor-db', version: '15.0.0', type: 'library',
        group: ORG, purl: null, reason: 'NO_SBOM_ARTIFACT'
    };
}

/** The merge version rearm-saas sends with the declared licences (SCORE-40 ADR-5; 39b bumps it). */
export const LICENSE_MERGE_VERSION = 7;
/** The merge version of the SCORE-30 requests, without licences. */
export const FACTS_MERGE_VERSION = 6;
/** The merge version whose products record a dropped listed licence expression (SCORE-44 ADR-6; a label, rebom reads nothing from it). */
export const DROPPED_MERGE_VERSION = 8;

/** SCORE-40 T-5: the licence the component release declares, proprietary with its text online. */
export const COMPONENT_LICENSE = 'LicenseRef-Facts-Commercial';
export const COMPONENT_LICENSE_URL = 'https://facts.example/licence';
/** SCORE-40 T-5: the licence the product release and the SBOM-less INTERNAL release declare. */
export const PRODUCT_LICENSE = 'AGPL-3.0-only';

/**
 * An upload attached to the component release whose root is the release node itself (its purl is
 * the merged root's), as a build tool that describes the release writes it, with `licenses` on
 * that root: in the component merge it collapses into the merged root (SCORE-40 T-5).
 */
export function releaseNodeUpload(licenses: any[]): any {
    return uploadDoc({ type: 'application', name: 'facts-api', version: '1.0.0', purl: RELEASE_NODE, 'bom-ref': RELEASE_NODE, licenses },
        [complete('lib-f', 'f')]);
}

/**
 * The product merge as rearm-saas asks for it (SCORE-30, SCORE-40): `withFacts` sends the release
 * node facts and the placeholder hashes, `productLicense` (when set) the licence the product
 * release and the INTERNAL placeholder declare, with the merge version that sends it.
 */
export function productMergeRequest(withFacts: boolean, productLicense?: string): { overrides: any, extra: any } {
    const licence = productLicense ? { licenseExpression: productLicense } : {};
    return {
        overrides: { name: 'facts-product', version: '3.0.0', rootComponentMergeMode: 'PRESERVE_UNDER_NEW_ROOT', mergeVersion: productLicense ? LICENSE_MERGE_VERSION : FACTS_MERGE_VERSION },
        extra: withFacts
            ? { releaseNode: { supplierName: ORG, ...licence }, missingSbomComponents: [{ ...placeholder(true), ...licence }, externalPlaceholder()] }
            : { missingSbomComponents: [{ ...placeholder(false), ...licence }, externalPlaceholder()] }
    };
}

export type Built = {
    componentMerge: any,
    product: any,
    ids: { sce: string, deliverable: string, release: string, releaseNodeCopy?: string, componentMerge: string, product: string }
};

/**
 * Uploads the three SBOMs as ReARM attaches them (the SCE and deliverable uploads with
 * supplierName, the release upload without it, as a legacy row), merges the component release
 * (FLATTEN) and the product (PRESERVE) the way rearm-saas asks, and returns both merged documents
 * as served. `withFacts` false sends the merge requests of the pre-task shape: no releaseNode, no
 * placeholder hashes. `withLicenses` (SCORE-40 T-5) adds the licences ReARM declares, under merge
 * version 7: COMPONENT_LICENSE with its url on the component release's node, PRODUCT_LICENSE on the
 * product root and the INTERNAL placeholder. `releaseNodeLicenses`, when set, also attaches
 * releaseNodeUpload(releaseNodeLicenses) to the component release.
 */
export async function buildProductExport(created: string[], withFacts: boolean, withLicenses = false,
    releaseNodeLicenses?: any[]): Promise<Built> {
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
            ...createTestRebomOptions({ serialNumber: generateSerialNumber(), group: ORG, mergeVersion: FACTS_MERGE_VERSION, ...overrides }),
            ...extra
        }, TEST_ORG_UUID, BomService.addBom);
        created.push(record.uuid);
        return record.uuid;
    }

    const sce = await upload(sceUpload(), { name: 'facts-api', version: '1.0.0', belongsTo: 'SCE', hash: COMMIT, supplierName: ORG });
    const deliverable = await upload(deliverableUpload(), { name: 'facts-api', version: '1.0.0', belongsTo: 'DELIVERABLE', hash: 'sha256:' + UPLOAD_DIGEST, supplierName: ORG });
    const release = await upload(releaseUpload(), { name: 'web', version: '2.0.0', belongsTo: 'RELEASE' });
    const releaseNodeCopy = releaseNodeLicenses
        ? await upload(releaseNodeUpload(releaseNodeLicenses), { name: 'facts-api', version: '1.0.0', purl: RELEASE_NODE })
        : undefined;

    const componentLicence = withLicenses ? { licenseExpression: COMPONENT_LICENSE, licenseUrl: COMPONENT_LICENSE_URL } : {};
    const componentFacts = withFacts ? { supplierName: ORG, hashes: [{ alg: 'SHA-256', content: RELEASE_DIGEST }] } : {};
    const componentMerge = await merge(releaseNodeCopy ? [sce, deliverable, releaseNodeCopy] : [sce, deliverable],
        { name: 'facts-api', version: '1.0.0', rootComponentMergeMode: 'FLATTEN_UNDER_NEW_ROOT', mergeVersion: withLicenses ? LICENSE_MERGE_VERSION : FACTS_MERGE_VERSION },
        withFacts || withLicenses ? { releaseNode: { ...componentFacts, ...componentLicence } } : {});
    const productRequest = productMergeRequest(withFacts, withLicenses ? PRODUCT_LICENSE : undefined);
    const product = await merge([componentMerge, release], productRequest.overrides, productRequest.extra);

    return {
        componentMerge: await BomService.findBomObjectById(componentMerge, TEST_ORG_UUID),
        product: await BomService.findBomObjectById(product, TEST_ORG_UUID),
        ids: { sce, deliverable, release, releaseNodeCopy, componentMerge, product }
    };
}

/** The component with bom-ref `ref` at the top level of `bom`, or undefined. */
export function node(bom: any, ref: string): any {
    return (bom.components || []).find((c: any) => c['bom-ref'] === ref);
}

/** SCORE-40 T-10: the application whose SBOM lists the component release by its ReARM purl. */
export const LISTING_ROOT = 'pkg:npm/web-app@2.0.0';

/** SCORE-40 T-10: the library the nested listing sits under, the listing upload's top-level component. */
export const LISTING_MID = 'pkg:npm/mid@1.0.0';

/**
 * SCORE-40 T-10: an upload of another application (`LISTING_ROOT`) that lists the component
 * release under its release node purl and bom-ref, as an in-house library, with `licenses` when
 * given (no key when undefined), and a dependency from its root to it. `nested`: the listing sits
 * in the `components` of the library `LISTING_MID`, the upload's top-level component, with
 * dependencies from the root to it and from it to the listing. `rootPurl` (SCORE-44 T-7) names
 * another listing application, at the top level only.
 */
export function releaseNodeListing(licenses?: any[], nested = false, rootPurl = LISTING_ROOT): any {
    const listed: any = { type: 'library', name: 'facts-api', version: '1.0.0', purl: RELEASE_NODE, 'bom-ref': RELEASE_NODE };
    if (licenses !== undefined) listed.licenses = licenses;
    const root = { type: 'application', name: 'web-app', version: '2.0.0', purl: rootPurl, 'bom-ref': rootPurl };
    if (!nested) return uploadDoc(root, [listed]);
    const mid = { type: 'library', name: 'mid', version: '1.0.0', purl: LISTING_MID, 'bom-ref': LISTING_MID, components: [listed] };
    const bom = uploadDoc(root, [mid]);
    bom.dependencies = [
        { ref: LISTING_ROOT, dependsOn: [LISTING_MID] },
        { ref: LISTING_MID, dependsOn: [RELEASE_NODE] },
        { ref: RELEASE_NODE, dependsOn: [] }
    ];
    return bom;
}

/** SCORE-40 T-10: the licence a component release declares, as rearm-saas sends it. */
export type DeclaredLicence = { licenseExpression: string, licenseUrl?: string };

export type ListingProducts = {
    /** The product merged over [component merge, listing] and over [listing, component merge], in memory. */
    merged: any[],
    /** The same two product merges stored and served. */
    stored: any[],
    /** The record ids of the two stored product merges (SCORE-44 T-7: inputs of a product above). */
    storedIds: string[]
};

/**
 * SCORE-40 T-10: merges the component release (FLATTEN, its facts plus `declared` when set) over
 * the SCE and deliverable uploads, uploads releaseNodeListing(listingLicenses, nested), then merges the
 * product (PRESERVE, the product facts of productMergeRequest with PRODUCT_LICENSE) over the
 * component merge and the listing in both input orders: once in memory (BomService.mergeBoms,
 * the shape before any store normalisation) and once stored and served. `releaseNodeLicenses`,
 * when set (round 4), replaces the component merge by an upload of
 * releaseNodeUpload(releaseNodeLicenses), whose root is the release node itself (`declared` unused).
 * The product merges carry DROPPED_MERGE_VERSION (SCORE-44 T-5).
 */
export async function buildListingProducts(created: string[], declared: DeclaredLicence | undefined,
    listingLicenses: any[] | undefined, nested = false, releaseNodeLicenses?: any[]): Promise<ListingProducts> {
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
    const options = (overrides: any, extra: any) => ({
        ...createTestRebomOptions({ serialNumber: generateSerialNumber(), group: ORG, ...overrides }),
        ...extra
    });

    async function releaseNodeInput(): Promise<string> {
        if (releaseNodeLicenses !== undefined) {
            return upload(releaseNodeUpload(releaseNodeLicenses), { name: 'facts-api', version: '1.0.0', purl: RELEASE_NODE });
        }
        const sce = await upload(sceUpload(), { name: 'facts-api', version: '1.0.0', belongsTo: 'SCE', hash: COMMIT, supplierName: ORG });
        const deliverable = await upload(deliverableUpload(), { name: 'facts-api', version: '1.0.0', belongsTo: 'DELIVERABLE', hash: 'sha256:' + UPLOAD_DIGEST, supplierName: ORG });
        const merged = await BomService.mergeAndStoreBoms([sce, deliverable],
            options({ name: 'facts-api', version: '1.0.0', rootComponentMergeMode: 'FLATTEN_UNDER_NEW_ROOT', mergeVersion: declared ? LICENSE_MERGE_VERSION : FACTS_MERGE_VERSION },
                { releaseNode: { supplierName: ORG, hashes: [{ alg: 'SHA-256', content: RELEASE_DIGEST }], ...(declared || {}) } }),
            TEST_ORG_UUID, BomService.addBom);
        created.push(merged.uuid);
        return merged.uuid;
    }

    const component = await releaseNodeInput();
    const listing = await upload(releaseNodeListing(listingLicenses, nested), { name: 'web-app', version: '2.0.0', purl: LISTING_ROOT });

    const productOptions = () => {
        const request = productMergeRequest(true, PRODUCT_LICENSE);
        return options({ ...request.overrides, mergeVersion: DROPPED_MERGE_VERSION }, request.extra);
    };
    const merged: any[] = [];
    const stored: any[] = [];
    const storedIds: string[] = [];
    for (const ids of [[component, listing], [listing, component]]) {
        merged.push(await BomService.mergeBoms(ids, productOptions(), TEST_ORG_UUID));
        const record = await BomService.mergeAndStoreBoms(ids, productOptions(), TEST_ORG_UUID, BomService.addBom);
        created.push(record.uuid);
        storedIds.push(record.uuid);
        stored.push(await BomService.findBomObjectById(record.uuid, TEST_ORG_UUID));
    }
    return { merged, stored, storedIds };
}
