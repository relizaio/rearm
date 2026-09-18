/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;

import io.reliza.common.Utils;
import io.reliza.model.SbomComponent;
import io.reliza.repositories.SbomComponentRepository;
import tools.jackson.databind.JsonNode;

/**
 * Weaves stored per-component support facts into a served CycloneDX BOM at read time
 * (FDA-Readiness-1 PR2a). Owns the encoding-aware match; the pure {@link SupportBomInjector}
 * owns the tree edit. Depends only on {@link SbomComponentRepository}, so it introduces no
 * cycle with {@code SharedArtifactService} / {@code SbomComponentService}.
 */
@Service
public class SupportInjectionService {

	private final SbomComponentRepository sbomComponentRepository;

	SupportInjectionService(SbomComponentRepository sbomComponentRepository) {
		this.sbomComponentRepository = sbomComponentRepository;
	}

	/**
	 * Inject the CURRENT support facts (status derived as-of now, UTC) into a served CycloneDX
	 * BOM tree, in place. This is the living, non-attested current-state view. Orgs with no
	 * support attestations skip the per-component resolution entirely -- the injector still
	 * runs (with no facts) to strip any uploader-forged reliza:support:* properties and stamp
	 * the document-level disclosure marker.
	 *
	 * <p>Note: PR6's attested snapshot does NOT reuse {@link #resolveSupportFactsForBom} (that
	 * returns LIVE rows); it builds its facts map from the attestation history as-of a cutoff
	 * and calls {@link SupportBomInjector#inject} directly with that map and the cutoff clock.
	 */
	public JsonNode injectCurrentSupport(JsonNode bom, UUID orgUuid) {
		Map<String, SbomComponent> facts = sbomComponentRepository.existsSupportByOrg(orgUuid.toString())
				? resolveSupportFactsForBom(bom, orgUuid)
				: Map.of();
		return SupportBomInjector.inject(bom, facts, LocalDate.now(ZoneOffset.UTC));
	}

	/**
	 * DB-free fallback for when fact resolution fails (e.g. a transient DB error): strip any
	 * uploader-forged reliza:support:* tree-wide and stamp the disclosure marker with NO facts,
	 * so a resolution outage cannot leave a forged server-owned property in the served BOM.
	 */
	public JsonNode stripForgedProvenanceAndMark(JsonNode bom) {
		return SupportBomInjector.inject(bom, Map.of(), LocalDate.now(ZoneOffset.UTC));
	}

	/**
	 * Resolve the stored (LIVE) support facts for every component in a BOM, keyed by
	 * {@link SupportBomInjector#componentKey}. Read-only. Two-pass match mirroring
	 * {@code SbomComponentService.stampEnrichedLicenses}: a byte-exact pass, then an
	 * encoding-safe fallback for the Debian/rpm {@code +}/{@code %2B} canonical-purl drift.
	 * Unlike the license stamper it uses the qualifier-aware IDENTITY comparator
	 * ({@link Utils#purlsSemanticallyEqual}), not {@code purlsSameCoordinates} (license-scope
	 * only). Ties among fallback candidates resolve to the most recently assessed row so the
	 * result is reproducible.
	 */
	public Map<String, SbomComponent> resolveSupportFactsForBom(JsonNode bom, UUID orgUuid) {
		Map<String, SbomComponent> byKey = new HashMap<>();
		Set<String> keys = SupportBomInjector.collectKeys(bom);
		if (keys.isEmpty()) return byKey;

		// Byte-exact pass (catches the common case, incl. all cpe-only keys).
		Set<String> byteMatched = new HashSet<>();
		for (SbomComponent sc :
				sbomComponentRepository.findByOrgAndCanonicalPurlIn(orgUuid.toString(), new ArrayList<>(keys))) {
			byteMatched.add(sc.getCanonicalPurl());
			byKey.put(sc.getCanonicalPurl(), sc);
		}

		// Encoding-safe fallback for purl keys the byte pass missed. cpe keys and malformed
		// purls do not parse here, so they stay byte-only -- exactly the desired behavior.
		Map<String, List<String>> unmatchedByName = new HashMap<>();
		for (String key : keys) {
			if (byteMatched.contains(key)) continue;
			try {
				PackageURL parsed = new PackageURL(key.replace("+", "%2B"));
				if (parsed.getName() == null) continue;
				unmatchedByName.computeIfAbsent(parsed.getName(), k -> new ArrayList<>()).add(key);
			} catch (MalformedPackageURLException ignored) {
			}
		}
		if (unmatchedByName.isEmpty()) return byKey;

		for (SbomComponent candidate :
				sbomComponentRepository.findCandidatesByOrgAndNames(orgUuid, unmatchedByName.keySet())) {
			String candName = candidate.getRecordData() != null
					&& candidate.getRecordData().get("name") instanceof String cn ? cn : null;
			if (candName == null && candidate.getCanonicalPurl() != null) {
				try {
					candName = new PackageURL(candidate.getCanonicalPurl().replace("+", "%2B")).getName();
				} catch (MalformedPackageURLException ignored) {
				}
			}
			List<String> sameName = candName != null ? unmatchedByName.get(candName) : null;
			if (sameName == null) continue;
			for (String key : sameName) {
				if (!Utils.purlsSemanticallyEqual(key, candidate.getCanonicalPurl())) continue;
				// Deterministic tie-break: newest assessment wins (the query has no ORDER BY, and
				// an org could hold >1 encoding-variant row for the same identity).
				SbomComponent existing = byKey.get(key);
				if (existing == null || moreRecentlyAssessed(candidate, existing)) {
					byKey.put(key, candidate);
				}
			}
		}
		return byKey;
	}

	private static boolean moreRecentlyAssessed(SbomComponent a, SbomComponent b) {
		ZonedDateTime la = a.getSupportLastAssessed();
		ZonedDateTime lb = b.getSupportLastAssessed();
		if (la == null) return false;
		if (lb == null) return true;
		return la.isAfter(lb);
	}
}
