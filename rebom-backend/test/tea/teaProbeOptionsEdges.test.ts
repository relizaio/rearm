import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../src/services/tea/teaCliRunner', async (importOriginal) => {
    const actual = await importOriginal<typeof import('../../src/services/tea/teaCliRunner')>();
    return { ...actual, runTeaCli: vi.fn(actual.runTeaCli) };
});

import { runTeaCli } from '../../src/services/tea/teaCliRunner';
import { ensureTeaCli, resetTeaProbe, teaProbeRuns } from '../../src/services/tea/teaCliProbe';
import { shutdownTea } from '../../src/services/tea/teaService';
import { cleanupDirs, configure, Stub } from './teaTestKit';

/**
 * What the probe asks of the runner (design 3.9): the command, five seconds, one mebibyte of
 * output, and a count of the probes that starts at zero.
 */

const mRun = vi.mocked(runTeaCli);
let stub: Stub;

beforeEach(() => {
    stub = new Stub();
    mRun.mockClear();
});

afterEach(() => {
    shutdownTea();
    stub.restore();
    vi.restoreAllMocks();
    cleanupDirs();
});

describe('the probe', () => {
    it('runs "tea machine" without a verb, within five seconds and one mebibyte of standard output', async () => {
        configure();
        mRun.mockClear();
        resetTeaProbe();
        expect(await ensureTeaCli()).toBeNull();
        expect(mRun).toHaveBeenCalledTimes(1);
        const [argv, options] = mRun.mock.calls[0];
        expect(argv).toEqual(['tea', 'machine']);
        expect(options.timeoutMs).toBe(5000);
        expect(options.maxStdoutBytes).toBe(1048576);
        expect(teaProbeRuns()).toBe(1);
    });

    it('counts from zero in a module that no call has used yet, and again from zero after a reset', async () => {
        vi.resetModules();
        const fresh = await import('../../src/services/tea/teaCliProbe');
        expect(fresh.teaProbeRuns()).toBe(0);
        configure();
        fresh.resetTeaProbe();
        expect(fresh.teaProbeRuns()).toBe(0);
        await fresh.ensureTeaCli();
        expect(fresh.teaProbeRuns()).toBe(1);
        fresh.resetTeaProbe();
        expect(fresh.teaProbeRuns()).toBe(0);
    });
});
