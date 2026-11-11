import * as fs from 'fs';
import * as path from 'path';

/**
 * The documents of the CLI's machine mode, as rebom checks them (TPF-180 design 3.3, 3.6.1).
 * rebom types only what it decides on: the format version, the kind, and the rows of a download
 * manifest. Everything else of a document is returned as the CLI wrote it.
 */

export const TEA_FORMAT_VERSION = 1;

export const TeaDocumentKind = {
    RESOLUTION: 'TEA_RESOLUTION',
    PRODUCT: 'TEA_PRODUCT',
    COMPONENT: 'TEA_COMPONENT',
    RELEASE_LIST: 'TEA_RELEASE_LIST',
    RELEASE: 'TEA_RELEASE',
    DOWNLOAD_MANIFEST: 'TEA_DOWNLOAD_MANIFEST',
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
