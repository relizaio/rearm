import * as os from 'os';
import * as path from 'path';

/**
 * The REBOM_TEA_ settings, read once and validated (TPF-180 design 3.2, 3.5, 3.7, 3.8).
 * An invalid value never falls back to a default: the operations answer NOT_CONFIGURED with
 * reason SETTING and the variable's name (never its value).
 */

export const TeaLocalTesting = {
    OFF: 'OFF',
    ON: 'ON'
} as const;
export type TeaLocalTesting = typeof TeaLocalTesting[keyof typeof TeaLocalTesting];

export interface TeaCeilings {
    readDeadlineSeconds: number;
    downloadDeadlineSeconds: number;
    connectTimeoutSeconds: number;
    maxDocumentBytes: number;
    maxOutputBytes: number;
    maxPages: number;
    maxArtifactBytes: number;
    maxTotalBytes: number;
    maxFiles: number;
    maxDiskBytes: number;
    holdSeconds: number;
    maxChunkBytes: number;
    maxConcurrentReads: number;
    maxConcurrentDownloads: number;
    maxConcurrentResults: number;
    maxQueued: number;
    queueWaitSeconds: number;
}

interface SettingSpec {
    variable: string;
    key: keyof TeaCeilings;
    defaultValue: number;
    min: number;
    max: number;
}

/** The table of design 3.7: variable, default, range. */
export const TEA_SETTINGS: readonly SettingSpec[] = [
    { variable: 'REBOM_TEA_READ_DEADLINE_SECONDS', key: 'readDeadlineSeconds', defaultValue: 60, min: 1, max: 600 },
    { variable: 'REBOM_TEA_DOWNLOAD_DEADLINE_SECONDS', key: 'downloadDeadlineSeconds', defaultValue: 600, min: 1, max: 3600 },
    { variable: 'REBOM_TEA_CONNECT_TIMEOUT_SECONDS', key: 'connectTimeoutSeconds', defaultValue: 5, min: 1, max: 60 },
    { variable: 'REBOM_TEA_MAX_DOCUMENT_BYTES', key: 'maxDocumentBytes', defaultValue: 16777216, min: 1024, max: 268435456 },
    { variable: 'REBOM_TEA_MAX_OUTPUT_BYTES', key: 'maxOutputBytes', defaultValue: 8388608, min: 65536, max: 25165824 },
    { variable: 'REBOM_TEA_MAX_PAGES', key: 'maxPages', defaultValue: 10, min: 1, max: 1000 },
    { variable: 'REBOM_TEA_MAX_ARTIFACT_BYTES', key: 'maxArtifactBytes', defaultValue: 52428800, min: 1, max: 2147483647 },
    { variable: 'REBOM_TEA_MAX_TOTAL_BYTES', key: 'maxTotalBytes', defaultValue: 268435456, min: 1, max: 2147483647 },
    { variable: 'REBOM_TEA_MAX_FILES', key: 'maxFiles', defaultValue: 200, min: 1, max: 100000 },
    { variable: 'REBOM_TEA_MAX_DISK_BYTES', key: 'maxDiskBytes', defaultValue: 1073741824, min: 1048576, max: 68719476736 },
    { variable: 'REBOM_TEA_HOLD_SECONDS', key: 'holdSeconds', defaultValue: 600, min: 10, max: 3600 },
    { variable: 'REBOM_TEA_MAX_CHUNK_BYTES', key: 'maxChunkBytes', defaultValue: 8388608, min: 65536, max: 33554432 },
    { variable: 'REBOM_TEA_MAX_CONCURRENT_READS', key: 'maxConcurrentReads', defaultValue: 4, min: 1, max: 64 },
    { variable: 'REBOM_TEA_MAX_CONCURRENT_DOWNLOADS', key: 'maxConcurrentDownloads', defaultValue: 2, min: 1, max: 16 },
    { variable: 'REBOM_TEA_MAX_CONCURRENT_RESULTS', key: 'maxConcurrentResults', defaultValue: 2, min: 1, max: 16 },
    { variable: 'REBOM_TEA_MAX_QUEUED', key: 'maxQueued', defaultValue: 16, min: 0, max: 1024 },
    { variable: 'REBOM_TEA_QUEUE_WAIT_SECONDS', key: 'queueWaitSeconds', defaultValue: 20, min: 1, max: 300 }
];

export const TEA_CALLER_TOKEN_VARIABLE = 'REBOM_TEA_CALLER_TOKEN';
export const TEA_LOCAL_TESTING_VARIABLE = 'REBOM_TEA_LOCAL_TESTING';
export const TEA_TMP_ROOT_VARIABLE = 'REBOM_TEA_TMP_ROOT';
const TMP_ROOT_DEFAULT_NAME = 'rebom-tea';

/** 32 to 256 characters of printable ASCII without spaces. */
const CALLER_TOKEN_PATTERN = /^[\x21-\x7e]{32,256}$/;
const DIGITS = /^[0-9]+$/;

export interface TeaConfig {
    /** The caller token, or null when absent, empty or invalid: the operations are not configured. */
    callerToken: string | null;
    localTesting: TeaLocalTesting;
    tmpRoot: string;
    ceilings: TeaCeilings;
}

export type TeaConfigRead =
    | { ok: true, config: TeaConfig }
    | { ok: false, callerToken: string | null, localTesting: TeaLocalTesting, variable: string };

/** The token when it is valid, else null. */
export function validCallerToken(value: string | undefined): string | null {
    return value !== undefined && CALLER_TOKEN_PATTERN.test(value) ? value : null;
}

/** ON only for the exact string ON; any other value is OFF. */
export function localTestingOf(value: string | undefined): TeaLocalTesting {
    return value === TeaLocalTesting.ON ? TeaLocalTesting.ON : TeaLocalTesting.OFF;
}

/** Reads every setting from env. The first invalid one (in table order) is named. */
export function readTeaConfig(env: NodeJS.ProcessEnv): TeaConfigRead {
    const callerToken = validCallerToken(env[TEA_CALLER_TOKEN_VARIABLE]);
    const localTesting = localTestingOf(env[TEA_LOCAL_TESTING_VARIABLE]);
    const ceilings = {} as TeaCeilings;
    for (const spec of TEA_SETTINGS) {
        const raw = env[spec.variable];
        if (raw === undefined) {
            ceilings[spec.key] = spec.defaultValue;
            continue;
        }
        const value = DIGITS.test(raw) ? Number(raw) : NaN;
        if (!Number.isSafeInteger(value) || value < spec.min || value > spec.max) {
            return { ok: false, callerToken, localTesting, variable: spec.variable };
        }
        ceilings[spec.key] = value;
    }
    const rawRoot = env[TEA_TMP_ROOT_VARIABLE];
    let tmpRoot = path.join(os.tmpdir(), TMP_ROOT_DEFAULT_NAME);
    if (rawRoot !== undefined) {
        if (!path.isAbsolute(rawRoot)) {
            return { ok: false, callerToken, localTesting, variable: TEA_TMP_ROOT_VARIABLE };
        }
        tmpRoot = path.resolve(rawRoot);
    }
    return { ok: true, config: { callerToken, localTesting, tmpRoot, ceilings } };
}

/** The limits a caller may lower on every run. */
export interface TeaLimitsInput {
    deadlineSeconds?: number | null;
    connectTimeoutSeconds?: number | null;
    maxDocumentBytes?: number | null;
    maxOutputBytes?: number | null;
}

/** The limits a caller may lower on a fetch. */
export interface TeaFetchLimitsInput {
    maxArtifactBytes?: number | null;
    maxTotalBytes?: number | null;
    maxFiles?: number | null;
    holdSeconds?: number | null;
}

/** The limits that ran, echoed in every result. */
export interface TeaEffectiveLimits {
    deadlineSeconds: number;
    connectTimeoutSeconds: number;
    maxDocumentBytes: number;
    maxOutputBytes: number;
    maxPages: number | null;
    pageSize: number | null;
    maxArtifactBytes: number | null;
    maxTotalBytes: number | null;
    maxFiles: number | null;
    holdSeconds: number | null;
}

export const TeaRunKind = {
    READ: 'READ',
    RELEASE_LIST: 'RELEASE_LIST',
    DOWNLOAD: 'DOWNLOAD'
} as const;
export type TeaRunKind = typeof TeaRunKind[keyof typeof TeaRunKind];

/** The page size when the caller gives none (the CLI's default, and the TEA maximum). */
export const TEA_DEFAULT_PAGE_SIZE = 100;

function lower(caller: number | null | undefined, ceiling: number): number {
    return caller == null ? ceiling : Math.min(caller, ceiling);
}

/**
 * min(caller, ceiling) per limit: a caller can lower every limit and raise none. The values were
 * validated (integers at or above the CLI's minimum) before this is called.
 */
export function effectiveLimits(
    kind: TeaRunKind,
    ceilings: TeaCeilings,
    limits?: TeaLimitsInput | null,
    fetchLimits?: TeaFetchLimitsInput | null,
    paging?: { pageSize?: number | null, maxPages?: number | null }
): TeaEffectiveLimits {
    const deadlineCeiling = kind === TeaRunKind.DOWNLOAD ? ceilings.downloadDeadlineSeconds : ceilings.readDeadlineSeconds;
    const out: TeaEffectiveLimits = {
        deadlineSeconds: lower(limits?.deadlineSeconds, deadlineCeiling),
        connectTimeoutSeconds: lower(limits?.connectTimeoutSeconds, ceilings.connectTimeoutSeconds),
        maxDocumentBytes: lower(limits?.maxDocumentBytes, ceilings.maxDocumentBytes),
        maxOutputBytes: lower(limits?.maxOutputBytes, ceilings.maxOutputBytes),
        maxPages: null,
        pageSize: null,
        maxArtifactBytes: null,
        maxTotalBytes: null,
        maxFiles: null,
        holdSeconds: null
    };
    if (kind === TeaRunKind.RELEASE_LIST) {
        out.pageSize = paging?.pageSize ?? TEA_DEFAULT_PAGE_SIZE;
        out.maxPages = lower(paging?.maxPages, ceilings.maxPages);
    }
    if (kind === TeaRunKind.DOWNLOAD) {
        out.maxArtifactBytes = lower(fetchLimits?.maxArtifactBytes, ceilings.maxArtifactBytes);
        out.maxTotalBytes = lower(fetchLimits?.maxTotalBytes, ceilings.maxTotalBytes);
        out.maxFiles = lower(fetchLimits?.maxFiles, ceilings.maxFiles);
        out.holdSeconds = lower(fetchLimits?.holdSeconds, ceilings.holdSeconds);
    }
    return out;
}

/** The ceilings as teaStatus reports them. */
export function ceilingsAsLimits(ceilings: TeaCeilings): TeaEffectiveLimits {
    return {
        deadlineSeconds: ceilings.downloadDeadlineSeconds,
        connectTimeoutSeconds: ceilings.connectTimeoutSeconds,
        maxDocumentBytes: ceilings.maxDocumentBytes,
        maxOutputBytes: ceilings.maxOutputBytes,
        maxPages: ceilings.maxPages,
        pageSize: TEA_DEFAULT_PAGE_SIZE,
        maxArtifactBytes: ceilings.maxArtifactBytes,
        maxTotalBytes: ceilings.maxTotalBytes,
        maxFiles: ceilings.maxFiles,
        holdSeconds: ceilings.holdSeconds
    };
}
