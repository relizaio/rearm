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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;

import io.reliza.common.Utils;
import io.reliza.model.DeviceLifecycle;
import io.reliza.model.SbomComponent;
import io.reliza.model.SupportData;
import io.reliza.model.SupportMilestoneFact;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentSupportRepository;
import io.reliza.repositories.SbomComponentSupportRepository.SupportPayloadRow;
import io.reliza.service.SupportBomInjector.ComponentSupportFacts;
import tools.jackson.databind.JsonNode;

/**
 * Weaves stored per-component support facts into a served CycloneDX BOM at read time
 * (FDA-Readiness-1 PR2a). Owns the encoding-aware match; the pure {@link SupportBomInjector}
 * owns the tree edit. Depends only on {@link SbomComponentRepository} and
 * {@link SbomComponentSupportRepository}, so it introduces no cycle with
 * {@code SharedArtifactService} / {@code SbomComponentService}.
 */
@Slf4j
@Service
public class SupportInjectionService {

	private final SbomComponentRepository sbomComponentRepository;
	private final SbomComponentSupportRepository sbomComponentSupportRepository;

	SupportInjectionService(SbomComponentRepository sbomComponentRepository,
			SbomComponentSupportRepository sbomComponentSupportRepository) {
		this.sbomComponentRepository = sbomComponentRepository;
		this.sbomComponentSupportRepository = sbomComponentSupportRepository;
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
		return injectCurrentSupport(bom, orgUuid, null);
	}

	/**
	 * As {@link #injectCurrentSupport(JsonNode, UUID)}, but for a PRODUCT (device) download also
	 * stamps the {@code reliza:support:deviceSupportRisk} verdict and the device-anchor properties
	 * against {@code device} -- the enclosing device release's support window
	 * ({@code ReleaseData.eos}/{@code eol}), resolved by the caller from the download's release
	 * context. Null on a non-PRODUCT release or when that context is absent/ambiguous (nothing
	 * device-related is emitted; the raw component milestone dates still are).
	 */
	public JsonNode injectCurrentSupport(JsonNode bom, UUID orgUuid, DeviceLifecycle device) {
		Map<String, ComponentSupportFacts> facts = sbomComponentSupportRepository.existsSupportByOrg(orgUuid.toString())
				? resolveSupportFactsForBom(bom, orgUuid)
				: Map.of();
		return SupportBomInjector.inject(bom, facts, LocalDate.now(ZoneOffset.UTC), device);
	}

	/**
	 * DB-free sweep: strip any uploader-forged reliza:support:* tree-wide and mark the document
	 * as swept-but-not-disclosed, injecting nothing. Two kinds of caller:
	 *
	 * <ul>
	 *   <li>the fallback when fact resolution fails on an injected egress (a transient DB
	 *       error), so an outage cannot leave a forged server-owned property in a served BOM;</li>
	 *   <li>egresses that do not inject at all yet -- the merged release SBOM export and the
	 *       SPDX-augmented download. The strip is unconditional there because it is a security
	 *       control; injection is a content choice an operator opts into.</li>
	 * </ul>
	 *
	 * <p>Marks with {@code provenance-stripped-no-disclosure}, NOT the current-state value: in
	 * both cases we asserted nothing, and saying otherwise would let an absent property read as
	 * "we checked and there is nothing to report" on components we hold dates for.
	 */
	public JsonNode stripForgedProvenanceAndMark(JsonNode bom) {
		return SupportBomInjector.stripOnly(bom);
	}

	/**
	 * Resolve the stored (LIVE) support facts for every component in a BOM, keyed by
	 * {@link SupportBomInjector#componentKey}. Read-only. Two-pass match mirroring
	 * {@code SbomComponentService.stampEnrichedLicenses}: a byte-exact pass, then an
	 * encoding-safe fallback for the Debian/rpm {@code +}/{@code %2B} canonical-purl drift.
	 * Unlike the license stamper it uses the qualifier-aware IDENTITY comparator
	 * ({@link Utils#purlsSemanticallyEqual}), not {@code purlsSameCoordinates} (license-scope
	 * only). Ties among fallback candidates resolve to the most recently assessed row so the
	 * result is reproducible. Attestations are bulk-loaded once for every candidate component
	 * (never per component -- see
	 * {@link SbomComponentSupportRepository#findRawByComponentUuids}).
	 */
	public Map<String, ComponentSupportFacts> resolveSupportFactsForBom(JsonNode bom, UUID orgUuid) {
		Map<String, SbomComponent> byKey = new HashMap<>();
		Set<String> keys = SupportBomInjector.collectKeys(bom);
		if (keys.isEmpty()) return Map.of();

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
		List<SbomComponent> fallbackCandidates = unmatchedByName.isEmpty() ? List.of()
				: sbomComponentRepository.findCandidatesByOrgAndNames(orgUuid, unmatchedByName.keySet());

		// Bulk-load attestations once for every component that could end up in the result --
		// byte-matched rows plus every fallback candidate -- before the tie-break loop needs them.
		Set<UUID> candidateUuids = new HashSet<>();
		byKey.values().forEach(sc -> candidateUuids.add(sc.getUuid()));
		fallbackCandidates.forEach(sc -> candidateUuids.add(sc.getUuid()));
		// Parsed per row: one unreadable payload is excluded, never allowed to fail the
		// whole download. candidateUuids deliberately includes encoding-variant fallbacks
		// that may not be in this BOM at all, so an entity-materializing bulk read would
		// let an unrelated component's poison row take this release's export down.
		Map<UUID, SupportData> supportByComponent = new HashMap<>();
		if (!candidateUuids.isEmpty()) {
			for (SupportPayloadRow row : sbomComponentSupportRepository
					.findRawByComponentUuids(orgUuid.toString(),
							SbomComponentService.joinUuids(candidateUuids))) {
				try {
					supportByComponent.put(row.getComponentUuid(), SupportData.parse(row.getPayload()));
				} catch (RuntimeException e) {
					// This is the EXPORT path, so a silent skip is the worst option: a
					// component would vanish from a served BOM with nothing to explain it.
					log.warn("Unreadable support attestation for sbom component {}; excluded"
							+ " from this export: {}", row.getComponentUuid(), e.getMessage(), e);
				}
			}
		}

		if (!unmatchedByName.isEmpty()) {
			for (SbomComponent candidate : fallbackCandidates) {
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
					if (existing == null || moreRecentlyAssessed(candidate, existing, supportByComponent)) {
						byKey.put(key, candidate);
					}
				}
			}
		}

		Map<String, ComponentSupportFacts> result = new HashMap<>();
		byKey.forEach((key, sc) -> result.put(key,
				new ComponentSupportFacts(sc, supportByComponent.get(sc.getUuid()))));
		return result;
	}

	private static boolean moreRecentlyAssessed(SbomComponent a, SbomComponent b,
			Map<UUID, SupportData> supportByComponent) {
		ZonedDateTime la = mostRecentlyAssessed(a, supportByComponent);
		ZonedDateTime lb = mostRecentlyAssessed(b, supportByComponent);
		if (la == null) return false;
		if (lb == null) return true;
		return la.isAfter(lb);
	}

	private static ZonedDateTime mostRecentlyAssessed(SbomComponent sc,
			Map<UUID, SupportData> supportByComponent) {
		SupportData sd = supportByComponent.get(sc.getUuid());
		if (sd == null) return null;
		return Stream.concat(Stream.of(sd.assessedAt()),
						sd.milestones().values().stream().map(SupportMilestoneFact::lastAssessed))
				.filter(Objects::nonNull)
				.map(SupportBomInjector::parseInstantOrNull)
				.filter(Objects::nonNull)
				.max(ZonedDateTime::compareTo)
				.orElse(null);
	}
}
