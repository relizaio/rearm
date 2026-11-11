import { describe, expect, it } from 'vitest';
import { TeaLocalTesting } from '../../src/services/tea/teaConfig';
import {
    describeRefusal, isPlainHttp, TeaVerb, validateChunk, validateFetch, validateFetchId, validateObject, validateRelease, validateReleases,
    validateResolve, validateStore
} from '../../src/services/tea/teaRequest';
import { API_ROOT, NONE, UUID_A, UUID_B } from './teaTestKit';

/**
 * Every rule of the input validation at its edges (design 3.5): the lengths and ranges one on each
 * side, the shapes that are refused, and the field and rule a refusal names, never the value.
 */

const OFF = TeaLocalTesting.OFF;
const ON = TeaLocalTesting.ON;
const INT_MAX = 2147483647;

type Refused = { ok: false, field: string, rule: string };

function refusal(result: { ok: boolean }): Refused {
    if (result.ok) throw new Error('accepted');
    return result as unknown as Refused;
}

function objectInput(extra: Record<string, unknown> = {}): any {
    return { apiRoot: API_ROOT, uuid: UUID_A, credential: NONE, ...extra };
}

function fetchInput(extra: Record<string, unknown> = {}): any {
    return {
        apiRoot: API_ROOT, releaseKind: 'PRODUCT_RELEASE', releaseUuid: UUID_A, collectionVersion: 1, documentClasses: ['SBOM'], credential: NONE, ...extra
    };
}

const URL_RULE = (max: number) => `1 to ${max} bytes of printable ASCII, an https URL (http only under local testing) without user name or password`;

/** An https URL of exactly n bytes. */
function urlOf(n: number): string {
    const base = 'https://tea.example.com/';
    return base + 'p'.repeat(n - base.length);
}

describe('urls', () => {
    it('an https URL of 2048 bytes is accepted, of 2049 refused with the rule that names the bound', () => {
        expect(validateObject(objectInput({ apiRoot: urlOf(2048) }), TeaVerb.PRODUCT, OFF).ok).toBe(true);
        expect(refusal(validateObject(objectInput({ apiRoot: urlOf(2049) }), TeaVerb.PRODUCT, OFF))).toEqual({ ok: false, field: 'input.apiRoot', rule: URL_RULE(2048) });
        expect(validateObject(objectInput({ apiRoot: urlOf(25) }), TeaVerb.PRODUCT, OFF).ok).toBe(true);
    });

    it('an empty URL, a space, a non-string, a bare scheme and another scheme are refused', () => {
        for (const apiRoot of ['', 'https://tea.example.com/a b', 5, null, undefined, 'https://', 'tea.example.com/tea', 'ftp://tea.example.com/tea', 'file:///etc/passwd']) {
            expect(refusal(validateObject(objectInput({ apiRoot }), TeaVerb.PRODUCT, OFF)).field, String(apiRoot)).toBe('input.apiRoot');
        }
    });

    it('a user name or a password in the URL is refused, either alone or both', () => {
        for (const apiRoot of ['https://user@tea.example.com/tea', 'https://:secret@tea.example.com/tea', 'https://user:secret@tea.example.com/tea']) {
            const refused = refusal(validateObject(objectInput({ apiRoot }), TeaVerb.PRODUCT, OFF));
            expect(refused.field).toBe('input.apiRoot');
            expect(JSON.stringify(refused)).not.toContain('secret');
        }
    });

    it('plain http only under local testing', () => {
        const apiRoot = 'http://127.0.0.1:8080/tea';
        expect(refusal(validateObject(objectInput({ apiRoot }), TeaVerb.PRODUCT, OFF)).field).toBe('input.apiRoot');
        expect(validateObject(objectInput({ apiRoot }), TeaVerb.PRODUCT, ON).ok).toBe(true);
        expect(isPlainHttp(apiRoot)).toBe(true);
        expect(isPlainHttp('https://tea.example.com/tea')).toBe(false);
    });

    it('the URL is passed as given, not re-serialised', () => {
        const apiRoot = 'https://TEA.example.com:443/tea/./x';
        const result = validateObject(objectInput({ apiRoot }), TeaVerb.PRODUCT, OFF);
        expect(result.ok && result.value.apiRoot).toBe(apiRoot);
    });
});

describe('uuids and the verbs\' shapes', () => {
    it('a uuid is 8-4-4-4-12 hexadecimal characters and is passed in lower case', () => {
        const upper = UUID_A.toUpperCase();
        const ok = validateObject(objectInput({ uuid: upper }), TeaVerb.COMPONENT, OFF);
        expect(ok.ok && ok.value.uuid).toBe(UUID_A);
        expect(ok.ok && ok.value.verb).toBe('component');
        for (const uuid of ['', UUID_A.slice(1), UUID_A + '0', UUID_A.replace(/-/g, ''), 'g' + UUID_A.slice(1), 5, null, `${UUID_A}\n`]) {
            expect(refusal(validateObject(objectInput({ uuid }), TeaVerb.PRODUCT, OFF)), String(uuid)).toEqual({ ok: false, field: 'input.uuid', rule: 'a uuid (8-4-4-4-12 hexadecimal)' });
        }
    });

    it('a release list names its parent kind and uuid; the kind is one of PRODUCT and COMPONENT', () => {
        const ok = validateReleases({ apiRoot: API_ROOT, parentKind: 'COMPONENT', parentUuid: UUID_B.toUpperCase(), credential: NONE }, OFF);
        expect(ok.ok && ok.value).toMatchObject({ verb: 'releases', parentKind: 'COMPONENT', parentUuid: UUID_B, pageToken: null, pageSize: null, maxPages: null });
        for (const parentKind of ['product', 'RELEASE', '', null, undefined, 7]) {
            expect(refusal(validateReleases({ apiRoot: API_ROOT, parentKind, parentUuid: UUID_A, credential: NONE }, OFF)), String(parentKind))
                .toEqual({ ok: false, field: 'input.parentKind', rule: 'one of PRODUCT, COMPONENT' });
        }
        expect(refusal(validateReleases({ apiRoot: API_ROOT, parentKind: 'PRODUCT', parentUuid: 'x', credential: NONE }, OFF)).field).toBe('input.parentUuid');
    });

    it('a release names its kind and uuid; the kind is one of PRODUCT_RELEASE and COMPONENT_RELEASE', () => {
        const ok = validateRelease({ apiRoot: API_ROOT, releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B, credential: NONE }, OFF);
        expect(ok.ok && ok.value).toMatchObject({ verb: 'release', releaseKind: 'COMPONENT_RELEASE', releaseUuid: UUID_B });
        expect(refusal(validateRelease({ apiRoot: API_ROOT, releaseKind: 'RELEASE', releaseUuid: UUID_B, credential: NONE }, OFF)))
            .toEqual({ ok: false, field: 'input.releaseKind', rule: 'one of PRODUCT_RELEASE, COMPONENT_RELEASE' });
        expect(refusal(validateRelease({ apiRoot: API_ROOT, releaseKind: 'PRODUCT_RELEASE', releaseUuid: '', credential: NONE }, OFF)).field).toBe('input.releaseUuid');
    });

    it('a refusal reads field: rule, and an absent or non-object input is "input: required"', () => {
        expect(describeRefusal('input.x', 'a rule')).toBe('input.x: a rule');
        for (const input of [null, undefined, 'x', 5]) {
            expect(refusal(validateObject(input, TeaVerb.PRODUCT, OFF)), String(input)).toEqual({ ok: false, field: 'input', rule: 'required' });
            expect(refusal(validateReleases(input, OFF)).field).toBe('input');
            expect(refusal(validateRelease(input, OFF)).field).toBe('input');
            expect(refusal(validateFetch(input, OFF)).field).toBe('input');
            expect(refusal(validateResolve(input, OFF)).field).toBe('input');
            expect(refusal(validateStore(input)), String(input)).toEqual({ ok: false, field: 'input', rule: 'required' });
        }
    });
});

describe('credentials', () => {
    const bearer = { kind: 'BEARER_TOKEN', authRoot: API_ROOT, token: 'tok' };
    const apiKey = { kind: 'API_KEY', authRoot: API_ROOT, keyId: 'id', keySecret: 'secret' };

    it('NONE carries nothing; each of the four fields is refused naming itself and the kind', () => {
        const ok = validateObject(objectInput({ credential: { kind: 'NONE' } }), TeaVerb.PRODUCT, OFF);
        expect(ok.ok && ok.value.credential).toEqual({ kind: 'NONE', authRoot: null, token: null, keyId: null, keySecret: null });
        for (const name of ['authRoot', 'token', 'keyId', 'keySecret']) {
            expect(refusal(validateObject(objectInput({ credential: { kind: 'NONE', [name]: 'x' } }), TeaVerb.PRODUCT, OFF)), name)
                .toEqual({ ok: false, field: `input.credential.${name}`, rule: 'not allowed with kind NONE' });
        }
    });

    it('a bearer token needs its root and token and allows no key; an API key needs both parts and allows no token', () => {
        const b = validateObject(objectInput({ credential: bearer }), TeaVerb.PRODUCT, OFF);
        expect(b.ok && b.value.credential).toEqual({ kind: 'BEARER_TOKEN', authRoot: API_ROOT, token: 'tok', keyId: null, keySecret: null });
        const k = validateObject(objectInput({ credential: apiKey }), TeaVerb.PRODUCT, OFF);
        expect(k.ok && k.value.credential).toEqual({ kind: 'API_KEY', authRoot: API_ROOT, token: null, keyId: 'id', keySecret: 'secret' });
        expect(refusal(validateObject(objectInput({ credential: { ...bearer, keyId: 'x' } }), TeaVerb.PRODUCT, OFF)))
            .toEqual({ ok: false, field: 'input.credential.keyId', rule: 'not allowed with kind BEARER_TOKEN' });
        expect(refusal(validateObject(objectInput({ credential: { ...bearer, keySecret: 'x' } }), TeaVerb.PRODUCT, OFF)).field).toBe('input.credential.keySecret');
        expect(refusal(validateObject(objectInput({ credential: { ...apiKey, token: 'x' } }), TeaVerb.PRODUCT, OFF)))
            .toEqual({ ok: false, field: 'input.credential.token', rule: 'not allowed with kind API_KEY' });
        for (const [part, name] of [[{ ...bearer, authRoot: undefined }, 'authRoot'], [{ ...bearer, token: undefined }, 'token'],
            [{ ...apiKey, keyId: undefined }, 'keyId'], [{ ...apiKey, keySecret: undefined }, 'keySecret']] as const) {
            expect(refusal(validateObject(objectInput({ credential: part }), TeaVerb.PRODUCT, OFF)).field, name).toBe(`input.credential.${name}`);
        }
    });

    it('the credential and its kind are required; an unknown kind names the three', () => {
        for (const credential of [undefined, null, 'NONE', 5]) {
            expect(refusal(validateObject(objectInput({ credential }), TeaVerb.PRODUCT, OFF)), String(credential)).toEqual({ ok: false, field: 'input.credential', rule: 'required' });
        }
        for (const kind of ['none', 'TOKEN', '', undefined, 3]) {
            expect(refusal(validateObject(objectInput({ credential: { kind } }), TeaVerb.PRODUCT, OFF)), String(kind))
                .toEqual({ ok: false, field: 'input.credential.kind', rule: 'one of NONE, BEARER_TOKEN, API_KEY' });
        }
    });

    it('a secret is 1 to 8192 bytes of printable ASCII, a space included; the root follows the URL rules', () => {
        const secretRule = (max: number) => `1 to ${max} bytes of printable ASCII`;
        const withToken = (token: unknown) => validateObject(objectInput({ credential: { ...bearer, token } }), TeaVerb.PRODUCT, OFF);
        expect(withToken('a'.repeat(8192)).ok).toBe(true);
        expect(refusal(withToken('a'.repeat(8193)))).toEqual({ ok: false, field: 'input.credential.token', rule: secretRule(8192) });
        expect(withToken('a b c').ok).toBe(true);
        expect(withToken('~').ok).toBe(true);
        for (const token of ['', '\x1f', '\x7f', 'a\nb', 5, null]) expect(refusal(withToken(token)).field, JSON.stringify(token)).toBe('input.credential.token');
        const withKey = (keyId: unknown, keySecret: unknown) => validateObject(objectInput({ credential: { kind: 'API_KEY', authRoot: API_ROOT, keyId, keySecret } }), TeaVerb.PRODUCT, OFF);
        expect(withKey('k'.repeat(8192), 's'.repeat(8192)).ok).toBe(true);
        expect(refusal(withKey('k'.repeat(8193), 's')).field).toBe('input.credential.keyId');
        expect(refusal(withKey('k', 's'.repeat(8193))).field).toBe('input.credential.keySecret');
        expect(refusal(validateObject(objectInput({ credential: { ...bearer, authRoot: 'http://x.example.com/tea' } }), TeaVerb.PRODUCT, OFF)).field).toBe('input.credential.authRoot');
        expect(validateObject(objectInput({ credential: { ...bearer, authRoot: 'http://x.example.com/tea' } }), TeaVerb.PRODUCT, ON).ok).toBe(true);
    });
});

describe('limits and the correlation id', () => {
    it('each per-call limit has its minimum, one below is refused, 2147483647 is the top', () => {
        const minimums: Array<[string, number]> = [['deadlineSeconds', 1], ['connectTimeoutSeconds', 1], ['maxDocumentBytes', 1024], ['maxOutputBytes', 65536]];
        for (const [key, min] of minimums) {
            const field = `input.limits.${key}`;
            const limits = (value: unknown) => validateObject(objectInput({ limits: { [key]: value } }), TeaVerb.PRODUCT, OFF);
            const ok = limits(min);
            expect(ok.ok && ok.value.limits).toMatchObject({ [key]: min });
            expect(refusal(limits(min - 1)), key).toEqual({ ok: false, field, rule: `an integer from ${min} to ${INT_MAX}` });
            expect(limits(INT_MAX).ok).toBe(true);
            expect(refusal(limits(INT_MAX + 1)).field).toBe(field);
            for (const bad of [1.5, '5', NaN, Infinity, true]) expect(refusal(limits(bad)).field, `${key} ${String(bad)}`).toBe(field);
            expect(limits(null).ok).toBe(true);
        }
        const none = validateObject(objectInput(), TeaVerb.PRODUCT, OFF);
        expect(none.ok && none.value.limits).toBeNull();
        const empty = validateObject(objectInput({ limits: {} }), TeaVerb.PRODUCT, OFF);
        expect(empty.ok && empty.value.limits).toEqual({ deadlineSeconds: null, connectTimeoutSeconds: null, maxDocumentBytes: null, maxOutputBytes: null });
    });

    it('each fetch limit has the minimum 1, one below is refused, 2147483647 is the top', () => {
        for (const key of ['maxArtifactBytes', 'maxTotalBytes', 'maxFiles', 'holdSeconds']) {
            const field = `input.fetchLimits.${key}`;
            const limits = (value: unknown) => validateFetch(fetchInput({ fetchLimits: { [key]: value } }), OFF);
            const ok = limits(2);
            expect(ok.ok && ok.value.fetchLimits).toMatchObject({ [key]: 2 });
            expect(limits(1).ok).toBe(true);
            expect(refusal(limits(0)), key).toEqual({ ok: false, field, rule: `an integer from 1 to ${INT_MAX}` });
            expect(limits(INT_MAX).ok).toBe(true);
            expect(refusal(limits(INT_MAX + 1)).field).toBe(field);
        }
        const none = validateFetch(fetchInput(), OFF);
        expect(none.ok && none.value.fetchLimits).toBeNull();
    });

    it('a correlation id is 1 to 64 letters, digits, underscore or dash', () => {
        const id = (correlationId: unknown) => validateObject(objectInput({ correlationId }), TeaVerb.PRODUCT, OFF);
        const ok = id('a'.repeat(64));
        expect(ok.ok && ok.value.correlationId).toBe('a'.repeat(64));
        expect(id('Az09_-').ok).toBe(true);
        expect(id(null).ok).toBe(true);
        const none = validateObject(objectInput(), TeaVerb.PRODUCT, OFF);
        expect(none.ok && none.value.correlationId).toBeNull();
        for (const value of ['a'.repeat(65), '', 'a b', 'a.b', 'a/b', 'a\n', 5, true]) {
            expect(refusal(id(value)), String(value)).toEqual({ ok: false, field: 'input.correlationId', rule: '1 to 64 of letters, digits, underscore, dash' });
        }
    });
});

describe('resolve', () => {
    const tei = (value: unknown, extra: Record<string, unknown> = {}) => validateResolve({ tei: value, credential: NONE, ...extra }, OFF);
    const teiRule = (max: number) => `1 to ${max} bytes of printable ASCII starting with tei://`;

    it('a TEI starts with tei:// and is 1 to 2048 bytes of printable ASCII without a space', () => {
        const long = 'tei://' + 'a'.repeat(2048 - 6);
        const ok = tei(long);
        expect(ok.ok && ok.value).toMatchObject({ verb: 'resolve', tei: long, localPlainHttpPort: null });
        expect(refusal(tei(long + 'a'))).toEqual({ ok: false, field: 'input.tei', rule: teiRule(2048) });
        expect(tei('tei://x').ok).toBe(true);
        for (const value of ['', 'http://x', 'tei:/x', 'TEI://x', ' tei://x', 'tei://a b', 'tei://x\n', 5, null, undefined]) {
            expect(refusal(tei(value)), String(value)).toEqual({ ok: false, field: 'input.tei', rule: teiRule(2048) });
        }
        expect(refusal(tei('x-tei://a')).field).toBe('input.tei');
    });

    it('the local plain http port is allowed only under local testing, from 1 to 65535', () => {
        const port = (value: unknown, mode = ON) => validateResolve({ tei: 'tei://localhost/x', credential: NONE, localPlainHttpPort: value }, mode);
        expect(refusal(port(8080, OFF))).toEqual({ ok: false, field: 'input.localPlainHttpPort', rule: 'allowed only under local testing' });
        const ok = port(8080);
        expect(ok.ok && ok.value.localPlainHttpPort).toBe(8080);
        const low = port(1);
        expect(low.ok && low.value.localPlainHttpPort).toBe(1);
        const high = port(65535);
        expect(high.ok && high.value.localPlainHttpPort).toBe(65535);
        expect(refusal(port(0))).toEqual({ ok: false, field: 'input.localPlainHttpPort', rule: 'an integer from 1 to 65535' });
        expect(refusal(port(65536)).field).toBe('input.localPlainHttpPort');
        expect(refusal(port(80.5)).field).toBe('input.localPlainHttpPort');
        const none = port(null, OFF);
        expect(none.ok && none.value.localPlainHttpPort).toBeNull();
        const absent = tei('tei://x');
        expect(absent.ok && absent.value.localPlainHttpPort).toBeNull();
    });
});

describe('releases', () => {
    const list = (extra: Record<string, unknown>) => validateReleases({ apiRoot: API_ROOT, parentKind: 'PRODUCT', parentUuid: UUID_A, credential: NONE, ...extra }, OFF);

    it('a page token is 1 to 4096 bytes of printable ASCII, a space included', () => {
        const ok = list({ pageToken: 'a b'.repeat(1) });
        expect(ok.ok && ok.value.pageToken).toBe('a b');
        expect(list({ pageToken: 'a'.repeat(4096) }).ok).toBe(true);
        expect(refusal(list({ pageToken: 'a'.repeat(4097) }))).toEqual({ ok: false, field: 'input.pageToken', rule: '1 to 4096 bytes of printable ASCII' });
        for (const pageToken of ['', '\x1f', '\x7f', 'a\nb', 5]) expect(refusal(list({ pageToken })).field, JSON.stringify(pageToken)).toBe('input.pageToken');
        const none = list({ pageToken: null });
        expect(none.ok && none.value.pageToken).toBeNull();
    });

    it('the page size is 1 to 100 and the page count 1 to 1000', () => {
        for (const [key, min, max] of [['pageSize', 1, 100], ['maxPages', 1, 1000]] as const) {
            const field = `input.${key}`;
            const run = (value: unknown) => list({ [key]: value });
            const lo = run(min);
            expect(lo.ok && lo.value[key]).toBe(min);
            const hi = run(max);
            expect(hi.ok && hi.value[key]).toBe(max);
            expect(refusal(run(min - 1))).toEqual({ ok: false, field, rule: `an integer from ${min} to ${max}` });
            expect(refusal(run(max + 1)).field).toBe(field);
            expect(refusal(run('5')).field).toBe(field);
        }
    });
});

describe('download', () => {
    const fetch = (extra: Record<string, unknown>) => validateFetch(fetchInput(extra), OFF);
    const CLASS_RULE = '1 to 3 distinct values of SBOM, VEX, OTHER';

    it('the collection version is an integer from 1 to 2147483647', () => {
        const ok = fetch({ collectionVersion: INT_MAX });
        expect(ok.ok && ok.value.collectionVersion).toBe(INT_MAX);
        expect(refusal(fetch({ collectionVersion: 0 }))).toEqual({ ok: false, field: 'input.collectionVersion', rule: `an integer from 1 to ${INT_MAX}` });
        expect(refusal(fetch({ collectionVersion: INT_MAX + 1 })).field).toBe('input.collectionVersion');
        expect(refusal(fetch({ collectionVersion: '1' })).field).toBe('input.collectionVersion');
        expect(refusal(fetch({ collectionVersion: 1.5 })).field).toBe('input.collectionVersion');
    });

    it('one to three distinct document classes, in the order given', () => {
        const ok = fetch({ documentClasses: ['VEX', 'SBOM', 'OTHER'] });
        expect(ok.ok && ok.value.documentClasses).toEqual(['VEX', 'SBOM', 'OTHER']);
        const one = fetch({ documentClasses: ['OTHER'] });
        expect(one.ok && one.value.documentClasses).toEqual(['OTHER']);
        for (const documentClasses of [[], undefined, null, 'SBOM', ['SBOM', 'SBOM'], ['SBOM', 'VEX', 'SBOM'], ['SBOM', 'VEX', 'OTHER', 'SBOM']]) {
            expect(refusal(fetch({ documentClasses })), JSON.stringify(documentClasses)).toEqual({ ok: false, field: 'input.documentClasses', rule: CLASS_RULE });
        }
        expect(refusal(fetch({ documentClasses: ['SBOM', 'BOM'] }))).toEqual({ ok: false, field: 'input.documentClasses', rule: 'one of SBOM, VEX, OTHER' });
        expect(refusal(fetch({ documentClasses: [1] })).field).toBe('input.documentClasses');
    });

    it('at most 16 media types, each type/subtype of token characters of at most 255 bytes', () => {
        const rule = 'at most 16 values, each type/subtype of token characters, at most 255 bytes';
        const sixteen = Array.from({ length: 16 }, (_, i) => `application/t${i}`);
        const ok = fetch({ mediaTypes: sixteen });
        expect(ok.ok && ok.value.mediaTypes).toEqual(sixteen);
        expect(refusal(fetch({ mediaTypes: [...sixteen, 'application/x'] }))).toEqual({ ok: false, field: 'input.mediaTypes', rule });
        const longest = 'a/' + 'b'.repeat(253);
        expect(longest.length).toBe(255);
        expect(fetch({ mediaTypes: [longest] }).ok).toBe(true);
        expect(refusal(fetch({ mediaTypes: [longest + 'b'] }))).toEqual({ ok: false, field: 'input.mediaTypes', rule });
        for (const value of ['application', 'application/', '/json', 'a/b/c', 'a/b c', 'a b/c', 'a/b;q=1', 'a/b,c/d', '', 'a/(b)', 5, null]) {
            expect(refusal(fetch({ mediaTypes: [value] })), JSON.stringify(value)).toEqual({ ok: false, field: 'input.mediaTypes', rule });
        }
        expect(fetch({ mediaTypes: ["application/vnd.cyclonedx+json", "x-y/z.w_!#$%&'*^`|~-1"] }).ok).toBe(true);
        for (const mediaTypes of ['application/json', 5, {}]) expect(refusal(fetch({ mediaTypes })).field).toBe('input.mediaTypes');
        const none = fetch({});
        expect(none.ok && none.value.mediaTypes).toEqual([]);
        const nulled = fetch({ mediaTypes: null });
        expect(nulled.ok && nulled.value.mediaTypes).toEqual([]);
    });
});

describe('store and chunk requests', () => {
    const store = (extra: Record<string, unknown>) => validateStore({ fetchId: 'a'.repeat(32), number: 1, org: UUID_A, rebomOptions: {}, ...extra });

    it('a fetch id is 32 lower-case hexadecimal characters and names the field it was given for', () => {
        const id = '0123456789abcdef0123456789abcdef';
        expect(validateFetchId(id)).toEqual({ ok: true, value: id });
        for (const value of ['', id.slice(1), id + '0', id.toUpperCase(), 'g'.repeat(32), `${id}\n`, 5, null]) {
            expect(validateFetchId(value, 'x.id'), String(value)).toEqual({ ok: false, field: 'x.id', rule: '32 lower-case hexadecimal characters' });
        }
        expect(refusal(validateFetchId('bad')).field).toBe('fetchId');
        expect(refusal(store({ fetchId: 'bad' }))).toEqual({ ok: false, field: 'input.fetchId', rule: '32 lower-case hexadecimal characters' });
        expect(refusal(validateChunk({ fetchId: 'bad', number: 1, offset: 0, length: 1 })).field).toBe('fetchId');
        expect(refusal(validateChunk(null)).field).toBe('fetchId');
    });

    it('a store carries a file number from 1, the organisation as a uuid, the options, the tags and the lineage', () => {
        const ok = store({ number: INT_MAX, org: UUID_B.toUpperCase(), tags: { a: 1 }, rebomOptions: { b: 2 }, continueLineage: 'urn:uuid:x' });
        expect(ok.ok && ok.value).toEqual({ fetchId: 'a'.repeat(32), number: INT_MAX, org: UUID_B, tags: { a: 1 }, rebomOptions: { b: 2 }, continueLineage: 'urn:uuid:x' });
        expect(refusal(store({ number: 0 }))).toEqual({ ok: false, field: 'input.number', rule: `an integer from 1 to ${INT_MAX}` });
        expect(refusal(store({ number: INT_MAX + 1 })).field).toBe('input.number');
        expect(refusal(store({ number: 1.5 })).field).toBe('input.number');
        expect(refusal(store({ org: 'x' }))).toEqual({ ok: false, field: 'input.org', rule: 'a uuid (8-4-4-4-12 hexadecimal)' });
        for (const rebomOptions of [undefined, null, 'x', 5]) {
            expect(refusal(store({ rebomOptions })), String(rebomOptions)).toEqual({ ok: false, field: 'input.rebomOptions', rule: 'required' });
        }
        const plain = store({});
        expect(plain.ok && plain.value).toMatchObject({ tags: null, continueLineage: null });
        // the tags are passed on as given, whatever they hold
        for (const tags of [0, '', false, []]) {
            const kept = store({ tags });
            expect(kept.ok && kept.value.tags, JSON.stringify(tags)).toEqual(tags);
        }
    });

    it('a lineage name is 1 to 1024 bytes of printable ASCII without a space', () => {
        const rule = '1 to 1024 bytes of printable ASCII';
        expect(store({ continueLineage: 'a'.repeat(1024) }).ok).toBe(true);
        expect(refusal(store({ continueLineage: 'a'.repeat(1025) }))).toEqual({ ok: false, field: 'input.continueLineage', rule });
        for (const continueLineage of ['', 'a b', 'a\n', 5]) expect(refusal(store({ continueLineage })).field, JSON.stringify(continueLineage)).toBe('input.continueLineage');
        const none = store({ continueLineage: null });
        expect(none.ok && none.value.continueLineage).toBeNull();
    });

    it('a chunk is a number from 1, an offset from 0 and a length from 1, up to 2147483647', () => {
        const chunk = (extra: Record<string, unknown>) => validateChunk({ fetchId: 'b'.repeat(32), number: 1, offset: 0, length: 1, ...extra });
        expect(chunk({})).toEqual({ ok: true, value: { fetchId: 'b'.repeat(32), number: 1, offset: 0, length: 1 } });
        expect(chunk({ number: INT_MAX, offset: INT_MAX, length: INT_MAX }).ok).toBe(true);
        expect(refusal(chunk({ number: 0 }))).toEqual({ ok: false, field: 'number', rule: `an integer from 1 to ${INT_MAX}` });
        expect(refusal(chunk({ offset: -1 }))).toEqual({ ok: false, field: 'offset', rule: `an integer from 0 to ${INT_MAX}` });
        expect(refusal(chunk({ length: 0 }))).toEqual({ ok: false, field: 'length', rule: `an integer from 1 to ${INT_MAX}` });
        expect(refusal(chunk({ number: INT_MAX + 1 })).field).toBe('number');
        expect(refusal(chunk({ offset: INT_MAX + 1 })).field).toBe('offset');
        expect(refusal(chunk({ length: INT_MAX + 1 })).field).toBe('length');
        expect(refusal(chunk({ length: 2.5 })).field).toBe('length');
    });
});
