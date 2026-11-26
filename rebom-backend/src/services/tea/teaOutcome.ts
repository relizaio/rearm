import { ShellResult } from '../../utils';
import { parseTeaDocument, printableAscii, TeaDocumentKind, TEA_FORMAT_VERSION } from './teaDocument';

/**
 * Outcomes of the TEA operations and the mapping of a CLI run to one (TPF-180 design 3.3, 3.4).
 * Outcomes are data in every result, never GraphQL errors. This module is the only place that
 * names an outcome or one of rebom's own reasons as a string.
 */

export const TEA_API_VERSION = 1;

export const TeaOutcome = {
    OK: 'OK',
    PARTIAL: 'PARTIAL',
    NOT_CONFIGURED: 'NOT_CONFIGURED',
    CALLER_REFUSED: 'CALLER_REFUSED',
    NOT_AVAILABLE: 'NOT_AVAILABLE',
    INVALID_REQUEST: 'INVALID_REQUEST',
    BUSY: 'BUSY',
    FETCH_NOT_HELD: 'FETCH_NOT_HELD',
    NOT_FOUND: 'NOT_FOUND',
    URL_REFUSED: 'URL_REFUSED',
    AUTH: 'AUTH',
    ADDRESS_REFUSED: 'ADDRESS_REFUSED',
    LIMIT: 'LIMIT',
    CONNECT_FAILED: 'CONNECT_FAILED',
    TIMEOUT: 'TIMEOUT',
    CONTRACT_MISMATCH: 'CONTRACT_MISMATCH',
    FAILED: 'FAILED'
} as const;
export type TeaOutcome = typeof TeaOutcome[keyof typeof TeaOutcome];

/** rebom's own reasons. The CLI's reasons (an open set) are copied from its error object. */
export const TeaReason = {
    CALLER_TOKEN: 'CALLER_TOKEN',
    SETTING: 'SETTING',
    TMP_ROOT: 'TMP_ROOT',
    CALLER: 'CALLER',
    INPUT: 'INPUT',
    SLOTS: 'SLOTS',
    QUEUE_FULL: 'QUEUE_FULL',
    DISK: 'DISK',
    FETCH: 'FETCH',
    FILE_NOT_HELD: 'FILE_NOT_HELD',
    INTERNAL: 'INTERNAL',
    CLI_MISSING: 'CLI_MISSING',
    CLI_TOO_OLD: 'CLI_TOO_OLD',
    CLI_FORMAT: 'CLI_FORMAT',
    NO_ERROR_OBJECT: 'NO_ERROR_OBJECT',
    EXIT_CODE: 'EXIT_CODE',
    SIGNAL: 'SIGNAL',
    KILLED_AT_DEADLINE: 'KILLED_AT_DEADLINE',
    OUTPUT_OVERFLOW: 'OUTPUT_OVERFLOW',
    OUTPUT_NOT_JSON: 'OUTPUT_NOT_JSON',
    FORMAT_VERSION: 'FORMAT_VERSION',
    KIND: 'KIND',
    UNEXPECTED_PARTIAL: 'UNEXPECTED_PARTIAL',
    MANIFEST_INVALID: 'MANIFEST_INVALID',
    PARTIAL: 'PARTIAL'
} as const;
export type TeaReason = typeof TeaReason[keyof typeof TeaReason];

/** One fixed sentence per outcome: no value of the request, the server or the environment in it. */
const MESSAGES: Record<TeaOutcome, string> = {
    OK: 'The operation succeeded.',
    PARTIAL: 'Some files of the collection were not fetched; the rows say which and why.',
    NOT_CONFIGURED: 'The TEA operations are not configured on this rebom.',
    CALLER_REFUSED: 'The caller is not allowed to use the TEA operations.',
    NOT_AVAILABLE: 'The TEA client is not available on this rebom.',
    INVALID_REQUEST: 'The request is invalid.',
    BUSY: 'rebom is busy with other TEA operations; try again later.',
    FETCH_NOT_HELD: 'The fetched files are no longer held; fetch the collection again.',
    NOT_FOUND: 'The TEA server does not know the requested object.',
    URL_REFUSED: 'The TEA client refused a URL or a redirect.',
    AUTH: 'The TEA server refused the request for lack of a valid credential.',
    ADDRESS_REFUSED: 'The TEA client refused to connect to an address it does not allow.',
    LIMIT: 'A limit of the TEA client was reached.',
    CONNECT_FAILED: 'The TEA server could not be reached.',
    TIMEOUT: 'The TEA client did not finish in time and was stopped.',
    CONTRACT_MISMATCH: 'The TEA client answered in a form rebom does not accept.',
    FAILED: 'The TEA operation failed.'
};

export const TEA_DETAIL_MAX = 2048;
export const TEA_URL_MAX = 2048;
const TEA_REASON_MAX = 64;
const TEA_SHORT_MAX = 256;
const PROXY_VARIABLES = ['HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'NO_PROXY', 'http_proxy', 'https_proxy', 'all_proxy', 'no_proxy'];

export interface TeaErrorDto {
    reason: string;
    cliExitCode: number | null;
    message: string;
    detail: string | null;
    url: string | null;
    httpStatus: number | null;
    address: string | null;
    addressClass: string | null;
    phase: string | null;
    hint: string | null;
    ignoredProxyVariables: string[] | null;
}

/** The fields every result type carries. */
export interface TeaResultBase {
    teaApiVersion: number;
    outcome: TeaOutcome;
    error: TeaErrorDto | null;
}

export function teaError(outcome: TeaOutcome, reason: string, detail?: string | null, message?: string): TeaErrorDto {
    return {
        reason,
        cliExitCode: null,
        message: message ?? MESSAGES[outcome],
        detail: detail == null ? null : printableAscii(detail, TEA_DETAIL_MAX),
        url: null,
        httpStatus: null,
        address: null,
        addressClass: null,
        phase: null,
        hint: null,
        ignoredProxyVariables: null
    };
}

/** A result with the outcome and, unless it is OK, the error. */
export function teaResult(outcome: TeaOutcome, reason?: string, detail?: string | null, message?: string): TeaResultBase {
    return {
        teaApiVersion: TEA_API_VERSION,
        outcome,
        error: outcome === TeaOutcome.OK ? null : teaError(outcome, reason ?? TeaReason.INTERNAL, detail, message)
    };
}

export function teaOk(): TeaResultBase {
    return teaResult(TeaOutcome.OK);
}

/** The message of an INTERNAL failure: the reference that is also in the log line. */
export function internalMessage(ref: string): string {
    return `Internal error (ref ${ref})`;
}

/** What a run came to: the outcome, the error, and for 0 (and 7 on download) the document. */
export interface TeaRunMapping {
    outcome: TeaOutcome;
    error: TeaErrorDto | null;
    /** The CLI's line, as it wrote it, without the line feed. */
    line?: string;
    /** The same line, parsed. */
    doc?: Record<string, unknown>;
}

function stringField(obj: Record<string, unknown>, key: string, max: number): string | null {
    const value = obj[key];
    return typeof value === 'string' ? printableAscii(value, max) : null;
}

/**
 * The CLI's error object: a complete line of standard error, read from the last one backwards,
 * that is a JSON object with formatVersion 1 and kind TEA_ERROR. Null when there is none, or when
 * its exitCode is not the real exit code.
 */
export function findTeaErrorObject(stderr: string, exitCode: number | null): Record<string, unknown> | null {
    const lines = stderr.split('\n');
    // the part after the last line feed is not a complete line
    lines.pop();
    for (let i = lines.length - 1; i >= 0; i--) {
        const line = lines[i];
        if (!line.startsWith('{')) continue;
        let parsed: unknown;
        try {
            parsed = JSON.parse(line);
        } catch {
            continue;
        }
        if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) continue;
        const obj = parsed as Record<string, unknown>;
        if (obj.formatVersion !== TEA_FORMAT_VERSION || obj.kind !== TeaDocumentKind.ERROR) continue;
        return obj.exitCode === exitCode ? obj : null;
    }
    return null;
}

/** Names (never values) of proxy variables set in rebom's own environment. */
export function proxyVariableNames(env: NodeJS.ProcessEnv): string[] {
    return PROXY_VARIABLES.filter((name) => env[name] !== undefined);
}

function cliError(outcome: TeaOutcome, exitCode: number, obj: Record<string, unknown> | null, fixedReason?: string): TeaErrorDto {
    const error = teaError(outcome, fixedReason ?? TeaReason.NO_ERROR_OBJECT);
    error.cliExitCode = exitCode;
    if (!obj) return error;
    if (!fixedReason) {
        const reason = stringField(obj, 'reason', TEA_REASON_MAX);
        if (reason) error.reason = reason;
    }
    error.detail = stringField(obj, 'message', TEA_DETAIL_MAX);
    error.url = stringField(obj, 'url', TEA_URL_MAX);
    error.httpStatus = Number.isInteger(obj.httpStatus) ? obj.httpStatus as number : null;
    error.address = stringField(obj, 'address', TEA_SHORT_MAX);
    error.addressClass = stringField(obj, 'addressClass', TEA_REASON_MAX);
    error.phase = stringField(obj, 'phase', TEA_REASON_MAX);
    error.hint = stringField(obj, 'hint', TEA_DETAIL_MAX);
    if (Array.isArray(obj.ignoredProxyVariables)) {
        error.ignoredProxyVariables = obj.ignoredProxyVariables
            .filter((name): name is string => typeof name === 'string')
            .map((name) => printableAscii(name, TEA_REASON_MAX));
    }
    return error;
}

const OUTCOME_BY_EXIT_CODE: Record<number, TeaOutcome> = {
    1: TeaOutcome.FAILED,
    2: TeaOutcome.INVALID_REQUEST,
    3: TeaOutcome.NOT_FOUND,
    5: TeaOutcome.URL_REFUSED,
    6: TeaOutcome.AUTH,
    8: TeaOutcome.ADDRESS_REFUSED,
    9: TeaOutcome.LIMIT,
    10: TeaOutcome.CONNECT_FAILED
};
const EXIT_OK = 0;
const EXIT_PARTIAL = 7;
const EXIT_CONNECT_FAILED = 10;

/**
 * Turns the result of a run into an outcome (design 3.4). The real exit code decides; the error
 * object only fills the fields. No raw standard error or standard output is ever returned.
 */
export function mapTeaRun(run: ShellResult, expectedKind: TeaDocumentKind, parentEnv: NodeJS.ProcessEnv): TeaRunMapping {
    if (run.spawnError) {
        return { outcome: TeaOutcome.NOT_AVAILABLE, error: teaError(TeaOutcome.NOT_AVAILABLE, TeaReason.CLI_MISSING) };
    }
    if (run.timedOut) {
        return { outcome: TeaOutcome.TIMEOUT, error: teaError(TeaOutcome.TIMEOUT, TeaReason.KILLED_AT_DEADLINE) };
    }
    if (run.stdoutOverflow) {
        return { outcome: TeaOutcome.CONTRACT_MISMATCH, error: teaError(TeaOutcome.CONTRACT_MISMATCH, TeaReason.OUTPUT_OVERFLOW) };
    }
    if (run.code === null) {
        return { outcome: TeaOutcome.FAILED, error: teaError(TeaOutcome.FAILED, TeaReason.SIGNAL) };
    }
    const code = run.code;
    if (code === EXIT_OK || code === EXIT_PARTIAL) {
        const isDownload = expectedKind === TeaDocumentKind.DOWNLOAD_MANIFEST;
        if (code === EXIT_PARTIAL && !isDownload) {
            return mismatch(code, TeaReason.UNEXPECTED_PARTIAL);
        }
        const parsed = parseTeaDocument(run.stdout, expectedKind);
        if (!parsed.ok) {
            return mismatch(code, code === EXIT_PARTIAL ? TeaReason.UNEXPECTED_PARTIAL : parsed.reason);
        }
        if (code === EXIT_PARTIAL) {
            const error = teaError(TeaOutcome.PARTIAL, TeaReason.PARTIAL);
            error.cliExitCode = code;
            return { outcome: TeaOutcome.PARTIAL, error, line: parsed.line, doc: parsed.doc };
        }
        return { outcome: TeaOutcome.OK, error: null, line: parsed.line, doc: parsed.doc };
    }
    const obj = findTeaErrorObject(run.stderr, code);
    const outcome = OUTCOME_BY_EXIT_CODE[code];
    if (!outcome) {
        // exit 4 (the machine mode never returns it) and any code outside the table
        return { outcome: TeaOutcome.FAILED, error: cliError(TeaOutcome.FAILED, code, obj, TeaReason.EXIT_CODE) };
    }
    const error = cliError(outcome, code, obj);
    if (code === EXIT_CONNECT_FAILED) {
        // the child never sees rebom's proxy variables; name the ones set here
        const names = new Set(error.ignoredProxyVariables ?? []);
        for (const name of proxyVariableNames(parentEnv)) names.add(name);
        error.ignoredProxyVariables = [...names];
    }
    return { outcome, error };
}

function mismatch(code: number, reason: string): TeaRunMapping {
    const error = teaError(TeaOutcome.CONTRACT_MISMATCH, reason);
    error.cliExitCode = code;
    return { outcome: TeaOutcome.CONTRACT_MISMATCH, error };
}
