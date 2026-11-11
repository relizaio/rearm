import { createHash, randomBytes } from 'crypto';
import * as fs from 'fs';
import { logger } from '../../logger';
import * as BomRepository from '../../bomRepository';
import * as SpdxRepository from '../../spdxRepository';
import { BomFormat, BomRecord, SpdxBomRecord } from '../../types';
import { BomLineageNotPermittedError, BomValidationError, BomVersionConflictError } from '../../types/errors';
import { addBomDetailed, AddDisposition, LineagePolicyKind } from '../bom/bomAddService';
import { printableAscii } from './teaDocument';
import { TeaHeldFile } from './teaFetchStore';
import { internalMessage, TeaOutcome, teaResult, TeaResultBase } from './teaOutcome';
import { TeaStoreRequest } from './teaRequest';

/**
 * Storing one held file as an SBOM through the existing add path, in process (TPF-180 design
 * 3.6.3, 3.6.4). Whether a file is an SBOM is decided by its parsed content, not by what the server
 * called it. The origin is UPLOAD, so a file fetched from a TEA source and the same file uploaded
 * by hand are one row. A fetched document may not continue a lineage the caller did not name.
 */

export const TeaSbomStoreOutcome = {
    CREATED: 'CREATED',
    EXISTING: 'EXISTING',
    LINEAGE_EXISTS: 'LINEAGE_EXISTS',
    VERSION_CONFLICT: 'VERSION_CONFLICT',
    NOT_PARSEABLE: 'NOT_PARSEABLE',
    NOT_AN_SBOM: 'NOT_AN_SBOM',
    INVALID: 'INVALID',
    TOO_LARGE: 'TOO_LARGE',
    STORE_FAILED: 'STORE_FAILED'
} as const;
export type TeaSbomStoreOutcome = typeof TeaSbomStoreOutcome[keyof typeof TeaSbomStoreOutcome];

/** The size of the largest SBOM the upload door admits (the request body limit). */
export const TEA_SBOM_MAX_BYTES = 52428800;
const STORE_MESSAGE_MAX = 1024;
const CYCLONEDX = 'cyclonedx';
const SPDX_NAMESPACE_FIELD = 'documentNamespace';
const SPDX_NAMESPACE_TAKEN = 'The documentNamespace of this SPDX document already holds another document; a new version of an SPDX document needs a namespace of its own.';

export interface TeaLineageConflict {
    serialNumber: string;
    storedVersion: string | null;
    documentVersion: string | null;
    existingUuid: string | null;
}

export interface TeaSbomStoreResult extends TeaResultBase {
    stored: TeaSbomStoreOutcome | null;
    format: BomFormat | null;
    bom: BomRecord | null;
    conflict: TeaLineageConflict | null;
    storeMessage: string | null;
}

function stored(outcome: TeaSbomStoreOutcome, extra: Partial<TeaSbomStoreResult> = {}): TeaSbomStoreResult {
    return { ...teaResult(TeaOutcome.OK), stored: outcome, format: null, bom: null, conflict: null, storeMessage: null, ...extra };
}

function versionText(value: unknown): string | null {
    return value === null || value === undefined ? null : String(value);
}

/** The rule the ReARM backend applies to uploads: CycloneDX by bomFormat or specFormat, else SPDX by spdxVersion. */
export function sbomFormatOf(doc: unknown): BomFormat | null {
    if (doc === null || typeof doc !== 'object' || Array.isArray(doc)) return null;
    const obj = doc as Record<string, unknown>;
    const named = (value: unknown) => typeof value === 'string' && value.toLowerCase() === CYCLONEDX;
    if (named(obj.bomFormat) || named(obj.specFormat)) return 'CYCLONEDX';
    if (typeof obj.spdxVersion === 'string') return 'SPDX';
    return null;
}

/** The add path's digest of a parsed document: SHA-256 of JSON.stringify. */
function documentDigest(doc: unknown): string {
    return createHash('sha256').update(JSON.stringify(doc)).digest('hex');
}

/**
 * After a lost race (or a document already stored under another version): the row of the lineage
 * that holds this very document, or null.
 */
async function rowWithDigest(serialNumber: string, org: string, digest: string): Promise<BomRecord | null> {
    const rows = await BomRepository.allBomsBySerialNumber(serialNumber, org);
    return rows.find((row) => row.meta?.originalFileDigest === digest) ?? null;
}

/** The row of rebom.spdx_boms that holds an SPDX document's namespace in the organization, with its converted row. */
interface SpdxNamespaceHolder {
    spdxRow: SpdxBomRecord;
    converted: BomRecord | null;
}

function namespaceOf(doc: Record<string, unknown>): string {
    return typeof doc.documentNamespace === 'string' ? doc.documentNamespace : '';
}

async function spdxNamespaceHolder(doc: Record<string, unknown>, org: string): Promise<SpdxNamespaceHolder | null> {
    const spdxRow = await SpdxRepository.findSpdxBomByNamespace(namespaceOf(doc), org);
    if (!spdxRow) return null;
    const rows = spdxRow.converted_bom_uuid ? await BomRepository.bomById(spdxRow.converted_bom_uuid) : [];
    return { spdxRow, converted: rows[0] ?? null };
}

/**
 * The stored document that holds an SPDX namespace (design 3.6.4 step 2): its converted row, not the
 * newest row of that row's lineage. Null when the holder has no converted row with a serialNumber.
 */
function holderConflict(holder: SpdxNamespaceHolder | null): TeaLineageConflict | null {
    const converted = holder?.converted;
    const serialNumber = converted?.meta?.serialNumber;
    if (!converted || !serialNumber) return null;
    return {
        serialNumber,
        storedVersion: versionText(converted.meta?.bomVersion),
        documentVersion: null,
        existingUuid: converted.uuid
    };
}

/** A namespace held by another document: final, so INVALID with rebom's fixed sentence, never LINEAGE_EXISTS. */
function namespaceTaken(holder: SpdxNamespaceHolder | null): TeaSbomStoreResult {
    return stored(TeaSbomStoreOutcome.INVALID, { format: 'SPDX', storeMessage: SPDX_NAMESPACE_TAKEN, conflict: holderConflict(holder) });
}

/**
 * The SPDX rule of design 3.6.4, step 2, for every SPDX store, with or without a named lineage:
 * the same file with a converted row is EXISTING; the same file without one is an earlier store
 * that did not finish, STORE_FAILED; another document is INVALID. Null when the namespace is free.
 */
async function spdxNamespaceDecision(doc: Record<string, unknown>, digest: string, org: string): Promise<TeaSbomStoreResult | null> {
    const holder = await spdxNamespaceHolder(doc, org);
    if (!holder) return null;
    if (holder.spdxRow.file_sha256 !== digest) return namespaceTaken(holder);
    if (holder.converted) return stored(TeaSbomStoreOutcome.EXISTING, { format: 'SPDX', bom: holder.converted });
    const ref = randomBytes(4).toString('hex');
    logger.error({ ref, spdxUuid: holder.spdxRow.uuid }, 'TEA: the SPDX namespace holds this file without a converted document');
    return stored(TeaSbomStoreOutcome.STORE_FAILED, { format: 'SPDX', storeMessage: internalMessage(ref) });
}

async function readJson(file: TeaHeldFile): Promise<{ ok: true, doc: unknown } | { ok: false }> {
    const bytes = await fs.promises.readFile(file.path);
    try {
        // fatal: invalid UTF-8 is refused; the decoder drops one leading byte order mark
        const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
        return { ok: true, doc: JSON.parse(text) };
    } catch {
        return { ok: false };
    }
}

export async function storeHeldSbom(file: TeaHeldFile, request: TeaStoreRequest, maxBytes: number): Promise<TeaSbomStoreResult> {
    if (file.sizeBytes > maxBytes) return stored(TeaSbomStoreOutcome.TOO_LARGE);
    const read = await readJson(file);
    if (!read.ok) return stored(TeaSbomStoreOutcome.NOT_PARSEABLE);
    const format = sbomFormatOf(read.doc);
    if (!format) return stored(TeaSbomStoreOutcome.NOT_AN_SBOM);
    const doc = read.doc as Record<string, unknown>;
    const digest = documentDigest(doc);
    if (format === 'SPDX') {
        if (request.continueLineage) {
            // the SPDX update route always writes a new version: a document the named lineage already
            // holds is answered from the stored row, so a second store of it stays idempotent
            const same = await rowWithDigest(request.continueLineage, request.org, digest);
            if (same) return stored(TeaSbomStoreOutcome.EXISTING, { format, bom: same });
        }
        // a namespace holds one SPDX document per organization (rebom.spdx_boms is unique on it):
        // decided here, named lineage or not, before the add path pushes anything
        const decided = await spdxNamespaceDecision(doc, digest, request.org);
        if (decided) return decided;
    }
    try {
        const added = await addBomDetailed({
            bomInput: {
                bom: doc,
                format,
                org: request.org,
                tags: request.tags as Object | undefined,
                rebomOptions: { ...request.rebomOptions } as any,
                ...(format === 'SPDX' && request.continueLineage ? { existingSerialNumber: request.continueLineage } : {})
            }
        }, 'UPLOAD', { kind: LineagePolicyKind.NEW_OR_NAMED, named: request.continueLineage });
        const outcome = added.disposition === AddDisposition.EXISTING ? TeaSbomStoreOutcome.EXISTING : TeaSbomStoreOutcome.CREATED;
        return stored(outcome, { format, bom: added.record });
    } catch (error) {
        return await storeFailure(error, doc, digest, format, request);
    }
}

async function storeFailure(error: unknown, doc: Record<string, unknown>, digest: string, format: BomFormat, request: TeaStoreRequest): Promise<TeaSbomStoreResult> {
    if (error instanceof BomVersionConflictError || error instanceof BomLineageNotPermittedError) {
        // the loser of two stores of one document, or a document stored before: the same row
        const same = await rowWithDigest(error.serialNumber, request.org, digest);
        if (same) return stored(TeaSbomStoreOutcome.EXISTING, { format, bom: same });
        const conflict: TeaLineageConflict = {
            serialNumber: error.serialNumber,
            storedVersion: versionText(error.storedVersion),
            documentVersion: versionText(error.newVersion),
            existingUuid: error.existingUuid
        };
        const outcome = error instanceof BomVersionConflictError ? TeaSbomStoreOutcome.VERSION_CONFLICT : TeaSbomStoreOutcome.LINEAGE_EXISTS;
        return stored(outcome, { format, conflict });
    }
    if (error instanceof BomValidationError) {
        if (format === 'SPDX' && error.details?.field === SPDX_NAMESPACE_FIELD) {
            // only the loser of a race for the namespace meets the add path's refusal: the same answer as step 2
            return namespaceTaken(await spdxNamespaceHolder(doc, request.org));
        }
        return stored(TeaSbomStoreOutcome.INVALID, { format, storeMessage: printableAscii(error.message, STORE_MESSAGE_MAX) });
    }
    if (error instanceof RangeError) {
        return stored(TeaSbomStoreOutcome.INVALID, { format, storeMessage: printableAscii(error.message, STORE_MESSAGE_MAX) });
    }
    const ref = randomBytes(4).toString('hex');
    logger.error({ err: error, ref }, 'TEA: storing a fetched SBOM failed');
    return stored(TeaSbomStoreOutcome.STORE_FAILED, { format, storeMessage: internalMessage(ref) });
}
