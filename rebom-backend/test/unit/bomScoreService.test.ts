import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';
import { inspect } from 'util';

// Only the CLI call is replaced; runQuery and the pool are stubs that must never be called.
vi.mock('../../src/utils', async (importOriginal) => {
    const actual = await importOriginal<typeof import('../../src/utils')>();
    return {
        ...actual,
        shellExecDetailed: vi.fn(),
        runQuery: vi.fn(),
        pool: { query: vi.fn(), connect: vi.fn() }
    };
});

// Every function of the OCI client module becomes a spy: scoring must call none of them.
vi.mock('../../src/services/oci', async (importOriginal) => {
    const actual = await importOriginal<Record<string, unknown>>();
    return Object.fromEntries(Object.entries(actual).map(
        ([name, value]) => [name, typeof value === 'function' ? vi.fn() : value]));
});

import {
    scoreBom,
    SCORE_MAX_INPUT_BYTES,
    SCORE_MAX_REPORT_BYTES,
    SCORE_TIMEOUT_MS,
    SCORE_MAX_CONCURRENT
} from '../../src/services/bom/bomScoreService';
import { BomScoreError, BomScoreErrorReason, toGraphQLError, ERROR_CODES } from '../../src/types/errors';
import { shellExecDetailed, ShellResult, runQuery, pool } from '../../src/utils';
import * as Oci from '../../src/services/oci';
import { logger } from '../../src/logger';

const mockedExec = vi.mocked(shellExecDetailed);

const REPORT_V1 = JSON.stringify({ reportVersion: 1, profiles: [{ key: 'fda', verdict: 'NOT_READY' }] }, null, 2) + '\n';

function result(overrides: Partial<ShellResult> = {}): ShellResult {
    return {
        code: 0,
        signal: null,
        stdout: REPORT_V1,
        stderr: '',
        timedOut: false,
        stdoutOverflow: false,
        ...overrides
    };
}

let mkdtempSpy: ReturnType<typeof vi.spyOn>;

/** Directories mkdtemp handed out in this test (never a listing of os.tmpdir()). */
async function createdDirs(): Promise<string[]> {
    const dirs: string[] = [];
    for (const r of mkdtempSpy.mock.results) {
        if (r.type === 'return') dirs.push(await r.value);
    }
    return dirs;
}

async function expectDirsRemoved(): Promise<void> {
    const dirs = await createdDirs();
    expect(dirs.length).toBeGreaterThan(0);
    for (const dir of dirs) expect(fs.existsSync(dir)).toBe(false);
}

async function scoreError(bom: string, profiles: string[]): Promise<BomScoreError> {
    try {
        await scoreBom(bom, profiles);
    } catch (e) {
        expect(e).toBeInstanceOf(BomScoreError);
        return e as BomScoreError;
    }
    throw new Error('scoreBom did not throw');
}

beforeEach(() => {
    mockedExec.mockReset();
    mkdtempSpy = vi.spyOn(fs.promises, 'mkdtemp');
});

afterEach(() => {
    vi.restoreAllMocks();
});

describe('scoreBom: profiles (U-1, U-2)', () => {
    it.each([
        ['an unknown key', ['nope'], 'Unknown profile "nope"; valid profiles: cisa-2026, ntia-2021, fda'],
        ['an empty list', [], 'No profile requested; valid profiles: cisa-2026, ntia-2021, fda'],
        ['a key in another case', ['FDA'], 'Unknown profile "FDA"; valid profiles: cisa-2026, ntia-2021, fda'],
        ['a valid key after an unknown one', ['fda', 'x'], 'Unknown profile "x"; valid profiles: cisa-2026, ntia-2021, fda'],
        ['a key with shell characters', ['cisa-2026; rm -rf /'],
            'Unknown profile "cisa-2026; rm -rf /"; valid profiles: cisa-2026, ntia-2021, fda']
    ])('refuses %s before any file or process', async (_label, profiles, message) => {
        const err = await scoreError('{}', profiles);
        expect(err.reason).toBe(BomScoreErrorReason.UNKNOWN_PROFILE);
        expect(err.message).toBe(message);
        expect(mockedExec).not.toHaveBeenCalled();
        expect(mkdtempSpy).not.toHaveBeenCalled();
    });

    it('cuts an echoed key to 64 characters', async () => {
        const err = await scoreError('{}', ['a'.repeat(200)]);
        expect(err.message).toBe(`Unknown profile "${'a'.repeat(64)}"; valid profiles: cisa-2026, ntia-2021, fda`);
    });
});

describe('scoreBom: the CLI call (U-3, U-4, U-5)', () => {
    it('U-3 passes exactly the argument array and the input bytes in a private temp file', async () => {
        const input = 'SPDXVersion: SPDX-2.3\nPackageName: caf\u00e9\n';
        let seen: { exists: boolean; content: Buffer; mode: number; dirName: string } | undefined;
        mockedExec.mockImplementation(async (_cmd, args) => {
            const file = args[args.length - 1];
            const stat = fs.statSync(file);
            seen = {
                exists: true,
                content: fs.readFileSync(file),
                mode: stat.mode & 0o777,
                dirName: path.basename(path.dirname(file))
            };
            return result();
        });

        await scoreBom(input, ['fda', 'cisa-2026']);

        expect(mockedExec).toHaveBeenCalledTimes(1);
        const [cmd, args, opts] = mockedExec.mock.calls[0];
        expect(cmd).toBe('rearm');
        const file = args[args.length - 1];
        expect(args).toEqual(['bomutils', 'score', '--format', 'json', '--profile', 'fda', '--profile', 'cisa-2026', '--infile', file]);
        expect(opts).toEqual({ timeoutMs: SCORE_TIMEOUT_MS, maxStdoutBytes: SCORE_MAX_REPORT_BYTES });
        expect(SCORE_TIMEOUT_MS).toBe(60000);
        expect(SCORE_MAX_REPORT_BYTES).toBe(16777216);
        expect(seen!.exists).toBe(true);
        expect(seen!.content.equals(Buffer.from(input, 'utf8'))).toBe(true);
        expect(seen!.mode).toBe(0o600);
        expect(seen!.dirName).toMatch(/^rebom-score-/);
        await expectDirsRemoved();
    });

    it('U-3 passes a profile given twice through unchanged', async () => {
        mockedExec.mockResolvedValue(result());
        await scoreBom('{}', ['fda', 'fda']);
        const args = mockedExec.mock.calls[0][1];
        expect(args.slice(4, 8)).toEqual(['--profile', 'fda', '--profile', 'fda']);
    });

    it('U-4 returns stdout verbatim, indentation and trailing newline included', async () => {
        mockedExec.mockResolvedValue(result({ stdout: REPORT_V1 }));
        const report = await scoreBom('{}', ['fda']);
        expect(report === REPORT_V1).toBe(true);
        await expectDirsRemoved();
    });

    it('U-5 passes a newer reportVersion through unchanged', async () => {
        const v2 = JSON.stringify({ reportVersion: 2, extra: [1, 2] }, null, 2) + '\n';
        mockedExec.mockResolvedValue(result({ stdout: v2 }));
        expect(await scoreBom('{}', ['fda'])).toBe(v2);
    });
});

describe('scoreBom: outcomes (U-6, U-7)', () => {
    const enoent = Object.assign(new Error('spawn rearm ENOENT'), { code: 'ENOENT' }) as NodeJS.ErrnoException;

    it.each<[string, Partial<ShellResult>, BomScoreErrorReason, string]>([
        ['row 4 binary missing', { code: null, stdout: '', spawnError: enoent }, BomScoreErrorReason.CLI_UNAVAILABLE,
            'SBOM scoring is not available: the rearm CLI is not installed in this rebom image'],
        ['row 5 timeout', { code: null, signal: 'SIGKILL', stdout: '', timedOut: true }, BomScoreErrorReason.TIMEOUT,
            'SBOM scoring timed out'],
        ['row 6 report too large', { code: null, signal: 'SIGKILL', stdout: '', stdoutOverflow: true }, BomScoreErrorReason.REPORT_INVALID,
            'SBOM scoring returned a report larger than 16777216 bytes'],
        ['row 7 unsupported', { code: 1, stdout: '', stderr: 'unsupported SBOM: SPDX RDF/XML is not supported\n' }, BomScoreErrorReason.INPUT_REFUSED,
            'unsupported SBOM: SPDX RDF/XML is not supported'],
        ['row 7 unparsable', { code: 1, stdout: '', stderr: 'unparsable SBOM: x\n' }, BomScoreErrorReason.INPUT_REFUSED,
            'unparsable SBOM: x'],
        ['row 8 CLI without the command', { code: 1, stdout: 'unknown flag: --format\n', stderr: 'Error: unknown flag: --format\nUsage:\n  rearm bomutils [command]\n' },
            BomScoreErrorReason.CLI_UNAVAILABLE, 'SBOM scoring is not available: the rearm CLI in this rebom image has no bomutils score'],
        ['row 8 unknown command', { code: 1, stdout: '', stderr: 'Error: unknown command "score" for "rearm bomutils"\n' },
            BomScoreErrorReason.CLI_UNAVAILABLE, 'SBOM scoring is not available: the rearm CLI in this rebom image has no bomutils score'],
        ['row 9 other exit 1', { code: 1, stdout: '', stderr: 'open /tmp/x: permission denied\n' }, BomScoreErrorReason.CLI_FAILED,
            'SBOM scoring failed'],
        ['row 10 exit 2', { code: 2, stdout: '', stderr: 'unknown --profile "fda"; valid profiles: cisa-2026\n' }, BomScoreErrorReason.CLI_FAILED,
            'SBOM scoring failed: unknown --profile "fda"; valid profiles: cisa-2026'],
        ['row 11 exit 3', { code: 3 }, BomScoreErrorReason.CLI_FAILED, 'SBOM scoring failed'],
        ['row 12 foreign signal', { code: null, signal: 'SIGSEGV', stdout: '' }, BomScoreErrorReason.CLI_FAILED, 'SBOM scoring failed'],
        ['row 13 not json', { stdout: 'not json' }, BomScoreErrorReason.REPORT_INVALID, 'SBOM scoring returned an invalid report'],
        ['row 13 an array', { stdout: '[]' }, BomScoreErrorReason.REPORT_INVALID, 'SBOM scoring returned an invalid report'],
        ['row 13 no reportVersion', { stdout: '{}' }, BomScoreErrorReason.REPORT_INVALID, 'SBOM scoring returned an invalid report'],
        ['row 13 reportVersion 0', { stdout: '{"reportVersion":0}' }, BomScoreErrorReason.REPORT_INVALID, 'SBOM scoring returned an invalid report'],
        ['row 13 reportVersion 1.5', { stdout: '{"reportVersion":1.5}' }, BomScoreErrorReason.REPORT_INVALID, 'SBOM scoring returned an invalid report'],
        ['row 13 reportVersion as text', { stdout: '{"reportVersion":"1"}' }, BomScoreErrorReason.REPORT_INVALID, 'SBOM scoring returned an invalid report']
    ])('%s', async (_label, outcome, reason, message) => {
        mockedExec.mockResolvedValue(result(outcome));
        const err = await scoreError('{"bomFormat":"CycloneDX"}', ['fda']);
        expect(err.reason).toBe(reason);
        expect(err.message).toBe(message);
        expect(err.message).not.toContain('/tmp/');
        await expectDirsRemoved();
    });

    it('cuts the stderr line to 500 characters', async () => {
        mockedExec.mockResolvedValue(result({ code: 1, stdout: '', stderr: 'unparsable SBOM: ' + 'y'.repeat(1000) }));
        const err = await scoreError('{}', ['fda']);
        expect(err.message.length).toBe(500);
    });

    it('U-7 removes the directory when writing the file fails', async () => {
        vi.spyOn(fs.promises, 'writeFile').mockRejectedValueOnce(new Error('disk full'));
        await expect(scoreBom('{}', ['fda'])).rejects.toThrow('disk full');
        expect(mockedExec).not.toHaveBeenCalled();
        await expectDirsRemoved();
    });

    it('U-7 removes the directory when the helper itself throws', async () => {
        mockedExec.mockRejectedValue(new Error('unexpected'));
        await expect(scoreBom('{}', ['fda'])).rejects.toThrow('unexpected');
        await expectDirsRemoved();
    });
});

describe('scoreBom: size (U-8)', () => {
    it('refuses one byte over the limit before any file or process', async () => {
        expect(SCORE_MAX_INPUT_BYTES).toBe(52428800);
        const err = await scoreError('x'.repeat(SCORE_MAX_INPUT_BYTES + 1), ['fda']);
        expect(err.reason).toBe(BomScoreErrorReason.INPUT_TOO_LARGE);
        expect(err.message).toBe('SBOM is larger than 52428800 bytes');
        expect(mockedExec).not.toHaveBeenCalled();
        expect(mkdtempSpy).not.toHaveBeenCalled();
    });

    it('counts UTF-8 bytes, not characters', async () => {
        // 2-byte characters: half as many characters as the limit, one byte over it
        const err = await scoreError('\u00e9'.repeat(SCORE_MAX_INPUT_BYTES / 2) + 'x', ['fda']);
        expect(err.reason).toBe(BomScoreErrorReason.INPUT_TOO_LARGE);
    });
});

describe('scoreBom: concurrency (U-9)', () => {
    it('runs at most 4 CLI processes; all 6 calls complete; a failed call frees its slot', async () => {
        expect(SCORE_MAX_CONCURRENT).toBe(4);
        const pending: Array<(r: ShellResult) => void> = [];
        let active = 0;
        let maxActive = 0;
        mockedExec.mockImplementation(() => {
            active++;
            maxActive = Math.max(maxActive, active);
            return new Promise<ShellResult>((resolve) => pending.push((r) => { active--; resolve(r); }));
        });

        const calls = Array.from({ length: 6 }, () => scoreBom('{}', ['fda']).then(
            (report) => ({ ok: true as const, report }),
            (error) => ({ ok: false as const, error })));

        await vi.waitFor(() => expect(mockedExec).toHaveBeenCalledTimes(4));
        await new Promise((r) => setTimeout(r, 50));
        expect(mockedExec).toHaveBeenCalledTimes(4);

        // a failing call frees its slot: the fifth starts
        pending[0](result({ code: 2, stdout: '', stderr: 'bad\n' }));
        await vi.waitFor(() => expect(mockedExec).toHaveBeenCalledTimes(5));
        pending[1](result());
        await vi.waitFor(() => expect(mockedExec).toHaveBeenCalledTimes(6));
        for (let i = 2; i < 6; i++) pending[i](result());

        const outcomes = await Promise.all(calls);
        expect(maxActive).toBe(4);
        expect(outcomes.filter((o) => o.ok)).toHaveLength(5);
        expect(outcomes.filter((o) => !o.ok)).toHaveLength(1);
        await expectDirsRemoved();
    });
});

describe('scoreBom: stateless (U-10)', () => {
    it('makes no database, pool or OCI call on success or failure', async () => {
        mockedExec.mockResolvedValueOnce(result());
        await scoreBom('{}', ['fda']);
        mockedExec.mockResolvedValueOnce(result({ code: 1, stdout: '', stderr: 'unparsable SBOM: x\n' }));
        await expect(scoreBom('{', ['fda'])).rejects.toBeInstanceOf(BomScoreError);

        expect(vi.mocked(runQuery)).not.toHaveBeenCalled();
        expect((pool as any).query).not.toHaveBeenCalled();
        expect((pool as any).connect).not.toHaveBeenCalled();
        const ociFns = Object.values(Oci).filter((v) => vi.isMockFunction(v));
        expect(ociFns.length).toBeGreaterThan(0);
        for (const fn of ociFns) expect(fn).not.toHaveBeenCalled();
    });
});

describe('BomScoreError mapping (U-11)', () => {
    it('maps to BOM_SCORE_ERROR with details.reason', () => {
        const mapped = toGraphQLError(new BomScoreError(BomScoreErrorReason.TIMEOUT, 'SBOM scoring timed out'));
        expect(mapped.message).toBe('SBOM scoring timed out');
        expect(mapped.extensions.code).toBe(ERROR_CODES.BOM_SCORE_ERROR);
        expect(mapped.extensions.code).toBe('BOM_SCORE_ERROR');
        expect(mapped.extensions.details).toEqual({ reason: 'TIMEOUT' });
    });
});

describe('scoreBom: logging (U-12)', () => {
    const DOC_MARKER = 'DOC-MARKER-7f3a1c';
    const REPORT_MARKER = 'REPORT-MARKER-9b2e4d';

    function logged(): string {
        const spies = ['info', 'warn', 'error', 'debug'].map((level) => vi.mocked((logger as any)[level]));
        return spies.flatMap((spy) => spy.mock.calls).map((args) => inspect(args, { depth: 10 })).join('\n');
    }

    beforeEach(() => {
        for (const level of ['info', 'warn', 'error', 'debug']) vi.spyOn(logger as any, level).mockImplementation(() => undefined);
    });

    it('logs one info line per call and never the document or the report', async () => {
        const doc = JSON.stringify({ bomFormat: 'CycloneDX', metadata: { component: { name: DOC_MARKER } } });
        const report = JSON.stringify({ reportVersion: 2, note: REPORT_MARKER }) + '\n';
        mockedExec.mockResolvedValueOnce(result({ stdout: report }));
        await scoreBom(doc, ['fda']);
        mockedExec.mockResolvedValueOnce(result({ code: 0, stdout: `not json ${REPORT_MARKER}` }));
        await expect(scoreBom(doc, ['fda'])).rejects.toBeInstanceOf(BomScoreError);
        mockedExec.mockResolvedValueOnce(result({ code: 1, stdout: '', stderr: 'open /tmp/rebom-score-x/bom: permission denied\n' }));
        await expect(scoreBom(doc, ['fda'])).rejects.toBeInstanceOf(BomScoreError);

        const text = logged();
        expect(text).not.toContain(DOC_MARKER);
        expect(text).not.toContain(REPORT_MARKER);
        expect(vi.mocked(logger.info)).toHaveBeenCalledTimes(3);
        // the failure line carries the first stderr line, the success line the report version
        expect(text).toContain('permission denied');
        expect(text).toContain('reportVersion: 2');
    });
});
