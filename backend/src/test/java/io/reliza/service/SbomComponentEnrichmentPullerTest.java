/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.SbomComponent;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.model.tea.Rebom.ParsedBom;
import io.reliza.model.tea.Rebom.ParsedBomComponent;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.ReleaseArtifactIndexRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentSupportAuditRepository;

/** Unit tests for the BEAR-enrichment puller in {@link SbomComponentService}. */
@ExtendWith(MockitoExtension.class)
class SbomComponentEnrichmentPullerTest {

	@Mock private SbomComponentRepository sbomComponentRepository;
	@Mock private ArtifactSbomComponentRepository artifactSbomComponentRepository;
	@Mock private ReleaseArtifactIndexRepository releaseArtifactIndexRepository;
	@Mock private ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
	@Mock private SbomComponentSupportAuditRepository sbomComponentSupportAuditRepository;
	@Mock private ReleaseRepository releaseRepository;
	@Mock private RebomService rebomService;
	@Mock private ArtifactService artifactService;

	private SbomComponentService service;

	private final UUID ORG = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		service = new SbomComponentService(
				sbomComponentRepository, artifactSbomComponentRepository,
				releaseArtifactIndexRepository, artifactCanonicalMapRepository,
				sbomComponentSupportAuditRepository);
		ReflectionTestUtils.setField(service, "releaseRepository", releaseRepository);
		ReflectionTestUtils.setField(service, "rebomService", rebomService);
		ReflectionTestUtils.setField(service, "artifactService", artifactService);
		// stampEnrichedLicenses is invoked through the self-proxy; point it at the
		// real instance so the @Transactional method body actually runs in the test.
		ReflectionTestUtils.setField(service, "self", service);
	}

	private SbomComponent comp(String canonicalPurl) {
		SbomComponent sc = new SbomComponent();
		sc.setUuid(UUID.randomUUID());
		sc.setOrg(ORG);
		sc.setCanonicalPurl(canonicalPurl);
		return sc;
	}

	private List<Map<String, Object>> licenses(String id) {
		return List.of(Map.of("license", Map.of("id", id)));
	}

	/** Wire a candidate component to a BOM via its representative artifact. */
	private void wireComponentToBom(SbomComponent sc, UUID bomId) {
		UUID canonicalArtifact = UUID.randomUUID();
		ArtifactSbomComponent asc = new ArtifactSbomComponent();
		asc.setOrg(ORG);
		asc.setCanonicalArtifactUuid(canonicalArtifact);
		asc.setSbomComponentUuid(sc.getUuid());
		lenient().when(artifactSbomComponentRepository
				.findFirstByOrgAndSbomComponentUuid(ORG, sc.getUuid()))
				.thenReturn(Optional.of(asc));
		ArtifactData ad = org.mockito.Mockito.mock(ArtifactData.class);
		lenient().when(ad.getInternalBom()).thenReturn(new InternalBom(bomId, null));
		lenient().when(artifactService.getArtifactData(canonicalArtifact)).thenReturn(Optional.of(ad));
	}

	@Test
	void skipsEntirelyWhenBearNotConfigured() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(false);

		service.pullEnrichmentForOrg(ORG);

		verify(sbomComponentRepository, never()).findUnenrichedMatchableByOrgOrdered(any(), anyInt());
		verify(rebomService, never()).parseBom(any(), any());
	}

	@Test
	void stampsCompletedBomComponentsFillNullsOnly() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent candidate = comp("pkg:npm/foo@1.0");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(candidate));
		UUID bomId = UUID.randomUUID();
		wireComponentToBom(candidate, bomId);
		when(rebomService.getBomMetadataById(bomId, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));

		// BOM carries two components: the un-enriched candidate (gets stamped) and a
		// co-resident already enriched by a prior pull (must be left untouched).
		SbomComponent alreadyEnriched = comp("pkg:npm/bar@2.0");
		alreadyEnriched.setEnrichedAt(java.time.ZonedDateTime.now().minusDays(1));
		alreadyEnriched.setLicenses(licenses("Apache-2.0"));
		when(rebomService.parseBom(bomId, ORG)).thenReturn(new ParsedBom(
				List.of(pc("pkg:npm/foo@1.0", licenses("MIT")),
						pc("pkg:npm/bar@2.0", licenses("GPL-3.0"))),
				List.of()));
		when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(eq(ORG.toString()), any()))
				.thenReturn(List.of(candidate, alreadyEnriched));

		service.pullEnrichmentForOrg(ORG);

		// Candidate stamped with enriched licenses + enriched_at.
		assertNotNull(candidate.getEnrichedAt());
		org.junit.jupiter.api.Assertions.assertEquals(licenses("MIT"), candidate.getLicenses());
		verify(sbomComponentRepository).save(candidate);
		// Already-enriched co-resident untouched (no save, original licenses kept).
		org.junit.jupiter.api.Assertions.assertEquals(licenses("Apache-2.0"), alreadyEnriched.getLicenses());
		verify(sbomComponentRepository, never()).save(alreadyEnriched);
	}

	@Test
	void skipsPendingBomAndPullsNextCompleted() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent pendingComp = comp("pkg:npm/pending@1.0");
		SbomComponent readyComp = comp("pkg:npm/ready@1.0");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(pendingComp, readyComp));
		UUID pendingBom = UUID.randomUUID();
		UUID readyBom = UUID.randomUUID();
		wireComponentToBom(pendingComp, pendingBom);
		wireComponentToBom(readyComp, readyBom);
		when(rebomService.getBomMetadataById(pendingBom, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.PENDING));
		when(rebomService.getBomMetadataById(readyBom, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));
		when(rebomService.parseBom(readyBom, ORG)).thenReturn(new ParsedBom(
				List.of(pc("pkg:npm/ready@1.0", licenses("MIT"))), List.of()));
		when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(eq(ORG.toString()), any()))
				.thenReturn(List.of(readyComp));

		service.pullEnrichmentForOrg(ORG);

		// PENDING bom not parsed; the next (COMPLETED) bom is pulled — "add one more".
		verify(rebomService, never()).parseBom(eq(pendingBom), any());
		verify(rebomService, times(1)).parseBom(readyBom, ORG);
		assertNotNull(readyComp.getEnrichedAt());
		assertNull(pendingComp.getEnrichedAt());
	}

	private static RebomService.BomMeta meta(RebomService.EnrichmentStatus status) {
		return new RebomService.BomMeta(null, null, null, null, null, null, null, null, null,
				null, null, null, status, null, null, null);
	}

	private static ParsedBomComponent pc(String canonicalPurl, List<Map<String, Object>> licenses) {
		return new ParsedBomComponent(canonicalPurl, canonicalPurl, "library", null,
				canonicalPurl, "1.0", false, null, licenses);
	}

	/**
	 * The 2026-07-26 diagnosis: {COMPLETED=N} heading the un-enriched backlog --
	 * BOMs pulled every tick, ZERO rows stamped, because stored canonicals are
	 * qualifier-stripped (pre-#281 era, V66 skipped) while the fresh parse emits
	 * qualifier-bearing forms. License metadata is qualifier-invariant, so the
	 * stamp must cross qualifier variants via the coordinate pass.
	 */
	@org.junit.jupiter.api.Test
	void stampsQualifierStrippedStoredRowFromQualifierBearingParse() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent strippedStored = comp("pkg:oci/node@latest");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(strippedStored));
		UUID bomId = UUID.randomUUID();
		wireComponentToBom(strippedStored, bomId);
		when(rebomService.getBomMetadataById(bomId, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));
		when(rebomService.parseBom(bomId, ORG)).thenReturn(new ParsedBom(
				List.of(pc("pkg:oci/node@latest?repository_url=docker.io%2Flibrary", licenses("MIT"))),
				List.of()));
		// Byte match finds nothing (stripped vs qualifier-bearing).
		when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(eq(ORG.toString()), any()))
				.thenReturn(List.of());
		when(sbomComponentRepository.findCandidatesByOrgAndNames(eq(ORG), any()))
				.thenReturn(List.of(strippedStored));

		service.pullEnrichmentForOrg(ORG);

		org.junit.jupiter.api.Assertions.assertNotNull(strippedStored.getEnrichedAt(),
				"a stripped-era stored row must be stamped from the qualifier-bearing fresh parse");
		org.junit.jupiter.api.Assertions.assertEquals(licenses("MIT"), strippedStored.getLicenses());
		verify(sbomComponentRepository).save(strippedStored);
	}

	/** Coordinates must actually match: a different version is a different package. */
	@org.junit.jupiter.api.Test
	void coordinatePassDoesNotStampDifferentVersion() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent otherVersion = comp("pkg:oci/node@18");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(otherVersion));
		UUID bomId = UUID.randomUUID();
		wireComponentToBom(otherVersion, bomId);
		when(rebomService.getBomMetadataById(bomId, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));
		when(rebomService.parseBom(bomId, ORG)).thenReturn(new ParsedBom(
				List.of(pc("pkg:oci/node@latest?repository_url=docker.io%2Flibrary", licenses("MIT"))),
				List.of()));
		when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(eq(ORG.toString()), any()))
				.thenReturn(List.of());
		lenient().when(sbomComponentRepository.findCandidatesByOrgAndNames(eq(ORG), any()))
				.thenReturn(List.of(otherVersion));

		service.pullEnrichmentForOrg(ORG);

		org.junit.jupiter.api.Assertions.assertNull(otherVersion.getEnrichedAt(),
				"version mismatch means different coordinates -- must NOT be stamped");
		verify(sbomComponentRepository, never()).save(otherVersion);
	}

	/**
	 * Pull-slot de-heading: a BOM pulled this window must not be re-pulled next
	 * tick even if some of its components remain un-enriched (stubborn heads
	 * otherwise monopolize the pull budget -- {COMPLETED=N} persisting in the
	 * 2026-07-26 stall diagnostics).
	 */
	@org.junit.jupiter.api.Test
	void recentlyPulledBomIsNotRepulledWithinBackoff() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent stubborn = comp("pkg:npm/stubborn@1.0");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(stubborn));
		UUID bomId = UUID.randomUUID();
		wireComponentToBom(stubborn, bomId);
		when(rebomService.getBomMetadataById(bomId, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));
		// Fresh parse shares no coordinates with the stubborn candidate -> nothing stamps.
		when(rebomService.parseBom(bomId, ORG)).thenReturn(new ParsedBom(
				List.of(pc("pkg:npm/other@9.9", licenses("MIT"))), List.of()));
		when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(eq(ORG.toString()), any()))
				.thenReturn(List.of());
		lenient().when(sbomComponentRepository.findCandidatesByOrgAndNames(any(), any()))
				.thenReturn(List.of());

		service.pullEnrichmentForOrg(ORG);
		service.pullEnrichmentForOrg(ORG);

		verify(rebomService, org.mockito.Mockito.times(1)).parseBom(bomId, ORG);
	}

	/**
	 * Twin-copy: an un-enriched candidate sharing coordinates with an
	 * already-enriched row inherits its enrichment DB-side -- and no BOM is
	 * pulled at all for it (the whole point: rebom stays out of it).
	 */
	@org.junit.jupiter.api.Test
	void twinCopyStampsFromEnrichedCoordinateMateWithoutAnyPull() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent stripped = comp("pkg:apk/alpine/musl@1.2.4");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(stripped));
		SbomComponent enrichedTwin = comp("pkg:apk/alpine/musl@1.2.4?distro=alpine-3.18");
		enrichedTwin.setEnrichedAt(java.time.ZonedDateTime.now().minusDays(2));
		enrichedTwin.setLicenses(licenses("MIT"));
		when(sbomComponentRepository.findCandidatesByOrgAndNames(eq(ORG), any()))
				.thenReturn(List.of(stripped, enrichedTwin));

		service.pullEnrichmentForOrg(ORG);

		org.junit.jupiter.api.Assertions.assertNotNull(stripped.getEnrichedAt(),
				"candidate must inherit enrichment from its coordinate mate");
		org.junit.jupiter.api.Assertions.assertEquals(licenses("MIT"), stripped.getLicenses());
		verify(rebomService, org.mockito.Mockito.never()).parseBom(any(), any());
	}

	/**
	 * Same name+version under a DIFFERENT purl type is a different package --
	 * the type/namespace check in purlsSameCoordinates is what makes twin-copy
	 * safe. No inheritance may cross it.
	 */
	@org.junit.jupiter.api.Test
	void twinCopyRefusesCrossTypeNameCollision() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent apk = comp("pkg:apk/alpine/musl@1.2.4");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(apk));
		SbomComponent golangImpostor = comp("pkg:golang/musl@1.2.4");
		golangImpostor.setEnrichedAt(java.time.ZonedDateTime.now().minusDays(2));
		golangImpostor.setLicenses(licenses("BSD-3-Clause"));
		when(sbomComponentRepository.findCandidatesByOrgAndNames(eq(ORG), any()))
				.thenReturn(List.of(apk, golangImpostor));

		service.pullEnrichmentForOrg(ORG);

		org.junit.jupiter.api.Assertions.assertNull(apk.getEnrichedAt(),
				"a different purl type is a different package -- no cross-type inheritance");
	}

	/**
	 * De-heading: a head candidate whose BOM is in pull backoff must not consume
	 * a probe slot -- the dedupe walks past it and pulls a fresh BOM deeper in
	 * the window on the SAME tick.
	 */
	@org.junit.jupiter.api.Test
	void backoffHeadDoesNotBlockDeeperPullableBoms() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent stubbornHead = comp("pkg:npm/stubborn@1.0");
		SbomComponent freshDeeper = comp("pkg:npm/deeper@1.0");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(stubbornHead))
				.thenReturn(List.of(stubbornHead, freshDeeper));
		UUID stubbornBom = UUID.randomUUID();
		UUID freshBom = UUID.randomUUID();
		wireComponentToBom(stubbornHead, stubbornBom);
		wireComponentToBom(freshDeeper, freshBom);
		when(rebomService.getBomMetadataById(stubbornBom, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));
		when(rebomService.getBomMetadataById(freshBom, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));
		// Neither BOM's parse stamps anything (stubborn residue shape).
		when(rebomService.parseBom(any(), eq(ORG))).thenReturn(new ParsedBom(
				List.of(pc("pkg:npm/other@9.9", licenses("MIT"))), List.of()));
		when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(eq(ORG.toString()), any()))
				.thenReturn(List.of());
		lenient().when(sbomComponentRepository.findCandidatesByOrgAndNames(any(), any()))
				.thenReturn(List.of());

		service.pullEnrichmentForOrg(ORG); // tick 1: pulls stubbornBom, backoff starts
		service.pullEnrichmentForOrg(ORG); // tick 2: must step past it and pull freshBom

		verify(rebomService, org.mockito.Mockito.times(1)).parseBom(stubbornBom, ORG);
		verify(rebomService, org.mockito.Mockito.times(1)).parseBom(freshBom, ORG);
	}

	/**
	 * One-strike terminal: a candidate whose OWN representative BOM was fully
	 * pulled and still could not be matched is provably unenrichable -- it must
	 * be marked terminal so it exits the candidate window instead of squatting
	 * at its head forever ({COMPLETED=N} residue decay).
	 */
	@org.junit.jupiter.api.Test
	void unmatchedCandidateIsMarkedTerminalAfterOwnBomPull() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent residue = comp("pkg:generic/legacy-era-name@1.0");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(residue));
		UUID bomId = UUID.randomUUID();
		wireComponentToBom(residue, bomId);
		when(rebomService.getBomMetadataById(bomId, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));
		// Fresh parse shares nothing with the residue candidate.
		when(rebomService.parseBom(bomId, ORG)).thenReturn(new ParsedBom(
				List.of(pc("pkg:npm/modern@2.0", licenses("MIT"))), List.of()));
		when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(eq(ORG.toString()), any()))
				.thenReturn(List.of());
		lenient().when(sbomComponentRepository.findCandidatesByOrgAndNames(any(), any()))
				.thenReturn(List.of());
		when(sbomComponentRepository.findById(residue.getUuid())).thenReturn(Optional.of(residue));
		when(sbomComponentRepository.markEnrichmentTerminal(eq(residue.getUuid()), any())).thenReturn(1);

		service.pullEnrichmentForOrg(ORG);

		verify(sbomComponentRepository).markEnrichmentTerminal(eq(residue.getUuid()),
				eq("OWN_BOM_PULLED_UNMATCHED"));
	}

	/** A candidate the pull DID stamp must never be marked terminal. */
	@org.junit.jupiter.api.Test
	void stampedCandidateIsNotMarkedTerminal() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent candidate = comp("pkg:npm/foo@1.0");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(candidate));
		UUID bomId = UUID.randomUUID();
		wireComponentToBom(candidate, bomId);
		when(rebomService.getBomMetadataById(bomId, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));
		when(rebomService.parseBom(bomId, ORG)).thenReturn(new ParsedBom(
				List.of(pc("pkg:npm/foo@1.0", licenses("MIT"))), List.of()));
		when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(eq(ORG.toString()), any()))
				.thenReturn(List.of(candidate));
		lenient().when(sbomComponentRepository.findCandidatesByOrgAndNames(any(), any()))
				.thenReturn(List.of());
		lenient().when(sbomComponentRepository.findById(candidate.getUuid())).thenReturn(Optional.of(candidate));

		service.pullEnrichmentForOrg(ORG);

		verify(sbomComponentRepository, org.mockito.Mockito.never()).markEnrichmentTerminal(any(), any());
	}

	@SuppressWarnings("unchecked")
	private void clearPullBackoff() {
		((java.util.Map<UUID, java.time.Instant>) ReflectionTestUtils
				.getField(service, "recentlyPulledBoms")).clear();
	}

	/**
	 * The gap that kept terminal=0 in prod: a COMPLETED BOM whose parse returns
	 * null/empty previously hit a SILENT continue -- no strike, no backoff, no
	 * log -- so its candidates squatted in the window forever. Now: 3
	 * consecutive failed pulls => candidates struck OWN_BOM_UNPULLABLE.
	 */
	@org.junit.jupiter.api.Test
	void unparseableBomStrikesCandidatesAfterThreeConsecutiveFailures() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent stuck = comp("pkg:generic/lost-era@1.0");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(stuck));
		UUID bomId = UUID.randomUUID();
		wireComponentToBom(stuck, bomId);
		when(rebomService.getBomMetadataById(bomId, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));
		when(rebomService.parseBom(bomId, ORG)).thenReturn(null); // unparseable/lost content
		lenient().when(sbomComponentRepository.findCandidatesByOrgAndNames(any(), any()))
				.thenReturn(List.of());
		when(sbomComponentRepository.findById(stuck.getUuid())).thenReturn(Optional.of(stuck));
		when(sbomComponentRepository.markEnrichmentTerminal(eq(stuck.getUuid()), any())).thenReturn(1);

		service.pullEnrichmentForOrg(ORG);      // failure 1 -> backoff, no strike
		verify(sbomComponentRepository, org.mockito.Mockito.never()).markEnrichmentTerminal(any(), any());
		clearPullBackoff();
		service.pullEnrichmentForOrg(ORG);      // failure 2
		verify(sbomComponentRepository, org.mockito.Mockito.never()).markEnrichmentTerminal(any(), any());
		clearPullBackoff();
		service.pullEnrichmentForOrg(ORG);      // failure 3 -> strike

		verify(sbomComponentRepository).markEnrichmentTerminal(eq(stuck.getUuid()), eq("OWN_BOM_UNPULLABLE"));
	}

	/** A successful pull resets the consecutive-failure counter. */
	@org.junit.jupiter.api.Test
	void successfulPullResetsFailureCounter() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		SbomComponent flaky = comp("pkg:npm/flaky@1.0");
		when(sbomComponentRepository.findUnenrichedMatchableByOrgOrdered(eq(ORG.toString()), anyInt()))
				.thenReturn(List.of(flaky));
		UUID bomId = UUID.randomUUID();
		wireComponentToBom(flaky, bomId);
		when(rebomService.getBomMetadataById(bomId, ORG)).thenReturn(meta(RebomService.EnrichmentStatus.COMPLETED));
		// fail, fail, succeed(without matching flaky), fail, fail -> never 3 consecutive
		when(rebomService.parseBom(bomId, ORG))
				.thenReturn(null).thenReturn(null)
				.thenReturn(new ParsedBom(List.of(pc("pkg:npm/other@9.9", licenses("MIT"))), List.of()))
				.thenReturn(null).thenReturn(null);
		when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(eq(ORG.toString()), any()))
				.thenReturn(List.of());
		lenient().when(sbomComponentRepository.findCandidatesByOrgAndNames(any(), any()))
				.thenReturn(List.of());
		lenient().when(sbomComponentRepository.findById(flaky.getUuid())).thenReturn(Optional.of(flaky));

		for (int i = 0; i < 5; i++) { service.pullEnrichmentForOrg(ORG); clearPullBackoff(); }

		// the successful pull DID strike via the one-strike matched path? flaky was
		// unmatched by the success parse -> one-strike marks it there. Accept either
		// terminal reason but NEVER the unpullable one before 3 consecutive.
		verify(sbomComponentRepository, org.mockito.Mockito.never())
				.markEnrichmentTerminal(any(), eq("OWN_BOM_UNPULLABLE"));
	}
}
