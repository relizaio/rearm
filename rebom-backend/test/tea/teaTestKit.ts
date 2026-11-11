import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { vi } from 'vitest';
import { ApolloServer } from '@apollo/server';
import { createGraphqlServer, startGraphqlHttp } from '../../src/graphqlServer';
import { logger } from '../../src/logger';
import { configureTea, TeaConfigureOptions } from '../../src/services/tea/teaService';
import { TeaRequestContext } from '../../src/services/tea/teaCallerGate';

/**
 * The rig of the TEA rows (TPF-180 design section 4): the configuration seam, the stub CLI, the
 * HTTP server, the queries, and the marker strings.
 */

export const TOKEN = 'caller-token-0123456789abcdef0123456789abcdef0123';
export const MARK_TOKEN = 'tok-MARK-A';
export const MARK_KEY = 'key-MARK-B';
export const MARK_CALLER = 'caller-MARK-C';
export const MARK_PG = 'pg-MARK-D';
export const MARKERS = [MARK_TOKEN, MARK_KEY, MARK_CALLER, MARK_PG];

export const STUB_SOURCE = path.join(__dirname, 'stub', 'rearm');

/** Every directory a row made, removed in afterEach by cleanupDirs. */
const made: string[] = [];

export function tempDir(prefix: string): string {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
    made.push(dir);
    return dir;
}

export function cleanupDirs(): void {
    while (made.length > 0) {
        const dir = made.pop()!;
        fs.rmSync(dir, { recursive: true, force: true });
    }
}

/** configureTea with the token, a fresh scratch root and whatever the row adds. Returns the root. */
export function configure(extra: Record<string, string | undefined> = {}, options: TeaConfigureOptions = {}): string {
    const root = path.join(tempDir('rebom-tea-root-'), 'root');
    const env: Record<string, string | undefined> = { REBOM_TEA_CALLER_TOKEN: TOKEN, REBOM_TEA_TMP_ROOT: root, ...extra };
    for (const key of Object.keys(env)) if (env[key] === undefined) delete env[key];
    configureTea(env as NodeJS.ProcessEnv, options);
    return root;
}

/** A copy of the stub first on PATH. */
export class Stub {
    readonly dir: string;
    private readonly savedPath: string | undefined;

    constructor() {
        this.dir = tempDir('rebom-tea-stub-');
        fs.copyFileSync(STUB_SOURCE, path.join(this.dir, 'rearm'));
        fs.chmodSync(path.join(this.dir, 'rearm'), 0o755);
        this.savedPath = process.env.PATH;
        process.env.PATH = `${this.dir}${path.delimiter}${process.env.PATH ?? ''}`;
    }

    restore(): void {
        process.env.PATH = this.savedPath;
    }

    write(name: string, content: string | Buffer): void {
        fs.writeFileSync(path.join(this.dir, name), content);
    }

    remove(name: string): void {
        fs.rmSync(path.join(this.dir, name), { recursive: true, force: true });
    }

    /** A run that exits with code and writes stdout and stderr. */
    behave(opts: { exit?: number, stdout?: string, stderr?: string, sleep?: number, child?: boolean, signal?: string }): void {
        for (const name of ['run.exit', 'run.stdout', 'run.stderr', 'run.sleep', 'run.child', 'run.signal']) this.remove(name);
        if (opts.exit !== undefined) this.write('run.exit', String(opts.exit));
        if (opts.stdout !== undefined) this.write('run.stdout', opts.stdout);
        if (opts.stderr !== undefined) this.write('run.stderr', opts.stderr);
        if (opts.sleep !== undefined) this.write('run.sleep', String(opts.sleep));
        if (opts.child) this.write('run.child', '1');
        if (opts.signal !== undefined) this.write('run.signal', opts.signal);
    }

    /** The directory copied to --dir: files by name (a Buffer or a string), or a symbolic link. */
    downloadDir(files: Record<string, string | Buffer | { link: string }>): void {
        this.remove('run.dir');
        const dir = path.join(this.dir, 'run.dir');
        fs.mkdirSync(dir);
        for (const [name, content] of Object.entries(files)) {
            if (typeof content === 'object' && !Buffer.isBuffer(content)) {
                fs.symlinkSync(content.link, path.join(dir, name));
            } else {
                fs.writeFileSync(path.join(dir, name), content);
            }
        }
    }

    runs(): string[] {
        const dir = path.join(this.dir, 'runs');
        return fs.existsSync(dir) ? fs.readdirSync(dir) : [];
    }

    probes(): number {
        const dir = path.join(this.dir, 'probes');
        return fs.existsSync(dir) ? fs.readdirSync(dir).length : 0;
    }

    /** The argument vector of the only (or the last started) run. */
    argv(run?: string): string[] {
        const id = run ?? this.lastRun();
        const text = fs.readFileSync(path.join(this.dir, 'runs', id, 'argv'), 'utf8');
        const out: string[] = [];
        let at = 0;
        while (at < text.length) {
            const colon = text.indexOf(':', at);
            const length = Number(text.substring(at, colon));
            out.push(text.substring(colon + 1, colon + 1 + length));
            at = colon + 1 + length + 1;
        }
        return out;
    }

    env(run?: string): Record<string, string> {
        const id = run ?? this.lastRun();
        const text = fs.readFileSync(path.join(this.dir, 'runs', id, 'env'), 'utf8');
        const out: Record<string, string> = {};
        for (const line of text.split('\n')) {
            const eq = line.indexOf('=');
            if (eq > 0) out[line.substring(0, eq)] = line.substring(eq + 1);
        }
        return out;
    }

    lastRun(): string {
        const runs = this.runs().sort((a, b) => Number(a) - Number(b));
        if (runs.length === 0) throw new Error('no run recorded');
        const byTime = runs.map((id) => ({ id, t: fs.statSync(path.join(this.dir, 'runs', id)).mtimeMs }));
        byTime.sort((a, b) => a.t - b.t);
        return byTime[byTime.length - 1].id;
    }

    clearRuns(): void {
        this.remove('runs');
        this.remove('probes');
        this.remove('starts');
    }

    /** The pids of the runs in the order they started. */
    starts(): string[] {
        const file = path.join(this.dir, 'starts');
        return fs.existsSync(file) ? fs.readFileSync(file, 'utf8').split('\n').filter((l) => l.length > 0) : [];
    }

    readPid(name: string): number {
        return Number(fs.readFileSync(path.join(this.dir, name), 'utf8').trim());
    }
}

/**
 * The process group of a running process: field 5 of /proc/<pid>/stat, read as the third field
 * after the last ')' because the command name may hold spaces and parentheses. The rows run in the
 * builder image (alpine, busybox), whose ps has no -p.
 */
export function processGroupOf(pid: number): number {
    const stat = fs.readFileSync(`/proc/${pid}/stat`, 'utf8');
    return Number(stat.slice(stat.lastIndexOf(')') + 2).split(' ')[2]);
}

export function isAlive(pid: number): boolean {
    try {
        process.kill(pid, 0);
        return true;
    } catch {
        return false;
    }
}

// -------------------------------------------------------------------------------------------------
// HTTP

export interface Http {
    server: ApolloServer<TeaRequestContext>;
    url: string;
}

export async function startHttp(): Promise<Http> {
    const server = createGraphqlServer();
    const { url } = await startGraphqlHttp(server, 0);
    return { server, url };
}

export interface GqlAnswer {
    status: number;
    text: string;
    json: any;
}

export async function gql(url: string, query: string, variables: Record<string, unknown> = {}, header: string | null = TOKEN): Promise<GqlAnswer> {
    const headers: Record<string, string> = { 'content-type': 'application/json' };
    if (header !== null) headers['x-rebom-caller-token'] = header;
    const response = await fetch(url, { method: 'POST', headers, body: JSON.stringify({ query, variables }) });
    const text = await response.text();
    let json: any = null;
    try {
        json = JSON.parse(text);
    } catch {
        // not JSON
    }
    return { status: response.status, text, json };
}

const ERROR = 'error { reason cliExitCode message detail url httpStatus address addressClass phase hint ignoredProxyVariables }';
const LIMITS = 'limits { deadlineSeconds connectTimeoutSeconds maxDocumentBytes maxOutputBytes maxPages pageSize maxArtifactBytes maxTotalBytes maxFiles holdSeconds }';
const DOC = `teaApiVersion outcome ${ERROR} ${LIMITS} document`;

export const Q = {
    status: `query { teaStatus { teaApiVersion outcome ${ERROR} localTesting ceilings { deadlineSeconds connectTimeoutSeconds maxDocumentBytes maxOutputBytes maxPages pageSize maxArtifactBytes maxTotalBytes maxFiles holdSeconds } } }`,
    chunk: `query($fetchId: String!, $number: Int!, $offset: Int!, $length: Int!) { teaFetchedFileChunk(fetchId: $fetchId, number: $number, offset: $offset, length: $length) { teaApiVersion outcome ${ERROR} dataBase64 offset nextOffset fileSizeBytes } }`,
    resolve: `mutation($input: TeaResolveInput!) { teaResolve(input: $input) { ${DOC} } }`,
    product: `mutation($input: TeaObjectInput!) { teaProduct(input: $input) { ${DOC} } }`,
    component: `mutation($input: TeaObjectInput!) { teaComponent(input: $input) { ${DOC} } }`,
    releases: `mutation($input: TeaReleasesInput!) { teaReleases(input: $input) { ${DOC} } }`,
    release: `mutation($input: TeaReleaseInput!) { teaRelease(input: $input) { ${DOC} } }`,
    fetch: `mutation($input: TeaFetchInput!) { teaFetchCollection(input: $input) { teaApiVersion outcome ${ERROR} ${LIMITS} manifest files { number status reason documentClass sizeBytes sha256 } fetchId expiresAt } }`,
    store: `mutation($input: TeaSbomStoreInput!) { teaStoreFetchedSbom(input: $input) { teaApiVersion outcome ${ERROR} stored format bom { uuid meta organization } conflict { serialNumber storedVersion documentVersion existingUuid } storeMessage } }`,
    releaseFetch: `mutation($fetchId: String!) { teaReleaseFetch(fetchId: $fetchId) { teaApiVersion outcome ${ERROR} } }`
};

export const NONE = { kind: 'NONE' };
export const API_ROOT = 'https://tea.example.com/tea';
export const UUID_A = '33333333-3333-4333-8333-333333333333';
export const UUID_B = '55555555-5555-4555-8555-555555555551';
export const FETCH_ID = '0123456789abcdef0123456789abcdef';

/** One plausible call of each of the ten operations: [name, query, variables]. */
export function tenOperations(): Array<[string, string, Record<string, unknown>]> {
    return [
        ['teaStatus', Q.status, {}],
        ['teaFetchedFileChunk', Q.chunk, { fetchId: FETCH_ID, number: 1, offset: 0, length: 10 }],
        ['teaResolve', Q.resolve, { input: { tei: `tei://tea.example.com/uuid/${UUID_A}`, credential: NONE } }],
        ['teaProduct', Q.product, { input: { apiRoot: API_ROOT, uuid: UUID_A, credential: NONE } }],
        ['teaComponent', Q.component, { input: { apiRoot: API_ROOT, uuid: UUID_A, credential: NONE } }],
        ['teaReleases', Q.releases, { input: { apiRoot: API_ROOT, parentKind: 'PRODUCT', parentUuid: UUID_A, credential: NONE } }],
        ['teaRelease', Q.release, { input: { apiRoot: API_ROOT, releaseKind: 'PRODUCT_RELEASE', releaseUuid: UUID_A, credential: NONE } }],
        ['teaFetchCollection', Q.fetch, { input: { apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: ['SBOM'], credential: NONE } }],
        ['teaStoreFetchedSbom', Q.store, { input: { fetchId: FETCH_ID, number: 1, org: UUID_A, rebomOptions: {} } }],
        ['teaReleaseFetch', Q.releaseFetch, { fetchId: FETCH_ID }]
    ];
}

/** The result object of the one field of an answer. */
export function field(answer: GqlAnswer): any {
    if (!answer.json?.data) throw new Error(`no data: ${answer.text.substring(0, 500)}`);
    return Object.values(answer.json.data)[0];
}

// -------------------------------------------------------------------------------------------------
// Logs

export interface LogSpy {
    calls(): unknown[][];
    text(): string;
    restore(): void;
}

/** A spy on every level of rebom's logger, and on console, for the whole row. */
export function spyLogs(): LogSpy {
    const spies = (['trace', 'debug', 'info', 'warn', 'error', 'fatal'] as const).map((level) =>
        vi.spyOn(logger, level).mockImplementation(() => undefined as any));
    const consoles = (['log', 'warn', 'error'] as const).map((level) => vi.spyOn(console, level).mockImplementation(() => undefined));
    const all = [...spies, ...consoles];
    return {
        calls: () => all.flatMap((spy) => spy.mock.calls as unknown[][]),
        text: () => all.flatMap((spy) => spy.mock.calls).map((call) => safeStringify(call)).join('\n'),
        restore: () => all.forEach((spy) => spy.mockRestore())
    };
}

function safeStringify(value: unknown): string {
    const seen = new WeakSet();
    return JSON.stringify(value, (_key, v) => {
        if (v instanceof Error) return { message: v.message, stack: v.stack, ...v };
        if (typeof v === 'object' && v !== null) {
            if (seen.has(v)) return '[circular]';
            seen.add(v);
        }
        return v;
    }) ?? String(value);
}

/** The warn calls of a log spy whose message (last string argument) is msg. */
export function callsWith(spy: LogSpy, msg: string): unknown[][] {
    return spy.calls().filter((call) => call.some((arg) => arg === msg));
}

export function sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Every file under dir (recursively), for scans. */
export function filesUnder(dir: string): string[] {
    if (!fs.existsSync(dir)) return [];
    const out: string[] = [];
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, entry.name);
        if (entry.isDirectory()) out.push(...filesUnder(p));
        else out.push(p);
    }
    return out;
}

export function workDirOf(root: string): string {
    return path.join(root, 'work');
}
