export type BomRecord = {
    uuid: string,
    created_date: Date,
    last_updated_date: Date,
    meta: RebomOptions,
    bom: any,
    tags: Object,
    organization: string,
    public: boolean,
    duplicate: boolean,
    source_format?: string,
    source_spdx_uuid?: string
}

export type BomDto = {
    uuid: string,
    createdDate: Date,
    lastUpdatedDate: Date,
    meta: RebomOptions,
    bom: Object,
    tags: Object,
    organization: string,
    public: boolean,
    bomVersion: string,
    group: string,
    name: string,
    version: string
}

export type BomMetaDto = {
    uuid: string,
    name: string,
    group: string,
    bomVersion: string,
    version: string,
    bomDigest: string | undefined,
    belongsTo: string,
    tldOnly: boolean,
    structure: string,
    notes: string,
    stripBom: string,
    serialNumber: string,
    processedSerialNumber?: string,
    createdDate: Date,
    lastUpdatedDate: Date,
    ignoreDev?: boolean,
    enrichmentStatus?: EnrichmentStatus,
    enrichmentTimestamp?: string,
    enrichmentError?: string,
    enrichments?: EnrichmentRun[]
}

export type BomInput = {
    bomInput: { 
        format: BomFormat,
        bom: any,
        tags?: Object,
        rebomOptions: RebomOptions,
        org: string,
        existingSerialNumber?: string,
    }
}

export enum RootComponentMergeMode {
    PRESERVE_UNDER_NEW_ROOT = 'PRESERVE_UNDER_NEW_ROOT',
    FLATTEN_UNDER_NEW_ROOT = 'FLATTEN_UNDER_NEW_ROOT'
}

export enum EnrichmentStatus {
    PENDING = 'PENDING',
    COMPLETED = 'COMPLETED',
    FAILED = 'FAILED',
    SKIPPED = 'SKIPPED'  // When BEAR env vars are not set
}

/** One enrichment run, or entry 0: the upload that created the row. */
export type EnrichmentRun = {
    sequence: number,            // the n in '<uuid>-e<n>'; 0 is the upload
    tag?: string,                // null when the run pushed nothing
    repository?: string,
    digest?: string,
    size?: number,
    serialNumber?: string,       // identity of the document this run pushed
    startedAt?: string,
    completedAt?: string,
    // ABANDONED: started and never reported back; aged out by a later run.
    status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'ABANDONED',
    error?: string | null,
    source?: 'scheduler' | 'on-upload' | 'manual',
    enricherVersion?: string
}

export type RebomOptions = {
    serialNumber: string,
    name: string,
    group: string,
    version: string,
    belongsTo: string,
    hash?: string,
    notes: string,
    tldOnly: boolean,
    structure: string,
    bomState: string,
    mod: string,
    storage: string,
    bomDigest?: string,
    originalFileDigest?: string,
    originalFileSize?: number,
    originalMediaType?: string,
    processedFileDigest?: string,  // SHA256 of augmented/processed BOM file
    processedFileSize?: number,
    // serialNumber INSIDE the processed document the row currently points at.
    // Not the row's identity -- that is `serialNumber` above and it stays the
    // producer's. The processed copy is a different document (augmented at
    // ingest, rewritten by every enrichment run) and carries its own, so a
    // BOM-Link to the producer's identity still means one thing. Absent on
    // rows written before that was true, and on uploads stored without
    // augmentation; readers must not assume the two differ.
    processedSerialNumber?: string,
    // Repository holding the '-raw' copy. The processed BOM's repository
    // (bom.ociRepositoryName) moves to the CURRENT month on enrichment
    // re-push, but the raw copy stays where it was uploaded -- one pointer
    // cannot describe both. Stamped at upload; resolved-and-stamped on
    // demand for legacy rows (see rawBomResolver).
    rawOciRepositoryName?: string,
    // True when no '-raw' copy exists anywhere (pre-2026-01 uploads, or
    // removed by retention). Raw requests then serve the processed BOM as
    // an explicit substitute instead of re-probing repositories every time.
    rawBomMissing?: boolean,
    stripBom: string,
    bomVersion: string,
    purl?: string,
    rootComponentMergeMode?: RootComponentMergeMode,
    ignoreDev?: boolean,
    // Deduplication metadata
    isDuplicate?: boolean,
    duplicateOf?: string,  // UUID of the original BOM if this is a duplicate
    deduplicationTimestamp?: string,  // ISO timestamp when deduplication was detected
    // Which artifact holds the processed BOM. Enrichment never overwrites in
    // place: each run pushes '<uuid>-e<n>' and moves this pointer, so a reader
    // holding an older row still finds the bytes its digest describes. Absent
    // on rows written before that change, which resolve to the bare uuid.
    processedTag?: string,
    // Enrichment metadata
    enrichmentStatus?: EnrichmentStatus,
    enrichmentTimestamp?: string,  // ISO timestamp when enrichment completed/failed
    enrichmentError?: string,  // Error message if enrichment failed
    // Append-only history of every processed artifact this row has pointed at,
    // entry 0 being the upload itself. The scalars above are the summary of the
    // last COMPLETED entry; a FAILED run appends an entry and moves nothing.
    // Retention reads it to know which superseded tags are safe to remove.
    enrichments?: EnrichmentRun[]
}

export type BomSearch = {
    bomSearch: {
        serialNumber: string,
        version: string,
        componentVersion: string,
        componentGroup: string,
        componentName: string,
        singleQuery: string,
        page: number,
        offset: number
    }
}

export type BomFormat = 'CYCLONEDX' | 'SPDX';

export const HIERARCHICHAL = 'hierarchical'
