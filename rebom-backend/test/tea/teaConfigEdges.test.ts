import * as os from 'os';
import * as path from 'path';
import { describe, expect, it } from 'vitest';
import {
    ceilingsAsLimits, effectiveLimits, localTestingOf, readTeaConfig, TeaCeilings, TeaLocalTesting, TeaRunKind, validCallerToken
} from '../../src/services/tea/teaConfig';

/**
 * The settings, at both ends of every range, against the table of the README ("TEA operations"),
 * the caller token and local testing values, and the per-call limits clamped by the ceilings.
 */

// variable, ceiling key, default, minimum, maximum: as the README lists them
const TABLE: Array<[string, keyof TeaCeilings, number, number, number]> = [
    ['REBOM_TEA_READ_DEADLINE_SECONDS', 'readDeadlineSeconds', 60, 1, 600],
    ['REBOM_TEA_DOWNLOAD_DEADLINE_SECONDS', 'downloadDeadlineSeconds', 600, 1, 3600],
    ['REBOM_TEA_CONNECT_TIMEOUT_SECONDS', 'connectTimeoutSeconds', 5, 1, 60],
    ['REBOM_TEA_MAX_DOCUMENT_BYTES', 'maxDocumentBytes', 16777216, 1024, 268435456],
    ['REBOM_TEA_MAX_OUTPUT_BYTES', 'maxOutputBytes', 8388608, 65536, 25165824],
    ['REBOM_TEA_MAX_PAGES', 'maxPages', 10, 1, 1000],
    ['REBOM_TEA_MAX_ARTIFACT_BYTES', 'maxArtifactBytes', 52428800, 1, 2147483647],
    ['REBOM_TEA_MAX_TOTAL_BYTES', 'maxTotalBytes', 268435456, 1, 2147483647],
    ['REBOM_TEA_MAX_FILES', 'maxFiles', 200, 1, 100000],
    ['REBOM_TEA_MAX_DISK_BYTES', 'maxDiskBytes', 1073741824, 1048576, 68719476736],
    ['REBOM_TEA_HOLD_SECONDS', 'holdSeconds', 600, 10, 3600],
    ['REBOM_TEA_MAX_CHUNK_BYTES', 'maxChunkBytes', 8388608, 65536, 33554432],
    ['REBOM_TEA_MAX_CONCURRENT_READS', 'maxConcurrentReads', 4, 1, 64],
    ['REBOM_TEA_MAX_CONCURRENT_DOWNLOADS', 'maxConcurrentDownloads', 2, 1, 16],
    ['REBOM_TEA_MAX_CONCURRENT_RESULTS', 'maxConcurrentResults', 2, 1, 16],
    ['REBOM_TEA_MAX_QUEUED', 'maxQueued', 16, 0, 1024],
    ['REBOM_TEA_QUEUE_WAIT_SECONDS', 'queueWaitSeconds', 20, 1, 300]
];

function ceilingsOf(env: NodeJS.ProcessEnv): TeaCeilings {
    const read = readTeaConfig(env);
    if (!read.ok) throw new Error(`refused: ${read.variable}`);
    return read.config.ceilings;
}

function refusedVariable(env: NodeJS.ProcessEnv): string {
    const read = readTeaConfig(env);
    if (read.ok) throw new Error('accepted');
    return read.variable;
}

describe('the settings of the README table', () => {
    it('every default, and the 17 ceilings are exactly the table', () => {
        const ceilings = ceilingsOf({});
        expect(Object.keys(ceilings).sort()).toEqual(TABLE.map((row) => row[1]).sort());
        for (const [, key, def] of TABLE) expect(ceilings[key], key).toBe(def);
    });

    it('every setting accepts its minimum and its maximum and refuses one below and one above, naming itself', () => {
        for (const [variable, key, , min, max] of TABLE) {
            expect(ceilingsOf({ [variable]: String(min) })[key], `${variable} min`).toBe(min);
            expect(ceilingsOf({ [variable]: String(max) })[key], `${variable} max`).toBe(max);
            if (min > 0) expect(refusedVariable({ [variable]: String(min - 1) }), `${variable} below`).toBe(variable);
            expect(refusedVariable({ [variable]: String(max + 1) }), `${variable} above`).toBe(variable);
            // a set value changes only its own ceiling
            const others = ceilingsOf({ [variable]: String(max) });
            for (const [, otherKey, otherDefault] of TABLE) if (otherKey !== key) expect(others[otherKey]).toBe(otherDefault);
        }
    });

    it('a value that is not made of digits is refused, an empty one too, and never read as a default', () => {
        for (const raw of ['', ' ', '+5', '-1', '5.0', ' 5', '5 ', '0x10', '1e3', 'five', '\uff15', '99999999999999999999']) {
            expect(refusedVariable({ REBOM_TEA_MAX_FILES: raw }), JSON.stringify(raw)).toBe('REBOM_TEA_MAX_FILES');
        }
    });

    it('the first invalid setting in the order of the table is the one named, a bad scratch root comes last', () => {
        expect(refusedVariable({ REBOM_TEA_MAX_FILES: '0', REBOM_TEA_READ_DEADLINE_SECONDS: '0', REBOM_TEA_MAX_PAGES: '0' })).toBe('REBOM_TEA_READ_DEADLINE_SECONDS');
        expect(refusedVariable({ REBOM_TEA_MAX_FILES: '0', REBOM_TEA_TMP_ROOT: 'relative/root' })).toBe('REBOM_TEA_MAX_FILES');
        expect(refusedVariable({ REBOM_TEA_TMP_ROOT: 'relative/root' })).toBe('REBOM_TEA_TMP_ROOT');
        expect(refusedVariable({ REBOM_TEA_TMP_ROOT: '' })).toBe('REBOM_TEA_TMP_ROOT');
    });

    it('an invalid read still carries the token and the local testing value, so the gate can answer', () => {
        const token = 'x'.repeat(40);
        const read = readTeaConfig({ REBOM_TEA_CALLER_TOKEN: token, REBOM_TEA_LOCAL_TESTING: 'ON', REBOM_TEA_MAX_FILES: '0' });
        expect(read).toEqual({ ok: false, callerToken: token, localTesting: 'ON', variable: 'REBOM_TEA_MAX_FILES' });
        const bad = readTeaConfig({ REBOM_TEA_CALLER_TOKEN: 'short', REBOM_TEA_TMP_ROOT: 'relative' });
        expect(bad).toEqual({ ok: false, callerToken: null, localTesting: 'OFF', variable: 'REBOM_TEA_TMP_ROOT' });
    });

    it('the scratch root: the default under the system temporary directory, an absolute value as resolved', () => {
        const read = readTeaConfig({});
        expect(read.ok && read.config.tmpRoot).toBe(path.join(os.tmpdir(), 'rebom-tea'));
        const given = readTeaConfig({ REBOM_TEA_TMP_ROOT: '/srv/tea/../scratch//root/' });
        expect(given.ok && given.config.tmpRoot).toBe('/srv/scratch/root');
    });
});

describe('the caller token and local testing', () => {
    it('a token is 32 to 256 characters of printable ASCII without a space', () => {
        const of = (n: number, ch = 'a') => ch.repeat(n);
        expect(validCallerToken(of(31))).toBeNull();
        expect(validCallerToken(of(32))).toBe(of(32));
        expect(validCallerToken(of(256))).toBe(of(256));
        expect(validCallerToken(of(257))).toBeNull();
        expect(validCallerToken(of(40, '!'))).toBe(of(40, '!'));
        expect(validCallerToken(of(40, '~'))).toBe(of(40, '~'));
        expect(validCallerToken(of(40, ' '))).toBeNull();
        expect(validCallerToken(`${of(20)} ${of(20)}`)).toBeNull();
        expect(validCallerToken(of(40, '\x7f'))).toBeNull();
        expect(validCallerToken(`${of(40)}\n`)).toBeNull();
        expect(validCallerToken(`${of(40)}\u00e9`)).toBeNull();
        expect(validCallerToken('')).toBeNull();
        expect(validCallerToken(undefined)).toBeNull();
    });

    it('local testing is ON for the exact string ON and OFF for everything else', () => {
        expect(localTestingOf('ON')).toBe(TeaLocalTesting.ON);
        for (const value of [undefined, '', 'on', 'On', 'ON ', ' ON', 'TRUE', '1', 'OFF', 'ONN']) {
            expect(localTestingOf(value), JSON.stringify(value)).toBe(TeaLocalTesting.OFF);
        }
    });
});

// ceilings with a different value in every field, so that a limit read from another field shows
const DISTINCT: TeaCeilings = {
    readDeadlineSeconds: 101, downloadDeadlineSeconds: 102, connectTimeoutSeconds: 103, maxDocumentBytes: 104, maxOutputBytes: 105,
    maxPages: 106, maxArtifactBytes: 107, maxTotalBytes: 108, maxFiles: 109, maxDiskBytes: 110, holdSeconds: 111, maxChunkBytes: 112,
    maxConcurrentReads: 113, maxConcurrentDownloads: 114, maxConcurrentResults: 115, maxQueued: 116, queueWaitSeconds: 117
};

describe('the limits of one run: the smaller of the caller\'s and the ceiling', () => {
    it('a read takes the read deadline ceiling and leaves the paging and the fetch limits unset', () => {
        expect(effectiveLimits(TeaRunKind.READ, DISTINCT)).toEqual({
            deadlineSeconds: 101, connectTimeoutSeconds: 103, maxDocumentBytes: 104, maxOutputBytes: 105,
            maxPages: null, pageSize: null, maxArtifactBytes: null, maxTotalBytes: null, maxFiles: null, holdSeconds: null
        });
    });

    it('a release list adds the page size (100 unless given) and the page count below its ceiling', () => {
        expect(effectiveLimits(TeaRunKind.RELEASE_LIST, DISTINCT)).toMatchObject({ deadlineSeconds: 101, pageSize: 100, maxPages: 106, maxFiles: null });
        expect(effectiveLimits(TeaRunKind.RELEASE_LIST, DISTINCT, null, null, { pageSize: 7, maxPages: 3 })).toMatchObject({ pageSize: 7, maxPages: 3 });
        expect(effectiveLimits(TeaRunKind.RELEASE_LIST, DISTINCT, null, null, { pageSize: null, maxPages: 106 })).toMatchObject({ pageSize: 100, maxPages: 106 });
        expect(effectiveLimits(TeaRunKind.RELEASE_LIST, DISTINCT, null, null, { maxPages: 107 })).toMatchObject({ maxPages: 106 });
        expect(effectiveLimits(TeaRunKind.RELEASE_LIST, DISTINCT, null, { maxFiles: 1 }, { pageSize: 5 })).toMatchObject({ maxFiles: null });
    });

    it('a download takes the download deadline and the four fetch ceilings, and no paging', () => {
        expect(effectiveLimits(TeaRunKind.DOWNLOAD, DISTINCT)).toEqual({
            deadlineSeconds: 102, connectTimeoutSeconds: 103, maxDocumentBytes: 104, maxOutputBytes: 105,
            maxPages: null, pageSize: null, maxArtifactBytes: 107, maxTotalBytes: 108, maxFiles: 109, holdSeconds: 111
        });
        expect(effectiveLimits(TeaRunKind.DOWNLOAD, DISTINCT, null, null, { pageSize: 5, maxPages: 2 })).toMatchObject({ pageSize: null, maxPages: null });
    });

    it('a caller lowers every limit and raises none: below passes, equal passes, above is the ceiling', () => {
        const below = effectiveLimits(TeaRunKind.DOWNLOAD, DISTINCT,
            { deadlineSeconds: 11, connectTimeoutSeconds: 12, maxDocumentBytes: 13, maxOutputBytes: 14 },
            { maxArtifactBytes: 15, maxTotalBytes: 16, maxFiles: 17, holdSeconds: 18 });
        expect(below).toMatchObject({ deadlineSeconds: 11, connectTimeoutSeconds: 12, maxDocumentBytes: 13, maxOutputBytes: 14, maxArtifactBytes: 15, maxTotalBytes: 16, maxFiles: 17, holdSeconds: 18 });
        const equal = effectiveLimits(TeaRunKind.DOWNLOAD, DISTINCT,
            { deadlineSeconds: 102, connectTimeoutSeconds: 103, maxDocumentBytes: 104, maxOutputBytes: 105 },
            { maxArtifactBytes: 107, maxTotalBytes: 108, maxFiles: 109, holdSeconds: 111 });
        expect(equal).toEqual(effectiveLimits(TeaRunKind.DOWNLOAD, DISTINCT));
        const above = effectiveLimits(TeaRunKind.DOWNLOAD, DISTINCT,
            { deadlineSeconds: 1000, connectTimeoutSeconds: 1000, maxDocumentBytes: 1000, maxOutputBytes: 1000 },
            { maxArtifactBytes: 1000, maxTotalBytes: 1000, maxFiles: 1000, holdSeconds: 1000 });
        expect(above).toEqual(effectiveLimits(TeaRunKind.DOWNLOAD, DISTINCT));
        const read = effectiveLimits(TeaRunKind.READ, DISTINCT, { deadlineSeconds: 102 });
        expect(read.deadlineSeconds).toBe(101);
    });

    it('teaStatus reports the ceilings, the deadline being the download one and the page size 100', () => {
        expect(ceilingsAsLimits(DISTINCT)).toEqual({
            deadlineSeconds: 102, connectTimeoutSeconds: 103, maxDocumentBytes: 104, maxOutputBytes: 105, maxPages: 106, pageSize: 100,
            maxArtifactBytes: 107, maxTotalBytes: 108, maxFiles: 109, holdSeconds: 111
        });
    });
});
