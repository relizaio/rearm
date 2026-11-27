import { describe, it, expect, vi, beforeEach, afterAll } from 'vitest';
import * as fs from 'fs';

// utils.ts captures spawn at load, so it is patched before any import runs. `rearm` and
// `cyclonedx-cli` children are stand-ins the test drives; the stand-in emits exit and then close,
// each with the code and the signal, and carries the killed and signalCode the test sets.
const fake = vi.hoisted(() => {
    const cp = (process as any).getBuiltinModule('node:child_process');
    const { EventEmitter } = (process as any).getBuiltinModule('node:events');
    const original = cp.spawn;
    const state = {
        cp,
        original,
        calls: [] as { cmd: string, args: string[], options: any, child: any }[],
        onSpawn: null as null | ((call: { cmd: string, args: string[], options: any, child: any }) => void)
    };
    cp.spawn = (cmd: string, args: string[], options: any) => {
        if (cmd !== 'rearm' && cmd !== 'cyclonedx-cli') return original(cmd, args, options);
        const child: any = new EventEmitter();
        child.stdout = new EventEmitter();
        child.stderr = new EventEmitter();
        child.killed = false;
        child.signalCode = null;
        const call = { cmd, args: [...args], options, child };
        state.calls.push(call);
        const handler = state.onSpawn;
        setImmediate(() => {
            if (handler) handler(call);
            else {
                child.emit('exit', 99, null);
                child.emit('close', 99, null);
            }
        });
        return child;
    };
    return state;
});
const log = vi.hoisted(() => ({
    info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn(), trace: vi.fn(), fatal: vi.fn()
}));
const runQuery = vi.hoisted(() => vi.fn());

vi.mock('../../src/logger', () => ({ logger: log }));
// shellExec and the temp files stay real; only the database is replaced, so the text a failed run
// hands on for storage is read off the UPDATE's parameters.
vi.mock('../../src/utils', async (importOriginal) => {
    const actual: any = await importOriginal();
    return { ...actual, runQuery: (...a: any[]) => runQuery(...a) };
});

import { enrichBomAsync, enrichCycloneDxBom } from '../../src/services/bom/bomProcessingService';
import { SpdxService } from '../../src/services/spdx';
import { EnrichmentStatus } from '../../src/types';

/**
 * The enrichment catch, the store and the two debug lines (TPF-261 design 3.5, 3.7, 3.8; rows
 * T-1246, T-1247 and T-1249). USERW, PASSW, BODYW and FILLERW are plain marker words.
 */

type Call = { cmd: string, args: string[], options: any, child: any };

const URI = 'http://bear.invalid';
const MARKERS = ['USERW', 'PASSW', 'BODYW', 'FILLERW'];
const ENRICHED = { bomFormat: 'CycloneDX', specVersion: '1.6', serialNumber: 'urn:uuid:enriched', components: [] };
const BOM_UUID = 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee';
const CREDENTIALS = { bearUri: URI, bearApiKey: '', skipPatterns: [] as string[] };
const FAILED_STDOUT = 'Using BEAR at http://USERW:PASSW@host\n' + 'filler FILLERW line\n'.repeat(100000)
    + 'Error during enrichment: failed to enrich batch 1-1: BEAR API returned status 502: {"message":"BODYW"} BODYW\n';
const BOUNDED = 'rearm exited with code 1: Error during enrichment: failed to enrich batch 1-1: BEAR API returned status 502';

const bom = () => ({
    bomFormat: 'CycloneDX', specVersion: '1.6', serialNumber: 'urn:uuid:3b1d7c4e-8a7f-4c1e-9d2a-0f6b5e4d3c21', version: 1,
    components: [{ type: 'library', name: 'left-pad', version: '1.3.0', purl: 'pkg:npm/left-pad@1.3.0' }]
});

const argAfter = (call: Call, flag: string) => call.args[call.args.indexOf(flag) + 1];

function end(call: Call, opts: { code: number | null, signal?: string | null, killed?: boolean, stdout?: string, output?: unknown, outFlag?: string }): void {
    if (opts.output !== undefined) fs.writeFileSync(argAfter(call, opts.outFlag ?? '-o'), JSON.stringify(opts.output));
    if (opts.stdout) call.child.stdout.emit('data', opts.stdout);
    call.child.killed = opts.killed ?? false;
    call.child.signalCode = opts.signal ?? null;
    call.child.emit('exit', opts.code, opts.signal ?? null);
    call.child.emit('close', opts.code, opts.signal ?? null);
}

function loggedText(): string {
    const all = Object.values(log).flatMap((fn) => fn.mock.calls);
    return JSON.stringify(all, (_k, v) => (v instanceof Error ? { ...v, message: v.message, stack: v.stack } : v));
}

/** The logger calls, at any level, any of whose arguments serialised holds `text`. */
function callsHolding(text: string): unknown[][] {
    return Object.values(log).flatMap((fn) => fn.mock.calls).filter((args) => JSON.stringify(args).includes(text));
}

function enrichmentStatusUpdates(): any[][] {
    return runQuery.mock.calls.filter(([sql]) => String(sql).includes('{enrichmentError}')).map(([, params]) => params);
}

describe('TPF-261 the enrichment failure text: catch, store and debug lines', () => {
    beforeEach(() => {
        fake.calls.length = 0;
        fake.onSpawn = null;
        vi.clearAllMocks();
        runQuery.mockReset();
    });

    afterAll(() => {
        fake.cp.spawn = fake.original;
    });

    it('T-1246 (a) a child killed by the timeout is reported and logged as a timeout', async () => {
        fake.onSpawn = (call) => end(call, { code: null, signal: 'SIGTERM', killed: true,
            stdout: 'Using BEAR at http://USERW:PASSW@host\nProcessing batch 1-1 of 1\n' });

        const result = await enrichCycloneDxBom(bom(), URI, '', [], BOM_UUID);

        const text = 'rearm timed out after 1800000 ms: Processing batch 1-1 of 1';
        expect(result).toStrictEqual({ success: false, error: text });
        expect(log.error).toHaveBeenCalledWith(
            expect.objectContaining({ error: text, isTimeout: true, timeoutMs: 1800000, bomUuid: BOM_UUID }),
            'CycloneDX BOM enrichment timed out');
        expect(callsHolding(text)).toHaveLength(1);
        expect(loggedText()).not.toContain('USERW');
        expect(fake.calls).toHaveLength(1);
        expect(fs.existsSync(argAfter(fake.calls[0], '-f'))).toBe(false);
        expect(fs.existsSync(argAfter(fake.calls[0], '-o'))).toBe(false);
    });

    it('T-1246 (b) a plain failure whose text holds the word timeout is a plain failure', async () => {
        fake.onSpawn = (call) => end(call, { code: 1,
            stdout: 'Using BEAR at http://bear.invalid\nError during enrichment: failed to enrich batch 1-1: dial tcp 10.0.0.1:443: i/o timeout\n' });

        const result = await enrichCycloneDxBom(bom(), URI, '', [], BOM_UUID);

        const text = 'rearm exited with code 1: Error during enrichment: failed to enrich batch 1-1: dial tcp 10.0.0.1:443: i/o timeout';
        expect(result).toStrictEqual({ success: false, error: text });
        const failed = log.error.mock.calls.filter(([, msg]) => msg === 'CycloneDX BOM enrichment failed');
        expect(failed).toHaveLength(1);
        expect(failed[0][0]).toMatchObject({ error: text, isTimeout: false });
        expect(failed[0][0].timeoutMs).toBeUndefined();
        expect(log.error).not.toHaveBeenCalledWith(expect.anything(), 'CycloneDX BOM enrichment timed out');
        expect(callsHolding(text)).toHaveLength(1);
        expect(fs.existsSync(argAfter(fake.calls[0], '-f'))).toBe(false);
        expect(fs.existsSync(argAfter(fake.calls[0], '-o'))).toBe(false);
    });

    it('T-1247 (a) a failed child: the stored parameter is the bounded text', async () => {
        fake.onSpawn = (call) => end(call, { code: 1, stdout: FAILED_STDOUT });

        await enrichBomAsync(BOM_UUID, bom(), 'org-1', CREDENTIALS);

        const updates = enrichmentStatusUpdates();
        expect(updates).toHaveLength(1);
        expect(updates[0][0]).toBe(BOM_UUID);
        expect(updates[0][1]).toBe(JSON.stringify(EnrichmentStatus.FAILED));
        expect(updates[0][3]).toBe(JSON.stringify(BOUNDED));
        for (const m of MARKERS) {
            expect(JSON.stringify(runQuery.mock.calls)).not.toContain(m);
            expect(loggedText()).not.toContain(m);
        }
        expect(callsHolding(BOUNDED)).toHaveLength(1);
    });

    it('T-1247 (b) a failure that is not a child\'s: the store bounds whatever it is given', async () => {
        const longMessage = 'connection to postgres://USERW:PASSW@db.internal:5432/rebom refused ' + 'detail words '.repeat(380);
        expect(longMessage.length).toBeGreaterThan(5000);
        fake.onSpawn = (call) => end(call, { code: 0, output: ENRICHED });
        runQuery.mockImplementation(async (sql: string) => {
            if (String(sql).includes('{enrichmentError}')) return { rows: [], rowCount: 1 };
            throw new Error(longMessage);
        });

        await enrichBomAsync(BOM_UUID, bom(), 'org-1', CREDENTIALS);

        const updates = enrichmentStatusUpdates();
        expect(updates).toHaveLength(1);
        expect(updates[0][1]).toBe(JSON.stringify(EnrichmentStatus.FAILED));
        const stored = JSON.parse(updates[0][3]);
        expect(typeof stored).toBe('string');
        expect(stored.length).toBeLessThanOrEqual(300);
        expect(stored.startsWith('Could not reserve an enrichment sequence: connection to [removed] refused detail words')).toBe(true);
        for (const m of ['USERW', 'PASSW', '@']) expect(stored).not.toContain(m);
    });

    it('T-1249 the debug line of a successful enrichment logs a size, not the output', async () => {
        const stdout = 'Using BEAR at http://USERW:PASSW@host\nFound 1 components\nBODYW done';
        fake.onSpawn = (call) => end(call, { code: 0, stdout, output: ENRICHED });

        const result = await enrichCycloneDxBom(bom(), URI, '', [], BOM_UUID);

        expect(result.success).toBe(true);
        expect(log.debug).toHaveBeenCalledWith({ enrichmentOutputChars: stdout.length }, 'rearm-cli enrichment completed');
        for (const m of MARKERS) expect(loggedText()).not.toContain(m);
    });

    it('T-1249 the debug line of a successful conversion logs a size, not the output', async () => {
        const stdout = 'Converted with USERW:PASSW@host and BODYW';
        fake.onSpawn = (call) => {
            if (call.cmd === 'rearm') end(call, { code: 0, stdout, output: ENRICHED, outFlag: '--outfile' });
            else end(call, { code: 0, stdout: 'BOM validated BODYW' });
        };

        const result = await SpdxService.convertSpdxToCycloneDx({ SPDXID: 'SPDXRef-DOCUMENT', spdxVersion: 'SPDX-2.3' });

        expect(result.success).toBe(true);
        expect(fake.calls.map((c) => c.cmd)).toStrictEqual(['rearm', 'cyclonedx-cli']);
        expect(log.debug).toHaveBeenCalledWith({ conversionOutputChars: stdout.length }, 'rearm-cli conversion completed');
        for (const m of MARKERS) expect(loggedText()).not.toContain(m);
    });
});
