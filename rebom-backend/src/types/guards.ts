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

/** A uuid in the 8-4-4-4-12 hex form, either case (the shape an organization has). */
export function isUuid(value: unknown): value is string {
    return typeof value === 'string' && UUID_PATTERN.test(value);
}

// PostgreSQL's uuid input (string_to_uuid): 32 hex digits, either case, a hyphen
// allowed after any group of four but the last, optionally in braces.
const UUID_INPUT_PATTERN = /^(?:\{([0-9a-f]{4}(?:-?[0-9a-f]{4}){7})\}|([0-9a-f]{4}(?:-?[0-9a-f]{4}){7}))$/i;

/**
 * The canonical text of a uuid, lowercase 8-4-4-4-12 (what PostgreSQL's
 * uuid::text answers), for every spelling PostgreSQL's uuid type accepts; null
 * for anything else.
 */
export function canonicalUuid(value: unknown): string | null {
    if (typeof value !== 'string') return null;
    const match = UUID_INPUT_PATTERN.exec(value);
    if (!match) return null;
    const hex = (match[1] ?? match[2]).replace(/-/g, '').toLowerCase();
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
