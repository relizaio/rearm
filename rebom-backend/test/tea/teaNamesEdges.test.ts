import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../src/services/tea/teaService', async (importOriginal) => {
    const actual = await importOriginal<typeof import('../../src/services/tea/teaService')>();
    const wrapped: Record<string, unknown> = { ...actual };
    for (const name of ['teaStatus', 'teaFetchedFileChunk', 'teaResolve', 'teaProduct', 'teaComponent', 'teaReleases', 'teaRelease',
        'teaFetchCollection', 'teaStoreFetchedSbom', 'teaReleaseFetch']) {
        wrapped[name] = vi.fn((actual as any)[name]);
    }
    return wrapped;
});

import { logger } from '../../src/logger';
import { makeCallDir, setTeaWorkDir } from '../../src/services/tea/teaCliRunner';
import { buildTeaArgv } from '../../src/services/tea/teaArgv';
import { effectiveLimits, readTeaConfig, TeaLocalTesting, TeaRunKind } from '../../src/services/tea/teaConfig';
import { validateFetch, validateRelease, validateReleases } from '../../src/services/tea/teaRequest';
import * as TeaService from '../../src/services/tea/teaService';
import { cleanupDirs, configure, field, gql, Http, NONE, startHttp, Stub, tenOperations, UUID_A, UUID_B } from './teaTestKit';

/**
 * The names the operations carry: the operation named in the log line of an unexpected failure, in
 * the log line of each read, the field a refusal names for each verb, and the flag of a release
 * list under a component.
 */

const OFF = TeaLocalTesting.OFF;

describe('an operation that fails unexpectedly', () => {
    let http: Http;
    let stub: Stub;

    beforeAll(async () => {
        http = await startHttp();
    });

    afterAll(async () => {
        await http.server.stop();
    });

    beforeEach(() => {
        stub = new Stub();
        configure();
    });

    afterEach(() => {
        TeaService.shutdownTea();
        stub.restore();
        vi.restoreAllMocks();
        cleanupDirs();
    });

    it.each(tenOperations().map((operation) => [operation[0], operation] as const))('%s is named in the one log line and answers FAILED with INTERNAL', async (name, operation) => {
        const errors = vi.spyOn(logger, 'error').mockImplementation(() => undefined as never);
        const boom = new Error('boom');
        vi.mocked((TeaService as any)[name]).mockRejectedValueOnce(boom);
        const answer = field(await gql(http.url, operation[1], operation[2]));
        expect(answer).toMatchObject({ outcome: 'FAILED', error: { reason: 'INTERNAL' } });
        const lines = errors.mock.calls.filter((call) => call[1] === 'TEA operation failed unexpectedly');
        expect(lines).toHaveLength(1);
        expect(lines[0][0]).toMatchObject({ err: boom, operationName: name, ref: expect.stringMatching(/^[0-9a-f]{8}$/) });
        expect(answer.error.message).toContain((lines[0][0] as any).ref);
    });
});

describe('the field a refusal names', () => {
    it('is input.apiRoot for a root that is not a URL, in a release list, a release and a download', () => {
        const bad = 'not a url';
        expect(validateReleases({ apiRoot: bad, parentKind: 'PRODUCT', parentUuid: UUID_A, credential: NONE }, OFF)).toMatchObject({ ok: false, field: 'input.apiRoot' });
        expect(validateRelease({ apiRoot: bad, releaseKind: 'PRODUCT_RELEASE', releaseUuid: UUID_A, credential: NONE }, OFF)).toMatchObject({ ok: false, field: 'input.apiRoot' });
        expect(validateFetch({ apiRoot: bad, releaseKind: 'PRODUCT_RELEASE', releaseUuid: UUID_A, collectionVersion: 1, documentClasses: ['SBOM'], credential: NONE }, OFF))
            .toMatchObject({ ok: false, field: 'input.apiRoot' });
    });

    it('is input.releaseKind and input.releaseUuid for a download of a release that is wrongly named', () => {
        const base = { apiRoot: 'https://tea.example.com/tea', collectionVersion: 1, documentClasses: ['SBOM'], credential: NONE };
        expect(validateFetch({ ...base, releaseKind: 'RELEASE', releaseUuid: UUID_A }, OFF)).toMatchObject({ ok: false, field: 'input.releaseKind' });
        expect(validateFetch({ ...base, releaseKind: 'PRODUCT_RELEASE', releaseUuid: 'x' }, OFF)).toMatchObject({ ok: false, field: 'input.releaseUuid' });
    });
});

describe('the arguments of a release list', () => {
    it('name the component under a component, the product under a product', () => {
        const read = readTeaConfig({});
        if (!read.ok) throw new Error('defaults must be valid');
        for (const [kind, flag] of [['COMPONENT', 'component'], ['PRODUCT', 'product']]) {
            const validation = validateReleases({ apiRoot: 'https://tea.example.com/tea', parentKind: kind, parentUuid: UUID_B, credential: NONE }, OFF);
            if (!validation.ok) throw new Error('refused');
            const request = validation.value;
            const limits = effectiveLimits(TeaRunKind.RELEASE_LIST, read.config.ceilings, request.limits, request.fetchLimits, { pageSize: request.pageSize, maxPages: request.maxPages });
            const argv = buildTeaArgv(request, limits, OFF, '/scratch/dl');
            expect(argv).toContain(`--${flag}=${UUID_B}`);
            expect(argv.filter((a) => a.startsWith('--product=') || a.startsWith('--component='))).toHaveLength(1);
        }
    });
});

describe('a read', () => {
    let stub: Stub;

    beforeEach(() => {
        stub = new Stub();
    });

    afterEach(() => {
        TeaService.shutdownTea();
        stub.restore();
        vi.restoreAllMocks();
        cleanupDirs();
    });

    it.each([
        ['teaResolve', { tei: `tei://tea.example.com/uuid/${UUID_A}`, credential: NONE }],
        ['teaProduct', { apiRoot: 'https://tea.example.com/tea', uuid: UUID_A, credential: NONE }],
        ['teaComponent', { apiRoot: 'https://tea.example.com/tea', uuid: UUID_A, credential: NONE }],
        ['teaReleases', { apiRoot: 'https://tea.example.com/tea', parentKind: 'PRODUCT', parentUuid: UUID_A, credential: NONE }],
        ['teaRelease', { apiRoot: 'https://tea.example.com/tea', releaseKind: 'PRODUCT_RELEASE', releaseUuid: UUID_A, credential: NONE }]
    ])('%s logs its own name', async (name, input) => {
        stub.behave({ stdout: '{"formatVersion":1,"kind":"TEA_PRODUCT"}\n' });
        configure();
        const info = vi.spyOn(logger, 'info').mockImplementation(() => undefined as never);
        await (TeaService as any)[name](input);
        const lines = info.mock.calls.filter((call) => call[1] === 'TEA operation').map((call) => call[0] as Record<string, any>);
        expect(lines).toHaveLength(1);
        expect(lines[0].operation).toBe(name);
    });
});

describe('the per-call directory', () => {
    it('is refused with a message when there is no scratch directory', async () => {
        setTeaWorkDir(null);
        await expect(makeCallDir()).rejects.toThrow(/^TEA scratch directory is not configured$/);
    });
});
