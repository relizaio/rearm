import { logger } from '../../logger';
import { buildTeaChildEnv } from './teaChildEnv';
import { runTeaCli, withCallDir } from './teaCliRunner';
import { TeaDocumentKind, TEA_FORMAT_VERSION } from './teaDocument';
import { TeaOutcome, TeaReason, teaResult, TeaResultBase } from './teaOutcome';

/**
 * Whether the rearm on the path has the machine mode (TPF-180 design 3.9). Run on the first TEA
 * call that passed the gate: `rearm tea machine` without a verb must exit 2 with a TEA_ERROR object
 * of format version 1 on standard error. It opens no connection. A good result is kept for the life
 * of the process; a bad one for the retry time; concurrent first calls share one probe.
 */

const PROBE_TIMEOUT_MS = 5000;
const PROBE_MAX_STDOUT_BYTES = 1048576;
const EXIT_USAGE = 2;

export const TEA_PROBE_RETRY_MS = 60000;

type ProbeState =
    | { available: true }
    | { available: false, reason: string, at: number };

let state: ProbeState | null = null;
let running: Promise<ProbeState> | null = null;
let retryMs = TEA_PROBE_RETRY_MS;
let probeRuns = 0;

/** Forgets the probe result (configureTea) and sets the retry time of a bad result. */
export function resetTeaProbe(retry: number = TEA_PROBE_RETRY_MS): void {
    state = null;
    running = null;
    retryMs = retry;
    probeRuns = 0;
}

/** How many probe processes were started since the last reset (for tests and logs). */
export function teaProbeRuns(): number {
    return probeRuns;
}

/** Every JSON object on a complete line of stderr whose kind is TEA_ERROR. */
function errorObjects(stderr: string): Record<string, unknown>[] {
    const lines = stderr.split('\n');
    lines.pop();
    const out: Record<string, unknown>[] = [];
    for (const line of lines) {
        if (!line.startsWith('{')) continue;
        try {
            const parsed = JSON.parse(line);
            if (parsed && typeof parsed === 'object' && !Array.isArray(parsed) && parsed.kind === TeaDocumentKind.ERROR) out.push(parsed);
        } catch {
            // not an error object
        }
    }
    return out;
}

async function probeOnce(): Promise<ProbeState> {
    probeRuns++;
    const run = await withCallDir(async (dir) => ({
        value: await runTeaCli(['tea', 'machine'], {
            env: buildTeaChildEnv(null, dir),
            timeoutMs: PROBE_TIMEOUT_MS,
            maxStdoutBytes: PROBE_MAX_STDOUT_BYTES
        }),
        keep: false
    }));
    const at = Date.now();
    if (run.spawnError) return { available: false, reason: TeaReason.CLI_MISSING, at };
    const objects = errorObjects(run.stderr);
    if (run.code === EXIT_USAGE && objects.some((obj) => obj.formatVersion === TEA_FORMAT_VERSION)) {
        return { available: true };
    }
    if (objects.some((obj) => obj.formatVersion !== TEA_FORMAT_VERSION)) {
        return { available: false, reason: TeaReason.CLI_FORMAT, at };
    }
    return { available: false, reason: TeaReason.CLI_TOO_OLD, at };
}

/** Null when the CLI has the machine mode; else the NOT_AVAILABLE result. */
export async function ensureTeaCli(): Promise<TeaResultBase | null> {
    if (state && !state.available && Date.now() - state.at >= retryMs) state = null;
    if (!state) {
        if (!running) {
            running = probeOnce()
                .then((result) => {
                    state = result;
                    if (!result.available) logger.warn({ reason: result.reason }, 'TEA: the rearm CLI has no usable machine mode');
                    return result;
                })
                .finally(() => {
                    running = null;
                });
        }
        await running;
    }
    const current = state;
    if (current && current.available) return null;
    return teaResult(TeaOutcome.NOT_AVAILABLE, current ? current.reason : TeaReason.CLI_MISSING);
}
