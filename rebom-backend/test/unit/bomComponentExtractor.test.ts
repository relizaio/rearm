import { describe, it, expect } from 'vitest';
import {
	canonicalizePurl,
	extractBomComponents,
	parseBom,
} from '../../src/services/bom/bomComponentExtractor';

describe('bomComponentExtractor.canonicalizePurl', () => {
	it('preserves distro but strips arch for apk', () => {
		const raw = 'pkg:apk/alpine/alpine-baselayout-data@3.6.5-r0?arch=x86_64&distro=alpine-3.20.5';
		expect(canonicalizePurl(raw)).toBe(
			'pkg:apk/alpine/alpine-baselayout-data@3.6.5-r0?distro=alpine-3.20.5');
	});

	it('preserves distro but strips arch for deb', () => {
		const raw = 'pkg:deb/debian/curl@7.50.3-1?arch=i386&distro=debian-9';
		expect(canonicalizePurl(raw)).toBe('pkg:deb/debian/curl@7.50.3-1?distro=debian-9');
	});

	it('preserves distro and epoch for rpm, strips arch', () => {
		const raw = 'pkg:rpm/fedora/curl@7.50.3-1.fc25?arch=i386&distro=fedora-25&epoch=1';
		expect(canonicalizePurl(raw)).toBe(
			'pkg:rpm/fedora/curl@7.50.3-1.fc25?distro=fedora-25&epoch=1');
	});

	it('canonicalizes an apk purl without distro into its bare form', () => {
		const raw = 'pkg:apk/alpine/openssl@3.5.7-r0?arch=x86_64';
		expect(canonicalizePurl(raw)).toBe('pkg:apk/alpine/openssl@3.5.7-r0');
	});

	it('produces the same canonical regardless of input qualifier order', () => {
		expect(canonicalizePurl('pkg:apk/alpine/openssl@3.5.7-r0?arch=x86_64&distro=alpine-3.24.1'))
			.toBe(canonicalizePurl('pkg:apk/alpine/openssl@3.5.7-r0?distro=alpine-3.24.1&arch=x86_64'));
		const rpmPermutations = [
			'pkg:rpm/fedora/curl@7.50.3-1.fc25?arch=i386&distro=fedora-25&epoch=1',
			'pkg:rpm/fedora/curl@7.50.3-1.fc25?epoch=1&arch=i386&distro=fedora-25',
			'pkg:rpm/fedora/curl@7.50.3-1.fc25?epoch=1&distro=fedora-25&arch=i386',
			'pkg:rpm/fedora/curl@7.50.3-1.fc25?distro=fedora-25&epoch=1&arch=i386',
		].map(canonicalizePurl);
		expect(new Set(rpmPermutations).size).toBe(1);
		expect(rpmPermutations[0]).toBe('pkg:rpm/fedora/curl@7.50.3-1.fc25?distro=fedora-25&epoch=1');
	});

	it('strips subpath from a purl', () => {
		const raw = 'pkg:golang/github.com/foo/bar@v1.2.3?go-version=1.22#pkg/util';
		expect(canonicalizePurl(raw)).toBe('pkg:golang/github.com/foo/bar@v1.2.3');
	});

	it('returns null for an empty/missing purl', () => {
		expect(canonicalizePurl(undefined)).toBeNull();
		expect(canonicalizePurl('')).toBeNull();
		expect(canonicalizePurl(null)).toBeNull();
	});

	it('returns null for an unparseable purl', () => {
		expect(canonicalizePurl('not-a-purl')).toBeNull();
	});

	it('canonicalizes a purl without qualifiers into itself', () => {
		const raw = 'pkg:npm/foo@1.0.0';
		expect(canonicalizePurl(raw)).toBe('pkg:npm/foo@1.0.0');
	});

	it('preserves the required uuid qualifier for julia', () => {
		const raw = 'pkg:julia/Dates@1.9.0?uuid=ade2ca70-3b73-5b8e-9b35-2c0d1c0e1f2a&os=linux';
		expect(canonicalizePurl(raw)).toBe(
			'pkg:julia/Dates@1.9.0?uuid=ade2ca70-3b73-5b8e-9b35-2c0d1c0e1f2a');
	});

	it('preserves the required tag_id qualifier for swid', () => {
		const raw = 'pkg:swid/Acme/Enterprise%20Server@1.0.0?tag_id=75b8c285-fa7b-485b-b199-4745e3004d0d&arch=x64';
		expect(canonicalizePurl(raw)).toBe(
			'pkg:swid/Acme/Enterprise%20Server@1.0.0?tag_id=75b8c285-fa7b-485b-b199-4745e3004d0d');
	});

	it('preserves repository_url for oci and strips other qualifiers', () => {
		const raw = 'pkg:oci/myapp@sha256%3Aabc?repository_url=ghcr.io%2Facme%2Fmyapp&tag=latest';
		expect(canonicalizePurl(raw)).toBe(
			'pkg:oci/myapp@sha256:abc?repository_url=ghcr.io%2Facme%2Fmyapp');
	});

	it('still strips qualifiers for types without a required qualifier', () => {
		const raw = 'pkg:npm/foo@1.0.0?arch=any&os=linux';
		expect(canonicalizePurl(raw)).toBe('pkg:npm/foo@1.0.0');
	});
});

describe('bomComponentExtractor.parseBom', () => {
	it('returns empty arrays when bom is missing or typeless', () => {
		expect(parseBom(null)).toEqual({ components: [], dependencies: [] });
		expect(parseBom({})).toEqual({ components: [], dependencies: [] });
	});

	it('extracts canonical + full purls for every component with a purl', () => {
		const bom = {
			components: [
				{ type: 'library', name: 'foo', version: '1.0', purl: 'pkg:npm/foo@1.0?arch=any' },
				{ type: 'library', name: 'bar', version: '2.0', purl: 'pkg:npm/bar@2.0' },
			],
		};
		const got = parseBom(bom);
		expect(got.dependencies).toEqual([]);
		expect(got.components).toHaveLength(2);
		expect(got.components[0]).toMatchObject({
			canonicalPurl: 'pkg:npm/foo@1.0',
			fullPurl: 'pkg:npm/foo@1.0?arch=any',
			type: 'npm',
			name: 'foo',
			version: '1.0',
			isRoot: false,
		});
	});

	it('captures cpe and passes licenses through in exact CycloneDX shape', () => {
		const bom = {
			components: [
				{
					purl: 'pkg:npm/withmeta@1.0',
					cpe: 'cpe:2.3:a:vendor:withmeta:1.0:*:*:*:*:*:*:*',
					licenses: [
						{ license: { id: 'MIT' } },
						{ license: { name: 'Custom License' } },
						{ expression: '(Apache-2.0 OR MIT)' },
					],
				},
				{ purl: 'pkg:npm/bare@1.0' },
				{ purl: 'pkg:npm/garbage@1.0', licenses: ['not-an-object', null] },
			],
		};
		const got = parseBom(bom);
		const withMeta = got.components.find((c) => c.canonicalPurl === 'pkg:npm/withmeta@1.0');
		expect(withMeta?.cpe).toBe('cpe:2.3:a:vendor:withmeta:1.0:*:*:*:*:*:*:*');
		// structural passthrough — id/name/expression distinction preserved
		expect(withMeta?.licenses).toEqual([
			{ license: { id: 'MIT' } },
			{ license: { name: 'Custom License' } },
			{ expression: '(Apache-2.0 OR MIT)' },
		]);
		const bare = got.components.find((c) => c.canonicalPurl === 'pkg:npm/bare@1.0');
		expect(bare?.cpe).toBeNull();
		expect(bare?.licenses).toEqual([]);
		// non-object entries are dropped
		const garbage = got.components.find((c) => c.canonicalPurl === 'pkg:npm/garbage@1.0');
		expect(garbage?.licenses).toEqual([]);
	});

	it('carries cpe/licenses (structural) onto the synthesised root node', () => {
		const bom = {
			metadata: {
				component: {
					purl: 'pkg:oci/myapp@1.0.0',
					cpe: 'cpe:2.3:a:acme:myapp:1.0.0:*:*:*:*:*:*:*',
					licenses: [{ license: { id: 'Apache-2.0' } }],
				},
			},
		};
		const root = parseBom(bom).components.find((c) => c.isRoot);
		expect(root?.cpe).toBe('cpe:2.3:a:acme:myapp:1.0.0:*:*:*:*:*:*:*');
		expect(root?.licenses).toEqual([{ license: { id: 'Apache-2.0' } }]);
	});

	it('drops components without a purl but keeps the rest', () => {
		const bom = {
			components: [
				{ name: 'no-purl', version: '1.0' },
				{ name: 'has-purl', version: '1.0', purl: 'pkg:npm/has-purl@1.0' },
			],
		};
		const got = parseBom(bom);
		expect(got.components).toHaveLength(1);
		expect(got.components[0].canonicalPurl).toBe('pkg:npm/has-purl@1.0');
	});

	it('emits a CPE-canonical component when it has a cpe but no purl', () => {
		const bom = {
			components: [
				{
					type: 'application',
					name: 'openssl',
					version: '1.0.1',
					cpe: 'cpe:2.3:a:openssl:openssl:1.0.1:*:*:*:*:*:*:*',
				},
			],
		};
		const got = parseBom(bom);
		expect(got.components).toHaveLength(1);
		expect(got.components[0]).toMatchObject({
			canonicalPurl: 'cpe:2.3:a:openssl:openssl:1.0.1:*:*:*:*:*:*:*',
			fullPurl: 'cpe:2.3:a:openssl:openssl:1.0.1:*:*:*:*:*:*:*',
			cpe: 'cpe:2.3:a:openssl:openssl:1.0.1:*:*:*:*:*:*:*',
			name: 'openssl',
			version: '1.0.1',
			isRoot: false,
		});
	});

	it('still drops a component with neither purl nor cpe', () => {
		const bom = { components: [{ name: 'device-only', version: '1.0' }] };
		expect(parseBom(bom).components).toHaveLength(0);
	});

	it('prefers purl over cpe when a component has both', () => {
		const bom = {
			components: [
				{ purl: 'pkg:npm/foo@1.0', cpe: 'cpe:2.3:a:vendor:foo:1.0:*:*:*:*:*:*:*' },
			],
		};
		const got = parseBom(bom);
		expect(got.components).toHaveLength(1);
		expect(got.components[0].canonicalPurl).toBe('pkg:npm/foo@1.0');
		expect(got.components[0].cpe).toBe('cpe:2.3:a:vendor:foo:1.0:*:*:*:*:*:*:*');
	});

	it('de-duplicates identical (canonical, full) pairs', () => {
		const bom = {
			components: [
				{ purl: 'pkg:npm/foo@1.0?arch=any' },
				{ purl: 'pkg:npm/foo@1.0?arch=any' },
			],
		};
		expect(parseBom(bom).components).toHaveLength(1);
	});

	it('synthesises a first-class root node from metadata.component when it has a purl', () => {
		const bom = {
			metadata: {
				component: {
					'bom-ref': 'root-ref',
					purl: 'pkg:oci/myapp@1.0.0?digest=sha256%3Aabc',
					name: 'myapp',
					version: '1.0.0',
				},
			},
			components: [
				{ 'bom-ref': 'c-1', purl: 'pkg:npm/foo@1.0' },
			],
		};
		const got = parseBom(bom);
		expect(got.components).toHaveLength(2);
		const root = got.components.find((c) => c.isRoot);
		expect(root).toBeTruthy();
		expect(root?.canonicalPurl).toBe('pkg:oci/myapp@1.0.0');
		expect(root?.fullPurl).toBe('pkg:oci/myapp@1.0.0?digest=sha256%3Aabc');
	});

	it('skips the root when metadata.component has no purl', () => {
		const bom = {
			metadata: { component: { 'bom-ref': 'root-ref', name: 'myapp' } },
			components: [{ 'bom-ref': 'c-1', purl: 'pkg:npm/foo@1.0' }],
		};
		const got = parseBom(bom);
		expect(got.components).toHaveLength(1);
		expect(got.components[0].isRoot).toBe(false);
	});

	it('resolves dependency refs through bom-ref and records edges', () => {
		const bom = {
			metadata: {
				component: {
					'bom-ref': 'root-ref',
					purl: 'pkg:oci/myapp@1.0.0',
				},
			},
			components: [
				{ 'bom-ref': 'c-1', purl: 'pkg:npm/foo@1.0?arch=x64' },
				{ 'bom-ref': 'c-2', purl: 'pkg:npm/bar@2.0' },
			],
			dependencies: [
				{ ref: 'root-ref', dependsOn: ['c-1'] },
				{ ref: 'c-1', dependsOn: ['c-2'] },
			],
		};
		const got = parseBom(bom);
		expect(got.dependencies).toHaveLength(2);
		const rootEdge = got.dependencies.find(
			(d) => d.sourceCanonicalPurl === 'pkg:oci/myapp@1.0.0'
		);
		expect(rootEdge).toMatchObject({
			targetCanonicalPurl: 'pkg:npm/foo@1.0',
			sourceFullPurl: 'pkg:oci/myapp@1.0.0',
			targetFullPurl: 'pkg:npm/foo@1.0?arch=x64',
			relationshipType: 'DEPENDS_ON',
		});
	});

	it('resolves deps when refs are the purls themselves (no separate bom-ref)', () => {
		const bom = {
			components: [
				{ 'bom-ref': 'pkg:npm/foo@1.0', purl: 'pkg:npm/foo@1.0' },
				{ 'bom-ref': 'pkg:npm/bar@2.0', purl: 'pkg:npm/bar@2.0' },
			],
			dependencies: [{ ref: 'pkg:npm/foo@1.0', dependsOn: ['pkg:npm/bar@2.0'] }],
		};
		const got = parseBom(bom);
		expect(got.dependencies).toHaveLength(1);
		expect(got.dependencies[0]).toMatchObject({
			sourceCanonicalPurl: 'pkg:npm/foo@1.0',
			targetCanonicalPurl: 'pkg:npm/bar@2.0',
		});
	});

	it('drops edges whose source or target is unresolvable (no purl, orphan ref)', () => {
		const bom = {
			components: [
				// foo has a purl and a bom-ref.
				{ 'bom-ref': 'c-foo', purl: 'pkg:npm/foo@1.0' },
				// bar has a bom-ref but no purl — must not be resolvable as an edge endpoint.
				{ 'bom-ref': 'c-bar', name: 'bar-with-no-purl' },
			],
			dependencies: [
				// foo → bar (bar unresolvable; drop)
				{ ref: 'c-foo', dependsOn: ['c-bar'] },
				// bar → foo (bar unresolvable as source; drop)
				{ ref: 'c-bar', dependsOn: ['c-foo'] },
				// foo → orphan (orphan unknown; drop)
				{ ref: 'c-foo', dependsOn: ['c-ghost'] },
			],
		};
		expect(parseBom(bom).dependencies).toEqual([]);
	});

	it('honours dependency.type when present and uppercases it', () => {
		const bom = {
			components: [
				{ 'bom-ref': 'a', purl: 'pkg:npm/a@1' },
				{ 'bom-ref': 'b', purl: 'pkg:npm/b@1' },
			],
			dependencies: [{ ref: 'a', dependsOn: ['b'], type: 'provides' }],
		};
		expect(parseBom(bom).dependencies[0].relationshipType).toBe('PROVIDES');
	});

	it('de-duplicates identical edges', () => {
		const bom = {
			components: [
				{ 'bom-ref': 'a', purl: 'pkg:npm/a@1' },
				{ 'bom-ref': 'b', purl: 'pkg:npm/b@1' },
			],
			dependencies: [
				{ ref: 'a', dependsOn: ['b', 'b'] },
				{ ref: 'a', dependsOn: ['b'] },
			],
		};
		expect(parseBom(bom).dependencies).toHaveLength(1);
	});
});

describe('bomComponentExtractor.extractBomComponents (legacy shim)', () => {
	it('still returns just the components list', () => {
		const bom = { components: [{ purl: 'pkg:npm/foo@1.0' }] };
		expect(extractBomComponents(bom)).toHaveLength(1);
	});
});
