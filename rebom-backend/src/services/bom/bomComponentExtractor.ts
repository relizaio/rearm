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
import type { Serialize } from '@cyclonedx/cyclonedx-library';
import { logger } from '../../logger';

// The CycloneDX JSON license shape (one array element): a union of
// { license: { id, ... } } | { license: { name, ... } } | { expression }.
// This is the library's own normalized-JSON type, so the licenses we pass
// through structurally are typed exactly as the spec defines them.
type CdxLicense = Serialize.JSON.Types.Normalized.License;

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
	licenses: CdxLicense[];
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
function extractLicenses(raw: { licenses?: any } | undefined): CdxLicense[] {
	const arr = raw?.licenses;
	if (!Array.isArray(arr)) return [];
	return arr.filter((entry): entry is CdxLicense => entry && typeof entry === 'object');
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
 * Build a component from its CPE when it has no purl. The CPE string is already
 * self-namespacing (`cpe:...`), so it serves directly as the canonical identity.
 * Used only as the purl > cpe fallback — components with neither are dropped.
 * Returns null when no usable CPE is present.
 */
function toCpeOnlyComponent(
	component: { type?: any; group?: any; name?: any; version?: any; cpe?: any; licenses?: any } | undefined,
	isRoot: boolean
): ParsedBomComponent | null {
	const cpe = typeof component?.cpe === 'string' && component.cpe.length > 0 ? component.cpe : null;
	if (!cpe) return null;
	return {
		canonicalPurl: cpe,
		fullPurl: cpe,
		type: typeof component?.type === 'string' ? component.type : null,
		group: typeof component?.group === 'string' ? component.group : null,
		name: typeof component?.name === 'string' ? component.name : null,
		version: typeof component?.version === 'string' ? component.version : null,
		isRoot,
		cpe,
		licenses: extractLicenses(component),
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

	// Root from metadata.component — synthesised as a first-class node if it has a
	// purl, else a CPE-canonical node when it carries a cpe (purl > cpe).
	const rootMeta: any = bom?.metadata?.component;
	// `type: device` roots are hardware — parseHbom owns them (see the
	// components loop below for the same rule).
	if (rootMeta && typeof rootMeta === 'object' && rootMeta.type !== 'device') {
		if (typeof rootMeta.purl === 'string' && rootMeta.purl.length > 0) {
			pushComponent(toParsedComponent(rootMeta.purl, rootMeta, true), rootMeta['bom-ref']);
		} else {
			pushComponent(toCpeOnlyComponent(rootMeta, true), rootMeta['bom-ref']);
		}
	}

	if (Array.isArray(bom.components)) {
		for (const component of bom.components) {
			if (!component || typeof component !== 'object') continue;
			// `type: device` nodes are hardware — parseHbom owns them. Excluded here
			// so a device that also carries a purl never double-counts into the SBOM.
			if (component.type === 'device') continue;
			const rawPurl = component.purl;
			if (typeof rawPurl === 'string' && rawPurl.length > 0) {
				pushComponent(toParsedComponent(rawPurl, component, false), component['bom-ref']);
			} else {
				// No purl — fall back to a CPE-canonical node (dropped if no cpe).
				pushComponent(toCpeOnlyComponent(component, false), component['bom-ref']);
			}
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
// and have no purl — they are identified by party-asserted identity claims, parties
// (manufacturer/supplier/...), and board location. parseHbom flattens the
// board -> devices -> firmware tree into one entry per hardware node (carrying
// the parent bom-ref so the backend can rebuild the nesting) while preserving
// the CDX party/identity structure instead of collapsing it to single fields.

// CDX 2.0 party (PR #930 names the component field `parties`; older drafts /
// our fixtures used `entities` — accepted interchangeably on read). Roles are
// normalized to ReARM's PartyRole enum names.
export interface ParsedHbomParty {
	bomRef: string | null;
	roles: string[];
	name: string | null;
	address: any | null;
	url: string | null;
}

// CDX 2.0 identity model (spec PR #936): identifiers group identity claims by
// the asserting party (bom-ref into parties). Scheme tokens are normalized to
// the backend's identifier-type names (mpn -> MPN, part-number -> PART_NUMBER,
// serial-number -> SERIAL, ...) so claims deserialize directly server-side —
// same approach as party roles. Custom {name, description} schemes are dropped
// here; the raw BOM in storage still carries them.
export interface ParsedHbomIdentityClaim {
	idType: string;
	idValue: string;
}

export interface ParsedHbomIdentifier {
	bomRef: string | null;
	party: string | null;
	identities: ParsedHbomIdentityClaim[];
}

export interface ParsedHbomComponent {
	bomRef: string | null;
	type: string | null; // device | component-choice
	// CDX #929 choice operator (XOR / AND / OPTIONAL); null for plain devices.
	operator: string | null;
	name: string | null;
	version: string | null;
	description: string | null;
	category: string | null; // classification.category (e.g. semiconductor)
	subcategory: string | null; // classification.subcategory (e.g. voltage-regulator)
	parties: ParsedHbomParty[];
	identifiers: ParsedHbomIdentifier[];
	boardLocation: string | null;
	deviceType: string | null; // DIP / QFN / SMD / PTH
	quantity: number | null;
	parentRef: string | null;
	isRoot: boolean;
}

export interface ParsedHbom {
	components: ParsedHbomComponent[];
}

const PARTY_ROLES = new Set(['MANUFACTURER', 'ASSEMBLER', 'SUPPLIER', 'QUALITY_CONTROL', 'DISTRIBUTOR']);

function normalizeRole(role: any): string | null {
	if (typeof role !== 'string' || !role) return null;
	const up = role.toUpperCase().replace(/[\s-]+/g, '_');
	return PARTY_ROLES.has(up) ? up : 'OTHER';
}

function partiesOf(c: any): ParsedHbomParty[] {
	const raw = Array.isArray(c.parties) ? c.parties : (Array.isArray(c.entities) ? c.entities : []);
	const out: ParsedHbomParty[] = [];
	for (const p of raw) {
		if (!p || typeof p !== 'object') continue;
		const roles: string[] = [];
		if (Array.isArray(p.roles)) {
			for (const r of p.roles) { const nr = normalizeRole(r); if (nr) roles.push(nr); }
		} else {
			const nr = normalizeRole(p.role);
			if (nr) roles.push(nr);
		}
		out.push({
			bomRef: typeof p['bom-ref'] === 'string' ? p['bom-ref'] : (typeof p.bomRef === 'string' ? p.bomRef : null),
			roles,
			name: typeof p.name === 'string' ? p.name : null,
			address: p.address && typeof p.address === 'object' ? p.address : null,
			url: typeof p.url === 'string' ? p.url : null,
		});
	}
	return out;
}

const IDENTITY_SCHEMES = new Set([
	'PURL', 'CPE', 'SWID', 'SWHID', 'OMNIBORID', 'GTIN', 'GMN', 'MPN', 'PART_NUMBER',
	'MODEL_NUMBER', 'SKU', 'SERIAL', 'ASSET_TAG', 'UDI_DI', 'UDI_PI', 'FCC_ID', 'IMEI', 'MAC_ADDRESS',
]);

function normalizeScheme(scheme: any): string | null {
	if (typeof scheme !== 'string' || !scheme) return null;
	if (scheme === 'serial-number') return 'SERIAL';
	const up = scheme.toUpperCase().replace(/-/g, '_');
	return IDENTITY_SCHEMES.has(up) ? up : null;
}

function claimOf(id: any): ParsedHbomIdentityClaim | null {
	if (!id || typeof id !== 'object' || typeof id.value !== 'string') return null;
	const idType = normalizeScheme(id.scheme);
	return idType ? { idType, idValue: id.value } : null;
}

// bom-ref of the party carrying the given normalized role, when unambiguous.
function partyRefByRole(parties: ParsedHbomParty[], role: string | null): string | null {
	if (!role) return null;
	const matches = parties.filter((p) => p.roles.includes(role) && p.bomRef);
	return matches.length === 1 ? matches[0].bomRef : null;
}

function identifiersOf(c: any, parties: ParsedHbomParty[]): ParsedHbomIdentifier[] {
	const out: ParsedHbomIdentifier[] = [];
	if (Array.isArray(c.identifiers)) {
		for (const block of c.identifiers) {
			if (!block || typeof block !== 'object' || !Array.isArray(block.identities)) continue;
			// Spec PR #936 form: { party, identities: [{ scheme, value }] }.
			const claims = block.identities.map(claimOf).filter((cl: any) => cl != null) as ParsedHbomIdentityClaim[];
			if (claims.length) {
				out.push({
					bomRef: typeof block['bom-ref'] === 'string' ? block['bom-ref'] : null,
					party: typeof block.party === 'string' ? block.party : null,
					identities: claims,
				});
				continue;
			}
			// Legacy draft form: { role, identities: [{ idType: PART_NUMBER, idValue }] }.
			const role = normalizeRole(block.role);
			const legacy = block.identities
				.filter((id: any) => id?.idType === 'PART_NUMBER' && typeof id?.idValue === 'string')
				.map((id: any) => ({
					idType: role === 'MANUFACTURER' ? 'MPN' : 'PART_NUMBER',
					idValue: id.idValue,
				}));
			if (legacy.length) {
				const entityRef = typeof block.entity === 'string' ? block.entity : null;
				out.push({
					bomRef: typeof block['bom-ref'] === 'string' ? block['bom-ref'] : null,
					party: entityRef ?? partyRefByRole(parties, role),
					identities: legacy,
				});
			}
		}
	}
	// Legacy draft form: component-level identities [{ role, partNumber: [...] }].
	if (Array.isArray(c.identities)) {
		for (const id of c.identities) {
			if (!id || typeof id !== 'object' || !Array.isArray(id.partNumber)) continue;
			const role = normalizeRole(id.role);
			const claims = id.partNumber
				.filter((pn: any) => typeof pn === 'string')
				.map((pn: string) => ({
					idType: role === 'MANUFACTURER' ? 'MPN' : 'PART_NUMBER',
					idValue: pn,
				}));
			if (claims.length) out.push({ bomRef: null, party: partyRefByRole(parties, role), identities: claims });
		}
	}
	return out;
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
		// HBOM = physical hardware (`type: device`) plus CDX #929 choice slots
		// (`type: component-choice`, whose options are nested device children).
		// Firmware is software and flows to the SBOM extractor when it carries
		// a purl/cpe (dropped otherwise).
		if (type === 'device' || type === 'component-choice') {
			const parties = partiesOf(c);
			out.components.push({
				bomRef: ref,
				type,
				operator: typeof c.operator === 'string' ? c.operator : null,
				name: typeof c.name === 'string' ? c.name : null,
				version: typeof c.version === 'string' ? c.version : null,
				description: typeof c.description === 'string' ? c.description : null,
				category: classificationOf(c).category,
				subcategory: classificationOf(c).subcategory,
				parties,
				identifiers: identifiersOf(c, parties),
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
