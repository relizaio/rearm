/**
 * SBOM component + dependency extraction.
 *
 * Parses a CycloneDX BOM into two structures consumed by ReARM backend:
 *
 *   - components: one entry per component (including the BOM's root from
 *     metadata.component when it has a purl of its own) that carries a
 *     purl. Each entry splits the purl into a canonical form (scheme +
 *     type + namespace + name + version, qualifiers and subpath stripped)
 *     and the original full purl (qualifiers preserved). Components
 *     synthesised from metadata.component carry `isRoot: true` so that
 *     the backend can flag them when upserting sbom_components.
 *
 *   - dependencies: one entry per edge declared in `bom.dependencies[]`.
 *     Each bom-ref on either side is resolved through a bom-ref → purl
 *     map built from the components scanned above. Edges whose source
 *     or target cannot be resolved (either because the ref maps to a
 *     component without a purl, or because it's an orphan ref) are
 *     silently dropped — the backend is allowed to assume every edge
 *     has a real component node on both ends.
 *
 * Purls are optional in SBOM specs; any component without a purl is
 * excluded from the output, which means any dependency edge touching
 * such a component is also excluded by virtue of unresolved ref lookup.
 * That single rule covers both sides.
 */
import { PackageURL } from 'packageurl-js';
import { logger } from '../../logger';

export interface ParsedBomComponent {
	canonicalPurl: string;
	fullPurl: string;
	type: string | null;
	group: string | null;
	name: string | null;
	version: string | null;
	isRoot: boolean;
	// cpe carried alongside the purl so downstream vuln matching can match on
	// either coordinate (NVD/CPE-keyed advisories vs purl-keyed ones). null when
	// the BOM declared no cpe for the component.
	cpe: string | null;
	// Declared licenses in EXACT CycloneDX shape: each item is
	// { license: { id | name, text?, url?, ... } } or { expression }. Passed
	// through structurally (not flattened to strings) so the precise id/name/
	// expression distinction is preserved and can be re-emitted to / matched by
	// Dependency-Track. Always an array (possibly empty).
	licenses: any[];
}

export interface ParsedBomDependency {
	sourceCanonicalPurl: string;
	sourceFullPurl: string;
	targetCanonicalPurl: string;
	targetFullPurl: string;
	relationshipType: string;
}

export interface ParsedBom {
	components: ParsedBomComponent[];
	dependencies: ParsedBomDependency[];
}

/**
 * purl types whose identity is incomplete without a specific qualifier — the
 * purl-spec marks these `requirement: "required"` (julia/swid), plus oci where
 * the registry must be preserved to disambiguate the image. Canonicalization
 * keeps ONLY the listed qualifier for these types and strips everything else.
 */
const PRESERVED_QUALIFIERS: Record<string, string> = {
	julia: 'uuid',
	swid: 'tag_id',
	oci: 'repository_url',
};

/**
 * Strip qualifiers and subpath from a purl, leaving the canonical
 * identity part: pkg:<type>/<namespace>/<name>@<version>. For purl types in
 * {@link PRESERVED_QUALIFIERS} the one required qualifier is retained (DTrack
 * and OSV match on the qualifier-stripped purl, but these types lose identity
 * without it). Returns null if the input is missing or unparseable.
 */
export function canonicalizePurl(rawPurl: string | undefined | null): string | null {
	if (!rawPurl) return null;
	try {
		const parsed = PackageURL.fromString(rawPurl);
		const preserveKey = PRESERVED_QUALIFIERS[parsed.type];
		let qualifiers: { [k: string]: string } | undefined = undefined;
		if (preserveKey && parsed.qualifiers && (parsed.qualifiers as any)[preserveKey] != null) {
			qualifiers = { [preserveKey]: String((parsed.qualifiers as any)[preserveKey]) };
		}
		const canonical = new PackageURL(
			parsed.type,
			parsed.namespace ?? undefined,
			parsed.name,
			parsed.version ?? undefined,
			qualifiers,
			undefined
		);
		return canonical.toString();
	} catch (err) {
		logger.debug({ err, rawPurl }, 'Unable to canonicalize purl; skipping');
		return null;
	}
}

/**
 * Pass a CycloneDX component.licenses array through structurally, preserving the
 * exact shape (each item is { license: { id | name, ... } } or { expression }).
 * Only well-formed object entries are kept; the array is returned verbatim
 * otherwise so it can be re-emitted to Dependency-Track unchanged.
 */
function extractLicenses(raw: { licenses?: any } | undefined): any[] {
	const arr = raw?.licenses;
	if (!Array.isArray(arr)) return [];
	return arr.filter((entry) => entry && typeof entry === 'object');
}

function toParsedComponent(
	rawPurl: string,
	fallback: { group?: any; name?: any; version?: any; cpe?: any; licenses?: any } | undefined,
	isRoot: boolean
): ParsedBomComponent | null {
	const canonicalPurl = canonicalizePurl(rawPurl);
	if (!canonicalPurl) return null;
	let parsed: PackageURL | null = null;
	try {
		parsed = PackageURL.fromString(rawPurl);
	} catch (_) {
		parsed = null;
	}
	return {
		canonicalPurl,
		fullPurl: rawPurl,
		type: parsed?.type ?? null,
		group:
			parsed?.namespace ??
			(typeof fallback?.group === 'string' ? fallback.group : null),
		name:
			parsed?.name ?? (typeof fallback?.name === 'string' ? fallback.name : null),
		version:
			parsed?.version ??
			(typeof fallback?.version === 'string' ? fallback.version : null),
		isRoot,
		cpe: typeof fallback?.cpe === 'string' && fallback.cpe.length > 0 ? fallback.cpe : null,
		licenses: extractLicenses(fallback),
	};
}

/**
 * Parse components + dependencies out of a CycloneDX BOM in one pass.
 * Safe to call with anything non-object / missing fields — always returns
 * the two arrays (possibly empty).
 */
export function parseBom(bom: any): ParsedBom {
	const out: ParsedBom = { components: [], dependencies: [] };
	if (!bom || typeof bom !== 'object') return out;

	// bom-ref → { canonicalPurl, fullPurl } index used for resolving edges.
	const refMap = new Map<string, { canonicalPurl: string; fullPurl: string }>();
	const seenCanonicalFull = new Set<string>();

	const pushComponent = (pc: ParsedBomComponent | null, ref: string | undefined) => {
		if (!pc) return;
		const dedupeKey = `${pc.canonicalPurl}\u0000${pc.fullPurl}`;
		if (!seenCanonicalFull.has(dedupeKey)) {
			seenCanonicalFull.add(dedupeKey);
			out.components.push(pc);
		}
		// Index by bom-ref (if present) and by the purl itself so that
		// `dependencies[]` entries can resolve whichever ref flavour they use.
		const record = { canonicalPurl: pc.canonicalPurl, fullPurl: pc.fullPurl };
		if (ref && !refMap.has(ref)) refMap.set(ref, record);
		if (!refMap.has(pc.fullPurl)) refMap.set(pc.fullPurl, record);
	};

	// Root from metadata.component — synthesised as a first-class node if it has a purl.
	const rootMeta: any = bom?.metadata?.component;
	if (rootMeta && typeof rootMeta.purl === 'string' && rootMeta.purl.length > 0) {
		pushComponent(toParsedComponent(rootMeta.purl, rootMeta, true), rootMeta['bom-ref']);
	}

	if (Array.isArray(bom.components)) {
		for (const component of bom.components) {
			if (!component || typeof component !== 'object') continue;
			const rawPurl = component.purl;
			if (typeof rawPurl !== 'string' || rawPurl.length === 0) continue;
			pushComponent(toParsedComponent(rawPurl, component, false), component['bom-ref']);
		}
	}

	if (!Array.isArray(bom.dependencies)) return out;

	const seenDepKey = new Set<string>();
	for (const dep of bom.dependencies) {
		if (!dep || typeof dep !== 'object') continue;
		const sourceRef = dep.ref;
		if (typeof sourceRef !== 'string') continue;
		const sourceResolved = refMap.get(sourceRef);
		if (!sourceResolved) continue;

		const list = Array.isArray(dep.dependsOn) ? dep.dependsOn : [];
		for (const targetRef of list) {
			if (typeof targetRef !== 'string') continue;
			const targetResolved = refMap.get(targetRef);
			if (!targetResolved) continue;
			// CDX 1.6 allows dependency.type; default to DEPENDS_ON when absent.
			const relationshipType =
				typeof dep.type === 'string' && dep.type.length > 0
					? dep.type.toUpperCase()
					: 'DEPENDS_ON';

			const dedupeKey = [
				sourceResolved.fullPurl,
				targetResolved.fullPurl,
				relationshipType,
			].join('\u0000');
			if (seenDepKey.has(dedupeKey)) continue;
			seenDepKey.add(dedupeKey);

			out.dependencies.push({
				sourceCanonicalPurl: sourceResolved.canonicalPurl,
				sourceFullPurl: sourceResolved.fullPurl,
				targetCanonicalPurl: targetResolved.canonicalPurl,
				targetFullPurl: targetResolved.fullPurl,
				relationshipType,
			});
		}
	}

	return out;
}

/** @deprecated use {@link parseBom} — kept only to smooth older tests. */
export function extractBomComponents(bom: any): ParsedBomComponent[] {
	return parseBom(bom).components;
}
