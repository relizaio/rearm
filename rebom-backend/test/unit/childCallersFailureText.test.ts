import { describe, it, expect, vi, beforeEach, afterAll } from 'vitest';
import * as fs from 'fs';

// utils.ts captures spawn at load, so it is patched before any import runs. `rearm` and
// `cyclonedx-cli` children are stand-ins the test drives; the stand-in emits exit and then close,
// each with the code and the signal.
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

vi.mock('../../src/logger', () => ({ logger: log }));
// The diff reads its two sides through bomService; they are replaced by two small documents, so the
// only child of the diff is cyclonedx-cli.
vi.mock('../../src/bomService', async (importOriginal) => {
    const actual: any = await importOriginal();
    const doc = () => ({ bomFormat: 'CycloneDX', specVersion: '1.6', version: 1, components: [] });
    return {
        ...actual,
        findBomObjectById: async () => doc(),
        augmentBomWithComponentContext: (b: any) => b,
        mergeBomObjects: async () => doc()
    };
});

import { bomDiffExec } from '../../src/services/bom/bomDiffService';
import { mergeBomObjects } from '../../src/services/bom/bomMergeService';
import { SpdxService } from '../../src/services/spdx';
import { ChildFailureKind, ChildProcessError } from '../../src/childFailure';

/**
 * The other callers of shellExec get the same bounded text (TPF-261 design 3.10; row T-1250): the
 * diff, the merge, the SPDX conversion and its validation. USERW, PASSW and FILLERW are plain
 * marker words.
 */

type Call = { cmd: string, args: string[], options: any, child: any };

const MARKERS = ['USERW', 'PASSW', 'FILLERW'];
const FILLER = 'filler FILLERW line\n'.repeat(100000);
const CONVERTED = { bomFormat: 'CycloneDX', specVersion: '1.6', serialNumber: 'urn:uuid:converted', version: 1, components: [] };

const argAfter = (call: Call, flag: string) => call.args[call.args.indexOf(flag) + 1];

function end(call: Call, code: number, stdout = '', output?: unknown): void {
    if (output !== undefined) fs.writeFileSync(argAfter(call, '--outfile'), JSON.stringify(output));
    if (stdout) call.child.stdout.emit('data', stdout);
    call.child.emit('exit', code, null);
    call.child.emit('close', code, null);
}

function loggedText(): string {
    const all = Object.values(log).flatMap((fn) => fn.mock.calls);
    return JSON.stringify(all, (_k, v) => (v instanceof Error ? { ...v, message: v.message, stack: v.stack } : v));
}

function expectNothingLeaks(text: string): void {
    for (const m of MARKERS) {
        expect(text).not.toContain(m);
        expect(loggedText()).not.toContain(m);
    }
}

async function thrownBy(run: () => Promise<unknown>): Promise<ChildProcessError> {
    let caught: unknown = null;
    try {
        await run();
    } catch (error) {
        caught = error;
    }
    expect(caught).toBeInstanceOf(ChildProcessError);
    return caught as ChildProcessError;
}

describe('T-1250 the diff, the merge and the conversion get the bounded text', () => {
    beforeEach(() => {
        fake.calls.length = 0;
        fake.onSpawn = null;
        vi.clearAllMocks();
    });

    afterAll(() => {
        fake.cp.spawn = fake.original;
    });

    it('the diff throws a ChildProcessError with the bounded message', async () => {
        fake.onSpawn = (call) => end(call, 1, 'Comparing http://USERW:PASSW@host/x\n' + FILLER + 'Error: the diff failed at USERW:PASSW@host\n');

        const error = await thrownBy(() => bomDiffExec(['a'], ['b'], 'org-1'));

        expect(fake.calls.map((c) => c.cmd)).toStrictEqual(['cyclonedx-cli']);
        expect(error.kind).toBe(ChildFailureKind.EXIT_CODE);
        expect(error.message).toBe('cyclonedx-cli exited with code 1: Error: the diff failed at [removed]');
        expectNothingLeaks(error.message + JSON.stringify({ ...error, stack: error.stack }));
        expect(log.error).toHaveBeenCalledWith({ err: error }, 'Error During diff');
    });

    it('the merge throws a ChildProcessError with the reason alone for an empty stdout', async () => {
        fake.onSpawn = (call) => end(call, 1);
        const doc = (name: string) => ({
            bomFormat: 'CycloneDX', specVersion: '1.6', version: 1,
            metadata: { component: { type: 'application', name, version: '1.0.0', 'bom-ref': name } }, components: []
        });

        const error = await thrownBy(() => mergeBomObjects([doc('a'), doc('b')],
            { name: 'merged', group: 'Example', version: '1.0.0', structure: 'FLAT', tldOnly: false, ignoreDev: false } as any));

        expect(fake.calls.map((c) => c.cmd)).toStrictEqual(['rearm']);
        expect(error.kind).toBe(ChildFailureKind.EXIT_CODE);
        expect(error.message).toBe('rearm exited with code 1');
        expect(log.error).toHaveBeenCalledWith({ err: error }, 'Error During merge');
        expectNothingLeaks(error.message);
    });

    it('the conversion returns Conversion failed: and the bounded text, no class name', async () => {
        fake.onSpawn = (call) => end(call, 1, 'Reading http://USERW:PASSW@host/doc.spdx.json\n' + FILLER
            + 'Error: failed to read SPDX document: unexpected end of JSON input\n');

        const result = await SpdxService.convertSpdxToCycloneDx({ SPDXID: 'SPDXRef-DOCUMENT' });

        expect(fake.calls.map((c) => c.cmd)).toStrictEqual(['rearm']);
        expect(result).toStrictEqual({
            success: false,
            error: 'Conversion failed: rearm exited with code 1: Error: failed to read SPDX document: unexpected end of JSON input'
        });
        expect(result.error).not.toContain('ChildProcessError');
        expectNothingLeaks(JSON.stringify(result));
    });

    it('the validation returns Conversion failed: CycloneDX validation failed: and the bounded text', async () => {
        fake.onSpawn = (call) => {
            if (call.cmd === 'rearm') end(call, 0, '', CONVERTED);
            else end(call, 1, FILLER + 'Validation failed for http://USERW:PASSW@host/schema\n');
        };

        const result = await SpdxService.convertSpdxToCycloneDx({ SPDXID: 'SPDXRef-DOCUMENT' });

        expect(fake.calls.map((c) => c.cmd)).toStrictEqual(['rearm', 'cyclonedx-cli']);
        expect(result).toStrictEqual({
            success: false,
            error: 'Conversion failed: CycloneDX validation failed: cyclonedx-cli exited with code 1: Validation failed for [removed]'
        });
        expect(result.error).not.toContain('BomConversionError');
        expect(result.error).not.toContain('ChildProcessError');
        expect(log.error).toHaveBeenCalledWith(expect.objectContaining({ err: expect.any(ChildProcessError) }), 'CycloneDX validation failed');
        expectNothingLeaks(JSON.stringify(result));
    });
});
