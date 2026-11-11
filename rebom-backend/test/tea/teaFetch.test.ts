import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';
import { configureTea, teaCounters } from '../../src/services/tea/teaService';
import {
    cleanupDirs, configure, FETCH_ID, field, gql, Http, LogSpy, MARKERS, MARK_TOKEN, Q, spyLogs, startHttp, Stub, tempDir, TOKEN,
    UUID_B, workDirOf, API_ROOT, NONE
} from './teaTestKit';
import { SAMPLE, sha256Hex, TeaFixtureArtifact, TeaFixtureServer } from './teaFixtureServer';

/**
 * The fetch: download, hold, bytes, release, expiry (TPF-180 design 3.6.1, 3.6.2, 3.6.6; rows
 * T-58, T-59 and T-70 to T-77; T-24 for T-58: the marker credential is in no result and no log).
 */

const ART = (n: number) => `66666666-6666-4666-8666-66666666666${n}`;

function cyclonedx(serialNumber: string): Buffer {
    const doc = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'fixtures', 'cyclonedx-simple.json'), 'utf8'));
    doc.serialNumber = serialNumber;
    return Buffer.from(JSON.stringify(doc));
}

function sized(prefix: string, bytes: number): Buffer {
    const head = `{"bomFormat":"CycloneDX","specVersion":"1.6","${prefix}":"`;
    return Buffer.from(head + 'v'.repeat(bytes - head.length - 2) + '"}');
}

/** The release of T-58: two SBOMs, a VEX of 150000 bytes, a PDF, a wrong checksum, no usable checksum. */
function t58Artifacts(): TeaFixtureArtifact[] {
    const spdx = Buffer.from(JSON.stringify({ SPDXID: 'SPDXRef-DOCUMENT', spdxVersion: 'SPDX-2.3', dataLicense: 'CC0-1.0', name: 'doc', documentNamespace: 'https://spdx.example.test/t58', packages: [] }));
    const wrong = Buffer.from('the listed bytes');
    return [
        { uuid: ART(1), type: 'BOM', mediaType: 'application/vnd.cyclonedx+json', body: cyclonedx('urn:uuid:7a0c1b2e-58aa-4e3f-9b1c-0d2e3f4a5b61') },
        { uuid: ART(2), type: 'BOM', mediaType: 'application/spdx+json', body: spdx },
        { uuid: ART(3), type: 'VULNERABILITIES', mediaType: 'application/vnd.cyclonedx+json', body: sized('vex', 150000) },
        { uuid: ART(4), type: 'LICENSE', mediaType: 'application/pdf', body: Buffer.from('%PDF-1.4\n%\u00e2\u00e3\n1 0 obj\n<<>>\nendobj\n', 'latin1') },
        { uuid: ART(5), type: 'BOM', mediaType: 'application/vnd.cyclonedx+json', body: wrong, served: Buffer.from('other bytes served') },
        { uuid: ART(6), type: 'BOM', mediaType: 'application/vnd.cyclonedx+json', body: Buffer.from('{"no":"checksum"}'), checksums: [] }
    ];
}

describe('TEA fetch', () => {
    let gqlHttp: Http;
    let fixture: TeaFixtureServer;
    let stub: Stub | undefined;
    let logs: LogSpy | undefined;
    let answers: string[] = [];

    beforeAll(async () => {
        gqlHttp = await startHttp();
    });

    afterAll(async () => {
        await gqlHttp.server.stop();
    });

    afterEach(async () => {
        if (logs) {
            const logged = logs.text();
            logs.restore();
            logs = undefined;
            for (const marker of MARKERS) {
                expect(logged).not.toContain(marker);
                for (const answer of answers) expect(answer).not.toContain(marker);
            }
        }
        answers = [];
        stub?.restore();
        stub = undefined;
        await fixture?.stop();
        cleanupDirs();
    });

    async function startFixture(artifacts: TeaFixtureArtifact[]): Promise<void> {
        fixture = new TeaFixtureServer();
        await fixture.start();
        fixture.serveComponentCollection(artifacts);
    }

    async function fetchCollection(extra: Record<string, unknown> = {}, credential: unknown = { kind: 'NONE' }): Promise<any> {
        const answer = await gql(gqlHttp.url, Q.fetch, { input: {
            apiRoot: fixture.root, releaseKind: 'COMPONENT_RELEASE', releaseUuid: SAMPLE.componentRelease, collectionVersion: 1,
            documentClasses: ['SBOM', 'VEX', 'OTHER'], credential, ...extra
        } });
        answers.push(answer.text);
        return field(answer);
    }

    async function chunk(fetchId: string, number: number, offset: number, length: number): Promise<any> {
        return field(await gql(gqlHttp.url, Q.chunk, { fetchId, number, offset, length }));
    }

    /** The download directories under the scratch root. */
    function dlDirs(root: string): string[] {
        const work = workDirOf(root);
        return fs.readdirSync(work).map((d) => path.join(work, d, 'dl')).filter((d) => fs.existsSync(d));
    }

    it('T-58 a mixed release: PARTIAL, a typed row per file, digests of the served bytes, the held files only', async () => {
        const artifacts = t58Artifacts();
        await startFixture(artifacts);
        const root = configure({ REBOM_TEA_LOCAL_TESTING: 'ON' });
        logs = spyLogs();
        const before = Date.now();
        const result = await fetchCollection({}, { kind: 'BEARER_TOKEN', authRoot: fixture.root, token: MARK_TOKEN });
        expect(result.outcome, JSON.stringify(result.error)).toBe('PARTIAL');
        const rows = result.files;
        expect(rows.map((r: any) => r.number)).toEqual([1, 2, 3, 4, 5, 6]);
        expect(rows.map((r: any) => r.status)).toEqual(['VERIFIED', 'VERIFIED', 'VERIFIED', 'VERIFIED', 'FAILED', 'FAILED']);
        expect(rows.map((r: any) => r.documentClass)).toEqual(['SBOM', 'SBOM', 'VEX', 'OTHER', 'SBOM', 'SBOM']);
        expect(rows[4].reason).toBe('CHECKSUM_MISMATCH');
        expect(rows[5].reason).toBe('NO_USABLE_CHECKSUM');
        for (let i = 0; i < 4; i++) {
            expect(rows[i].sizeBytes).toBe(artifacts[i].body.length);
            expect(rows[i].sha256).toBe(sha256Hex(artifacts[i].body));
        }
        expect(rows[4].sha256).toBeNull();
        const manifest = JSON.parse(result.manifest);
        expect(manifest.kind).toBe('TEA_DOWNLOAD_MANIFEST');
        expect(manifest.files.map((f: any) => [f.number, f.status])).toEqual(rows.map((r: any) => [r.number, r.status]));
        expect(result.fetchId).toMatch(/^[0-9a-f]{32}$/);
        expect(result.expiresAt).toMatch(/Z$/);
        const expires = Date.parse(result.expiresAt);
        expect(expires).toBeGreaterThan(before);
        expect(expires).toBeLessThanOrEqual(Date.now() + 600 * 1000);
        const dirs = dlDirs(root);
        expect(dirs).toHaveLength(1);
        expect(fs.readdirSync(dirs[0]).sort()).toEqual(['000001.bin', '000002.bin', '000003.bin', '000004.bin']);
    });

    it('T-59 a release whose files all verify is OK', async () => {
        await startFixture(t58Artifacts().slice(0, 4));
        configure({ REBOM_TEA_LOCAL_TESTING: 'ON' });
        const result = await fetchCollection();
        expect(result.outcome).toBe('OK');
        expect(result.error).toBeNull();
        expect(result.files.every((f: any) => f.status === 'VERIFIED')).toBe(true);
    });

    it('T-70 the VEX of T-58 read back by chunks equals the served bytes; clamp, end, beyond, a FAILED row, an empty file', async () => {
        const artifacts = [...t58Artifacts(), { uuid: ART(7), type: 'OTHER', mediaType: 'text/plain', body: Buffer.alloc(0) }];
        await startFixture(artifacts);
        configure({ REBOM_TEA_LOCAL_TESTING: 'ON', REBOM_TEA_MAX_CHUNK_BYTES: '65536' });
        const result = await fetchCollection();
        const vex = result.files[2];
        expect(vex.documentClass).toBe('VEX');
        expect(vex.sizeBytes).toBe(150000);
        const parts: Buffer[] = [];
        let offset: number | null = 0;
        while (offset !== null) {
            const piece = await chunk(result.fetchId, 3, offset, 1000000);
            expect(piece.outcome).toBe('OK');
            const data = Buffer.from(piece.dataBase64, 'base64');
            expect(data.length).toBeLessThanOrEqual(65536);
            expect(piece.fileSizeBytes).toBe(150000);
            parts.push(data);
            offset = piece.nextOffset;
        }
        const joined = Buffer.concat(parts);
        expect(joined.equals(artifacts[2].body)).toBe(true);
        expect(sha256Hex(joined)).toBe(vex.sha256);
        const clamped = await chunk(result.fetchId, 3, 0, 1000000);
        expect(Buffer.from(clamped.dataBase64, 'base64').length).toBe(65536);
        expect(clamped.nextOffset).toBe(65536);
        const atEnd = await chunk(result.fetchId, 3, 150000, 10);
        expect(atEnd.outcome).toBe('OK');
        expect(atEnd.dataBase64).toBe('');
        expect(atEnd.nextOffset).toBeNull();
        const beyond = await chunk(result.fetchId, 3, 150001, 10);
        expect(beyond.outcome).toBe('INVALID_REQUEST');
        const failedRow = await chunk(result.fetchId, 5, 0, 10);
        expect(failedRow.outcome).toBe('INVALID_REQUEST');
        expect(failedRow.error.reason).toBe('FILE_NOT_HELD');
        const empty = await chunk(result.fetchId, 7, 0, 10);
        expect(empty.outcome).toBe('OK');
        expect(empty.dataBase64).toBe('');
        expect(empty.nextOffset).toBeNull();
        expect(empty.fileSizeBytes).toBe(0);
    });

    it('T-71 release: directory gone, budget freed; later reads and stores are FETCH_NOT_HELD; release again and unknown are OK', async () => {
        await startFixture(t58Artifacts().slice(0, 3));
        const root = configure({ REBOM_TEA_LOCAL_TESTING: 'ON' });
        const result = await fetchCollection();
        expect(teaCounters().diskBytes).toBeGreaterThan(0);
        expect(field(await gql(gqlHttp.url, Q.releaseFetch, { fetchId: result.fetchId })).outcome).toBe('OK');
        expect(fs.readdirSync(workDirOf(root))).toEqual([]);
        expect(teaCounters().diskBytes).toBe(0);
        expect((await chunk(result.fetchId, 1, 0, 10)).outcome).toBe('FETCH_NOT_HELD');
        const store = field(await gql(gqlHttp.url, Q.store, { input: { fetchId: result.fetchId, number: 1, org: SAMPLE.product, rebomOptions: {} } }));
        expect(store.outcome).toBe('FETCH_NOT_HELD');
        expect(field(await gql(gqlHttp.url, Q.releaseFetch, { fetchId: result.fetchId })).outcome).toBe('OK');
        expect(field(await gql(gqlHttp.url, Q.releaseFetch, { fetchId: FETCH_ID })).outcome).toBe('OK');
    });

    it('T-72 the hold expires absolutely: a read at 9 s does not move it; after 10 s the fetch is gone; holdSeconds is clamped', async () => {
        await startFixture(t58Artifacts().slice(0, 1));
        let now = Date.now();
        const root = configure({ REBOM_TEA_LOCAL_TESTING: 'ON', REBOM_TEA_HOLD_SECONDS: '10' }, { now: () => now });
        const result = await fetchCollection({ fetchLimits: { holdSeconds: 3600 } });
        expect(result.limits.holdSeconds).toBe(10);
        const expiresAt = result.expiresAt;
        now += 9000;
        expect((await chunk(result.fetchId, 1, 0, 10)).outcome).toBe('OK');
        now += 1001;
        expect((await chunk(result.fetchId, 1, 0, 10)).outcome).toBe('FETCH_NOT_HELD');
        expect(fs.readdirSync(workDirOf(root))).toEqual([]);
        expect(expiresAt).toBe(new Date(Date.parse(expiresAt)).toISOString());
    });

    it('T-73 a release whose only file fails its checksum: PARTIAL, no fetchId, the directory gone', async () => {
        await startFixture([t58Artifacts()[4]]);
        const root = configure({ REBOM_TEA_LOCAL_TESTING: 'ON' });
        const result = await fetchCollection();
        expect(result.outcome).toBe('PARTIAL');
        expect(result.fetchId).toBeNull();
        expect(result.files[0].reason).toBe('CHECKSUM_MISMATCH');
        expect(fs.readdirSync(workDirOf(root))).toEqual([]);
        expect(teaCounters().diskBytes).toBe(0);
    });

    it('T-74 a manifest and a directory that disagree are MANIFEST_INVALID: nothing held, the directory gone', async () => {
        fixture = new TeaFixtureServer();
        await fixture.start();
        stub = new Stub();
        const root = configure();
        const row = (extra: Record<string, unknown>) => ({ number: 1, status: 'VERIFIED', fileName: '000001.bin', sizeBytes: 3, artifactType: 'BOM', ...extra });
        const cases: Array<[string, Record<string, unknown>[], Record<string, string | Buffer | { link: string }>]> = [
            ['a path in fileName', [row({ fileName: '../../etc/passwd' })], { '000001.bin': 'abc' }],
            ['another number\'s file', [row({ fileName: '000002.bin' })], { '000002.bin': 'abc' }],
            ['VERIFIED without a file', [row({})], {}],
            ['a size that differs', [row({ sizeBytes: 4 })], { '000001.bin': 'abc' }],
            ['a symbolic link', [row({})], { '000001.bin': { link: '/etc/hostname' } }],
            ['an extra file', [row({})], { '000001.bin': 'abc', 'extra.bin': 'x' }],
            ['a number twice', [row({}), row({})], { '000001.bin': 'abc' }],
            ['an unknown status', [row({ status: 'MAYBE' })], { '000001.bin': 'abc' }],
            ['a negative size', [row({ sizeBytes: -1 })], { '000001.bin': 'abc' }],
            ['a fractional size', [row({ sizeBytes: 2.5 })], { '000001.bin': 'abc' }],
            ['a size as a string', [row({ sizeBytes: '3' })], { '000001.bin': 'abc' }]
        ];
        for (const [label, files, dir] of cases) {
            stub.downloadDir(dir);
            stub.behave({ stdout: JSON.stringify({ formatVersion: 1, kind: 'TEA_DOWNLOAD_MANIFEST', files }) + '\n' });
            const result = field(await gql(gqlHttp.url, Q.fetch, { input: { apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: ['SBOM'], credential: NONE } }));
            expect(result.outcome, label).toBe('CONTRACT_MISMATCH');
            expect(result.error.reason, label).toBe('MANIFEST_INVALID');
            expect(result.fetchId, label).toBeNull();
            expect(fs.readdirSync(workDirOf(root)), label).toEqual([]);
        }
        expect(teaCounters().diskBytes).toBe(0);
        expect(fixture.connections).toBe(0);
    });

    it('T-75 artifact, total and file-count limits and a stalled third file: typed rows, PARTIAL or LIMIT', async () => {
        const body = (n: number, size: number) => Buffer.alloc(size, 65 + n);
        const three = [1, 2, 3].map((n) => ({ uuid: ART(n), type: 'BOM', mediaType: 'application/octet-stream', body: body(n, 2000) }));
        await startFixture(three);
        configure({ REBOM_TEA_LOCAL_TESTING: 'ON' });
        const artifact = await fetchCollection({ fetchLimits: { maxArtifactBytes: 1000 } });
        expect(artifact.outcome).toBe('PARTIAL');
        expect(artifact.files.map((f: any) => [f.status, f.reason])).toEqual([['FAILED', 'ARTIFACT_BYTES'], ['FAILED', 'ARTIFACT_BYTES'], ['FAILED', 'ARTIFACT_BYTES']]);

        const total = await fetchCollection({ fetchLimits: { maxTotalBytes: 4500 } });
        expect(total.outcome).toBe('PARTIAL');
        expect(total.files.map((f: any) => f.status)).toEqual(['VERIFIED', 'VERIFIED', 'FAILED']);
        expect(total.files[2].reason).toBe('TOTAL_BYTES');

        const count = await fetchCollection({ fetchLimits: { maxFiles: 2 } });
        expect(count.outcome).toBe('LIMIT');
        expect(count.error.reason).toBe('FILE_COUNT');
        expect(count.fetchId).toBeNull();

        await fixture.stop();
        await startFixture([three[0], three[1], { ...three[2], stall: true }]);
        const stalled = await fetchCollection({ limits: { deadlineSeconds: 3 } });
        expect(stalled.outcome).toBe('PARTIAL');
        expect(stalled.files.map((f: any) => f.status)).toEqual(['VERIFIED', 'VERIFIED', 'FAILED']);
        expect(stalled.fetchId).toMatch(/^[0-9a-f]{32}$/);
    });

    it('T-76 exit 7 with files but no manifest, and exit 1 after writing files: nothing held, the directory gone', async () => {
        stub = new Stub();
        const root = configure();
        const input = { input: { apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: ['SBOM'], credential: NONE } };
        stub.downloadDir({ '000001.bin': 'abc' });
        stub.behave({ exit: 7 });
        const partial = field(await gql(gqlHttp.url, Q.fetch, input));
        expect(partial.outcome).toBe('CONTRACT_MISMATCH');
        expect(fs.readdirSync(workDirOf(root))).toEqual([]);
        stub.behave({ exit: 1, stderr: '{"formatVersion":1,"kind":"TEA_ERROR","exitCode":1,"reason":"LOCAL_IO","message":"disk full"}\n' });
        const failed = field(await gql(gqlHttp.url, Q.fetch, input));
        expect(failed.outcome).toBe('FAILED');
        expect(failed.error.reason).toBe('LOCAL_IO');
        expect(fs.readdirSync(workDirOf(root))).toEqual([]);
        expect(teaCounters().diskBytes).toBe(0);
    });

    it('T-77 a held fetch is forgotten when the configuration is built again', async () => {
        await startFixture(t58Artifacts().slice(0, 1));
        const root = path.join(tempDir('rebom-tea-restart-'), 'root');
        const env = { REBOM_TEA_CALLER_TOKEN: TOKEN, REBOM_TEA_TMP_ROOT: root, REBOM_TEA_LOCAL_TESTING: 'ON' };
        configureTea(env);
        const result = await fetchCollection();
        expect(fs.readdirSync(workDirOf(root))).toHaveLength(1);
        configureTea(env);
        expect(fs.readdirSync(workDirOf(root))).toEqual([]);
        expect((await chunk(result.fetchId, 1, 0, 10)).outcome).toBe('FETCH_NOT_HELD');
    });
});
