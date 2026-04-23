import { describe, it, expect } from 'vitest';
import {
	canonicalizePurl,
	extractBomComponents,
	parseBom,
} from '../../src/services/bom/bomComponentExtractor';

describe('bomComponentExtractor.canonicalizePurl', () => {
	it('strips qualifiers from a purl', () => {
		const raw = 'pkg:apk/alpine/alpine-baselayout-data@3.6.5-r0?arch=x86_64&distro=alpine-3.20.5';
		expect(canonicalizePurl(raw)).toBe('pkg:apk/alpine/alpine-baselayout-data@3.6.5-r0');
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
