export enum StorageType {
    OCI = 'OCI',
    DATABASE = 'DATABASE',
}

export enum BomState {
    RAW = 'RAW',
    MERGED = 'MERGED',
    CROSS_MERGED = 'CROSS_MERGED'
}

export enum ModificationType {
    RAW = 'RAW',
    REBOM = 'REBOM',
    USER = 'USER'
}

export enum BomStructure {
    FLAT = 'FLAT',
    HIERARCHICAL = 'HIERARCHICAL'
}

export function normalizeStorageType(value: string): StorageType {
    const upper = value.toUpperCase();
    if (upper === 'OCI') return StorageType.OCI;
    if (upper === 'DB' || upper === 'DATABASE') return StorageType.DATABASE;
    return StorageType.OCI;
}

export function normalizeBomState(value: string): BomState {
    const upper = value.toUpperCase().replace('-', '_');
    return BomState[upper as keyof typeof BomState] ?? BomState.RAW;
}

export function normalizeModificationType(value: string): ModificationType {
    const upper = value.toUpperCase();
    return ModificationType[upper as keyof typeof ModificationType] ?? ModificationType.RAW;
}

export function normalizeBomStructure(value: string): BomStructure {
    const upper = value.toUpperCase();
    return BomStructure[upper as keyof typeof BomStructure] ?? BomStructure.FLAT;
}
