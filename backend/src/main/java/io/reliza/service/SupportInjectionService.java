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
import io.reliza.model.SupportExportState;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.SbomComponent;
import io.reliza.model.SupportData;
import io.reliza.model.SupportMilestoneFact;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentSupportRepository;
import io.reliza.repositories.SbomComponentSupportRepository.SupportPayloadRow;
import io.reliza.service.SupportBomInjector.Attester;
import io.reliza.service.SupportBomInjector.ComponentSupportFacts;
import io.reliza.service.SupportBomInjector.DeclarationContext;
import tools.jackson.databind.JsonNode;

/**
 * Weaves stored per-component support facts into a served CycloneDX BOM at read time
 * (FDA-Readiness-1 PR2a). Owns the encoding-aware match; the pure {@link SupportBomInjector}
 * owns the tree edit.
 *
 * <p>Depends on {@link SbomComponentRepository}, {@link SbomComponentSupportRepository},
 * {@link GetOrganizationService} (the export setting) and {@link UserService} (naming the
 * attester, per decision D4). None of those can reach back to {@code SharedArtifactService}
 * or {@code SbomComponentService}, so it still introduces no cycle -- but that is now a fact
 * about four collaborators rather than two, and {@link #declarationContext} carries the
 * argument for the one that was added last.
 */
@Slf4j
@Service
public class SupportInjectionService {

	private final SbomComponentRepository sbomComponentRepository;
	private final SbomComponentSupportRepository sbomComponentSupportRepository;
	private final GetOrganizationService getOrganizationService;
	private final UserService userService;

	SupportInjectionService(SbomComponentRepository sbomComponentRepository,
			SbomComponentSupportRepository sbomComponentSupportRepository,
			GetOrganizationService getOrganizationService,
			UserService userService) {
		this.sbomComponentRepository = sbomComponentRepository;
		this.sbomComponentSupportRepository = sbomComponentSupportRepository;
		this.getOrganizationService = getOrganizationService;
		this.userService = userService;
	}

	/**
	 * THE ONE PLACE that decides whether a served BOM carries support facts.
	 *
	 * <p>Every egress the strip covers calls this instead of choosing between
	 * {@link #injectCurrentSupport} and {@link #stripForgedProvenanceAndMark} itself. Having
	 * three call sites each make that choice is how one of them ends up on the wrong side of
	 * a setting -- which is exactly the state this replaces, where the merged export and the
	 * SPDX-augmented download stripped while the native download injected.
	 *
	 * <p>THE STRIP IS UNCONDITIONAL IN BOTH BRANCHES. Injection strips as part of its own
	 * work, and the DISABLED branch strips explicitly: a security control and a content choice
	 * are not the same switch, and turning injection off must never stop us removing an
	 * uploader's forged attribution.
	 *
	 * <p>The disclosure marker follows automatically and says which happened -- the
	 * current-state value when facts were injected, {@code provenance-stripped-no-disclosure}
	 * when they were not. A reader can therefore tell "we asserted nothing" from "we checked
	 * and there is nothing to report", which an absent property alone cannot express.
	 *
	 * <p>UNREADABLE ORG SETTINGS FALL BACK TO STRIP-ONLY. That is the safe direction: the
	 * document then says we asserted nothing, which is true, rather than carrying facts we
	 * could not confirm the org wanted published.
	 *
	 * @param device the enclosing device's support window for a PRODUCT download, else null
	 */
	public JsonNode injectIfEnabledElseStrip(JsonNode bom, UUID orgUuid, DeviceLifecycle device) {
		if (!isInjectionEnabled(orgUuid)) return stripForgedProvenanceAndMark(bom);
		try {
			return injectCurrentSupport(bom, orgUuid, device);
		} catch (RuntimeException e) {
			// THE STRIP MUST SURVIVE A FAILED INJECTION, and the fallback belongs HERE so no
			// egress can forget it.
			//
			// An earlier revision left this to each call site. Two of the three only logged,
			// and the consequence was the exact inverse of what this class promises: before
			// the gate they called the DB-FREE strip, which effectively cannot fail, and
			// afterwards the ENABLED branch did fact resolution -- existsSupportByOrg, the
			// purl lookups -- BEFORE inject() ever reached stripReservedEverywhere. So a
			// transient DB error on an org that had turned injection ON served an uploader's
			// forged reliza:support:* intact, under our attribution, with no marker. Turning
			// the feature on made spoofing easier.
			//
			// RuntimeException, not Exception: an InterruptedException or an Error is not
			// something to paper over with a partial document.
			log.error("Support resolution failed for org {}; falling back to strip-only: {}",
					orgUuid, e.getMessage(), e);
			return stripForgedProvenanceAndMark(bom);
		}
	}

	/** As above, for an egress with no device context. */
	public JsonNode injectIfEnabledElseStrip(JsonNode bom, UUID orgUuid) {
		return injectIfEnabledElseStrip(bom, orgUuid, null);
	}

	/**
	 * What this org's exports do with support facts -- THE predicate, read by the egresses and
	 * reported to the UI.
	 *
	 * <p>One method, deliberately. The gauge beside the coverage percentage and the decision an
	 * egress makes have to be the same answer: a gauge reporting "export injection ON" beside
	 * an export that carried nothing is the specific lie-by-omission this whole gate exists to
	 * prevent, and two predicates computed in two classes is how they would come to disagree.
	 *
	 * <p>UNKNOWN IS NOT DISABLED. DISABLED says this org chose not to export attestations;
	 * UNKNOWN says the settings row could not be read. A UI that renders UNKNOWN as "OFF"
	 * states a choice nobody made, so the distinction survives all the way to the gauge.
	 *
	 * <p>For the EGRESS, though, both non-ENABLED answers behave alike -- see
	 * {@link #isInjectionEnabled}: not knowing whether an org wanted facts published is a
	 * reason not to publish them.
	 */
	public SupportExportState supportExportState(UUID orgUuid) {
		try {
			// getSettingsWithDefaults, NOT getSettings: an org that has never had
			// updateOrganizationSettings called has a null settings block -- createOrganization
			// builds record_data with only the name -- so getSettings() NPEs, the broad catch
			// below swallows it, and a perfectly determinate DISABLED is reported as UNKNOWN
			// while a warn fires on every BOM download that org makes. The null-guarded
			// accessor is the convention the rest of the codebase already uses.
			return getOrganizationService.getOrganizationData(orgUuid)
					.map(od -> od.getSettingsWithDefaults().getSupportInjectionOrDefault() == SupportInjectionSetting.ENABLED
							? SupportExportState.ENABLED
							: SupportExportState.DISABLED)
					.orElse(SupportExportState.UNKNOWN);
		} catch (Exception e) {
			log.warn("Could not read support injection setting for org {}: {}", orgUuid, e.getMessage());
			return SupportExportState.UNKNOWN;
		}
	}

	/**
	 * Whether to inject, from the same predicate the gauge reports.
	 *
	 * <p>Only ENABLED injects. UNKNOWN falls to strip-only along with DISABLED, which is the
	 * safe direction: the document then says we asserted nothing, which is true, rather than
	 * carrying facts we could not confirm the org wanted published.
	 */
	private boolean isInjectionEnabled(UUID orgUuid) {
		return supportExportState(orgUuid) == SupportExportState.ENABLED;
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
		// No facts -> no claims, so skip the context entirely rather than paying for an org
		// read and a user lookup whose result emitDeclarations would discard. This is the
		// common case: most orgs attest nothing, and every export they take goes through here.
		DeclarationContext declarations = facts.isEmpty() ? null : declarationContext(orgUuid, facts);
		return SupportBomInjector.inject(bom, facts, LocalDate.now(ZoneOffset.UTC), device, declarations);
	}

	/**
	 * Assemble the document-scoped inputs for the Layer B {@code declarations} block: the
	 * assessing organization's name, and a name+role for each distinct {@code assertedBy} UUID
	 * among the facts being emitted (DECISION D4).
	 *
	 * <p>ONE LOOKUP PER DISTINCT ATTESTER, not per component. A release with hundreds of
	 * components typically has a handful of attesters, and the map is built from the resolved
	 * facts rather than from the BOM, so a component with no attestation costs nothing.
	 *
	 * <p><b>The cycle D4 warned about does not arise, and that is a property of the direction
	 * rather than luck:</b> {@code UserService} depends on {@code UserRepository} alone, so it
	 * cannot reach back to this service, {@code SharedArtifactService} or
	 * {@code SbomComponentService}. If it ever gains a dependency that does, this is the edge
	 * that closes the loop -- resolve the attester at the call site and pass it in instead.
	 *
	 * <p>NEVER THROWS. A user row that has been deleted, or an org name we cannot read, must
	 * degrade to an unattributed claim rather than fail the download: the claim itself is the
	 * regulated content, and attribution is an enrichment of it. An attester we cannot name is
	 * simply absent from the map, which emits {@code evidence} with no {@code author}.
	 */
	private DeclarationContext declarationContext(UUID orgUuid, Map<String, ComponentSupportFacts> facts) {
		Set<UUID> assertedBy = facts.values().stream()
				.map(ComponentSupportFacts::support)
				.filter(Objects::nonNull)
				.map(SupportData::assertedBy)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
		Map<UUID, Attester> attesters = new HashMap<>();
		for (UUID userUuid : assertedBy) {
			try {
				userService.getUserData(userUuid).ifPresent(ud -> {
					// getWireValue, never name(): this string lands in a redistributable BOM,
					// and ADMIN is an internal authorization token rather than something a
					// third-party reader of a regulated document should be handed.
					//
					// NONE and absent both yield no role rather than a stated one. This reads
					// only the user's OWN permission array, not permissions inherited from a
					// user group (contrast OrganizationService's getPermission fallback), so an
					// attester whose org access comes from an SSO group resolves to NONE here.
					// Publishing "organization role: none" for someone who demonstrably had
					// enough access to record an attestation would be a false statement in a
					// regulated document; saying nothing is the honest degradation, and the
					// name still identifies them.
					String role = ud.getPermission(orgUuid, PermissionScope.ORGANIZATION, orgUuid)
							.map(UserPermission::getType)
							.filter(pt -> pt != null && pt != PermissionType.NONE)
							.map(PermissionType::getWireValue)
							.orElse(null);
					attesters.put(userUuid, new Attester(ud.getName(), role));
				});
			} catch (RuntimeException e) {
				log.warn("Could not resolve attester {} for org {}; the claim ships unattributed: {}",
						userUuid, orgUuid, e.getMessage(), e);
			}
		}
		String orgName = null;
		try {
			orgName = getOrganizationService.getOrganizationData(orgUuid)
					.map(od -> od.getName()).orElse(null);
		} catch (RuntimeException e) {
			log.warn("Could not read organization name for {}; assessors ship unnamed: {}",
					orgUuid, e.getMessage(), e);
		}
		return new DeclarationContext(orgName, attesters);
	}

	/**
	 * DB-free sweep: strip any uploader-forged reliza:support:* tree-wide and mark the document
	 * as swept-but-not-disclosed, injecting nothing. Two kinds of caller:
	 *
	 * <ul>
	 *   <li>the fallback when fact resolution fails on an injected egress (a transient DB
	 *       error), so an outage cannot leave a forged server-owned property in a served BOM;</li>
	 *   <li>the DISABLED branch of {@link #injectIfEnabledElseStrip}, on every egress it
	 *       covers. The strip is unconditional because it is a security control; injection is
	 *       a content choice an operator opts into.</li>
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
