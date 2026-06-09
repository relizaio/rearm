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
 * Strip qualifiers and subpath from a purl, leaving the canonical
 * identity part: pkg:<type>/<namespace>/<name>@<version>. Returns null
 * if the input is missing or unparseable.
 */
export function canonicalizePurl(rawPurl: string | undefined | null): string | null {
	if (!rawPurl) return null;
	try {
		const parsed = PackageURL.fromString(rawPurl);
		const canonical = new PackageURL(
			parsed.type,
			parsed.namespace ?? undefined,
			parsed.name,
			parsed.version ?? undefined,
			undefined,
			undefined
		);
		return canonical.toString();
	} catch (err) {
		logger.debug({ err, rawPurl }, 'Unable to canonicalize purl; skipping');
		return null;
	}
}

function toParsedComponent(
	rawPurl: string,
	fallback: { group?: any; name?: any; version?: any } | undefined,
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
	if (rootMeta && rootMeta.type !== 'device' && typeof rootMeta.purl === 'string' && rootMeta.purl.length > 0) {
		pushComponent(toParsedComponent(rootMeta.purl, rootMeta, true), rootMeta['bom-ref']);
	}

	if (Array.isArray(bom.components)) {
		for (const component of bom.components) {
			if (!component || typeof component !== 'object') continue;
			// `type: device` nodes are hardware — parseHbom owns them. Excluded here
			// so a device that also carries a purl never double-counts into the SBOM.
			if (component.type === 'device') continue;
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

// ===== HBOM (hardware BOM) extraction =====
// CycloneDX HBOM components are `type: device` (with nested `type: firmware`)
// and have no purl — they are identified by part number + manufacturer +
// board location. parseHbom flattens the board -> devices -> firmware tree
// into one entry per hardware node, carrying the parent bom-ref so the
// backend can rebuild the nesting.

export interface ParsedHbomComponent {
	bomRef: string | null;
	type: string | null; // device | firmware
	name: string | null;
	version: string | null;
	description: string | null;
	category: string | null; // classification.category (e.g. semiconductor)
	subcategory: string | null; // classification.subcategory (e.g. voltage-regulator)
	partNumbers: string[];
	manufacturer: string | null;
	boardLocation: string | null;
	deviceType: string | null; // DIP / QFN / SMD / PTH
	quantity: number | null;
	parentRef: string | null;
	isRoot: boolean;
}

export interface ParsedHbom {
	components: ParsedHbomComponent[];
}

function extractPartNumbers(c: any): string[] {
	const out: string[] = [];
	if (Array.isArray(c.identities)) {
		for (const id of c.identities) {
			if (Array.isArray(id?.partNumber)) {
				for (const pn of id.partNumber) if (typeof pn === 'string') out.push(pn);
			}
		}
	}
	if (Array.isArray(c.identifiers)) {
		for (const block of c.identifiers) {
			const ids = block?.identities;
			if (Array.isArray(ids)) {
				for (const id of ids) {
					if (id?.idType === 'PART_NUMBER' && typeof id?.idValue === 'string') out.push(id.idValue);
				}
			}
		}
	}
	return Array.from(new Set(out));
}

function manufacturerOf(c: any): string | null {
	if (Array.isArray(c.entities)) {
		const m = c.entities.find((e: any) => e?.role === 'manufacturer' && typeof e?.name === 'string');
		if (m) return m.name;
		const any = c.entities.find((e: any) => typeof e?.name === 'string');
		if (any) return any.name;
	}
	return null;
}

function classificationOf(c: any): { category: string | null; subcategory: string | null } {
	const cl = c?.classification;
	if (cl && typeof cl === 'object') {
		return {
			category: typeof cl.category === 'string' ? cl.category : null,
			subcategory: typeof cl.subcategory === 'string' ? cl.subcategory : null,
		};
	}
	return { category: null, subcategory: null };
}

function boardLocationOf(c: any): string | null {
	if (typeof c.boardLocation === 'string') return c.boardLocation;
	const ev = c?.evidence?.boardLocation;
	if (ev && typeof ev.designator === 'string') return ev.designator;
	return null;
}

export function parseHbom(bom: any): ParsedHbom {
	const out: ParsedHbom = { components: [] };
	if (!bom || typeof bom !== 'object') return out;

	const visit = (c: any, parentRef: string | null, isRoot: boolean) => {
		if (!c || typeof c !== 'object') return;
		const type = typeof c.type === 'string' ? c.type : null;
		const ref = typeof c['bom-ref'] === 'string' ? c['bom-ref'] : null;
		// HBOM = physical hardware only (`type: device`). Firmware is software and
		// flows to the SBOM extractor when it carries a purl/cpe (dropped otherwise).
		if (type === 'device') {
			out.components.push({
				bomRef: ref,
				type,
				name: typeof c.name === 'string' ? c.name : null,
				version: typeof c.version === 'string' ? c.version : null,
				description: typeof c.description === 'string' ? c.description : null,
				category: classificationOf(c).category,
				subcategory: classificationOf(c).subcategory,
				partNumbers: extractPartNumbers(c),
				manufacturer: manufacturerOf(c),
				boardLocation: boardLocationOf(c),
				deviceType: typeof c.deviceType === 'string' ? c.deviceType : null,
				quantity: typeof c.quantity === 'number' ? c.quantity : null,
				parentRef,
				isRoot,
			});
		}
		// Recurse into nested components (firmware under devices, choice wrappers).
		if (Array.isArray(c.components)) {
			const childParent = ref ?? parentRef;
			for (const child of c.components) visit(child, childParent, false);
		}
	};

	const root = bom?.metadata?.component;
	const rootRef = root && typeof root['bom-ref'] === 'string' ? root['bom-ref'] : null;
	if (root) visit(root, null, true);
	if (Array.isArray(bom.components)) {
		for (const c of bom.components) visit(c, rootRef, false);
	}
	return out;
}
