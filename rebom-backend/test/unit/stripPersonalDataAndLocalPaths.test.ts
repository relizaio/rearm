import { describe, it, expect } from 'vitest';
import {
    stripPersonalDataAndLocalPaths,
    stripPurlCredentials,
    stripPurlQualifierCredentials,
    stripAddressTokens,
    PERSONAL_DATA_PROPERTY_NAMES,
    LOCAL_PATH_PROPERTY_NAMES,
    createRebomToolObject,
    ADDRESS_PATTERN,
    CREDENTIAL_URL_PATTERN
} from '../../src/services/bom/bomProcessingService';

/**
 * FDAW-6 T3, T4 (architecture 3.2, review items F-12 to F-14): the strip of personal data, local
 * paths and URL credentials from the merged document, by exact name and by shape.
 */

function doc(component: any, extra: any = {}): any {
    return {
        bomFormat: 'CycloneDX', specVersion: '1.6',
        metadata: { component: { type: 'application', name: 'root', 'bom-ref': 'root' } },
        components: [{ type: 'library', name: 'c', version: '1.0.0', 'bom-ref': 'pkg:npm/c@1.0.0', purl: 'pkg:npm/c@1.0.0', ...component }],
        ...extra
    };
}

function prop(name: string, value: string): any {
    return { name, value };
}

/** The pass on one component carrying `properties`; returns the kept properties and the counts. */
function onProperties(...properties: any[]) {
    const result = stripPersonalDataAndLocalPaths(doc({ properties }));
    return { kept: result.bom.components[0].properties, ...result };
}

describe('stripPersonalDataAndLocalPaths (FDAW-6 T3)', () => {
    it('S1: each personal-data property name is removed and counted once', () => {
        for (const name of PERSONAL_DATA_PROPERTY_NAMES) {
            const r = onProperties(prop(name, 'isaacs, i@izs.me'), prop('cdx:npm:versionCount', '188'));
            expect(r.kept).toStrictEqual([prop('cdx:npm:versionCount', '188')]);
            expect([r.personalData, r.localPaths, r.credentials]).toStrictEqual([1, 0, 0]);
        }
        expect(PERSONAL_DATA_PROPERTY_NAMES.size).toBe(7);
    });

    it('S2: a property whose value carries an address is removed by shape and counted once', () => {
        expect(onProperties(prop('cdx:foo:contact', 'a@b.co'))).toMatchObject({ kept: [], personalData: 1 });
        expect(onProperties(prop('cdx:foo:contact', 'mailto:a@b.co'))).toMatchObject({ kept: [], personalData: 1 });
        expect(onProperties(prop('cdx:foo:owner', 'Jane <sindresorhus@gmail.com>'))).toMatchObject({ kept: [], personalData: 1 });
        const glob = 'Old versions of glob are not supported. Support for old versions may be purchased (at exorbitant rates) by contacting i@izs.me';
        expect(onProperties(prop('cdx:npm:deprecated', glob))).toMatchObject({ kept: [], personalData: 1, localPaths: 0 });
    });

    it('order: a name rule takes the property before the address shape, one count each', () => {
        expect(onProperties(prop('cdx:npm:maintainerSet', 'hi@henryzoo.com'))).toMatchObject({ personalData: 1, localPaths: 0 });
        expect(onProperties(prop('LocalNodeModulesPath', 'node_modules/@nodelib/fs.scandir'))).toMatchObject({ kept: [], personalData: 0, localPaths: 1 });
        expect(onProperties(prop('SrcFile', '/usr/local/lib/node_modules/@isaacs/cliui/package.json'))).toMatchObject({ kept: [], personalData: 0, localPaths: 1 });
    });

    it('S6, S7, S8: local paths by name and shape, file: references', () => {
        for (const name of LOCAL_PATH_PROPERTY_NAMES) {
            expect(onProperties(prop(name, '/home/acme/scratch/x/node_modules/.bin/parser'))).toMatchObject({ kept: [], localPaths: 1 });
        }
        expect(onProperties(prop('SrcFile', '/home/x/package-lock.json'))).toMatchObject({ kept: [], localPaths: 1 });
        expect(onProperties(prop('SrcFile', '~/x/package-lock.json'))).toMatchObject({ kept: [], localPaths: 1 });
        expect(onProperties(prop('SrcFile', 'C:\\work\\package-lock.json'))).toMatchObject({ kept: [], localPaths: 1 });
        expect(onProperties(prop('SrcFile', '\\\\server\\share\\package-lock.json'))).toMatchObject({ kept: [], localPaths: 1 });
        expect(onProperties(prop('SrcFile', 'package-lock.json')).kept).toStrictEqual([prop('SrcFile', 'package-lock.json')]);
        expect(onProperties(prop('cdx:foo:source', 'file:../vendor/x.tgz'))).toMatchObject({ kept: [], localPaths: 1 });
        expect(onProperties(prop('cdx:foo:source', 'FILE:///x'))).toMatchObject({ kept: [], localPaths: 1 });

        // S7 (SrcFile shape) is a `pathRules` rule and does not apply to a service, unlike S1/S8/S10
        const svc = stripPersonalDataAndLocalPaths({
            metadata: { component: { name: 'root', 'bom-ref': 'root' } },
            components: [],
            services: [{ name: 's', 'bom-ref': 's', properties: [prop('SrcFile', '/usr/local/lib/x.py')] }]
        });
        expect(svc.bom.services[0].properties).toStrictEqual([prop('SrcFile', '/usr/local/lib/x.py')]);
        expect(svc.localPaths).toBe(0);

        const refs = stripPersonalDataAndLocalPaths(doc({ externalReferences: [
            { type: 'distribution', url: 'file:///home/acme/wheels/numpy.whl' },
            { type: 'distribution', url: 'https://files.example.org/numpy.whl' }
        ] }));
        expect(refs.bom.components[0].externalReferences).toStrictEqual([{ type: 'distribution', url: 'https://files.example.org/numpy.whl' }]);
        expect(refs.localPaths).toBe(1);
    });

    it('S3: authors lose email and phone, keep name; an entry of only an email goes with its array', () => {
        const r = stripPersonalDataAndLocalPaths(doc({ authors: [{ name: 'Jane', email: 'jane@example.org', phone: '+1 555' }] }));
        expect(r.bom.components[0].authors).toStrictEqual([{ name: 'Jane' }]);
        expect(r.personalData).toBe(2);
        const only = stripPersonalDataAndLocalPaths(doc({ authors: [{ email: 'jane@example.org' }] }));
        expect(only.bom.components[0]).not.toHaveProperty('authors');
        expect(only.personalData).toBe(1);
    });

    it('S3 (departure): an address in an author name is removed, the name kept', () => {
        const r = stripPersonalDataAndLocalPaths(doc({ authors: [{ name: '\u0141ukasz Langa <lukasz@langa.pl>' }, { name: 'Holger Krekel' }] }));
        expect(r.bom.components[0].authors).toStrictEqual([{ name: '\u0141ukasz Langa' }, { name: 'Holger Krekel' }]);
        expect(r.personalData).toBe(1);
        const bare = stripPersonalDataAndLocalPaths(doc({ authors: [{ name: 'lukasz@langa.pl' }] }));
        expect(bare.bom.components[0]).not.toHaveProperty('authors');
    });

    it('S4: author and publisher lose their address tokens, the name part stays', () => {
        const r = stripPersonalDataAndLocalPaths(doc({ author: 'Sindre Sorhus <sindresorhus@gmail.com> (https://sindresorhus.com)' }));
        expect(r.bom.components[0].author).toBe('Sindre Sorhus (https://sindresorhus.com)');
        expect(r.personalData).toBe(1);
        const syft = stripPersonalDataAndLocalPaths(doc({ publisher: 'Natanael Copa <ncopa@alpinelinux.org>' }));
        expect(syft.bom.components[0].publisher).toBe('Natanael Copa');
        expect(syft.personalData).toBe(1);
        const only = stripPersonalDataAndLocalPaths(doc({ publisher: 'ncopa@alpinelinux.org' }));
        expect(only.bom.components[0]).not.toHaveProperty('publisher');
        expect(only.personalData).toBe(1);
        expect(stripAddressTokens('A <a@x.org>, B <b@y.org>')).toBe('A, B');
        expect(stripAddressTokens('GNU Libc Maintainers <debian-glibc@lists.debian.org>')).toBe('GNU Libc Maintainers');
        expect(stripAddressTokens('a@x.org; Jane')).toBe('Jane');
        expect(stripAddressTokens('Jane, a@x.org, Joe')).toBe('Jane, Joe');
        expect(stripAddressTokens('<a@x.org>')).toBe('');
    });

    it('S5: supplier, manufacturer and service provider contacts lose email and phone, names stay', () => {
        const r = stripPersonalDataAndLocalPaths(doc({
            supplier: { name: 'Acme', contact: [{ name: 'Ops', email: 'ops@acme.example' }] },
            manufacturer: { name: 'Acme', contact: [{ email: 'm@acme.example' }] }
        }, { services: [{ 'bom-ref': 's', name: 's', provider: { name: 'Example Corp', contact: [{ name: 'Ops', email: 'ops@example.com', phone: '+1 555 0100' }] } }] }));
        expect(r.bom.components[0].supplier).toStrictEqual({ name: 'Acme', contact: [{ name: 'Ops' }] });
        expect(r.bom.components[0].manufacturer).toStrictEqual({ name: 'Acme' });
        expect(r.bom.services[0].provider).toStrictEqual({ name: 'Example Corp', contact: [{ name: 'Ops' }] });
        expect(r.personalData).toBe(4);
    });

    it('S10: credentials leave the userinfo of every listed URL field, the rest of the URL stays', () => {
        const r = stripPersonalDataAndLocalPaths(doc({
            externalReferences: [{ type: 'vcs', url: 'https://deploy:s3cret@git.example.com/org/repo.git' }, { type: 'vcs', url: 'https://ghp_abc@github.com/org/repo.git' }],
            properties: [
                prop('ResolvedUrl', 'https://x-access-token:ghp_example@npm.pkg.github.com/@org/pkg/-/pkg-1.0.0.tgz'),
                prop('cdx:foo:note', 'see https://u:p@h.example/x for details'),
                prop('cdx:foo:registry', 'https://u:p@registry.example.com'),
                prop('cdx:foo:at', 'https://u:p@ss@h.example/x'),
                prop('cdx:foo:rawat', 'ssh://git@ghp_tok@github.com/org/private.git'),
                prop('cdx:foo:assigned', 'registry=https://tok@r.example/x')
            ],
            licenses: [{ license: { id: 'MIT', url: 'https://u:p@licenses.example/mit' } }],
            supplier: { name: 'Acme', url: ['https://u:p@acme.example/', 'https://acme.example/'] }
        }, { services: [{ 'bom-ref': 's', name: 's', endpoints: ['https://svc:secret@api.example.com/v1', 'https://api.example.com/v2'], provider: { name: 'P', url: ['https://u:p@p.example'] } }] }));
        const c = r.bom.components[0];
        expect(c.externalReferences.map((e: any) => e.url)).toStrictEqual(['https://git.example.com/org/repo.git', 'https://github.com/org/repo.git']);
        expect(c.properties).toStrictEqual([
            prop('ResolvedUrl', 'https://npm.pkg.github.com/@org/pkg/-/pkg-1.0.0.tgz'),
            prop('cdx:foo:note', 'see https://h.example/x for details'),
            prop('cdx:foo:registry', 'https://registry.example.com'),
            prop('cdx:foo:at', 'https://h.example/x'),
            prop('cdx:foo:rawat', 'ssh://github.com/org/private.git'),
            prop('cdx:foo:assigned', 'registry=https://r.example/x')
        ]);
        expect(c.licenses[0].license.url).toBe('https://licenses.example/mit');
        expect(c.supplier.url).toStrictEqual(['https://acme.example/', 'https://acme.example/']);
        expect(r.bom.services[0].endpoints).toStrictEqual(['https://api.example.com/v1', 'https://api.example.com/v2']);
        expect(r.bom.services[0].provider.url).toStrictEqual(['https://p.example']);
        // F-14: the credential URL without a path is a credential, not an address
        expect([r.personalData, r.localPaths, r.credentials]).toStrictEqual([0, 0, 12]);
    });

    it('S10 then S2: a value that still carries an address after the edit goes as personal data, counted once', () => {
        expect(onProperties(prop('cdx:foo:x', 'https://u:p@h.example/x by jane@example.org'))).toMatchObject({ kept: [], personalData: 1, credentials: 0 });
    });

    it('walks nested components, the root, tools and nested services', () => {
        const bom = {
            metadata: {
                component: { name: 'root', 'bom-ref': 'root', author: 'R <r@x.org>' },
                tools: { components: [{ name: 'gen', authors: [{ name: 'G', email: 'g@x.org' }] }], services: [{ name: 'ts', endpoints: ['https://a:b@t.example/'] }] }
            },
            components: [{ name: 'p', 'bom-ref': 'p', components: [{ name: 'n', 'bom-ref': 'n', properties: [prop('LocalNodeModulesPath', 'node_modules/n')] }] }],
            services: [{ name: 's', services: [{ name: 'inner', properties: [prop('cdx:npm:publisher', 'x'), prop('LocalNodeModulesPath', 'kept-on-a-service')] }] }]
        };
        const r = stripPersonalDataAndLocalPaths(bom);
        expect(r.bom.metadata.component.author).toBe('R');
        expect(r.bom.metadata.tools.components[0].authors).toStrictEqual([{ name: 'G' }]);
        expect(r.bom.metadata.tools.services[0].endpoints).toStrictEqual(['https://t.example/']);
        expect(r.bom.components[0].components[0].properties).toStrictEqual([]);
        // S6 and S7 do not apply to a service; S1 does
        expect(r.bom.services[0].services[0].properties).toStrictEqual([prop('LocalNodeModulesPath', 'kept-on-a-service')]);
        expect([r.personalData, r.localPaths, r.credentials]).toStrictEqual([3, 1, 1]);
        // the input is not mutated
        expect(bom.metadata.component.author).toBe('R <r@x.org>');
    });

    it('never reads the purl', () => {
        const bom = doc({ purl: 'pkg:generic/x@1.0?download_url=https%3A%2F%2Fu%3Ap%40h.example%2Fx.tgz' });
        expect(stripPersonalDataAndLocalPaths(bom).bom).toBe(bom);
    });
});

describe('stripPersonalDataAndLocalPaths negatives (FDAW-6 T4)', () => {
    it('S9 and the negatives of S2, S4 and S10 are left as uploaded, and the input itself is returned', () => {
        const bom = {
            metadata: {
                component: { name: 'root', 'bom-ref': 'root' },
                tools: { components: [{ name: 'cdxgen', publisher: 'OWASP Foundation' }] }
            },
            components: [{
                type: 'library', name: 'c', version: '1.0.0', 'bom-ref': 'pkg:generic/c@1.0?download_url=file:///x', purl: 'pkg:generic/c@1.0?download_url=file:///x',
                description: 'Maintained by jane@example.org',
                publisher: 'OWASP Foundation',
                author: 'Jane Doe (https://example.org/@jane)',
                evidence: { occurrences: [{ location: '/usr/lib/x.so' }] },
                externalReferences: [{ type: 'vcs', url: 'git+ssh://git@github.com/org/repo.git' }],
                properties: [
                    prop('syft:location:0:path', '/usr/lib/x.so'),
                    prop('cdx:foo:ssh', 'ssh://git@github.com:22/org/repo.git'),
                    prop('cdx:foo:ftp', 'ftp://anonymous@ftp.example.org/pub/x'),
                    prop('ResolvedUrl', 'https://registry.npmjs.org/glob/-/glob-7.2.3.tgz'),
                    prop('ResolvedUrl', 'https://registry.npmjs.org/@babel/code-frame/-/code-frame-7.29.7.tgz'),
                    prop('cdx:foo:vcs', 'git+ssh://git@github.com/org/repo.git'),
                    prop('cdx:foo:scp', 'git@github.com:org/repo.git'),
                    prop('SrcFile', 'package-lock.json'),
                    prop('SrcFile', 'requirements-dev.txt'),
                    prop('cdx:foo:advice', 'use uuid@11 or pkg@1.2.3 instead'),
                    prop('cdx:npm:artifactShasum', 'b8df0fb802bbfa8e89bd1d938b4e16578ed44f2b')
                ]
            }, { type: 'library', name: 'rpm', publisher: 'Red Hat, Inc.' }],
            services: [{ name: 's', provider: { name: 'Example Corp' } }]
        };
        const result = stripPersonalDataAndLocalPaths(bom);
        expect(result.bom).toBe(bom);
        expect([result.personalData, result.localPaths, result.credentials]).toStrictEqual([0, 0, 0]);
    });

    it('leaves ReARM own tool entry as rebom wrote it, its corporate contact included', () => {
        const rebom = createRebomToolObject('1.6');
        const bom = { metadata: { component: { name: 'root' }, tools: { components: [rebom] } }, components: [] };
        expect(rebom.authors[0].email).toBeDefined();
        expect(stripPersonalDataAndLocalPaths(bom)).toStrictEqual({ bom, personalData: 0, localPaths: 0, credentials: 0 });
    });

    it('ADDRESS_PATTERN and CREDENTIAL_URL_PATTERN stay linear on long adversarial input', () => {
        const long = 'a'.repeat(200000);
        const started = Date.now();
        ADDRESS_PATTERN.test(`${long}@${long}`);
        ADDRESS_PATTERN.test(`${'a.'.repeat(100000)}`);
        new RegExp(CREDENTIAL_URL_PATTERN.source, 'g').test(`${long}://${long}`);
        stripAddressTokens(`${'<'.repeat(50000)}a@b.co`);
        expect(Date.now() - started).toBeLessThan(2000);
    });
});

describe('stripPurlCredentials (FDAW-6 T3, review F-12, F-13)', () => {
    it('cuts the userinfo bytes of a URL qualifier only, keeping every other byte of the purl', () => {
        expect(stripPurlQualifierCredentials('pkg:generic/x@1.0?download_url=https%3A%2F%2Fu%3Ap%40h.example%2Fx.tgz'))
            .toBe('pkg:generic/x@1.0?download_url=https%3A%2F%2Fh.example%2Fx.tgz');
        expect(stripPurlQualifierCredentials('pkg:generic/r@1.0?vcs_url=git+https://u:p@h.example/r.git&checksum=sha256:ab'))
            .toBe('pkg:generic/r@1.0?vcs_url=git+https://h.example/r.git&checksum=sha256:ab');
        expect(stripPurlQualifierCredentials('pkg:pypi/foo@1.0%2Blocal?repository_url=https://tok@pypi.example/simple#sub/path'))
            .toBe('pkg:pypi/foo@1.0%2Blocal?repository_url=https://pypi.example/simple#sub/path');
        // the last @ of the authority
        expect(stripPurlQualifierCredentials('pkg:generic/x@1?download_url=https%3A%2F%2Fu%3Ap%40ss%40h%2Fx'))
            .toBe('pkg:generic/x@1?download_url=https%3A%2F%2Fh%2Fx');
        // negatives: a login without a password in ssh, a qualifier that is not a URL qualifier, no qualifier at all
        for (const purl of ['pkg:generic/x@1?vcs_url=git+ssh://git@github.com/o/r.git', 'pkg:generic/x@1?other=https://u:p@h/x', 'pkg:npm/a@1.0.0', 'pkg:generic/x@1?download_url=file:///x']) {
            expect(stripPurlQualifierCredentials(purl)).toBe(purl);
        }
    });

    it('renames a byte-equal bom-ref and every reference to it, counting once per node', () => {
        const old = 'pkg:generic/x@1.0?download_url=https%3A%2F%2Fu%3Ap%40h.example%2Fx.tgz';
        const next = 'pkg:generic/x@1.0?download_url=https%3A%2F%2Fh.example%2Fx.tgz';
        const bom = {
            metadata: { component: { name: 'root', 'bom-ref': 'root' } },
            components: [{ name: 'x', version: '1.0', purl: old, 'bom-ref': old }],
            dependencies: [{ ref: 'root', dependsOn: [old] }, { ref: old, dependsOn: [] }]
        };
        const r = stripPurlCredentials(bom);
        expect(r.credentials).toBe(1);
        expect(r.bom.components[0]).toMatchObject({ purl: next, 'bom-ref': next });
        expect(r.bom.dependencies).toStrictEqual([{ ref: 'root', dependsOn: [next] }, { ref: next, dependsOn: [] }]);
        expect(bom.components[0].purl).toBe(old);
    });

    it('edits the root and a purl-shaped bom-ref that differs from the purl, and returns the input when nothing matches', () => {
        const root = 'pkg:npm/x@1.0?belongsto=RELEASE&vcs_url=https%3A%2F%2Fu%3Ap%40h.example%2Fr.git';
        const bom = {
            metadata: { component: { name: 'x', purl: root, 'bom-ref': root } },
            components: [{ name: 'y', 'bom-ref': 'pkg:npm/y@1?vcs_url=https://t@h.example/y#2' }],
            dependencies: [{ ref: root, dependsOn: ['pkg:npm/y@1?vcs_url=https://t@h.example/y#2'] }]
        };
        const r = stripPurlCredentials(bom);
        expect(r.credentials).toBe(2);
        expect(r.bom.metadata.component['bom-ref']).toBe('pkg:npm/x@1.0?belongsto=RELEASE&vcs_url=https%3A%2F%2Fh.example%2Fr.git');
        expect(r.bom.dependencies[0]).toStrictEqual({ ref: 'pkg:npm/x@1.0?belongsto=RELEASE&vcs_url=https%3A%2F%2Fh.example%2Fr.git', dependsOn: ['pkg:npm/y@1?vcs_url=https://h.example/y#2'] });
        const clean = { metadata: { component: { name: 'a', purl: 'pkg:npm/a@1' } }, components: [{ name: 'b', purl: 'pkg:npm/b@1' }] };
        expect(stripPurlCredentials(clean)).toStrictEqual({ bom: clean, credentials: 0 });
    });

    it('never edits a bom-ref that is not purl-shaped, even one that carries a query string that looks like a URL qualifier', () => {
        const ref = 'urn:custom:thing?repository_url=https://u:p@h.example/x';
        const bom = {
            metadata: { component: { name: 'root', 'bom-ref': 'root' } },
            components: [{ name: 'x', 'bom-ref': ref }]
        };
        expect(stripPurlCredentials(bom)).toStrictEqual({ bom, credentials: 0 });
    });
});
