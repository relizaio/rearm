import * as fs from 'fs';
import * as path from 'path';
import { logger } from '../../logger';
import { killProcessGroup, shellExecDetailed, ShellResult } from '../../utils';

/**
 * The only place that starts the CLI for the TEA operations (TPF-180 design 3.5): the per-call
 * directory under <root>/work, the run with its own process group, the kill timer, removal in
 * finally, and the registry of running process groups for the shut-down.
 */

export const TEA_CLI = 'rearm';
export const TEA_STDERR_KEPT_BYTES = 262144;
const CALL_DIR_PREFIX = 'f-';

let workDir: string | null = null;
const runningGroups = new Set<number>();

export function setTeaWorkDir(dir: string | null): void {
    workDir = dir;
}

export function teaWorkDir(): string | null {
    return workDir;
}

/** The process groups of runs that have not ended (for the shut-down and for tests). */
export function runningTeaGroups(): number[] {
    return [...runningGroups];
}

export interface TeaRunOptions {
    env: NodeJS.ProcessEnv;
    /** rebom's own bound: the CLI's deadline plus the kill margin. */
    timeoutMs: number;
    /** Standard output above this many bytes is an overflow and the group is killed. */
    maxStdoutBytes: number;
}

/** One run of the CLI. Never rejects (the helper never does). */
export async function runTeaCli(argv: string[], opts: TeaRunOptions): Promise<ShellResult> {
    let pid: number | undefined;
    try {
        return await shellExecDetailed(TEA_CLI, argv, {
            timeoutMs: opts.timeoutMs,
            maxStdoutBytes: opts.maxStdoutBytes,
            maxStderrBytes: TEA_STDERR_KEPT_BYTES,
            env: opts.env,
            killProcessGroup: true,
            onSpawn: (spawned) => {
                pid = spawned;
                runningGroups.add(spawned);
            }
        });
    } finally {
        if (pid !== undefined) runningGroups.delete(pid);
    }
}

/** A new per-call directory, mode 0700, inside <root>/work. */
export async function makeCallDir(): Promise<string> {
    if (!workDir) throw new Error('TEA scratch directory is not configured');
    return fs.promises.mkdtemp(path.join(workDir, CALL_DIR_PREFIX));
}

export async function removeCallDir(dir: string): Promise<void> {
    try {
        await fs.promises.rm(dir, { recursive: true, force: true });
    } catch (err) {
        logger.error({ err }, 'TEA: failed to remove a per-call directory');
    }
}

/**
 * Runs fn in a new per-call directory and removes it afterwards, on every path, unless fn
 * hands the directory over (keep true: a fetch with held files, removed at release or expiry).
 */
export async function withCallDir<T>(fn: (dir: string) => Promise<{ value: T, keep: boolean }>): Promise<T> {
    const dir = await makeCallDir();
    let keep = false;
    try {
        const out = await fn(dir);
        keep = out.keep;
        return out.value;
    } finally {
        if (!keep) await removeCallDir(dir);
    }
}

/** Kills every running process group (the shut-down). */
export function killAllTeaRuns(): void {
    for (const pid of runningGroups) killProcessGroup(pid);
    runningGroups.clear();
}
