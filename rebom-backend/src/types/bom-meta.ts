import { RebomOptions, RootComponentMergeMode } from '../types';

export interface BomMetaNested {
    identity: {
        serialNumber: string;
        name: string;
        group: string;
        version: string;
        purl?: string;
        bomVersion: number;
    };
    storage: {
        type: 'OCI' | 'DATABASE';
        state: 'RAW' | 'MERGED' | 'CROSS_MERGED';
        modification: 'RAW' | 'REBOM' | 'USER';
        bomDigest?: string;
        stripBom: boolean;
    };
    relationship?: {
        belongsTo?: string;
        hash?: string;
    };
    merge?: {
        structure: 'FLAT' | 'HIERARCHICAL';
        rootComponentMergeMode?: RootComponentMergeMode;
        tldOnly: boolean;
        ignoreDev: boolean;
        sourceIds?: string[];
    };
    spdx?: {
        originalFileDigest?: string;
        originalFileSize?: number;
        originalMediaType?: string;
    };
    notes?: string;
}

export function toNestedMeta(opts: RebomOptions): BomMetaNested {
    const nested: BomMetaNested = {
        identity: {
            serialNumber: opts.serialNumber,
            name: opts.name,
            group: opts.group,
            version: opts.version,
            purl: opts.purl,
            bomVersion: parseInt(opts.bomVersion) || 1
        },
        storage: {
            type: opts.storage?.toUpperCase() === 'DB' ? 'DATABASE' : 'OCI',
            state: opts.bomState?.toUpperCase().replace('-', '_') as any || 'RAW',
            modification: opts.mod?.toUpperCase() as any || 'RAW',
            bomDigest: opts.bomDigest,
            stripBom: opts.stripBom === 'true' || opts.stripBom === true as any
        }
    };

    if (opts.belongsTo || opts.hash) {
        nested.relationship = {
            belongsTo: opts.belongsTo || undefined,
            hash: opts.hash || undefined
        };
    }

    if (opts.structure || opts.tldOnly !== undefined) {
        nested.merge = {
            structure: (opts.structure?.toUpperCase() as any) || 'FLAT',
            rootComponentMergeMode: opts.rootComponentMergeMode,
            tldOnly: opts.tldOnly ?? false,
            ignoreDev: opts.ignoreDev ?? false
        };
    }

    if (opts.originalFileDigest) {
        nested.spdx = {
            originalFileDigest: opts.originalFileDigest,
            originalFileSize: opts.originalFileSize,
            originalMediaType: opts.originalMediaType
        };
    }

    if (opts.notes) {
        nested.notes = opts.notes;
    }

    return nested;
}

export function fromNestedMeta(nested: BomMetaNested): RebomOptions {
    return {
        serialNumber: nested.identity.serialNumber,
        name: nested.identity.name,
        group: nested.identity.group,
        version: nested.identity.version,
        purl: nested.identity.purl,
        bomVersion: String(nested.identity.bomVersion),
        storage: nested.storage.type.toLowerCase(),
        bomState: nested.storage.state.toLowerCase().replace('_', '-'),
        mod: nested.storage.modification.toLowerCase(),
        bomDigest: nested.storage.bomDigest,
        stripBom: String(nested.storage.stripBom),
        belongsTo: nested.relationship?.belongsTo ?? '',
        hash: nested.relationship?.hash,
        structure: nested.merge?.structure ?? 'FLAT',
        rootComponentMergeMode: nested.merge?.rootComponentMergeMode as any,
        tldOnly: nested.merge?.tldOnly ?? false,
        ignoreDev: nested.merge?.ignoreDev,
        originalFileDigest: nested.spdx?.originalFileDigest,
        originalFileSize: nested.spdx?.originalFileSize,
        originalMediaType: nested.spdx?.originalMediaType,
        notes: nested.notes ?? ''
    };
}
