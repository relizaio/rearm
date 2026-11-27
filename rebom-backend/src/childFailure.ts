/**
 * The failure text of a child CLI (rearm, cyclonedx-cli), bounded and cleaned (TPF-261 design 3.1 to 3.3).
 *
 * A failed child used to hand its whole stdout on as the failure: it was logged, stored on the BOM row
 * as meta.enrichmentError and served over GraphQL. For enrichment that stdout starts with the BEAR URI
 * as configured (userinfo included) and holds BEAR's response body on a non-2xx answer. This module
 * turns it into one short line. It imports nothing of rebom (no logger, no database), so the helper, the
 * catch blocks, the store and the read path all call the same function and cannot diverge.
 */

/** The longest failure text, in characters. */
export const FAILURE_TEXT_CAP = 300;
/** The most characters of the picked line any rule after the pick looks at. */
export const FAILURE_TEXT_WINDOW = 2000;
/** What stands where something was removed. */
export const REMOVED_MARK = '[removed]';

export enum ChildFailureKind {
    EXIT_CODE = 'EXIT_CODE',
    SIGNAL = 'SIGNAL',
    TIMEOUT = 'TIMEOUT',
    NOT_STARTED = 'NOT_STARTED'
}

/**
 * The rejection of a failed child CLI. Its message is the bounded text; it carries nothing else of the
 * child (no stdout, stderr, arguments, environment or spawn arguments), so a logger that prints the
 * fields of an error prints nothing unbounded.
 */
export class ChildProcessError extends Error {
    readonly kind: ChildFailureKind;
    readonly exitCode: number | null;
    readonly signal: string | null;
    readonly timeoutMs: number | null;

    constructor(message: string, kind: ChildFailureKind, exitCode: number | null, signal: string | null, timeoutMs: number | null) {
        super(message);
        this.name = 'ChildProcessError';
        this.kind = kind;
        this.exitCode = exitCode;
        this.signal = signal;
        this.timeoutMs = timeoutMs;
    }
}

/** How a child ended, as the helper saw it. */
export interface ChildOutcome {
    /** The child was sent the signal of the spawn timeout (child.killed), whatever code or signal it ended with. */
    killedByTimeout: boolean;
    exitCode: number | null;
    signal: string | null;
    timeoutMs?: number;
}

const VISIBLE = /[\x21-\x7E]/;
const DIGIT = /[0-9]/;
const STATUS_PHRASE = 'returned status ';

/**
 * The line a failure text is read from (rule 1): the first line that starts with `Error`, else the last
 * line that holds a visible character, else none. Lines end at a line feed. One pass over the text.
 */
function pickLine(text: string): string | null {
    let lastVisible: string | null = null;
    let start = 0;
    while (start <= text.length) {
        let end = text.indexOf('\n', start);
        if (end === -1) end = text.length;
        const line = text.slice(start, end);
        if (line.startsWith('Error')) return line;
        if (VISIBLE.test(line)) lastVisible = line;
        start = end + 1;
    }
    return lastVisible;
}

/** Rule 5: at the first `returned status ` followed by digits, everything after the digits is dropped. */
function dropAfterStatus(text: string): string {
    let at = text.indexOf(STATUS_PHRASE);
    while (at !== -1) {
        let end = at + STATUS_PHRASE.length;
        if (end < text.length && DIGIT.test(text[end])) {
            while (end < text.length && DIGIT.test(text[end])) end++;
            return text.slice(0, end);
        }
        at = text.indexOf(STATUS_PHRASE, at + 1);
    }
    return text;
}

/**
 * Rule 6: every segment from a double quote to the next one becomes the mark; inside a segment a
 * backslash takes the next character with it; a quote without a closing one takes the rest of the text.
 */
function markQuoted(text: string): string {
    let out = '';
    let i = 0;
    while (i < text.length) {
        const c = text[i];
        if (c !== '"') {
            out += c;
            i++;
            continue;
        }
        let j = i + 1;
        while (j < text.length && text[j] !== '"') j += text[j] === '\\' ? 2 : 1;
        out += REMOVED_MARK;
        i = j + 1;
    }
    return out;
}

/** Rule 7: every run of non-space characters that holds `://` or `@` becomes the mark. */
function markUriTokens(text: string): string {
    return text.split(' ').map((token) => (token.includes('://') || token.includes('@') ? REMOVED_MARK : token)).join(' ');
}

/**
 * Bounds and cleans a failure text (design 3.3). The result has at most FAILURE_TEXT_CAP characters, all
 * printable ASCII on one line, and holds no double quote, no `://`, no `@` and nothing after
 * `returned status <n>`. Applying it to its own result returns the same text.
 */
export function boundFailureText(raw: unknown): string {
    const text = typeof raw === 'string' ? raw : String(raw);
    // 1. the line
    const line = pickLine(text);
    if (line === null) return '';
    // 2. the window
    const cut = line.length > FAILURE_TEXT_WINDOW;
    let s = cut ? line.slice(0, FAILURE_TEXT_WINDOW) : line;
    // 3. printable ASCII only
    s = s.replace(/[^\x20-\x7E]/g, ' ');
    // 4. a token cut by the window is dropped
    if (cut) {
        const lastSpace = s.lastIndexOf(' ');
        s = lastSpace === -1 ? REMOVED_MARK : s.slice(0, lastSpace + 1) + REMOVED_MARK;
    }
    // 5. the response body
    s = dropAfterStatus(s);
    // 6. quoted segments
    s = markQuoted(s);
    // 7. URI tokens
    s = markUriTokens(s);
    // 8. spaces
    s = s.replace(/ {2,}/g, ' ').trim();
    // 9. the cap
    return s.slice(0, FAILURE_TEXT_CAP).trimEnd();
}

function failureText(cmd: string, reason: string, detail: string | null): string {
    return boundFailureText(detail === null ? `${cmd} ${reason}` : `${cmd} ${reason}: ${detail}`);
}

/**
 * The error a child that ended with a non-zero code or a signal rejects with: `<cmd> <reason>` and, when
 * its stdout gives one, `: <detail>` (design 3.2). The arguments are never part of it.
 */
export function childFailure(cmd: string, outcome: ChildOutcome, stdout: string): ChildProcessError {
    const detail = pickLine(stdout ?? '');
    if (outcome.killedByTimeout) {
        const timeoutMs = outcome.timeoutMs ?? null;
        return new ChildProcessError(failureText(cmd, `timed out after ${timeoutMs} ms`, detail), ChildFailureKind.TIMEOUT,
            outcome.exitCode, outcome.signal, timeoutMs);
    }
    if (outcome.signal) {
        return new ChildProcessError(failureText(cmd, `was killed by signal ${outcome.signal}`, detail), ChildFailureKind.SIGNAL,
            outcome.exitCode, outcome.signal, null);
    }
    return new ChildProcessError(failureText(cmd, `exited with code ${outcome.exitCode}`, detail), ChildFailureKind.EXIT_CODE,
        outcome.exitCode, null, null);
}

/** The error for a child that could not be started (the errno code such as ENOENT, else unknown). No detail. */
export function childNotStarted(cmd: string, code?: string): ChildProcessError {
    return new ChildProcessError(failureText(cmd, `could not be started: ${code || 'unknown'}`, null), ChildFailureKind.NOT_STARTED,
        null, null, null);
}
