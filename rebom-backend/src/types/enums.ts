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

export function normalizeBomState(value: string): BomState {
    const upper = value.toUpperCase().replace('-', '_');
    return BomState[upper as keyof typeof BomState] ?? BomState.RAW;
}

export function normalizeBomModification(value: string): BomModification {
    const upper = value.toUpperCase();
    return BomModification[upper as keyof typeof BomModification] ?? BomModification.RAW;
}

export function normalizeBomStructure(value: string): BomStructure {
    const upper = value.toUpperCase();
    return BomStructure[upper as keyof typeof BomStructure] ?? BomStructure.FLAT;
}
