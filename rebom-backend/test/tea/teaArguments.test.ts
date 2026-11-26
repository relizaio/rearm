import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { buildTeaArgv, selectionFlags } from '../../src/services/tea/teaArgv';
import { effectiveLimits, readTeaConfig, TEA_SETTINGS, TeaCeilings, TeaLocalTesting, TeaRunKind } from '../../src/services/tea/teaConfig';
import { TeaRequest, validateFetch, validateObject, validateReleases, validateRelease, validateResolve, TeaVerb } from '../../src/services/tea/teaRequest';
import { configureTea } from '../../src/services/tea/teaService';
import {
    API_ROOT, cleanupDirs, configure, field, gql, Http, MARKERS, MARK_KEY, MARK_TOKEN, NONE, Q, spyLogs, startHttp, Stub,
    tempDir, UUID_A, UUID_B
} from './teaTestKit';

/**
 * Validation, arguments, limits and settings (TPF-180 design 3.5, 3.6.5, 3.7; rows T-9 to T-16).
 */

const OFF = TeaLocalTesting.OFF;
const DEFAULTS = (() => {
    const read = readTeaConfig({});
    if (!read.ok) throw new Error('defaults must be valid');
    return read.config.ceilings;
})();
const DL_DIR = '/scratch/work/f-abc/dl';

function valid(v: { ok: boolean, value?: TeaRequest }): TeaRequest {
    if (!v.ok) throw new Error(`refused: ${JSON.stringify(v)}`);
    return v.value!;
}

/** One request per verb, with defaults. */
function requests(): Record<string, TeaRequest> {
    return {
        resolve: valid(validateResolve({ tei: `tei://tea.example.com/uuid/${UUID_A}`, credential: NONE }, OFF)),
        product: valid(validateObject({ apiRoot: API_ROOT, uuid: UUID_A, credential: NONE }, TeaVerb.PRODUCT, OFF)),
        component: valid(validateObject({ apiRoot: API_ROOT, uuid: UUID_A, credential: NONE }, TeaVerb.COMPONENT, OFF)),
        releases: valid(validateReleases({ apiRoot: API_ROOT, parentKind: 'PRODUCT', parentUuid: UUID_A, credential: NONE }, OFF)),
        release: valid(validateRelease({ apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, credential: NONE }, OFF)),
        download: valid(validateFetch({ apiRoot: API_ROOT, releaseKind: 'PRODUCT_RELEASE', releaseUuid: UUID_A, collectionVersion: 3, documentClasses: ['SBOM', 'VEX', 'OTHER'], credential: NONE }, OFF))
    };
}

function argvOf(request: TeaRequest, ceilings: TeaCeilings = DEFAULTS): string[] {
    const kind = request.verb === TeaVerb.DOWNLOAD ? TeaRunKind.DOWNLOAD : request.verb === TeaVerb.RELEASES ? TeaRunKind.RELEASE_LIST : TeaRunKind.READ;
    const limits = effectiveLimits(kind, ceilings, request.limits, request.fetchLimits, { pageSize: request.pageSize, maxPages: request.maxPages });
    return buildTeaArgv(request, limits, OFF, DL_DIR);
}

const READ_LIMITS = ['--connect-timeout=5s', '--deadline=60s', '--max-document-bytes=16777216', '--max-redirects=5', '--max-string-bytes=8192', '--max-output-bytes=8388608'];

/** The golden vectors of design 3.5 with the default settings. */
const GOLDEN: Record<string, string[]> = {
    resolve: ['tea', 'machine', 'resolve', `--tei=tei://tea.example.com/uuid/${UUID_A}`, ...READ_LIMITS],
    product: ['tea', 'machine', 'product', `--api-root=${API_ROOT}`, `--uuid=${UUID_A}`, ...READ_LIMITS],
    component: ['tea', 'machine', 'component', `--api-root=${API_ROOT}`, `--uuid=${UUID_A}`, ...READ_LIMITS],
    releases: ['tea', 'machine', 'releases', `--api-root=${API_ROOT}`, `--product=${UUID_A}`, ...READ_LIMITS, '--page-size=100', '--max-pages=10'],
    release: ['tea', 'machine', 'release', `--api-root=${API_ROOT}`, `--component-release=${UUID_B}`, ...READ_LIMITS],
    download: ['tea', 'machine', 'download', `--api-root=${API_ROOT}`, `--product-release=${UUID_A}`, '--collection-version=3', `--dir=${DL_DIR}`,
        '--connect-timeout=5s', '--deadline=600s', '--max-document-bytes=16777216', '--max-redirects=5', '--max-string-bytes=8192', '--max-output-bytes=8388608',
        '--max-artifact-bytes=52428800', '--max-total-bytes=268435456', '--max-files=200']
};

/** The limit flags of each verb. */
const LIMIT_FLAGS: Record<string, string[]> = {
    common: ['--connect-timeout', '--deadline', '--max-document-bytes', '--max-redirects', '--max-string-bytes', '--max-output-bytes'],
    releases: ['--page-size', '--max-pages'],
    download: ['--max-artifact-bytes', '--max-total-bytes', '--max-files']
};

function expectEveryLimitFlag(verb: string, argv: string[]): void {
    const flags = [...LIMIT_FLAGS.common, ...(LIMIT_FLAGS[verb] ?? [])];
    for (const flag of flags) {
        const element = argv.find((a) => a.startsWith(`${flag}=`));
        expect(element, `${verb} ${flag}`).toBeDefined();
        expect(parseInt(element!.substring(flag.length + 1), 10), `${verb} ${flag}`).toBeGreaterThan(0);
    }
    expect(argv).not.toContain('-d');
    expect(argv.some((a) => a.startsWith('--credential-stdin'))).toBe(false);
    expect(argv.some((a) => a.startsWith('-d=') || a.startsWith('--debug'))).toBe(false);
}

describe('TEA arguments, validation, limits and settings', () => {
    let http: Http;
    let stub: Stub;

    beforeAll(async () => {
        http = await startHttp();
    });

    afterAll(async () => {
        await http.server.stop();
    });

    afterEach(() => {
        stub?.restore();
        cleanupDirs();
    });

    it('T-9 the vector of each verb with defaults equals the golden list element by element', () => {
        const all = requests();
        for (const verb of Object.keys(GOLDEN)) {
            expect(argvOf(all[verb]), verb).toEqual(GOLDEN[verb]);
        }
    });

    it('T-10 a page token that looks like a flag, a command or holds quotes is exactly one element', async () => {
        stub = new Stub();
        stub.behave({ stdout: '{"formatVersion":1,"kind":"TEA_RELEASE_LIST"}\n' });
        configure();
        const reference = [...GOLDEN.releases.slice(0, 5), '--page-token=x', ...GOLDEN.releases.slice(5)].length;
        for (const token of ['--dir=/etc', '$(id)', 'a b', 'x\'y"z;w']) {
            stub.clearRuns();
            const answer = field(await gql(http.url, Q.releases, { input: { apiRoot: API_ROOT, parentKind: 'PRODUCT', parentUuid: UUID_A, pageToken: token, credential: NONE } }));
            expect(answer.outcome, token).toBe('OK');
            const argv = stub.argv();
            expect(argv.filter((a) => a === `--page-token=${token}`), token).toHaveLength(1);
            expect(argv.filter((a) => a.includes(token)), token).toHaveLength(1);
            expect(argv, token).toHaveLength(reference);
        }
    });

    it('T-11 one refused example per validation rule: INVALID_REQUEST naming the field, no marker anywhere, nothing starts', async () => {
        stub = new Stub();
        configure();
        const logs = spyLogs();
        const bearer = { kind: 'BEARER_TOKEN', authRoot: API_ROOT, token: MARK_TOKEN };
        const cases: Array<[string, string, Record<string, unknown>]> = [
            ['input.apiRoot', Q.product, { input: { apiRoot: `ftp://${MARK_TOKEN}.example.com/tea`, uuid: UUID_A, credential: NONE } }],
            ['input.apiRoot', Q.product, { input: { apiRoot: `https://${MARK_KEY}:p@tea.example.com/tea`, uuid: UUID_A, credential: NONE } }],
            ['input.apiRoot', Q.product, { input: { apiRoot: `https://tea.example.com/${MARK_TOKEN} x`, uuid: UUID_A, credential: NONE } }],
            ['input.credential.authRoot', Q.product, { input: { apiRoot: API_ROOT, uuid: UUID_A, credential: { kind: 'BEARER_TOKEN', authRoot: `http://${MARK_KEY}.example.com`, token: MARK_TOKEN } } }],
            ['input.tei', Q.resolve, { input: { tei: `urn:tei:${MARK_TOKEN}`, credential: NONE } }],
            ['input.uuid', Q.product, { input: { apiRoot: API_ROOT, uuid: MARK_TOKEN, credential: bearer } }],
            ['input.pageToken', Q.releases, { input: { apiRoot: API_ROOT, parentKind: 'PRODUCT', parentUuid: UUID_A, pageToken: `${MARK_TOKEN}\u00e9`, credential: NONE } }],
            ['input.pageSize', Q.releases, { input: { apiRoot: API_ROOT, parentKind: 'PRODUCT', parentUuid: UUID_A, pageSize: 101, credential: bearer } }],
            ['input.maxPages', Q.releases, { input: { apiRoot: API_ROOT, parentKind: 'PRODUCT', parentUuid: UUID_A, maxPages: 1001, credential: bearer } }],
            ['input.collectionVersion', Q.fetch, { input: { apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 0, documentClasses: ['SBOM'], credential: bearer } }],
            ['input.localPlainHttpPort', Q.resolve, { input: { tei: `tei://tea.example.com/uuid/${UUID_A}`, localPlainHttpPort: 8080, credential: bearer } }],
            ['input.credential.token', Q.product, { input: { apiRoot: API_ROOT, uuid: UUID_A, credential: { kind: 'BEARER_TOKEN', authRoot: API_ROOT, token: `${MARK_TOKEN}${'x'.repeat(8192)}` } } }],
            ['input.mediaTypes', Q.fetch, { input: { apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: ['SBOM'], mediaTypes: [`${MARK_TOKEN}`], credential: NONE } }],
            ['input.mediaTypes', Q.fetch, { input: { apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: ['SBOM'], mediaTypes: Array(17).fill('application/json'), credential: NONE } }],
            ['input.documentClasses', Q.fetch, { input: { apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: ['SBOM', 'SBOM'], credential: bearer } }],
            ['input.limits.maxDocumentBytes', Q.product, { input: { apiRoot: API_ROOT, uuid: UUID_A, credential: bearer, limits: { maxDocumentBytes: 1023 } } }],
            ['input.correlationId', Q.product, { input: { apiRoot: API_ROOT, uuid: UUID_A, credential: bearer, correlationId: `${MARK_TOKEN}!` } }],
            ['fetchId', Q.chunk, { fetchId: MARK_TOKEN, number: 1, offset: 0, length: 1 }],
            ['input.fetchId', Q.store, { input: { fetchId: MARK_KEY, number: 1, org: UUID_A, rebomOptions: {} } }],
            ['input.org', Q.store, { input: { fetchId: '0123456789abcdef0123456789abcdef', number: 1, org: MARK_KEY, rebomOptions: {} } }],
            ['fetchId', Q.releaseFetch, { fetchId: `${MARK_TOKEN}` }]
        ];
        try {
            for (const [name, query, variables] of cases) {
                const answer = await gql(http.url, query, variables);
                const result = field(answer);
                expect(result.outcome, name).toBe('INVALID_REQUEST');
                expect(result.error.reason, name).toBe('INPUT');
                expect(result.error.detail.startsWith(`${name}:`), `${name}: ${result.error.detail}`).toBe(true);
                for (const marker of MARKERS) expect(answer.text, name).not.toContain(marker);
            }
            const logged = logs.text();
            for (const marker of MARKERS) expect(logged).not.toContain(marker);
        } finally {
            logs.restore();
        }
        expect(stub.runs()).toEqual([]);
        expect(stub.probes()).toBe(0);
    });

    it('T-12 credential shapes: NONE with a token, BEARER without authRoot, API_KEY without secret, line feed, NUL', () => {
        const object = (credential: unknown) => validateObject({ apiRoot: API_ROOT, uuid: UUID_A, credential }, TeaVerb.PRODUCT, OFF);
        const refused = [
            { kind: 'NONE', token: MARK_TOKEN },
            { kind: 'BEARER_TOKEN', token: MARK_TOKEN },
            { kind: 'API_KEY', authRoot: API_ROOT, keyId: MARK_KEY },
            { kind: 'BEARER_TOKEN', authRoot: API_ROOT, token: `${MARK_TOKEN}\nx` },
            { kind: 'BEARER_TOKEN', authRoot: API_ROOT, token: `${MARK_TOKEN}\u0000x` },
            { kind: 'BEARER_TOKEN', authRoot: API_ROOT, token: MARK_TOKEN, keyId: MARK_KEY },
            { kind: 'API_KEY', authRoot: API_ROOT, keyId: MARK_KEY, keySecret: MARK_KEY, token: MARK_TOKEN }
        ];
        for (const credential of refused) {
            const result = object(credential);
            expect(result.ok, JSON.stringify(credential)).toBe(false);
        }
        expect(object({ kind: 'BEARER_TOKEN', authRoot: API_ROOT, token: MARK_TOKEN }).ok).toBe(true);
        expect(object({ kind: 'API_KEY', authRoot: API_ROOT, keyId: MARK_KEY, keySecret: MARK_KEY }).ok).toBe(true);
    });

    it('T-13 a limit above the ceiling is clamped, below it passes, zero and negative are refused, a lowered setting follows', async () => {
        stub = new Stub();
        stub.behave({ stdout: '{"formatVersion":1,"kind":"TEA_DOWNLOAD_MANIFEST","files":[]}\n' });
        const flags: Record<string, [string, string, string]> = {
            // limit: [input group, flag, setting]
            deadlineSeconds: ['limits', '--deadline', 'REBOM_TEA_DOWNLOAD_DEADLINE_SECONDS'],
            connectTimeoutSeconds: ['limits', '--connect-timeout', 'REBOM_TEA_CONNECT_TIMEOUT_SECONDS'],
            maxDocumentBytes: ['limits', '--max-document-bytes', 'REBOM_TEA_MAX_DOCUMENT_BYTES'],
            maxOutputBytes: ['limits', '--max-output-bytes', 'REBOM_TEA_MAX_OUTPUT_BYTES'],
            maxArtifactBytes: ['fetchLimits', '--max-artifact-bytes', 'REBOM_TEA_MAX_ARTIFACT_BYTES'],
            maxTotalBytes: ['fetchLimits', '--max-total-bytes', 'REBOM_TEA_MAX_TOTAL_BYTES'],
            maxFiles: ['fetchLimits', '--max-files', 'REBOM_TEA_MAX_FILES'],
            holdSeconds: ['fetchLimits', '', 'REBOM_TEA_HOLD_SECONDS']
        };
        const lowerBy: Record<string, number> = { maxDocumentBytes: 1024, maxOutputBytes: 65536 };
        const spec = (variable: string) => TEA_SETTINGS.find((s) => s.variable === variable)!;
        const fetchWith = async (group: string, key: string, value: number) => field(await gql(http.url, Q.fetch, { input: {
            apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: ['SBOM'], credential: NONE,
            [group]: { [key]: value }
        } }));
        const flagValue = (flag: string) => parseInt(stub.argv().find((a) => a.startsWith(`${flag}=`))!.substring(flag.length + 1), 10);
        for (const [key, [group, flag, setting]] of Object.entries(flags)) {
            configure();
            const ceiling = spec(setting).defaultValue;
            // below the ceiling, and a valid value of the setting too
            const below = Math.max(spec(setting).min, lowerBy[key] ?? 1) + 1;
            let result = await fetchWith(group, key, ceiling + 1);
            expect(result.outcome, `${key} above`).toBe('OK');
            expect(result.limits[key], `${key} above`).toBe(ceiling);
            if (flag) expect(flagValue(flag), `${key} above`).toBe(ceiling);
            result = await fetchWith(group, key, below);
            expect(result.limits[key], `${key} below`).toBe(below);
            if (flag) expect(flagValue(flag), `${key} below`).toBe(below);
            for (const bad of [0, -1]) {
                result = await fetchWith(group, key, bad);
                expect(result.outcome, `${key} ${bad}`).toBe('INVALID_REQUEST');
            }
            configure({ [setting]: String(below) });
            result = await fetchWith(group, key, ceiling);
            expect(result.limits[key], `${key} lowered setting`).toBe(below);
            if (flag) expect(flagValue(flag), `${key} lowered setting`).toBe(below);
        }
        // a read: its deadline follows the read ceiling
        configure({ REBOM_TEA_READ_DEADLINE_SECONDS: '7' });
        stub.behave({ stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT"}\n' });
        const read = field(await gql(http.url, Q.product, { input: { apiRoot: API_ROOT, uuid: UUID_A, credential: NONE, limits: { deadlineSeconds: 600 } } }));
        expect(read.limits.deadlineSeconds).toBe(7);
        expect(stub.argv()).toContain('--deadline=7s');
    });

    it('T-14 every vector holds every limit flag of its verb with a value above zero and neither -d nor --credential-stdin', () => {
        const all = requests();
        for (const verb of Object.keys(all)) expectEveryLimitFlag(verb, argvOf(all[verb]));
        for (const classes of CLASS_SETS) {
            const request = valid(validateFetch({ apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: classes, mediaTypes: ['application/json', 'text/xml'], credential: NONE }, OFF));
            expectEveryLimitFlag('download', argvOf(request));
        }
    });

    it('T-15 the seven sets of document classes give the flags of the table; media types; empty and comma refused', () => {
        const expected: Array<[string[], string[]]> = [
            [['SBOM', 'VEX', 'OTHER'], []],
            [['SBOM', 'OTHER'], ['--exclude-artifact-type=VULNERABILITIES']],
            [['VEX', 'OTHER'], ['--exclude-artifact-type=BOM']],
            [['OTHER'], ['--exclude-artifact-type=BOM', '--exclude-artifact-type=VULNERABILITIES']],
            [['SBOM', 'VEX'], ['--artifact-type=BOM', '--artifact-type=VULNERABILITIES']],
            [['SBOM'], ['--artifact-type=BOM']],
            [['VEX'], ['--artifact-type=VULNERABILITIES']]
        ];
        for (const [classes, flags] of expected) {
            const request = valid(validateFetch({ apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: classes, credential: NONE }, OFF));
            expect(selectionFlags(request.documentClasses!), classes.join(',')).toEqual(flags);
            const argv = argvOf(request);
            expect(argv.filter((a) => a.includes('artifact-type')), classes.join(',')).toEqual(flags);
        }
        const withMedia = valid(validateFetch({ apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: ['SBOM'], mediaTypes: ['application/vnd.cyclonedx+json', 'application/spdx+json'], credential: NONE }, OFF));
        expect(argvOf(withMedia).filter((a) => a.startsWith('--media-type='))).toEqual(['--media-type=application/vnd.cyclonedx+json', '--media-type=application/spdx+json']);
        expect(validateFetch({ apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: [], credential: NONE }, OFF).ok).toBe(false);
        expect(validateFetch({ apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, collectionVersion: 1, documentClasses: ['SBOM'], mediaTypes: ['application/json,text/xml'], credential: NONE }, OFF).ok).toBe(false);
    });

    it('T-16 a setting that is not a number, below or above its range is NOT_CONFIGURED SETTING naming the variable, never the value; absent gives the default', async () => {
        stub = new Stub();
        const root = tempDir('rebom-tea-settings-');
        for (const spec of TEA_SETTINGS) {
            // a negative number (min 0 minus one) is not digits: refused the same way
            for (const bad of ['twelve', String(spec.min - 1), String(spec.max + 1)]) {
                configureTea({ REBOM_TEA_CALLER_TOKEN: 'c'.repeat(40), REBOM_TEA_TMP_ROOT: root, [spec.variable]: bad });
                const answer = await gql(http.url, Q.status, {}, 'c'.repeat(40));
                const result = field(answer);
                expect(result.outcome, `${spec.variable}=${bad}`).toBe('NOT_CONFIGURED');
                expect(result.error.reason, `${spec.variable}=${bad}`).toBe('SETTING');
                expect(result.error.detail, `${spec.variable}=${bad}`).toBe(spec.variable);
                expect(answer.text.includes(`"${bad}"`) || answer.text.includes(`=${bad}`), `${spec.variable}=${bad}`).toBe(false);
            }
        }
        configureTea({ REBOM_TEA_CALLER_TOKEN: 'c'.repeat(40), REBOM_TEA_TMP_ROOT: root });
        const read = readTeaConfig({});
        expect(read.ok).toBe(true);
        if (read.ok) {
            for (const spec of TEA_SETTINGS) expect(read.config.ceilings[spec.key], spec.variable).toBe(spec.defaultValue);
        }
        const status = field(await gql(http.url, Q.status, {}, 'c'.repeat(40)));
        expect(status.outcome).toBe('OK');
        expect(status.ceilings.maxFiles).toBe(200);
    });
});

const CLASS_SETS = [['SBOM', 'VEX', 'OTHER'], ['SBOM', 'OTHER'], ['VEX', 'OTHER'], ['OTHER'], ['SBOM', 'VEX'], ['SBOM'], ['VEX']];
