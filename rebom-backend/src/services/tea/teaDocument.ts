import * as fs from 'fs';
import * as path from 'path';

/**
 * The documents of the CLI's machine mode, as rebom checks them (TPF-180 design 3.3, 3.6.1).
 * rebom types only what it decides on: the format version, the kind, the rows of a download
 * manifest and the page of a list (TPF-241). Everything else of a document is returned as the CLI
 * wrote it.
 */

export const TEA_FORMAT_VERSION = 1;

/** A uuid, 8-4-4-4-12 hexadecimal in either case: the one uuid pattern of the TEA module. */
export const TEA_UUID_PATTERN = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

export const TeaDocumentKind = {
    RESOLUTION: 'TEA_RESOLUTION',
    PRODUCT: 'TEA_PRODUCT',
    COMPONENT: 'TEA_COMPONENT',
    RELEASE_LIST: 'TEA_RELEASE_LIST',
    RELEASE: 'TEA_RELEASE',
    DOWNLOAD_MANIFEST: 'TEA_DOWNLOAD_MANIFEST',
    PRODUCT_LIST: 'TEA_PRODUCT_LIST',
    COMPONENT_LIST: 'TEA_COMPONENT_LIST',
    ERROR: 'TEA_ERROR'
} as const;
export type TeaDocumentKind = typeof TeaDocumentKind[keyof typeof TeaDocumentKind];

/** Why a document line is refused; the values are reasons of TeaOutcome CONTRACT_MISMATCH. */
export const TeaDocumentRefusal = {
    OUTPUT_NOT_JSON: 'OUTPUT_NOT_JSON',
    FORMAT_VERSION: 'FORMAT_VERSION',
    KIND: 'KIND'
} as const;
export type TeaDocumentRefusal = typeof TeaDocumentRefusal[keyof typeof TeaDocumentRefusal];

export type TeaParsedDocument =
    | { ok: true, line: string, doc: Record<string, unknown> }
    | { ok: false, reason: TeaDocumentRefusal };

/**
 * Standard output of a run: exactly one line (an optional final line feed), a JSON object,
 * formatVersion the number 1, and the kind expected for the verb.
 */
export function parseTeaDocument(stdout: string, expectedKind: TeaDocumentKind): TeaParsedDocument {
    const line = stdout.endsWith('\n') ? stdout.slice(0, -1) : stdout;
    if (line.length === 0 || line.includes('\n')) return { ok: false, reason: TeaDocumentRefusal.OUTPUT_NOT_JSON };
    let parsed: unknown;
    try {
        parsed = JSON.parse(line);
    } catch {
        return { ok: false, reason: TeaDocumentRefusal.OUTPUT_NOT_JSON };
    }
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        return { ok: false, reason: TeaDocumentRefusal.OUTPUT_NOT_JSON };
    }
    const doc = parsed as Record<string, unknown>;
    if (doc.formatVersion !== TEA_FORMAT_VERSION) return { ok: false, reason: TeaDocumentRefusal.FORMAT_VERSION };
    if (doc.kind !== expectedKind) return { ok: false, reason: TeaDocumentRefusal.KIND };
    return { ok: true, line, doc };
}

/** Which of SBOMs, VEX and everything else a document is, by its TEA artifact type. */
export const TeaDocumentClass = {
    SBOM: 'SBOM',
    VEX: 'VEX',
    OTHER: 'OTHER'
} as const;
export type TeaDocumentClass = typeof TeaDocumentClass[keyof typeof TeaDocumentClass];

/** The TEA artifact types the classes stand for. */
export const TEA_ARTIFACT_TYPE_BOM = 'BOM';
export const TEA_ARTIFACT_TYPE_VULNERABILITIES = 'VULNERABILITIES';

/** BOM is SBOM, VULNERABILITIES is VEX, anything else (unknown or missing included) is OTHER. */
export function documentClassOf(artifactType: unknown): TeaDocumentClass {
    if (artifactType === TEA_ARTIFACT_TYPE_BOM) return TeaDocumentClass.SBOM;
    if (artifactType === TEA_ARTIFACT_TYPE_VULNERABILITIES) return TeaDocumentClass.VEX;
    return TeaDocumentClass.OTHER;
}

export const TeaFileStatus = {
    VERIFIED: 'VERIFIED',
    FAILED: 'FAILED',
    NOT_ATTEMPTED: 'NOT_ATTEMPTED',
    NOT_SELECTED: 'NOT_SELECTED'
} as const;
export type TeaFileStatus = typeof TeaFileStatus[keyof typeof TeaFileStatus];
const FILE_STATUSES = new Set<string>(Object.values(TeaFileStatus));

/** One manifest row as rebom keeps it. */
export interface TeaManifestRow {
    number: number;
    status: TeaFileStatus;
    reason: string | null;
    documentClass: TeaDocumentClass;
    /** VERIFIED rows: the size the CLI reported, equal to the file's. */
    sizeBytes: number | null;
}

const ROW_REASON_MAX = 64;

/** The CLI's name of a row's file: the number as six digits and .bin. */
export function teaFileName(number: number): string {
    return `${String(number).padStart(6, '0')}.bin`;
}

/** The path of a held file, derived from its number alone; no string of a server takes part. */
export function teaFilePath(dlDir: string, number: number): string {
    return path.join(dlDir, teaFileName(number));
}

/** Text as printable ASCII (anything else becomes ?), at most max bytes. */
export function printableAscii(value: string, max: number): string {
    let out = '';
    for (const ch of value) {
        if (out.length >= max) break;
        const code = ch.codePointAt(0)!;
        out += code >= 0x20 && code <= 0x7e ? ch : '?';
    }
    return out;
}

function rowReason(value: unknown): string | null {
    return typeof value === 'string' ? printableAscii(value, ROW_REASON_MAX) : null;
}

async function lstatOrNull(p: string): Promise<fs.Stats | null> {
    try {
        return await fs.promises.lstat(p);
    } catch {
        return null;
    }
}

/**
 * The manifest checked against the download directory, strictly (design 3.6.1 step 5). Null when
 * anything disagrees: the caller then answers MANIFEST_INVALID and removes everything.
 */
export async function checkTeaManifest(doc: Record<string, unknown>, dlDir: string, maxArtifactBytes: number): Promise<TeaManifestRow[] | null> {
    const files = doc.files;
    if (!Array.isArray(files)) return null;
    const rows: TeaManifestRow[] = [];
    const expected = new Set<string>();
    let previous = 0;
    for (const raw of files) {
        if (raw === null || typeof raw !== 'object' || Array.isArray(raw)) return null;
        const row = raw as Record<string, unknown>;
        const number = row.number;
        if (typeof number !== 'number' || !Number.isInteger(number) || number <= previous) return null;
        previous = number;
        if (typeof row.status !== 'string' || !FILE_STATUSES.has(row.status)) return null;
        const status = row.status as TeaFileStatus;
        let sizeBytes: number | null = null;
        if (status === TeaFileStatus.VERIFIED) {
            const name = teaFileName(number);
            if (row.fileName !== name) return null;
            const size = row.sizeBytes;
            if (typeof size !== 'number' || !Number.isInteger(size) || size < 0 || size > maxArtifactBytes) return null;
            const stat = await lstatOrNull(path.join(dlDir, name));
            if (!stat || !stat.isFile() || stat.size !== size) return null;
            expected.add(name);
            sizeBytes = size;
        }
        rows.push({ number, status, reason: rowReason(row.reason), documentClass: documentClassOf(row.artifactType), sizeBytes });
    }
    const dirStat = await lstatOrNull(dlDir);
    if (dirStat === null) {
        // the CLI made no directory: valid only when it kept no file
        return expected.size === 0 ? rows : null;
    }
    if (!dirStat.isDirectory()) return null;
    const entries = await fs.promises.readdir(dlDir);
    for (const entry of entries) {
        if (!expected.has(entry)) return null;
    }
    return rows;
}

// ---------------------------------------------------------------------------------------------
// The list pages (TPF-241 design 3.5)

/** The key of the row array of each list document. */
const LIST_ROW_KEY: Partial<Record<TeaDocumentKind, string>> = {
    [TeaDocumentKind.PRODUCT_LIST]: 'products',
    [TeaDocumentKind.COMPONENT_LIST]: 'components'
};

/** Whether rows of the server's answer are missing from a page; the CLI's set is open. */
export const TeaListCut = {
    NONE: 'NONE',
    MAX_ROWS: 'MAX_ROWS',
    OTHER: 'OTHER'
} as const;
export type TeaListCut = typeof TeaListCut[keyof typeof TeaListCut];

/** Whether a page follows, and whether its token can be carried to the next call. */
export const TeaListNext = {
    NONE: 'NONE',
    TOKEN: 'TOKEN',
    TOKEN_UNUSABLE: 'TOKEN_UNUSABLE'
} as const;
export type TeaListNext = typeof TeaListNext[keyof typeof TeaListNext];

export interface TeaListIdentifier {
    idType: string;
    idValue: string;
}

/** One product or component of a list; every string is the server's text, unchanged. */
export interface TeaListRow {
    uuid: string;
    name: string;
    identifiers: TeaListIdentifier[];
}

/** A list document as checked: the rows and the CLI's page object, nothing else of it. */
export interface TeaListDocument {
    rows: TeaListRow[];
    nextPageToken: string | null;
    rowsReceived: number;
    cutBy: string;
}

function isObject(value: unknown): value is Record<string, unknown> {
    return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function listIdentifierOf(raw: unknown): TeaListIdentifier | null {
    if (!isObject(raw) || typeof raw.idType !== 'string' || typeof raw.idValue !== 'string') return null;
    return { idType: raw.idType, idValue: raw.idValue };
}

function listRowOf(raw: unknown): TeaListRow | null {
    if (!isObject(raw)) return null;
    if (typeof raw.uuid !== 'string' || !TEA_UUID_PATTERN.test(raw.uuid)) return null;
    // the empty name is allowed: the CLI prints it for a row without a name
    if (typeof raw.name !== 'string') return null;
    if (!Array.isArray(raw.identifiers)) return null;
    const identifiers: TeaListIdentifier[] = [];
    for (const entry of raw.identifiers) {
        const identifier = listIdentifierOf(entry);
        if (!identifier) return null;
        identifiers.push(identifier);
    }
    return { uuid: raw.uuid, name: raw.name, identifiers };
}

/**
 * The strict typed read of a TEA_PRODUCT_LIST or TEA_COMPONENT_LIST document. Null when any part
 * has another shape: the caller then answers LIST_INVALID and returns nothing of the document.
 * Keys this function does not name are dropped.
 */
export function teaListPageOf(doc: Record<string, unknown>, kind: TeaDocumentKind, maxRows: number): TeaListDocument | null {
    const key = LIST_ROW_KEY[kind];
    if (key === undefined) return null;
    const rawRows = doc[key];
    if (!Array.isArray(rawRows) || rawRows.length > maxRows) return null;
    const rows: TeaListRow[] = [];
    for (const raw of rawRows) {
        const row = listRowOf(raw);
        if (!row) return null;
        rows.push(row);
    }
    const page = doc.page;
    if (!isObject(page)) return null;
    const token = page.nextPageToken;
    if (token !== null && (typeof token !== 'string' || token.length === 0)) return null;
    const received = page.rowsReceived;
    if (typeof received !== 'number' || !Number.isSafeInteger(received) || received < rows.length) return null;
    if (typeof page.cutBy !== 'string' || page.cutBy.length === 0) return null;
    return { rows, nextPageToken: token, rowsReceived: received, cutBy: page.cutBy };
}

/** NONE and MAX_ROWS as named; any other cut is OTHER (rows are missing all the same). */
export function teaListCutOf(cutBy: string): TeaListCut {
    if (cutBy === TeaListCut.NONE) return TeaListCut.NONE;
    if (cutBy === TeaListCut.MAX_ROWS) return TeaListCut.MAX_ROWS;
    return TeaListCut.OTHER;
}
