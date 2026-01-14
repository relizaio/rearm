import { RebomOptions } from '../types';

export function hasMergeConfig(opts: RebomOptions): boolean {
    return opts.structure !== undefined || opts.tldOnly !== undefined;
}

export function hasSpdxInfo(opts: RebomOptions): boolean {
    return opts.originalFileDigest !== undefined;
}

export function hasRelationship(opts: RebomOptions): boolean {
    return !!opts.belongsTo || !!opts.hash;
}

export function hasRootComponentMergeMode(opts: RebomOptions): boolean {
    return opts.rootComponentMergeMode !== undefined;
}

export function hasIgnoreDev(opts: RebomOptions): boolean {
    return opts.ignoreDev !== undefined;
}
