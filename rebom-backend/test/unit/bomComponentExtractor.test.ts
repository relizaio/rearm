import { describe, it, expect } from 'vitest';
import {
	canonicalizePurl,
	extractBomComponents,
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

	it('percent-decodes namespace in canonical form (normalisation)', () => {
		// Namespace encoded in original; canonical produced via packageurl-js round trip.
		const raw = 'pkg:npm/%40cyclonedx/cdxgen@11.0.10?arch=any';
		expect(canonicalizePurl(raw)).toBe('pkg:npm/%40cyclonedx/cdxgen@11.0.10');
	});
});

describe('bomComponentExtractor.extractBomComponents', () => {
	it('returns an empty array when bom has no components', () => {
		expect(extractBomComponents({})).toEqual([]);
		expect(extractBomComponents({ components: null })).toEqual([]);
		expect(extractBomComponents(null)).toEqual([]);
	});

	it('extracts canonical + full purl for each component', () => {
		const bom = {
			components: [
				{ type: 'library', name: 'foo', version: '1.0', purl: 'pkg:npm/foo@1.0?arch=any' },
				{ type: 'library', name: 'bar', version: '2.0', purl: 'pkg:npm/bar@2.0' },
			],
		};
		const got = extractBomComponents(bom);
		expect(got).toHaveLength(2);
		expect(got[0]).toMatchObject({
			canonicalPurl: 'pkg:npm/foo@1.0',
			fullPurl: 'pkg:npm/foo@1.0?arch=any',
			type: 'npm',
			name: 'foo',
			version: '1.0',
		});
		expect(got[1]).toMatchObject({
			canonicalPurl: 'pkg:npm/bar@2.0',
			fullPurl: 'pkg:npm/bar@2.0',
		});
	});

	it('skips components without a purl', () => {
		const bom = {
			components: [
				{ name: 'no-purl', version: '1.0' },
				{ name: 'has-purl', version: '1.0', purl: 'pkg:npm/has-purl@1.0' },
			],
		};
		const got = extractBomComponents(bom);
		expect(got).toHaveLength(1);
		expect(got[0].canonicalPurl).toBe('pkg:npm/has-purl@1.0');
	});

	it('de-duplicates identical (canonical, full) pairs within a BOM', () => {
		const bom = {
			components: [
				{ purl: 'pkg:npm/foo@1.0?arch=any' },
				{ purl: 'pkg:npm/foo@1.0?arch=any' }, // exact dup
			],
		};
		expect(extractBomComponents(bom)).toHaveLength(1);
	});

	it('keeps one row per distinct full purl when they share canonical', () => {
		const bom = {
			components: [
				{ purl: 'pkg:npm/foo@1.0?arch=x86_64' },
				{ purl: 'pkg:npm/foo@1.0?arch=arm64' },
			],
		};
		const got = extractBomComponents(bom);
		expect(got).toHaveLength(2);
		expect(got.every((c) => c.canonicalPurl === 'pkg:npm/foo@1.0')).toBe(true);
		expect(got.map((c) => c.fullPurl).sort()).toEqual([
			'pkg:npm/foo@1.0?arch=arm64',
			'pkg:npm/foo@1.0?arch=x86_64',
		]);
	});

	it('skips components whose purl is unparseable', () => {
		const bom = {
			components: [
				{ purl: 'broken-purl' },
				{ purl: 'pkg:npm/ok@1.0' },
			],
		};
		const got = extractBomComponents(bom);
		expect(got).toHaveLength(1);
		expect(got[0].canonicalPurl).toBe('pkg:npm/ok@1.0');
	});
});
