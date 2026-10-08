import { describe, it, expect, vi, afterEach } from 'vitest';
import { logger } from '../../src/logger';
import {
    carryServices,
    dropDanglingRefs,
    DANGLING_REFS_DROPPED_PROPERTY
} from '../../src/services/bom/bomProcessingService';

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

    it('never lets a service without a bom-ref make a dangling ref resolve (T-5)', () => {
        vi.spyOn(logger, 'warn').mockImplementation(() => undefined as any);
        const bom = merged({ dependencies: [{ ref: ROOT, dependsOn: [A, 'cron'] }, { ref: 'syslog', dependsOn: [A] }] });
        const carried = carryServices(bom, [input([{ name: 'cron' }, { name: 'syslog' }])], { referencedOnly: false }).bom;
        const result = dropDanglingRefs(carried);

        expect(carried.services).toStrictEqual([{ name: 'cron' }, { name: 'syslog' }]);
        expect(result.count).toBe(2);
        expect(result.bom.dependencies).toStrictEqual([{ ref: ROOT, dependsOn: [A] }]);
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

    it('passes a document without services through both passes with no marker (T-8)', () => {
        const bom = merged({ dependencies: [{ ref: ROOT, dependsOn: [A, B] }] });
        const before = structuredClone(bom);
        const carried = carryServices(bom, [input(), input()], { referencedOnly: false });
        const checked = dropDanglingRefs(carried.bom);

        expect(checked.bom).toStrictEqual(before);
        expect(checked.count).toBe(0);
        expect(carried.count).toBe(0);
        expect(checked.bom).not.toHaveProperty('services');
        expect(checked.bom.metadata).not.toHaveProperty('properties');
        expect(JSON.stringify(checked.bom)).not.toContain(DANGLING_REFS_DROPPED_PROPERTY);
    });
});
