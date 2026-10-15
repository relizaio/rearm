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

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** A uuid in the 8-4-4-4-12 hex form, either case (the shape organization and ownerComponent have). */
export function isUuid(value: unknown): value is string {
    return typeof value === 'string' && UUID_PATTERN.test(value);
}
