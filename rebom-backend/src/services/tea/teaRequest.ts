import { TeaFetchLimitsInput, TeaLimitsInput, TeaLocalTesting } from './teaConfig';
import { TeaDocumentClass } from './teaDocument';

/**
 * Validation of every input of the TEA operations before anything starts (TPF-180 design 3.5).
 * A failure names the field and the rule, never the value.
 */

export const TeaVerb = {
    RESOLVE: 'resolve',
    PRODUCT: 'product',
    COMPONENT: 'component',
    RELEASES: 'releases',
    RELEASE: 'release',
    DOWNLOAD: 'download'
} as const;
export type TeaVerb = typeof TeaVerb[keyof typeof TeaVerb];

export const TeaCredentialKind = {
    NONE: 'NONE',
    BEARER_TOKEN: 'BEARER_TOKEN',
    API_KEY: 'API_KEY'
} as const;
export type TeaCredentialKind = typeof TeaCredentialKind[keyof typeof TeaCredentialKind];

export const TeaParentKind = {
    PRODUCT: 'PRODUCT',
    COMPONENT: 'COMPONENT'
} as const;
export type TeaParentKind = typeof TeaParentKind[keyof typeof TeaParentKind];

export const TeaReleaseKind = {
    PRODUCT_RELEASE: 'PRODUCT_RELEASE',
    COMPONENT_RELEASE: 'COMPONENT_RELEASE'
} as const;
export type TeaReleaseKind = typeof TeaReleaseKind[keyof typeof TeaReleaseKind];

export interface TeaCredential {
    kind: TeaCredentialKind;
    authRoot: string | null;
    token: string | null;
    keyId: string | null;
    keySecret: string | null;
}

/** A request that passed validation; the only shape the argument and environment builders take. */
export interface TeaRequest {
    verb: TeaVerb;
    credential: TeaCredential;
    limits: TeaLimitsInput | null;
    correlationId: string | null;
    tei?: string;
    localPlainHttpPort?: number | null;
    apiRoot?: string;
    uuid?: string;
    parentKind?: TeaParentKind;
    parentUuid?: string;
    pageToken?: string | null;
    pageSize?: number | null;
    maxPages?: number | null;
    releaseKind?: TeaReleaseKind;
    releaseUuid?: string;
    collectionVersion?: number;
    documentClasses?: TeaDocumentClass[];
    mediaTypes?: string[];
    fetchLimits?: TeaFetchLimitsInput | null;
}

export type TeaValidation<T> =
    | { ok: true, value: T }
    | { ok: false, field: string, rule: string };

class InputRefused extends Error {
    constructor(readonly field: string, readonly rule: string) {
        super('input refused');
    }
}

function refuse(field: string, rule: string): never {
    throw new InputRefused(field, rule);
}

function validating<T>(fn: () => T): TeaValidation<T> {
    try {
        return { ok: true, value: fn() };
    } catch (error) {
        if (error instanceof InputRefused) return { ok: false, field: error.field, rule: error.rule };
        throw error;
    }
}

/** The rule text of a failed validation, as INVALID_REQUEST's detail. */
export function describeRefusal(field: string, rule: string): string {
    return `${field}: ${rule}`;
}

const PRINTABLE_NO_SPACE = /^[\x21-\x7e]+$/;
const PRINTABLE = /^[\x20-\x7e]+$/;
const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;
const CORRELATION_ID = /^[A-Za-z0-9_-]{1,64}$/;
const FETCH_ID = /^[0-9a-f]{32}$/;
const TOKEN_CHARS = "[!#$%&'*+.^_`|~0-9A-Za-z-]+";
const MEDIA_TYPE = new RegExp(`^${TOKEN_CHARS}/${TOKEN_CHARS}$`);
const TEI_PREFIX = 'tei://';
const URL_MAX = 2048;
const PAGE_TOKEN_MAX = 4096;
const SECRET_MAX = 8192;
const MEDIA_TYPE_MAX = 255;
const MEDIA_TYPES_MAX = 16;
const LINEAGE_MAX = 1024;
const INT_MAX = 2147483647;
const HTTPS = 'https:';
const HTTP = 'http:';

/** The CLI's minimum for each per-call limit (below it is refused, above the ceiling is clamped). */
const LIMIT_MINIMUMS: Record<keyof TeaLimitsInput, number> = {
    deadlineSeconds: 1,
    connectTimeoutSeconds: 1,
    maxDocumentBytes: 1024,
    maxOutputBytes: 65536
};
const FETCH_LIMIT_MINIMUMS: Record<keyof TeaFetchLimitsInput, number> = {
    maxArtifactBytes: 1,
    maxTotalBytes: 1,
    maxFiles: 1,
    holdSeconds: 1
};

function present<T>(value: T | null | undefined): value is T {
    return value !== null && value !== undefined;
}

function text(field: string, value: unknown, pattern: RegExp, max: number, rule: string): string {
    if (typeof value !== 'string' || value.length === 0 || value.length > max || !pattern.test(value)) refuse(field, rule);
    return value;
}

function url(field: string, value: unknown, localTesting: TeaLocalTesting): string {
    const rule = `1 to ${URL_MAX} bytes of printable ASCII, an https URL (http only under local testing) without user name or password`;
    const value_ = text(field, value, PRINTABLE_NO_SPACE, URL_MAX, rule);
    let parsed: URL;
    try {
        parsed = new URL(value_);
    } catch {
        refuse(field, rule);
    }
    const schemeAllowed = parsed.protocol === HTTPS || (parsed.protocol === HTTP && localTesting === TeaLocalTesting.ON);
    if (!schemeAllowed || parsed.username !== '' || parsed.password !== '') refuse(field, rule);
    // passed as given, not re-serialised: the CLI applies its own stricter rules
    return value_;
}

export function isPlainHttp(value: string): boolean {
    return new URL(value).protocol === HTTP;
}

function uuid(field: string, value: unknown): string {
    if (typeof value !== 'string' || !UUID.test(value)) refuse(field, 'a uuid (8-4-4-4-12 hexadecimal)');
    return value.toLowerCase();
}

function integer(field: string, value: unknown, min: number, max: number): number {
    if (typeof value !== 'number' || !Number.isInteger(value) || value < min || value > max) {
        refuse(field, `an integer from ${min} to ${max}`);
    }
    return value;
}

function optionalInteger(field: string, value: unknown, min: number, max: number): number | null {
    return present(value) ? integer(field, value, min, max) : null;
}

function oneOf<T extends string>(field: string, value: unknown, allowed: Record<string, T>): T {
    const values = Object.values(allowed) as string[];
    if (typeof value !== 'string' || !values.includes(value)) refuse(field, `one of ${values.join(', ')}`);
    return value as T;
}

function secret(field: string, value: unknown): string {
    return text(field, value, PRINTABLE, SECRET_MAX, `1 to ${SECRET_MAX} bytes of printable ASCII`);
}

function credentialOf(raw: any, localTesting: TeaLocalTesting): TeaCredential {
    const field = 'input.credential';
    if (!present(raw) || typeof raw !== 'object') refuse(field, 'required');
    const kind = oneOf(`${field}.kind`, raw.kind, TeaCredentialKind);
    const absent = (name: string) => {
        if (present(raw[name])) refuse(`${field}.${name}`, `not allowed with kind ${kind}`);
    };
    const credential: TeaCredential = { kind, authRoot: null, token: null, keyId: null, keySecret: null };
    if (kind === TeaCredentialKind.NONE) {
        absent('authRoot');
        absent('token');
        absent('keyId');
        absent('keySecret');
        return credential;
    }
    credential.authRoot = url(`${field}.authRoot`, raw.authRoot, localTesting);
    if (kind === TeaCredentialKind.BEARER_TOKEN) {
        credential.token = secret(`${field}.token`, raw.token);
        absent('keyId');
        absent('keySecret');
    } else {
        credential.keyId = secret(`${field}.keyId`, raw.keyId);
        credential.keySecret = secret(`${field}.keySecret`, raw.keySecret);
        absent('token');
    }
    return credential;
}

function limitsOf<K extends string>(field: string, raw: any, minimums: Record<K, number>): Partial<Record<K, number | null>> | null {
    if (!present(raw)) return null;
    const out: Partial<Record<K, number | null>> = {};
    for (const key of Object.keys(minimums) as K[]) {
        out[key] = optionalInteger(`${field}.${key}`, raw[key], minimums[key], INT_MAX);
    }
    return out;
}

function correlationIdOf(raw: unknown): string | null {
    if (!present(raw)) return null;
    if (typeof raw !== 'string' || !CORRELATION_ID.test(raw)) refuse('input.correlationId', '1 to 64 of letters, digits, underscore, dash');
    return raw;
}

function common(input: any, verb: TeaVerb, localTesting: TeaLocalTesting): TeaRequest {
    if (!present(input) || typeof input !== 'object') refuse('input', 'required');
    return {
        verb,
        credential: credentialOf(input.credential, localTesting),
        limits: limitsOf('input.limits', input.limits, LIMIT_MINIMUMS),
        correlationId: correlationIdOf(input.correlationId)
    };
}

export function validateResolve(input: any, localTesting: TeaLocalTesting): TeaValidation<TeaRequest> {
    return validating(() => {
        const request = common(input, TeaVerb.RESOLVE, localTesting);
        const tei = text('input.tei', input.tei, PRINTABLE_NO_SPACE, URL_MAX, `1 to ${URL_MAX} bytes of printable ASCII starting with ${TEI_PREFIX}`);
        if (!tei.startsWith(TEI_PREFIX)) refuse('input.tei', `1 to ${URL_MAX} bytes of printable ASCII starting with ${TEI_PREFIX}`);
        request.tei = tei;
        if (present(input.localPlainHttpPort)) {
            if (localTesting !== TeaLocalTesting.ON) refuse('input.localPlainHttpPort', 'allowed only under local testing');
            request.localPlainHttpPort = integer('input.localPlainHttpPort', input.localPlainHttpPort, 1, 65535);
        } else {
            request.localPlainHttpPort = null;
        }
        return request;
    });
}

export function validateObject(input: any, verb: typeof TeaVerb.PRODUCT | typeof TeaVerb.COMPONENT, localTesting: TeaLocalTesting): TeaValidation<TeaRequest> {
    return validating(() => {
        const request = common(input, verb, localTesting);
        request.apiRoot = url('input.apiRoot', input.apiRoot, localTesting);
        request.uuid = uuid('input.uuid', input.uuid);
        return request;
    });
}

export function validateReleases(input: any, localTesting: TeaLocalTesting): TeaValidation<TeaRequest> {
    return validating(() => {
        const request = common(input, TeaVerb.RELEASES, localTesting);
        request.apiRoot = url('input.apiRoot', input.apiRoot, localTesting);
        request.parentKind = oneOf('input.parentKind', input.parentKind, TeaParentKind);
        request.parentUuid = uuid('input.parentUuid', input.parentUuid);
        request.pageToken = present(input.pageToken)
            ? text('input.pageToken', input.pageToken, PRINTABLE, PAGE_TOKEN_MAX, `1 to ${PAGE_TOKEN_MAX} bytes of printable ASCII`)
            : null;
        request.pageSize = optionalInteger('input.pageSize', input.pageSize, 1, 100);
        request.maxPages = optionalInteger('input.maxPages', input.maxPages, 1, 1000);
        return request;
    });
}

export function validateRelease(input: any, localTesting: TeaLocalTesting): TeaValidation<TeaRequest> {
    return validating(() => {
        const request = common(input, TeaVerb.RELEASE, localTesting);
        request.apiRoot = url('input.apiRoot', input.apiRoot, localTesting);
        request.releaseKind = oneOf('input.releaseKind', input.releaseKind, TeaReleaseKind);
        request.releaseUuid = uuid('input.releaseUuid', input.releaseUuid);
        return request;
    });
}

export function validateFetch(input: any, localTesting: TeaLocalTesting): TeaValidation<TeaRequest> {
    return validating(() => {
        const request = common(input, TeaVerb.DOWNLOAD, localTesting);
        request.apiRoot = url('input.apiRoot', input.apiRoot, localTesting);
        request.releaseKind = oneOf('input.releaseKind', input.releaseKind, TeaReleaseKind);
        request.releaseUuid = uuid('input.releaseUuid', input.releaseUuid);
        request.collectionVersion = integer('input.collectionVersion', input.collectionVersion, 1, INT_MAX);
        const classes = input.documentClasses;
        const classRule = '1 to 3 distinct values of SBOM, VEX, OTHER';
        if (!Array.isArray(classes) || classes.length < 1 || classes.length > 3) refuse('input.documentClasses', classRule);
        const seen = new Set<TeaDocumentClass>();
        for (const value of classes) {
            const documentClass = oneOf('input.documentClasses', value, TeaDocumentClass);
            if (seen.has(documentClass)) refuse('input.documentClasses', classRule);
            seen.add(documentClass);
        }
        request.documentClasses = [...seen];
        const mediaTypes = present(input.mediaTypes) ? input.mediaTypes : [];
        const mediaRule = `at most ${MEDIA_TYPES_MAX} values, each type/subtype of token characters, at most ${MEDIA_TYPE_MAX} bytes`;
        if (!Array.isArray(mediaTypes) || mediaTypes.length > MEDIA_TYPES_MAX) refuse('input.mediaTypes', mediaRule);
        request.mediaTypes = mediaTypes.map((value: unknown) => text('input.mediaTypes', value, MEDIA_TYPE, MEDIA_TYPE_MAX, mediaRule));
        request.fetchLimits = limitsOf('input.fetchLimits', input.fetchLimits, FETCH_LIMIT_MINIMUMS);
        return request;
    });
}

export interface TeaStoreRequest {
    fetchId: string;
    number: number;
    org: string;
    tags: unknown;
    rebomOptions: Record<string, unknown>;
    continueLineage: string | null;
}

export function validateFetchId(value: unknown, field = 'fetchId'): TeaValidation<string> {
    return validating(() => {
        if (typeof value !== 'string' || !FETCH_ID.test(value)) refuse(field, '32 lower-case hexadecimal characters');
        return value;
    });
}

export function validateStore(input: any): TeaValidation<TeaStoreRequest> {
    return validating(() => {
        if (!present(input) || typeof input !== 'object') refuse('input', 'required');
        const fetchId = validateFetchId(input.fetchId, 'input.fetchId');
        if (!fetchId.ok) refuse(fetchId.field, fetchId.rule);
        if (!present(input.rebomOptions) || typeof input.rebomOptions !== 'object') refuse('input.rebomOptions', 'required');
        return {
            fetchId: fetchId.value,
            number: integer('input.number', input.number, 1, INT_MAX),
            org: uuid('input.org', input.org),
            tags: input.tags ?? null,
            rebomOptions: input.rebomOptions,
            continueLineage: present(input.continueLineage)
                ? text('input.continueLineage', input.continueLineage, PRINTABLE_NO_SPACE, LINEAGE_MAX, `1 to ${LINEAGE_MAX} bytes of printable ASCII`)
                : null
        };
    });
}

export interface TeaChunkRequest {
    fetchId: string;
    number: number;
    offset: number;
    length: number;
}

export function validateChunk(args: any): TeaValidation<TeaChunkRequest> {
    return validating(() => {
        const fetchId = validateFetchId(args?.fetchId);
        if (!fetchId.ok) refuse(fetchId.field, fetchId.rule);
        return {
            fetchId: fetchId.value,
            number: integer('number', args.number, 1, INT_MAX),
            offset: integer('offset', args.offset, 0, INT_MAX),
            length: integer('length', args.length, 1, INT_MAX)
        };
    });
}
