import { ChildProcess, execFileSync, spawn } from 'child_process';
import * as fs from 'fs';
import * as net from 'net';
import * as path from 'path';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { cleanupDirs, gql, Q, Stub, tempDir, TOKEN } from './teaTestKit';

/**
 * The start and the stop of the process itself (src/index.ts, design 3.4): the settings are read
 * once at start, so the operations are configured; and a stop by SIGTERM or SIGINT removes the
 * scratch work directory before the process exits. The server of src/index.ts listens on 4000, so
 * this file must not run beside another process that holds that port.
 */

const BACKEND = path.resolve(__dirname, '../..');
const PORT = 4000;
const URL = `http://localhost:${PORT}/`;
/** The compiled copy of the sources, inside the project so that its modules are found. */
const BUILD = path.join(BACKEND, `.tea-process-build-${process.pid}`);

beforeAll(() => {
    execFileSync(path.join(BACKEND, 'node_modules/.bin/tsc'), ['-p', 'tsconfig.json', '--outDir', BUILD], { cwd: BACKEND, stdio: 'pipe', timeout: 120_000 });
}, 150_000);

afterAll(() => {
    fs.rmSync(BUILD, { recursive: true, force: true });
});

let stub: Stub;
let child: ChildProcess | null = null;
let output = '';

beforeEach(() => {
    stub = new Stub();
    output = '';
});

afterEach(async () => {
    if (child && child.exitCode === null) {
        child.kill('SIGKILL');
        await exitOf(child);
    }
    child = null;
    stub.restore();
    cleanupDirs();
});

function portFree(): Promise<boolean> {
    return new Promise((resolve) => {
        const probe = net.createServer();
        probe.once('error', () => resolve(false));
        probe.listen(PORT, () => probe.close(() => resolve(true)));
    });
}

async function started(root: string): Promise<ChildProcess> {
    expect(await portFree(), `port ${PORT} must be free for this test`).toBe(true);
    const proc = spawn(process.execPath, [path.join(BUILD, 'src/index.js')], {
        cwd: BACKEND,
        env: { ...process.env, REBOM_TEA_CALLER_TOKEN: TOKEN, REBOM_TEA_TMP_ROOT: root, OCI_STORAGE_ENABLED: 'true', MOCK_OCI: 'true', REBOM_VERSION: 'test' },
        stdio: ['ignore', 'pipe', 'pipe']
    });
    proc.stdout!.on('data', (chunk) => { output += chunk.toString(); });
    proc.stderr!.on('data', (chunk) => { output += chunk.toString(); });
    const deadline = Date.now() + 45_000;
    while (!output.includes('GraphQL Server ready')) {
        if (proc.exitCode !== null) throw new Error(`the process ended with ${proc.exitCode} before it was ready:\n${output.substring(0, 2000)}`);
        if (Date.now() > deadline) throw new Error(`the process was not ready in time:\n${output.substring(0, 2000)}`);
        await new Promise((resolve) => setTimeout(resolve, 100));
    }
    return proc;
}

function exitOf(proc: ChildProcess): Promise<number | null> {
    return new Promise((resolve) => {
        if (proc.exitCode !== null) return resolve(proc.exitCode);
        proc.once('exit', (code) => resolve(code));
    });
}

describe('the process', () => {
    it('configures the TEA operations at start from its environment', async () => {
        const root = path.join(tempDir('rebom-proc-'), 'root');
        child = await started(root);
        const answer = await gql(URL, Q.status);
        expect(answer.json?.data?.teaStatus, answer.text).toMatchObject({ outcome: 'OK' });
        expect(fs.statSync(path.join(root, 'work')).isDirectory()).toBe(true);
    }, 90_000);

    it.each(['SIGTERM', 'SIGINT'] as const)('removes the scratch work directory and exits 0 on %s', async (signal) => {
        const root = path.join(tempDir('rebom-proc-'), 'root');
        child = await started(root);
        fs.mkdirSync(path.join(root, 'work', 'f-left-over'));
        fs.writeFileSync(path.join(root, 'work', 'f-left-over', 'x'), 'x');
        child.kill(signal);
        expect(await exitOf(child)).toBe(0);
        expect(fs.existsSync(path.join(root, 'work'))).toBe(false);
        expect(fs.existsSync(root)).toBe(true);
    }, 90_000);
});
