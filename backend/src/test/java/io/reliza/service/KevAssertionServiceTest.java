/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.reliza.dto.KevRecordDetails;
import io.reliza.model.KevAssertion;
import io.reliza.model.KevAssertionData;
import io.reliza.model.KevRansomwareStatus;
import io.reliza.model.KevSource;
import io.reliza.repositories.KevAssertionRepository;
import io.reliza.service.KevAssertionService.KevCatalogApplyOutcome;

/**
 * Unit tests for {@link KevAssertionService}. Mock-based: pin the CVE-id
 * normalization contract, the per-org membership probe's pre-filter, the
 * per-source reconcile arithmetic (insert / rewrite-on-diff / skip-unchanged
 * / dedup), the soft-delete and revive semantics, the genuinely-new-KEV
 * detection across sources within ONE org, and the detail aggregation.
 *
 * <p>Per-org (V54) refactor: every read + write path takes a {@code UUID
 * orgUuid}; new-KEV detection compares against THIS ORG's prior assertions
 * only — the same CVE asserted in another org is independent.
 * Native queries run against a live DB in the integration suite.
 */
class KevAssertionServiceTest {

	private KevAssertionRepository repository;
	private KevAssertionService service;
	private UUID orgUuid;

	@BeforeEach
	void wireMocks() throws Exception {
		repository = mock(KevAssertionRepository.class);
		service = new KevAssertionService();
		Field f = KevAssertionService.class.getDeclaredField("repository");
		f.setAccessible(true);
		f.set(service, repository);
		orgUuid = UUID.randomUUID();
		// default: nothing pre-existing across sources within this org
		when(repository.findAllDistinctCveIdsForOrg(any(UUID.class))).thenReturn(List.of());
	}

	// ---------- normalizeCveId ----------

	@Test
	void normalizeUppercasesAndTrims() {
		assertEquals("CVE-2021-44228", KevAssertionService.normalizeCveId("  cve-2021-44228 "));
	}

	@Test
	void normalizeRejectsNonCveIds() {
		assertNull(KevAssertionService.normalizeCveId(null));
		assertNull(KevAssertionService.normalizeCveId("GHSA-jfh8-c2jp-5v3q"));
		assertNull(KevAssertionService.normalizeCveId("OSV-2021-1234"));
		assertNull(KevAssertionService.normalizeCveId(""));
	}

	// ---------- filterKnownExploited ----------

	@Test
	void filterPreFiltersNonCveIdsBeforeQuerying() {
		when(repository.findExistingCveIdsForOrg(eq(orgUuid), anyCollection()))
				.thenReturn(List.of("CVE-2021-44228"));

		Set<String> listed = service.filterKnownExploited(orgUuid,
				List.of("cve-2021-44228", "GHSA-aaaa-bbbb-cccc", "CVE-2020-0001"));

		assertEquals(Set.of("CVE-2021-44228"), listed);
		verify(repository).findExistingCveIdsForOrg(eq(orgUuid), any());
	}

	@Test
	void filterShortCircuitsWhenNoCveShapedIds() {
		assertEquals(Set.of(), service.filterKnownExploited(orgUuid, List.of("GHSA-x", "OSV-y")));
		assertEquals(Set.of(), service.filterKnownExploited(orgUuid, List.of()));
		assertEquals(Set.of(), service.filterKnownExploited(orgUuid, null));
		assertEquals(Set.of(), service.filterKnownExploited(null, List.of("CVE-X")));
		verify(repository, never()).findExistingCveIdsForOrg(any(UUID.class), anyCollection());
	}

	@Test
	void isAnyKnownExploitedReflectsProbe() {
		when(repository.findExistingCveIdsForOrg(eq(orgUuid), anyCollection())).thenReturn(List.of());
		assertFalse(service.isAnyKnownExploited(orgUuid, List.of("CVE-2020-0001")));
		when(repository.findExistingCveIdsForOrg(eq(orgUuid), anyCollection()))
				.thenReturn(List.of("CVE-2020-0001"));
		assertTrue(service.isAnyKnownExploited(orgUuid, List.of("CVE-2020-0001")));
	}

	// ---------- countRecordsForOrg ----------

	@Test
	void countRecordsForOrgDelegatesToRepository() {
		when(repository.countByOrgId(orgUuid)).thenReturn(42L);
		assertEquals(42L, service.countRecordsForOrg(orgUuid));
		assertEquals(0L, service.countRecordsForOrg(null));
	}

	// ---------- applyCatalog: inserts + new-KEV detection ----------

	@Test
	void applyInsertsNewEntriesAndFlagsThemNewKev() {
		when(repository.findByOrgIdAndSource(orgUuid, KevSource.CISA)).thenReturn(List.of());

		KevCatalogApplyOutcome outcome = service.applyCatalog(orgUuid, KevSource.CISA,
				List.of(entry("CVE-2021-44228"), entry("CVE-2023-1234")));

		assertEquals(List.of("CVE-2021-44228", "CVE-2023-1234"), outcome.newlyKevCveIds());
		assertEquals(0, outcome.updated());
		assertEquals(0, outcome.revived());
		assertEquals(0, outcome.revoked());
		assertEquals(2, outcome.total());
		assertEquals(2, captureSaved().size());
	}

	@Test
	void applyCatalogRequiresOrgUuid() {
		try {
			service.applyCatalog(null, KevSource.CISA, List.of(entry("CVE-2021-44228")));
		} catch (IllegalArgumentException e) {
			return;
		}
		throw new AssertionError("expected IllegalArgumentException when orgUuid is null");
	}

	@Test
	void newKevDetectionIgnoresCvesAlreadyAssertedByAnotherSourceInSameOrg() {
		when(repository.findByOrgIdAndSource(orgUuid, KevSource.CISA)).thenReturn(List.of());
		// CVE-EXISTING is already asserted (by some other source) in THIS org — not "new KEV"
		when(repository.findAllDistinctCveIdsForOrg(orgUuid)).thenReturn(List.of("CVE-EXISTING"));

		KevCatalogApplyOutcome outcome = service.applyCatalog(orgUuid, KevSource.CISA,
				List.of(entry("CVE-EXISTING"), entry("CVE-NEW")));

		assertEquals(List.of("CVE-NEW"), outcome.newlyKevCveIds());
		// both rows still inserted for CISA in this org
		assertEquals(2, captureSaved().size());
	}

	// ---------- applyCatalog: rewrite / skip ----------

	@Test
	void applyRewritesChangedAndSkipsUnchanged() {
		KevAssertionData unchanged = entry("CVE-2020-0001");
		KevAssertionData changed = entry("CVE-2020-0002");
		when(repository.findByOrgIdAndSource(orgUuid, KevSource.CISA)).thenReturn(List.of(
				existing("CVE-2020-0001", unchanged.toRecordData(), null),
				existing("CVE-2020-0002", entry("CVE-2020-0002", "old required action").toRecordData(), null)));

		KevCatalogApplyOutcome outcome = service.applyCatalog(orgUuid, KevSource.CISA,
				List.of(unchanged, changed));

		assertEquals(List.of(), outcome.newlyKevCveIds());
		assertEquals(1, outcome.updated());
		assertEquals(0, outcome.revived());
		List<KevAssertion> saved = captureSaved();
		assertEquals(1, saved.size());
		assertEquals("CVE-2020-0002", saved.get(0).getCveId());
		assertEquals(changed.toRecordData(), saved.get(0).getRecordData());
	}

	// ---------- applyCatalog: soft delete + revive ----------

	@Test
	void applySoftDeletesEntriesMissingFromFeedInsteadOfDeleting() {
		when(repository.findByOrgIdAndSource(orgUuid, KevSource.CISA)).thenReturn(List.of(
				existing("CVE-2020-0001", entry("CVE-2020-0001").toRecordData(), null),
				existing("CVE-2019-9999", entry("CVE-2019-9999").toRecordData(), null)));

		KevCatalogApplyOutcome outcome = service.applyCatalog(orgUuid, KevSource.CISA,
				List.of(entry("CVE-2020-0001")));

		assertEquals(1, outcome.revoked());
		List<KevAssertion> saved = captureSaved();
		assertEquals(1, saved.size());
		assertEquals("CVE-2019-9999", saved.get(0).getCveId());
		assertNotNull(saved.get(0).getRevokedDate(), "missing entry is marked revoked, not deleted");
	}

	@Test
	void applyRevivesAReappearedRevokedEntry() {
		KevAssertionData data = entry("CVE-2020-0001");
		when(repository.findByOrgIdAndSource(orgUuid, KevSource.CISA)).thenReturn(List.of(
				existing("CVE-2020-0001", data.toRecordData(), ZonedDateTime.now().minusDays(3))));

		KevCatalogApplyOutcome outcome = service.applyCatalog(orgUuid, KevSource.CISA, List.of(data));

		assertEquals(1, outcome.revived());
		assertEquals(0, outcome.updated());
		assertEquals(0, outcome.revoked());
		List<KevAssertion> saved = captureSaved();
		assertEquals(1, saved.size());
		assertNull(saved.get(0).getRevokedDate(), "reappeared entry is un-revoked");
	}

	@Test
	void applyDedupesAndNormalizesFeedEntries() {
		when(repository.findByOrgIdAndSource(orgUuid, KevSource.CISA)).thenReturn(List.of());

		KevCatalogApplyOutcome outcome = service.applyCatalog(orgUuid, KevSource.CISA, List.of(
				entry("cve-2021-44228"), // lowercase — normalized
				entry("CVE-2021-44228"), // duplicate — dropped
				entry("not-a-cve")));    // non-CVE — dropped

		assertEquals(List.of("CVE-2021-44228"), outcome.newlyKevCveIds());
		assertEquals(1, outcome.total());
	}

	// ---------- getKevDetails aggregation ----------

	@Test
	void detailsAggregateRansomwareAndSurfaceRevokedNote() {
		KevAssertionData known = entry("CVE-2021-44228");
		known.setRansomwareStatus(KevRansomwareStatus.KNOWN);
		KevAssertion active = existing("CVE-2021-44228", known.toRecordData(), null);
		active.setSource(KevSource.CISA);

		KevAssertionData unspecified = entry("CVE-2021-44228");
		ZonedDateTime revoked = ZonedDateTime.now().minusDays(1);
		KevAssertion withdrawn = existing("CVE-2021-44228", unspecified.toRecordData(), revoked);
		withdrawn.setSource(KevSource.CISA);

		when(repository.findByOrgIdAndCveIdOrderBySourceAsc(orgUuid, "CVE-2021-44228"))
				.thenReturn(List.of(active, withdrawn));

		Optional<KevRecordDetails> details = service.getKevDetails(orgUuid, "cve-2021-44228");

		assertTrue(details.isPresent());
		assertEquals("CVE-2021-44228", details.get().cveId());
		assertEquals(KevRansomwareStatus.KNOWN, details.get().ransomwareStatus());
		assertEquals(2, details.get().assertions().size());
		assertNull(details.get().assertions().get(0).revokedDate());
		assertNotNull(details.get().assertions().get(1).revokedDate());
	}

	@Test
	void detailsEmptyWhenNoAssertions() {
		when(repository.findByOrgIdAndCveIdOrderBySourceAsc(eq(orgUuid), eq("CVE-0000-0000")))
				.thenReturn(List.of());
		assertTrue(service.getKevDetails(orgUuid, "CVE-0000-0000").isEmpty());
		assertTrue(service.getKevDetails(orgUuid, "not-a-cve").isEmpty());
		assertTrue(service.getKevDetails(null, "CVE-2021-44228").isEmpty());
	}

	// ---------- helpers ----------

	private List<KevAssertion> captureSaved() {
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<KevAssertion>> saved = ArgumentCaptor.forClass((Class) List.class);
		verify(repository).saveAll(saved.capture());
		return saved.getValue();
	}

	private static KevAssertionData entry(String cveId) {
		return entry(cveId, "Apply updates per vendor instructions.");
	}

	private static KevAssertionData entry(String cveId, String requiredAction) {
		KevAssertionData kad = new KevAssertionData();
		kad.setCveId(cveId);
		kad.setVendorProject("Vendor");
		kad.setProduct("Product");
		kad.setVulnerabilityName(cveId + " vuln");
		kad.setDateAdded("2026-06-01");
		kad.setShortDescription("desc");
		kad.setRequiredAction(requiredAction);
		kad.setDueDate("2026-06-22");
		kad.setRansomwareStatus(KevRansomwareStatus.UNKNOWN);
		kad.setCwes(new ArrayList<>(List.of("CWE-502")));
		return kad;
	}

	private static KevAssertion existing(String cveId, Map<String, Object> recordData, ZonedDateTime revokedDate) {
		KevAssertion ka = new KevAssertion();
		ka.setUuid(UUID.randomUUID());
		ka.setSource(KevSource.CISA);
		ka.setCveId(cveId);
		ka.setCreatedDate(ZonedDateTime.now().minusDays(10));
		ka.setLastUpdatedDate(ZonedDateTime.now().minusDays(10));
		ka.setRevokedDate(revokedDate);
		ka.setRecordData(recordData);
		return ka;
	}
}
