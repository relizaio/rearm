export enum StorageType {
    OCI = 'OCI'
}

export enum BomState {
    RAW = 'RAW',
    PROCESSED = 'PROCESSED',
    MERGED = 'MERGED',
    CONVERTED = 'CONVERTED'
}

export enum BomModification {
    RAW = 'RAW',
    TLD = 'TLD',
    DEV_FILTERED = 'DEV_FILTERED',
    MERGED = 'MERGED'
}

export enum BomStructure {
    FLAT = 'FLAT',
    HIERARCHICAL = 'HIERARCHICAL'
}

/** What an upload is attached to in ReARM: rearm-saas ArtifactBelongsTo, sent as its enum name. */
export enum ArtifactBelongsTo {
    DELIVERABLE = 'DELIVERABLE',
    RELEASE = 'RELEASE',
    SCE = 'SCE',
    AGENT_SESSION = 'AGENT_SESSION'
}

export function normalizeBomState(value: string): BomState {
    const upper = value.toUpperCase().replace('-', '_');
    return BomState[upper as keyof typeof BomState] ?? BomState.RAW;
}

export function normalizeBomModification(value: string): BomModification {
    const upper = value.toUpperCase();
    return BomModification[upper as keyof typeof BomModification] ?? BomModification.RAW;
}

/** The attachment a stored `belongsTo` names, in any case; undefined when absent or unknown. */
export function normalizeArtifactBelongsTo(value: unknown): ArtifactBelongsTo | undefined {
    if (typeof value !== 'string') return undefined;
    const upper = value.trim().toUpperCase();
    return ArtifactBelongsTo[upper as keyof typeof ArtifactBelongsTo];
}

export function normalizeBomStructure(value: string): BomStructure {
    const upper = value.toUpperCase();
    return BomStructure[upper as keyof typeof BomStructure] ?? BomStructure.FLAT;
}
