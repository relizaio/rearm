import { describe, it, expect, vi, afterEach } from 'vitest';
import { logger } from '../../src/logger';
import { carryServices } from '../../src/services/bom/bomProcessingService';

/**
 * SCORE-14 (ADR-1, ADR-2): rearm-cli merge-boms carries the inputs' dependency entries but
 * never their services[]; carryServices re-attaches the inputs' services to the merged document.
 */

const ROOT = 'pkg:generic/Example/merged@1.0.0';
const A = 'pkg:npm/a@1.0.0';
const B = 'pkg:npm/b@1.0.0';

const S1 = { 'bom-ref': 's1', name: 'nginx', endpoints: ['http://localhost:80'] };
const S2 = { 'bom-ref': 's2', name: 'nginx-debug', authenticated: false };

function lib(ref: string, extra: any = {}): any {
    return { type: 'library', name: ref, version: '1.0.0', 'bom-ref': ref, ...extra };
}

/** A merged document as merge-boms returns it: no `services` key. */
function merged(extra: any = {}): any {
    return {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        serialNumber: 'urn:uuid:3c1f0a7e-14a1-4c1e-8f00-000000000014',
        version: 1,
        metadata: { component: { type: 'application', name: 'merged', 'bom-ref': ROOT } },
        components: [lib(A), lib(B)],
        dependencies: [
            { ref: ROOT, dependsOn: [A, B] },
            { ref: 's1', dependsOn: [A] }
        ],
        ...extra
    };
}

function input(services?: any[], extra: any = {}): any {
    const bom: any = {
        bomFormat: 'CycloneDX',
        specVersion: '1.6',
        metadata: { component: { type: 'application', name: 'in', 'bom-ref': 'pkg:npm/in@1.0.0' } },
        components: [lib(A)],
        ...extra
    };
    if (services) bom.services = services;
    return bom;
}

afterEach(() => vi.restoreAllMocks());

describe('carryServices', () => {
    it('carries every input service in input order and leaves the dependencies alone (T-2)', () => {
        const bom = merged();
        const result = carryServices(bom, [input([S1, S2])], { referencedOnly: false });

        expect(result.count).toBe(2);
        expect(result.bom.services).toStrictEqual([S1, S2]);
        expect(result.bom.dependencies).toBe(bom.dependencies);
        expect(result.bom.components).toBe(bom.components);
        expect(bom).not.toHaveProperty('services');
    });

    it('keeps the first occurrence of a bom-ref verbatim and does not count the later one (T-3)', () => {
        const second = { 'bom-ref': 's1', name: 'nginx-other', version: '2', endpoints: ['http://other'] };
        const result = carryServices(merged(), [input([S1]), input([second, S2])], { referencedOnly: false });

        expect(result.count).toBe(2);
        expect(result.bom.services).toStrictEqual([S1, S2]);
        expect(result.bom.services[0]).toBe(S1);
    });

    it('counts a service carried by two inputs once (T-3)', () => {
        const result = carryServices(merged(), [input([S1]), input([{ ...S1, name: 'changed' }])], { referencedOnly: false });

        expect(result.count).toBe(1);
        expect(result.bom.services).toStrictEqual([S1]);
    });

    it('skips a service whose bom-ref is a nested component bom-ref, and warns (T-4)', () => {
        const warn = vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        const bom = merged({
            components: [lib(A, { components: [lib('nested-x')] }), lib(B)]
        });
        const clash = { 'bom-ref': 'nested-x', name: 'clash' };
        const result = carryServices(bom, [input([S1, clash, S2])], { referencedOnly: false });

        expect(result.bom.services).toStrictEqual([S1, S2]);
        expect(result.count).toBe(2);
        expect(result.bom.components).toStrictEqual([lib(A, { components: [lib('nested-x')] }), lib(B)]);
        expect(warn).toHaveBeenCalledTimes(1);
        expect(String(warn.mock.calls[0][0])).toContain('nested-x');
    });

    it('skips a service whose bom-ref is the root component bom-ref (T-4)', () => {
        vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        const result = carryServices(merged(), [input([{ 'bom-ref': ROOT, name: 'root-clash' }, S2])], { referencedOnly: false });

        expect(result.bom.services).toStrictEqual([S2]);
    });

    it('carries services without a bom-ref once per distinct serialization (T-5)', () => {
        const anon1 = { name: 'cron' };
        const anon2 = { name: 'syslog', version: '1' };
        const result = carryServices(merged(), [input([anon1, { name: 'cron' }]), input([anon2, { ...anon1 }])], { referencedOnly: false });

        expect(result.bom.services).toStrictEqual([anon1, anon2]);
        expect(result.count).toBe(2);
    });

    it('carries only the services a dependency entry names when referencedOnly (T-6)', () => {
        const bom = merged({
            dependencies: [{ ref: ROOT, dependsOn: [A, 's1'] }]
        });
        const result = carryServices(bom, [input([S1, S2, { name: 'anonymous' }])], { referencedOnly: true });

        expect(result.bom.services).toStrictEqual([S1]);
        expect(result.count).toBe(1);
    });

    it('counts provides[] and an entry ref as naming a service when referencedOnly (T-6)', () => {
        const S3 = { 'bom-ref': 's3', name: 'unnamed' };
        const bom = merged({
            dependencies: [{ ref: ROOT, dependsOn: [A] }, { ref: 's1', dependsOn: [] }, { ref: A, provides: ['s2'] }]
        });
        const result = carryServices(bom, [input([S1, S2, S3])], { referencedOnly: true });

        expect(result.bom.services).toStrictEqual([S1, S2]);
    });

    it('carries a parent service when referencedOnly names a service nested in it (T-6)', () => {
        const parent = { 'bom-ref': 'p', name: 'parent', services: [{ 'bom-ref': 'child', name: 'child' }] };
        const bom = merged({ dependencies: [{ ref: ROOT, dependsOn: [A, 'child'] }] });
        const result = carryServices(bom, [input([parent, S2])], { referencedOnly: true });

        expect(result.bom.services).toStrictEqual([parent]);
    });

    it('keeps the services the merged document already has first, without repeats (T-7)', () => {
        const S0 = { 'bom-ref': 's0', name: 'native' };
        const bom = merged({ services: [S0] });
        const result = carryServices(bom, [input([S1, { 'bom-ref': 's0', name: 'input copy' }]), input([S2])], { referencedOnly: false });

        expect(result.bom.services).toStrictEqual([S0, S1, S2]);
        expect(result.bom.services[0]).toBe(S0);
        expect(result.count).toBe(2);
        expect(bom.services).toStrictEqual([S0]);
    });

    it('returns the document unchanged when no input has services (T-8)', () => {
        const bom = merged();
        const before = structuredClone(bom);
        const carried = carryServices(bom, [input(), input()], { referencedOnly: false });

        expect(carried.bom).toBe(bom);
        expect(carried.bom).toStrictEqual(before);
        expect(carried.count).toBe(0);
        expect(carried.bom).not.toHaveProperty('services');
    });

    it('returns the document unchanged when every input service is already there', () => {
        const bom = merged({ services: [S1] });
        const carried = carryServices(bom, [input([S1])], { referencedOnly: false });

        expect(carried.bom).toBe(bom);
        expect(carried.count).toBe(0);
    });

    it('carries nothing and adds no services key when referencedOnly names no service', () => {
        const bom = merged({ dependencies: [{ ref: ROOT, dependsOn: [A] }] });
        const carried = carryServices(bom, [input([S1, S2])], { referencedOnly: true });

        expect(carried.bom).toBe(bom);
        expect(carried.bom).not.toHaveProperty('services');
    });
});

describe('carryServices: the exact bom-ref string is the key (ADR-2, no normalisation)', () => {
    it('carries two services whose bom-refs differ only by case', () => {
        const upper = { 'bom-ref': 'urn:svc:Nginx', name: 'Nginx' };
        const lower = { 'bom-ref': 'urn:svc:nginx', name: 'nginx' };
        const result = carryServices(merged(), [input([upper]), input([lower])], { referencedOnly: false });

        expect(result.bom.services).toStrictEqual([upper, lower]);
        expect(result.count).toBe(2);
    });

    it('carries an input service whose bom-ref differs from one the merged document has only by case', () => {
        const S0 = { 'bom-ref': 'S1', name: 'native' };
        const result = carryServices(merged({ services: [S0] }), [input([S1])], { referencedOnly: false });

        expect(result.bom.services).toStrictEqual([S0, S1]);
        expect(result.count).toBe(1);
    });

    it('carries a service whose bom-ref differs from a component bom-ref only by case, without a warning', () => {
        const warn = vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        const bom = merged({ components: [lib('PKG'), lib(B)] });
        const service = { 'bom-ref': 'pkg', name: 'pkg-service' };
        const result = carryServices(bom, [input([service])], { referencedOnly: false });

        expect(result.bom.services).toStrictEqual([service]);
        expect(result.count).toBe(1);
        expect(warn).not.toHaveBeenCalled();
    });

    it('does not count a dependency reference that differs only by case as naming a service when referencedOnly', () => {
        const bom = merged({ dependencies: [{ ref: ROOT, dependsOn: [A, 'S1'] }, { ref: A, provides: ['S2'] }] });
        const carried = carryServices(bom, [input([S1, S2])], { referencedOnly: true });

        expect(carried.bom).toBe(bom);
        expect(carried.count).toBe(0);
        expect(carried.bom).not.toHaveProperty('services');
    });

    it('does not trim a bom-ref: a trailing space makes another service', () => {
        const spaced = { 'bom-ref': 's1 ', name: 'nginx spaced' };
        const result = carryServices(merged(), [input([S1, spaced])], { referencedOnly: false });

        expect(result.bom.services).toStrictEqual([S1, spaced]);
    });

    it('keys a service without a bom-ref by its serialization with the keys as given', () => {
        const nameFirst = { name: 'cron', version: '1' };
        const versionFirst = { version: '1', name: 'cron' };
        const result = carryServices(merged(), [input([nameFirst, versionFirst])], { referencedOnly: false });

        expect(result.bom.services).toStrictEqual([nameFirst, versionFirst]);
        expect(result.bom.services[1]).toBe(versionFirst);
        expect(result.count).toBe(2);
    });
});

describe('carryServices: order of what is carried', () => {
    it('keeps the merged document services, then each input in input order, then each input service in its own order', () => {
        const S0a = { 'bom-ref': 's0a', name: 'native-a' };
        const S0b = { 'bom-ref': 's0b', name: 'native-b' };
        const S3 = { 'bom-ref': 's3', name: 's3' };
        const S4 = { 'bom-ref': 's4', name: 's4' };
        const anon = { name: 'anonymous' };
        const result = carryServices(merged({ services: [S0a, S0b] }),
            [input([S2, anon, S1]), input([S4, S3])], { referencedOnly: false });

        expect(result.bom.services.map((s: any) => s['bom-ref'] ?? s.name))
            .toStrictEqual(['s0a', 's0b', 's2', 'anonymous', 's1', 's4', 's3']);
        expect(result.count).toBe(5);
    });

    it('keeps input order when referencedOnly filters', () => {
        const S3 = { 'bom-ref': 's3', name: 's3' };
        const bom = merged({ dependencies: [{ ref: ROOT, dependsOn: ['s3', A, 's1'] }] });
        const result = carryServices(bom, [input([S3, S2]), input([S1])], { referencedOnly: true });

        expect(result.bom.services).toStrictEqual([S3, S1]);
    });
});
