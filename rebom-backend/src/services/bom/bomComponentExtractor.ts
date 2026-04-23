/**
 * SBOM component extraction.
 *
 * Parses CycloneDX BOM component entries into a flat list suitable for
 * consumption by rearm-saas: one entry per component that carries a purl,
 * with the purl split into a canonical form (scheme + type + namespace +
 * name + version) and the original full purl (with any qualifiers or
 * subpath preserved).
 *
 * The canonical purl is what rearm-saas uses as the identity key for
 * the sbom_components table. The full purl preserves qualifier data so
 * that per-artifact participations can be recorded exactly as they
 * appeared in the upload.
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

/**
 * Extract flat list of parsed components from a CycloneDX BOM object.
 * Components without a purl are omitted — they have no identity we can
 * key on. Duplicates within a single BOM collapse on (canonicalPurl,
 * fullPurl) so the caller sees one row per distinct exact purl.
 */
export function extractBomComponents(bom: any): ParsedBomComponent[] {
	const out: ParsedBomComponent[] = [];
	if (!bom || !Array.isArray(bom.components)) return out;

	const seen = new Set<string>();
	for (const component of bom.components) {
		if (!component || typeof component !== 'object') continue;
		const rawPurl = component.purl;
		if (typeof rawPurl !== 'string' || rawPurl.length === 0) continue;
		const canonicalPurl = canonicalizePurl(rawPurl);
		if (!canonicalPurl) continue;

		const dedupeKey = `${canonicalPurl}\u0000${rawPurl}`;
		if (seen.has(dedupeKey)) continue;
		seen.add(dedupeKey);

		let parsed: PackageURL | null = null;
		try {
			parsed = PackageURL.fromString(rawPurl);
		} catch (_) {
			parsed = null;
		}

		out.push({
			canonicalPurl,
			fullPurl: rawPurl,
			type: parsed?.type ?? null,
			group: parsed?.namespace ?? (typeof component.group === 'string' ? component.group : null),
			name: parsed?.name ?? (typeof component.name === 'string' ? component.name : null),
			version: parsed?.version ?? (typeof component.version === 'string' ? component.version : null),
		});
	}
	return out;
}
