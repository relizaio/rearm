import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as fs from 'fs';
import * as os from 'os';

// As childEnvCallSites.test.ts: spawn is captured at load, so it is patched before any import runs;
// a `rearm` or `cyclonedx-cli` child is a stand-in the test drives.
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
        const call = { cmd, args: [...args], options, child };
        state.calls.push(call);
        const handler = state.onSpawn;
        setImmediate(() => {
            if (handler) handler(call);
            else { child.emit('exit', 1); child.emit('close', 1); }
        });
        return child;
    };
    return state;
});
const log = vi.hoisted(() => ({
    info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn(), trace: vi.fn(), fatal: vi.fn()
}));
const store = vi.hoisted(() => ({ boms: {} as Record<string, any> }));

vi.mock('../../src/logger', () => ({ logger: log }));
vi.mock('../../src/bomService', async (importOriginal) => {
    const actual: any = await importOriginal();
    return {
        ...actual,
        findBomObjectById: async (id: string) => store.boms[id],
        augmentBomWithComponentContext: (bom: any) => bom,
        mergeBomObjects: async (boms: any[]) => boms[0]
    };
});
// The score runs through shellExecDetailed, which is replaced here; the other helpers stay real.
vi.mock('../../src/utils', async (importOriginal) => {
    const actual = await importOriginal<typeof import('../../src/utils')>();
    return { ...actual, shellExecDetailed: vi.fn() };
});

import { mergeBomObjects } from '../../src/services/bom/bomMergeService';
import { bomDiffExec } from '../../src/services/bom/bomDiffService';
import { scoreBom } from '../../src/services/bom/bomScoreService';
import { SpdxService } from '../../src/services/spdx/spdxService';
import { shellExecDetailed } from '../../src/utils';

/**
 * Each call site of child (a) gives its child the row of its own tool, read literally (TPF-256
 * design 3.1 and 3.6, rows T-1191, T-1199 and T-1233), and passes the stderr level it always had.
 * The parent here holds the trust store, proxy and runtime names of the other rows, so a call site
 * that took the row of another tool shows. The levels are looked at by what each logger line says.
 */
type Call = { cmd: string, args: string[], options: any, child: any };

const PARENT: Record<string, string> = {
    PATH: '/opt/test/bin:/usr/bin', LANG: 'xx_XX.UTF-8', LC_ALL: 'xx_XX.UTF-8', HOME: '/home/test-user', TMPDIR: os.tmpdir(),
    SSL_CERT_FILE: '/etc/test/ca.pem', SSL_CERT_DIR: '/etc/test/certs', HTTPS_PROXY: 'http://proxy.invalid:3128',
    http_proxy: 'http://proxy.invalid:3128', REARM_RESILIENTDNS: 'false', DOTNET_SYSTEM_GLOBALIZATION_INVARIANT: 'false',
    DOTNET_BUNDLE_EXTRACT_BASE_DIR: '/tmp/test-dotnet', TZ: 'UTC'
};
const BOM_ROW = { PATH: '/opt/test/bin:/usr/bin', LANG: 'xx_XX.UTF-8', LC_ALL: 'xx_XX.UTF-8', HOME: '/nonexistent' };
const CYCLONEDX_ROW = {
    PATH: '/opt/test/bin:/usr/bin', LANG: 'xx_XX.UTF-8', LC_ALL: 'xx_XX.UTF-8', DOTNET_SYSTEM_GLOBALIZATION_INVARIANT: 'false',
    DOTNET_BUNDLE_EXTRACT_BASE_DIR: '/tmp/test-dotnet', HOME: '/home/test-user', TMPDIR: os.tmpdir()
};

const cdx = (serial: string, name: string) => ({
    bomFormat: 'CycloneDX', specVersion: '1.6', serialNumber: serial, version: 1,
    metadata: { component: { type: 'application', name, version: '1.0.0', 'bom-ref': name } },
    components: []
});
const spdx = () => ({
    spdxVersion: 'SPDX-2.3', dataLicense: 'CC0-1.0', SPDXID: 'SPDXRef-DOCUMENT', name: 'doc',
    documentNamespace: 'https://example.invalid/doc', creationInfo: { created: '2026-11-27T00:00:00Z', creators: ['Tool: test'] },
    packages: []
});
const mergeOptions: any = { name: 'merged', group: 'Example', version: '1.0.0', purl: 'pkg:generic/example/merged@1.0.0', structure: 'FLAT', tldOnly: false, ignoreDev: false };
const argAfter = (call: Call, flag: string) => call.args[call.args.indexOf(flag) + 1];

/** Emits one stderr chunk and then ends the child; what the logger does with the chunk is caught here. */
function speak(call: Call, caught: unknown[], text: string, code: number, stdout?: string): void {
    try {
        call.child.stderr.emit('data', text);
    } catch (e) {
        caught.push(e);
    }
    if (stdout) call.child.stdout.emit('data', stdout);
    call.child.emit('exit', code);
    call.child.emit('close', code);
}

const errorLines = () => log.error.mock.calls.map((c) => String(c[0]));
const stderrLines = () => [...log.info.mock.calls, ...log.warn.mock.calls].map((c) => String(c[0])).filter((l) => l.startsWith('shell command stderr'));

describe('the rows at the call sites', () => {
    const saved: Record<string, string | undefined> = {};
    const caught: unknown[] = [];

    beforeEach(() => {
        for (const name of Object.keys(PARENT)) saved[name] = process.env[name];
        Object.assign(process.env, PARENT);
        fake.calls.length = 0;
        fake.onSpawn = null;
        caught.length = 0;
        vi.clearAllMocks();
    });

    afterEach(() => {
        for (const [name, value] of Object.entries(saved)) {
            if (value === undefined) delete process.env[name];
            else process.env[name] = value;
        }
        fake.onSpawn = null;
    });

    afterAll(() => {
        fake.cp.spawn = fake.original;
    });

    it('merge: the REARM_BOM row read literally, and its stderr at info', async () => {
        fake.onSpawn = (call) => speak(call, caught, 'merge-says-this', 1);
        await expect(mergeBomObjects([cdx('urn:uuid:1a2b3c4d-0000-4000-8000-000000000001', 'one'), cdx('urn:uuid:1a2b3c4d-0000-4000-8000-000000000002', 'two')], mergeOptions))
            .rejects.toBeInstanceOf(Error);
        expect(fake.calls).toHaveLength(1);
        expect(fake.calls[0].options.env).toStrictEqual(BOM_ROW);
        expect(caught).toStrictEqual([]);
        expect(stderrLines()).toStrictEqual(['shell command stderr: merge-says-this']);
        expect(errorLines().some((l) => l.startsWith('shell command error'))).toBe(false);
    });

    it('SPDX conversion and validation: the REARM_BOM row and the CYCLONEDX_CLI row read literally, stderr of both at error', async () => {
        fake.onSpawn = (call) => {
            if (call.cmd === 'rearm') {
                fs.writeFileSync(argAfter(call, '--outfile'), JSON.stringify(cdx('urn:uuid:9c0e3a52-1d7b-4f60-8e21-6a5b4c3d2e10', 'conv')));
                speak(call, caught, 'convert-says-this', 0, 'converted');
            } else {
                speak(call, caught, 'validate-says-this', 0);
            }
        };
        const result = await SpdxService.convertSpdxToCycloneDx(spdx());
        expect(result.success).toBe(true);
        expect(fake.calls.map((c) => c.cmd)).toStrictEqual(['rearm', 'cyclonedx-cli']);
        expect(fake.calls[0].options.env).toStrictEqual(BOM_ROW);
        expect(fake.calls[1].options.env).toStrictEqual(CYCLONEDX_ROW);
        expect(caught).toStrictEqual([]);
        expect(errorLines()).toContain('shell command error: convert-says-this');
        expect(errorLines()).toContain('shell command error: validate-says-this');
        expect(stderrLines()).toStrictEqual([]);
    });

    it('diff: it resolves with the parsed result of cyclonedx-cli, with the CYCLONEDX_CLI row read literally and stderr at error', async () => {
        store.boms = { from: cdx('urn:uuid:1a2b3c4d-0000-4000-8000-0000000000a1', 'from'), to: cdx('urn:uuid:1a2b3c4d-0000-4000-8000-0000000000a2', 'to') };
        fake.onSpawn = (call) => speak(call, caught, 'diff-says-this', 0, JSON.stringify({ componentVersions: {} }));
        await expect(bomDiffExec(['from'], ['to'], 'org')).resolves.toStrictEqual({ componentVersions: {} });
        expect(fake.calls).toHaveLength(1);
        expect(fake.calls[0].cmd).toBe('cyclonedx-cli');
        expect(fake.calls[0].options.env).toStrictEqual(CYCLONEDX_ROW);
        expect(caught).toStrictEqual([]);
        expect(errorLines()).toContain('shell command error: diff-says-this');
        expect(stderrLines()).toStrictEqual([]);
    });

    it('score: the REARM_BOM row read literally, with the trust store, proxy and runtime names left out', async () => {
        vi.mocked(shellExecDetailed).mockResolvedValue({
            code: 0, signal: null, stdout: JSON.stringify({ reportVersion: 1, profiles: [{ key: 'fda', verdict: 'NOT_READY' }] }),
            stderr: '', timedOut: false, stdoutOverflow: false
        });
        await scoreBom('SPDXVersion: SPDX-2.3\n', ['fda']);
        expect(vi.mocked(shellExecDetailed)).toHaveBeenCalledTimes(1);
        expect(vi.mocked(shellExecDetailed).mock.calls[0][2].env).toStrictEqual(BOM_ROW);
    });
});
