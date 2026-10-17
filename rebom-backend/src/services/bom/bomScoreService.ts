import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { logger } from '../../logger';
import { shellExecDetailed, ShellResult } from '../../utils';
import { BomScoreError, BomScoreErrorReason } from '../../types/errors';

/**
 * Stateless SBOM scoring: hands the document to `rearm bomutils score` and returns
 * its JSON report verbatim. Nothing is stored, cached or logged of the document or
 * the report; the only module state is the slot counter below.
 * Design: SCORE-4 architecture round 1 (amends SCORE-2 section 3.6).
 */

export const SCORE_PROFILES: readonly string[] = ['cisa-2026', 'ntia-2021', 'fda'];
export const SCORE_TIMEOUT_MS = 60000;
export const SCORE_MAX_INPUT_BYTES = 50 * 1024 * 1024;
export const SCORE_MAX_REPORT_BYTES = 16 * 1024 * 1024;
export const SCORE_MAX_CONCURRENT = 4;
export const SCORE_REPORT_VERSION = 1;

const REARM_CLI = 'rearm';
const TEMP_DIR_PREFIX = 'rebom-score-';
const MAX_STDERR_LINE = 500;
const MAX_PROFILE_ECHO = 64;
const REFUSAL_PREFIXES = ['unsupported SBOM: ', 'unparsable SBOM: '];
const OLD_CLI_MARKERS = ['unknown flag', 'unknown command'];
const OUTCOME_OK = 'ok';
const OUTCOME_ERROR = 'error';

/** What the per-call log line records: ok, a BomScoreErrorReason, or error for anything else. */
type ScoreOutcome = typeof OUTCOME_OK | typeof OUTCOME_ERROR | BomScoreErrorReason;

// At most SCORE_MAX_CONCURRENT CLI processes; later calls wait in FIFO order.
let runningScores = 0;
const waitingScores: Array<() => void> = [];

async function acquireSlot(): Promise<void> {
    if (runningScores < SCORE_MAX_CONCURRENT) {
        runningScores++;
        return;
    }
    await new Promise<void>((resolve) => waitingScores.push(resolve));
}

function releaseSlot(): void {
    const next = waitingScores.shift();
    if (next) {
        // the slot passes straight to the next caller; the count stays the same
        next();
    } else {
        runningScores--;
    }
}

function validateProfiles(profiles: string[]): void {
    const valid = SCORE_PROFILES.join(', ');
    if (!profiles || profiles.length === 0) {
        throw new BomScoreError(BomScoreErrorReason.UNKNOWN_PROFILE,
            `No profile requested; valid profiles: ${valid}`);
    }
    for (const profile of profiles) {
        if (!SCORE_PROFILES.includes(profile)) {
            throw new BomScoreError(BomScoreErrorReason.UNKNOWN_PROFILE,
                `Unknown profile "${String(profile).substring(0, MAX_PROFILE_ECHO)}"; valid profiles: ${valid}`);
        }
    }
}

function firstStderrLine(stderr: string): string {
    return (stderr.split('\n')[0] ?? '').substring(0, MAX_STDERR_LINE);
}

/** reportVersion of a valid report, or null when stdout is not a report. */
function reportVersionOf(stdout: string): number | null {
    let parsed: unknown;
    try {
        parsed = JSON.parse(stdout);
    } catch {
        return null;
    }
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    const version = (parsed as Record<string, unknown>).reportVersion;
    if (typeof version !== 'number' || !Number.isInteger(version) || version < 1) return null;
    return version;
}

interface ScoreRun {
    report: string;
    reportVersion: number;
}

/** Maps the CLI outcome to the report (design 3.4 row 1) or a BomScoreError (rows 4 to 13). */
function mapOutcome(result: ShellResult): ScoreRun {
    const stderrLine = firstStderrLine(result.stderr);
    if (result.spawnError) {
        throw new BomScoreError(BomScoreErrorReason.CLI_UNAVAILABLE,
            'SBOM scoring is not available: the rearm CLI is not installed in this rebom image');
    }
    if (result.timedOut) {
        throw new BomScoreError(BomScoreErrorReason.TIMEOUT, 'SBOM scoring timed out');
    }
    if (result.stdoutOverflow) {
        throw new BomScoreError(BomScoreErrorReason.REPORT_INVALID,
            `SBOM scoring returned a report larger than ${SCORE_MAX_REPORT_BYTES} bytes`);
    }
    if (result.code === 1) {
        if (REFUSAL_PREFIXES.some((prefix) => stderrLine.startsWith(prefix))) {
            throw new BomScoreError(BomScoreErrorReason.INPUT_REFUSED, stderrLine);
        }
        if (OLD_CLI_MARKERS.some((marker) => result.stderr.includes(marker))) {
            throw new BomScoreError(BomScoreErrorReason.CLI_UNAVAILABLE,
                'SBOM scoring is not available: the rearm CLI in this rebom image has no bomutils score');
        }
        // the stderr line may name the temp path: it goes to the log only
        throw new BomScoreError(BomScoreErrorReason.CLI_FAILED, 'SBOM scoring failed');
    }
    if (result.code === 2) {
        throw new BomScoreError(BomScoreErrorReason.CLI_FAILED, `SBOM scoring failed: ${stderrLine}`);
    }
    if (result.code !== 0) {
        // exit 3 (never asked for), any other code, or a signal that was not ours
        throw new BomScoreError(BomScoreErrorReason.CLI_FAILED, 'SBOM scoring failed');
    }
    const reportVersion = reportVersionOf(result.stdout);
    if (reportVersion === null) {
        throw new BomScoreError(BomScoreErrorReason.REPORT_INVALID, 'SBOM scoring returned an invalid report');
    }
    return { report: result.stdout, reportVersion };
}

/** Steps 3 to 7 of the design: slot, temp file, CLI, outcome, cleanup. */
async function runScore(bom: string, profiles: string[], stderrSink: { line?: string }): Promise<ScoreRun> {
    await acquireSlot();
    let dir: string | undefined;
    try {
        dir = await fs.promises.mkdtemp(path.join(os.tmpdir(), TEMP_DIR_PREFIX));
        const file = path.join(dir, 'bom');
        await fs.promises.writeFile(file, bom, { encoding: 'utf8', mode: 0o600, flag: 'wx' });

        const args = ['bomutils', 'score', '--format', 'json'];
        for (const profile of profiles) args.push('--profile', profile);
        args.push('--infile', file);

        const result = await shellExecDetailed(REARM_CLI, args, {
            timeoutMs: SCORE_TIMEOUT_MS,
            maxStdoutBytes: SCORE_MAX_REPORT_BYTES
        });
        stderrSink.line = firstStderrLine(result.stderr);
        return mapOutcome(result);
    } finally {
        if (dir) {
            try {
                await fs.promises.rm(dir, { recursive: true, force: true });
            } catch (rmError) {
                logger.error({ err: rmError, path: dir }, 'scoreBom: failed to remove the temp directory');
            }
        }
        releaseSlot();
    }
}

/**
 * Scores one SBOM document (any serialisation the CLI reads) against the given
 * profiles and returns the CLI's JSON report, byte for byte.
 */
export async function scoreBom(bom: string, profiles: string[]): Promise<string> {
    const started = Date.now();
    const text = bom ?? '';
    const inputBytes = Buffer.byteLength(text, 'utf8');
    let outcome: ScoreOutcome = OUTCOME_OK;
    let reportVersion: number | null = null;
    const stderrSink: { line?: string } = {};
    try {
        validateProfiles(profiles);
        if (inputBytes > SCORE_MAX_INPUT_BYTES) {
            throw new BomScoreError(BomScoreErrorReason.INPUT_TOO_LARGE,
                `SBOM is larger than ${SCORE_MAX_INPUT_BYTES} bytes`);
        }
        const scored = await runScore(text, profiles, stderrSink);
        reportVersion = scored.reportVersion;
        if (reportVersion > SCORE_REPORT_VERSION) {
            logger.warn({ reportVersion }, `scoreBom: CLI report version ${reportVersion} is newer than ${SCORE_REPORT_VERSION}; passed through`);
        }
        return scored.report;
    } catch (error) {
        outcome = error instanceof BomScoreError ? error.reason : OUTCOME_ERROR;
        throw error;
    } finally {
        // never the document, never the report
        const logFields: Record<string, unknown> = {
            profiles: outcome === BomScoreErrorReason.UNKNOWN_PROFILE ? undefined : profiles,
            inputBytes,
            durationMs: Date.now() - started,
            outcome,
            reportVersion
        };
        if (outcome !== OUTCOME_OK && stderrSink.line) logFields.stderr = stderrSink.line;
        logger.info(logFields, 'scoreBom');
    }
}
