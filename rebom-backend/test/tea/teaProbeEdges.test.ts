import * as fs from 'fs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { logger } from '../../src/logger';
import { ensureTeaCli, resetTeaProbe, teaProbeRuns, TEA_PROBE_RETRY_MS } from '../../src/services/tea/teaCliProbe';
import { shutdownTea } from '../../src/services/tea/teaService';
import { cleanupDirs, configure, tempDir, Stub, workDirOf } from './teaTestKit';

/**
 * The probe of the CLI's machine mode (design 3.9) called directly: which error objects make the
 * CLI usable, too old or of another format, what is shared, kept and retried, and what is logged.
 */

let stub: Stub;

beforeEach(() => {
    stub = new Stub();
});

afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    shutdownTea();
    stub.restore();
    cleanupDirs();
});

const GOOD = '{"formatVersion":1,"kind":"TEA_ERROR","exitCode":2,"reason":"USAGE","message":"a verb is required"}\n';

function probeWith(opts: { exit?: number, stderr?: string, stdout?: string }): void {
    stub.remove('probe.exit');
    stub.remove('probe.stderr');
    stub.remove('probe.stdout');
    if (opts.exit !== undefined) stub.write('probe.exit', String(opts.exit));
    stub.write('probe.stderr', opts.stderr ?? '');
    if (opts.stdout !== undefined) stub.write('probe.stdout', opts.stdout);
}

async function verdict(opts: { exit?: number, stderr?: string }): Promise<string> {
    configure();
    probeWith(opts);
    const result = await ensureTeaCli();
    return result === null ? 'USABLE' : `${result.outcome}:${result.error!.reason}`;
}

describe('what the probe makes of the CLI', () => {
    it('usable: exit 2 and an error object of kind TEA_ERROR and format version 1, with no warning', async () => {
        const warn = vi.spyOn(logger, 'warn').mockImplementation(() => undefined as never);
        expect(await verdict({ exit: 2, stderr: GOOD })).toBe('USABLE');
        expect(warn).not.toHaveBeenCalled();
    });

    it('usable also when another error object of another format comes with it, in any order', async () => {
        const other = '{"formatVersion":2,"kind":"TEA_ERROR","exitCode":2}\n';
        expect(await verdict({ exit: 2, stderr: other + GOOD })).toBe('USABLE');
        expect(await verdict({ exit: 2, stderr: GOOD + other })).toBe('USABLE');
        expect(await verdict({ exit: 2, stderr: 'noise\n' + GOOD + 'more\n' })).toBe('USABLE');
    });

    it('too old: no error object, one of another kind, an exit code that is not 2, or a last part with no line feed', async () => {
        expect(await verdict({ exit: 0, stderr: '' })).toBe('NOT_AVAILABLE:CLI_TOO_OLD');
        expect(await verdict({ exit: 2, stderr: '' })).toBe('NOT_AVAILABLE:CLI_TOO_OLD');
        expect(await verdict({ exit: 2, stderr: 'Error: unknown command "machine"\n' })).toBe('NOT_AVAILABLE:CLI_TOO_OLD');
        // a JSON object of another kind does not count, whatever its version
        expect(await verdict({ exit: 2, stderr: '{"formatVersion":1,"kind":"TEA_PRODUCT"}\n' })).toBe('NOT_AVAILABLE:CLI_TOO_OLD');
        expect(await verdict({ exit: 2, stderr: '{"formatVersion":2,"kind":"TEA_PRODUCT"}\n' })).toBe('NOT_AVAILABLE:CLI_TOO_OLD');
        expect(await verdict({ exit: 2, stderr: '{"formatVersion":1}\n' })).toBe('NOT_AVAILABLE:CLI_TOO_OLD');
        expect(await verdict({ exit: 1, stderr: GOOD })).toBe('NOT_AVAILABLE:CLI_TOO_OLD');
        expect(await verdict({ exit: 0, stderr: GOOD })).toBe('NOT_AVAILABLE:CLI_TOO_OLD');
        expect(await verdict({ exit: 2, stderr: GOOD.trimEnd() })).toBe('NOT_AVAILABLE:CLI_TOO_OLD');
        expect(await verdict({ exit: 2, stderr: '[{"formatVersion":1,"kind":"TEA_ERROR"}]\n' })).toBe('NOT_AVAILABLE:CLI_TOO_OLD');
        expect(await verdict({ exit: 2, stderr: ' {"formatVersion":1,"kind":"TEA_ERROR"}\n' })).toBe('NOT_AVAILABLE:CLI_TOO_OLD');
    });

    it('another format: an error object whose version is not 1 and none that is, whatever the exit code', async () => {
        const two = '{"formatVersion":2,"kind":"TEA_ERROR","exitCode":2}\n';
        expect(await verdict({ exit: 2, stderr: two })).toBe('NOT_AVAILABLE:CLI_FORMAT');
        expect(await verdict({ exit: 3, stderr: two })).toBe('NOT_AVAILABLE:CLI_FORMAT');
        expect(await verdict({ exit: 0, stderr: two })).toBe('NOT_AVAILABLE:CLI_FORMAT');
        expect(await verdict({ exit: 3, stderr: GOOD + two })).toBe('NOT_AVAILABLE:CLI_FORMAT');
        expect(await verdict({ exit: 2, stderr: '{"kind":"TEA_ERROR"}\n' })).toBe('NOT_AVAILABLE:CLI_FORMAT');
        expect(await verdict({ exit: 2, stderr: '{"formatVersion":"1","kind":"TEA_ERROR"}\n' })).toBe('NOT_AVAILABLE:CLI_FORMAT');
    });

    it('a CLI that cannot be started is CLI_MISSING', async () => {
        configure();
        stub.restore();
        const saved = process.env.PATH;
        process.env.PATH = tempDir('rebom-empty-path-');
        try {
            const result = await ensureTeaCli();
            expect(result?.outcome).toBe('NOT_AVAILABLE');
            expect(result?.error?.reason).toBe('CLI_MISSING');
        } finally {
            process.env.PATH = saved;
        }
    });

    it('warns once, with the reason alone, when the CLI has no usable machine mode, and not again while the result is kept', async () => {
        const warn = vi.spyOn(logger, 'warn').mockImplementation(() => undefined as never);
        expect(await verdict({ exit: 0, stderr: '' })).toBe('NOT_AVAILABLE:CLI_TOO_OLD');
        expect(warn).toHaveBeenCalledTimes(1);
        expect(warn.mock.calls[0]).toEqual([{ reason: 'CLI_TOO_OLD' }, 'TEA: the rearm CLI has no usable machine mode']);
        await ensureTeaCli();
        await ensureTeaCli();
        expect(warn).toHaveBeenCalledTimes(1);
    });

    it('runs the probe in a directory of its own that is gone afterwards, with the verb-less command', async () => {
        const root = configure();
        probeWith({ exit: 2, stderr: GOOD });
        await ensureTeaCli();
        expect(fs.readdirSync(workDirOf(root))).toEqual([]);
        expect(stub.probes()).toBe(1);
        expect(stub.runs()).toEqual([]);
    });
});

describe('what the probe shares, keeps and retries', () => {
    it('concurrent first calls share one probe; a good result is kept for the life of the process', async () => {
        configure();
        probeWith({ exit: 2, stderr: GOOD });
        const results = await Promise.all(Array.from({ length: 8 }, () => ensureTeaCli()));
        expect(results.every((r) => r === null)).toBe(true);
        expect(teaProbeRuns()).toBe(1);
        expect(stub.probes()).toBe(1);
        expect(await ensureTeaCli()).toBeNull();
        expect(teaProbeRuns()).toBe(1);
    });

    it('a bad result is kept for the retry time and tried again at the instant it is over, not before', async () => {
        vi.useFakeTimers({ toFake: ['Date'] });
        vi.setSystemTime(new Date('2026-01-01T00:00:00Z'));
        configure({}, { probeRetryMs: 60000 });
        probeWith({ exit: 0, stderr: '' });
        expect((await ensureTeaCli())?.error?.reason).toBe('CLI_TOO_OLD');
        expect(teaProbeRuns()).toBe(1);
        // the CLI is fixed; the bad verdict stands until the retry time is over
        probeWith({ exit: 2, stderr: GOOD });
        vi.setSystemTime(new Date('2026-01-01T00:00:59.999Z'));
        expect((await ensureTeaCli())?.error?.reason).toBe('CLI_TOO_OLD');
        expect(teaProbeRuns()).toBe(1);
        vi.setSystemTime(new Date('2026-01-01T00:01:00.000Z'));
        expect(await ensureTeaCli()).toBeNull();
        expect(teaProbeRuns()).toBe(2);
    });

    it('the retry time is a minute unless configured, and a reset forgets the verdict and the count', async () => {
        expect(TEA_PROBE_RETRY_MS).toBe(60000);
        configure();
        probeWith({ exit: 0, stderr: '' });
        await ensureTeaCli();
        expect(teaProbeRuns()).toBe(1);
        resetTeaProbe();
        expect(teaProbeRuns()).toBe(0);
        probeWith({ exit: 2, stderr: GOOD });
        expect(await ensureTeaCli()).toBeNull();
        expect(teaProbeRuns()).toBe(1);
        resetTeaProbe(5);
        expect(teaProbeRuns()).toBe(0);
    });

    it('a reset while a probe is running starts the next call\'s own probe', async () => {
        configure();
        probeWith({ exit: 2, stderr: GOOD });
        const first = ensureTeaCli();
        resetTeaProbe();
        const second = ensureTeaCli();
        await Promise.all([first, second]);
        expect(teaProbeRuns()).toBe(1);
        expect(stub.probes()).toBe(2);
    });
});
