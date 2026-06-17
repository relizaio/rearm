/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.reliza.model.Artifact;
import io.reliza.model.ArtifactCanonicalMap;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.ComponentIdentity;
import io.reliza.model.SbomComponent;
import io.reliza.model.SyntheticDtrackBucket;
import io.reliza.model.SyntheticDtrackBucket.IngestState;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityAliasDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityAliasType;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SyntheticDtrackBucketRepository;
import io.reliza.service.DTrackService.SyntheticFindings;
import io.reliza.service.IntegrationService.ViolationWithCpe;
import io.reliza.service.IntegrationService.VulnWithCpe;
import tools.jackson.databind.JsonNode;

@ExtendWith(MockitoExtension.class)
class SyntheticSbomServiceTest {

	@Mock private SbomComponentRepository sbomComponentRepository;
	@Mock private SyntheticDtrackBucketRepository bucketRepository;
	@Mock private ArtifactSbomComponentRepository artifactSbomComponentRepository;
	@Mock private ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
	@Mock private SharedArtifactService sharedArtifactService;
	@Mock private DTrackService dTrackService;
	@Mock private RebomService rebomService;
	@Mock private IntegrationService integrationService;
	@Mock private VulnAnalysisService vulnAnalysisService;

	@InjectMocks private SyntheticSbomService service;

	// Simulate the real enrichment with no org analysis records: organize aliases
	// only (what processReleaseMetricsDto reduces to when nothing matches in the DB).
	@org.junit.jupiter.api.BeforeEach
	void stubVulnAnalysis() {
		lenient().doAnswer(inv -> {
			io.reliza.model.dto.ReleaseMetricsDto m = inv.getArgument(3);
			m.computeMetricsFromFacts();
			return null;
		}).when(vulnAnalysisService).processReleaseMetricsDto(any(), any(), any(), any());
	}

	private final UUID ORG = UUID.randomUUID();
	private final UUID PROJ = UUID.randomUUID();

	private SbomComponent comp(String canonicalPurl) {
		SbomComponent sc = new SbomComponent();
		sc.setOrg(ORG);
		sc.setCanonicalPurl(canonicalPurl);
		Map<String, Object> rd = new HashMap<>();
		rd.put("name", canonicalPurl);
		sc.setRecordData(rd);
		return sc;
	}

	private List<SbomComponent> comps(int n) {
		List<SbomComponent> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) out.add(comp("pkg:npm/c" + i + "@1.0"));
		return out;
	}

	/** Wire the bucket repo to behave like an in-memory store so submit/ingest round-trip. */
	private Map<Integer, SyntheticDtrackBucket> wireBucketStore() {
		Map<Integer, SyntheticDtrackBucket> store = new HashMap<>();
		lenient().when(bucketRepository.findByOrgAndBucketIndex(eq(ORG), anyInt()))
				.thenAnswer(inv -> Optional.ofNullable(store.get((Integer) inv.getArgument(1))));
		lenient().when(bucketRepository.save(any())).thenAnswer(inv -> {
			SyntheticDtrackBucket b = inv.getArgument(0);
			store.put(b.getBucketIndex(), b);
			return b;
		});
		return store;
	}

	@Test
	void slicesComponentsIntoBucketsOf500() throws Exception {
		wireBucketStore();
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(false);
		when(sbomComponentRepository.findMatchableByOrgOrdered(ORG.toString())).thenReturn(comps(1001));
		when(dTrackService.syntheticGetOrCreateProject(eq(ORG), any(), any())).thenReturn(PROJ);
		when(dTrackService.syntheticUploadBom(eq(ORG), eq(PROJ), any(), any(), any())).thenReturn("tok");

		service.submitOrg(ORG);

		// 1001 components -> ceil(1001/500) = 3 buckets, each uploaded once.
		verify(dTrackService, times(3)).syntheticUploadBom(eq(ORG), eq(PROJ), any(), any(), any());
	}

	@Test
	void skipsReuploadWhenMembershipUnchanged() throws Exception {
		wireBucketStore();
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(false);
		when(sbomComponentRepository.findMatchableByOrgOrdered(ORG.toString())).thenReturn(comps(10));
		when(dTrackService.syntheticGetOrCreateProject(eq(ORG), any(), any())).thenReturn(PROJ);
		when(dTrackService.syntheticUploadBom(eq(ORG), eq(PROJ), any(), any(), any())).thenReturn("tok");

		service.submitOrg(ORG); // first submit uploads
		service.submitOrg(ORG); // unchanged hash + SUBMITTED -> skip

		verify(dTrackService, times(1)).syntheticUploadBom(eq(ORG), eq(PROJ), any(), any(), any());
	}

	@Test
	void retriesFailedBucket() throws Exception {
		Map<Integer, SyntheticDtrackBucket> store = wireBucketStore();
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(false);
		when(sbomComponentRepository.findMatchableByOrgOrdered(ORG.toString())).thenReturn(comps(10));
		when(dTrackService.syntheticGetOrCreateProject(eq(ORG), any(), any())).thenReturn(PROJ);
		when(dTrackService.syntheticUploadBom(eq(ORG), eq(PROJ), any(), any(), any())).thenReturn("tok");

		service.submitOrg(ORG); // uploads, bucket SUBMITTED
		store.get(0).setIngestState(IngestState.FAILED); // simulate a prior failure
		service.submitOrg(ORG); // FAILED + unchanged hash must retry

		verify(dTrackService, times(2)).syntheticUploadBom(eq(ORG), eq(PROJ), any(), any(), any());
	}

	@Test
	void reorderedComponentSetKeepsSameHashSoNoReupload() throws Exception {
		wireBucketStore();
		List<SbomComponent> first = comps(5);
		List<SbomComponent> reordered = new ArrayList<>(first);
		java.util.Collections.reverse(reordered);
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(false);
		when(sbomComponentRepository.findMatchableByOrgOrdered(ORG.toString()))
				.thenReturn(first, reordered);
		when(dTrackService.syntheticGetOrCreateProject(eq(ORG), any(), any())).thenReturn(PROJ);
		when(dTrackService.syntheticUploadBom(eq(ORG), eq(PROJ), any(), any(), any())).thenReturn("tok");

		service.submitOrg(ORG);
		service.submitOrg(ORG); // same set, different order -> hash unchanged -> skip

		verify(dTrackService, times(1)).syntheticUploadBom(eq(ORG), eq(PROJ), any(), any(), any());
	}

	@Test
	void newLowSortingComponentReSubmitsOnlyItsOwnBucket() throws Exception {
		// Sticky bucketing: a component keeps its bucket for life, so adding one
		// must not perturb already-full buckets. With the old positional slicing a
		// new low-sorting component shifted every later component and re-submitted
		// every downstream bucket; here only the new component's bucket uploads.
		wireBucketStore();
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(false);

		// First submit: exactly BUCKET_SIZE components fill bucket 0.
		List<SbomComponent> initial = comps(SyntheticSbomService.BUCKET_SIZE);
		// Second submit: same (now-assigned) objects + one new component whose purl
		// sorts ahead of every existing one ("a..." < "c...").
		List<SbomComponent> withNew = new ArrayList<>(initial);
		withNew.add(comp("pkg:npm/aaa@1.0"));
		when(sbomComponentRepository.findMatchableByOrgOrdered(ORG.toString()))
				.thenReturn(initial, withNew);
		when(dTrackService.syntheticGetOrCreateProject(eq(ORG), any(), any())).thenReturn(PROJ);
		when(dTrackService.syntheticUploadBom(eq(ORG), eq(PROJ), any(), any(), any())).thenReturn("tok");

		service.submitOrg(ORG); // bucket 0 uploaded
		service.submitOrg(ORG); // bucket 0 unchanged -> skip; new comp -> bucket 1 uploaded

		// Two uploads total (bucket 0 once, bucket 1 once) — NOT three (no bucket-0
		// re-upload). The old positional scheme would have re-hashed bucket 0.
		verify(dTrackService, times(2)).syntheticUploadBom(eq(ORG), eq(PROJ), any(), any(), any());
	}

	@Test
	void hasPendingSyntheticWork_trueWhenUnbucketedComponentExists() {
		when(sbomComponentRepository.existsUnbucketedMatchableByOrg(ORG.toString())).thenReturn(true);
		// short-circuits before the bucket probe
		assertTrue(service.hasPendingSyntheticWork(ORG));
	}

	@Test
	void hasPendingSyntheticWork_trueWhenFailedBucketExists() {
		when(sbomComponentRepository.existsUnbucketedMatchableByOrg(ORG.toString())).thenReturn(false);
		when(bucketRepository.existsByOrgAndIngestState(ORG, IngestState.FAILED)).thenReturn(true);
		assertTrue(service.hasPendingSyntheticWork(ORG));
	}

	@Test
	void hasPendingSyntheticWork_falseWhenIdle() {
		when(sbomComponentRepository.existsUnbucketedMatchableByOrg(ORG.toString())).thenReturn(false);
		when(bucketRepository.existsByOrgAndIngestState(ORG, IngestState.FAILED)).thenReturn(false);
		assertFalse(service.hasPendingSyntheticWork(ORG));
	}

	@Test
	void ingestMapsPurlAndCpeFindingsToCanonicalAndDropsUnmappable() throws Exception {
		// A purl-canonical component with one extra CPE companion: the identity
		// map carries purl->canonical and the companion cpe->canonical.
		String canonical = "pkg:npm/foo@1.0";
		String companionCpe = "cpe:2.3:a:foo:foo:1.0:*:*:*:*:*:*:*";
		SyntheticDtrackBucket b = new SyntheticDtrackBucket();
		b.setOrg(ORG);
		b.setBucketIndex(0);
		b.setDtrackProjectUuid(PROJ);
		b.setIngestState(IngestState.SUBMITTED);
		b.getRefMap().put("__token", "tok");
		b.getRefMap().put(canonical, canonical);
		b.getRefMap().put(companionCpe, canonical);
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(b));
		when(dTrackService.syntheticIsTokenProcessing(ORG, "tok")).thenReturn(false);

		// finding via purl
		VulnerabilityDto byPurl = vuln("pkg:npm/foo@1.0", "CVE-1");
		// finding via CPE companion (no purl) -> must map to the same canonical
		VulnerabilityDto byCpe = vuln(null, "CVE-2");
		// unmappable (no purl, no/unknown cpe) -> dropped
		VulnerabilityDto orphan = vuln(null, "CVE-3");
		ViolationDto viol = new ViolationDto("pkg:npm/foo@1.0", null, null, null, null, null, null, null);
		when(dTrackService.syntheticFetchFindings(ORG, PROJ)).thenReturn(new SyntheticFindings(
				List.of(new VulnWithCpe(byPurl, null),
						new VulnWithCpe(byCpe, companionCpe),
						new VulnWithCpe(orphan, "cpe:2.3:a:unknown:x:1:*:*:*:*:*:*:*")),
				List.of(new ViolationWithCpe(viol, null))));

		service.ingestOrgBuckets(ORG);

		ArgumentCaptor<SyntheticDtrackBucket> cap = ArgumentCaptor.forClass(SyntheticDtrackBucket.class);
		verify(bucketRepository).save(cap.capture());
		SyntheticDtrackBucket saved = cap.getValue();
		assertEquals(IngestState.INGESTED, saved.getIngestState());
		Map<String, Object> findings = saved.getFindings();
		// purl + cpe findings both land on the one canonical; orphan dropped.
		assertEquals(1, findings.size());
		assertTrue(findings.containsKey(canonical));
		@SuppressWarnings("unchecked")
		Map<String, Object> entry = (Map<String, Object>) findings.get(canonical);
		assertEquals(2, ((List<?>) entry.get("vulns")).size());
		assertEquals(1, ((List<?>) entry.get("violations")).size());
	}

	@Test
	void ingestAliasMergesDuplicateVulns() throws Exception {
		// DTrack returns the same advisory twice — once as its CVE id, once as its
		// GHSA alias — both for the same component. Ingest must collapse them to one
		// (via the shared organizeVulnerabilitiesWithAliases), else the artifact write
		// (which collapses) never matches the raw bucket findings and churns forever.
		String canonical = "pkg:npm/ws@8.18.3";
		java.util.Set<VulnerabilityAliasDto> aliases = java.util.Set.of(
				new VulnerabilityAliasDto(VulnerabilityAliasType.CVE, "CVE-1"),
				new VulnerabilityAliasDto(VulnerabilityAliasType.GHSA, "GHSA-X"));
		VulnerabilityDto cve = new VulnerabilityDto(canonical, "CVE-1", VulnerabilitySeverity.HIGH,
				aliases, null, null, null, null, null, null, null, null, null, null, null);
		VulnerabilityDto ghsa = new VulnerabilityDto(canonical, "GHSA-X", VulnerabilitySeverity.MEDIUM,
				aliases, null, null, null, null, null, null, null, null, null, null, null);
		SyntheticDtrackBucket b = new SyntheticDtrackBucket();
		b.setOrg(ORG);
		b.setBucketIndex(0);
		b.setDtrackProjectUuid(PROJ);
		b.setIngestState(IngestState.SUBMITTED);
		b.getRefMap().put("__token", "tok");
		b.getRefMap().put(canonical, canonical);
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(b));
		when(dTrackService.syntheticIsTokenProcessing(ORG, "tok")).thenReturn(false);
		when(dTrackService.syntheticFetchFindings(ORG, PROJ)).thenReturn(new SyntheticFindings(
				List.of(new VulnWithCpe(cve, null), new VulnWithCpe(ghsa, null)), List.of()));

		service.ingestOrgBuckets(ORG);

		ArgumentCaptor<SyntheticDtrackBucket> cap = ArgumentCaptor.forClass(SyntheticDtrackBucket.class);
		verify(bucketRepository).save(cap.capture());
		@SuppressWarnings("unchecked")
		Map<String, Object> entry = (Map<String, Object>) cap.getValue().getFindings().get(canonical);
		assertEquals(1, ((List<?>) entry.get("vulns")).size());
	}

	@Test
	void emitsCompanionComponentsForExtraCpes() throws Exception {
		Map<Integer, SyntheticDtrackBucket> store = wireBucketStore();
		// One canonical with a purl + two CPEs -> primary (purl+cpe0) + 1 companion (cpe1).
		SbomComponent sc = comp("pkg:npm/multi@1.0");
		sc.setIdentities(List.of(
				idEntry("purl", "pkg:npm/multi@1.0"),
				idEntry("cpe", "cpe:2.3:a:v:multi:1.0:*:*:*:*:*:*:*"),
				idEntry("cpe", "cpe:2.3:a:v:multi-core:1.0:*:*:*:*:*:*:*")));
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(false);
		when(sbomComponentRepository.findMatchableByOrgOrdered(ORG.toString())).thenReturn(List.of(sc));
		when(dTrackService.syntheticGetOrCreateProject(eq(ORG), any(), any())).thenReturn(PROJ);
		ArgumentCaptor<JsonNode> bomCap =
				ArgumentCaptor.forClass(JsonNode.class);
		when(dTrackService.syntheticUploadBom(eq(ORG), eq(PROJ), bomCap.capture(), any(), any())).thenReturn("tok");

		service.submitOrg(ORG);

		// primary + 1 companion = 2 emitted components
		assertEquals(2, bomCap.getValue().get("components").size());
		// identity map (refMap) resolves both CPEs back to the canonical
		Map<String, Object> refMap = store.get(0).getRefMap();
		assertEquals("pkg:npm/multi@1.0", refMap.get("cpe:2.3:a:v:multi:1.0:*:*:*:*:*:*:*"));
		assertEquals("pkg:npm/multi@1.0", refMap.get("cpe:2.3:a:v:multi-core:1.0:*:*:*:*:*:*:*"));
		assertEquals("pkg:npm/multi@1.0", refMap.get("pkg:npm/multi@1.0"));
	}

	@Test
	void emitsCdx16AndPreservesLicenseAcknowledgement() throws Exception {
		// The synthetic BOM is emitted as CycloneDX 1.6 (DTrack 4.11+ supports it),
		// so newer SBOM-tool license fields like `acknowledgement` are valid and
		// must pass through untouched — they were only a problem under a 1.5
		// declaration. Guards against a regression back to specVersion 1.5.
		wireBucketStore();
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(false);
		SbomComponent sc = comp("pkg:npm/marked@0.3.6");
		Map<String, Object> licenseInner = new HashMap<>();
		licenseInner.put("id", "MIT");
		licenseInner.put("acknowledgement", "declared");
		Map<String, Object> licenseEntry = new HashMap<>();
		licenseEntry.put("license", licenseInner);
		sc.setLicenses(List.of(licenseEntry));
		when(sbomComponentRepository.findMatchableByOrgOrdered(ORG.toString())).thenReturn(List.of(sc));
		when(dTrackService.syntheticGetOrCreateProject(eq(ORG), any(), any())).thenReturn(PROJ);
		ArgumentCaptor<JsonNode> bomCap = ArgumentCaptor.forClass(JsonNode.class);
		when(dTrackService.syntheticUploadBom(eq(ORG), eq(PROJ), bomCap.capture(), any(), any())).thenReturn("tok");

		service.submitOrg(ORG);

		JsonNode bom = bomCap.getValue();
		assertEquals("1.6", bom.get("specVersion").asText());
		JsonNode lic = bom.get("components").get(0).get("licenses").get(0).get("license");
		assertEquals("MIT", lic.get("id").asText());
		assertEquals("declared", lic.get("acknowledgement").asText());
	}

	@Test
	void resyncRefetchesChangedBucketFindings() throws Exception {
		// A DTrack project re-analysed since lastSync (new advisory) must be re-pulled
		// and the bucket findings updated — the time-varying refresh the every-minute
		// submit/ingest doesn't do.
		String canonical = "pkg:npm/foo@1.0";
		SyntheticDtrackBucket b = new SyntheticDtrackBucket();
		b.setOrg(ORG);
		b.setBucketIndex(0);
		b.setDtrackProjectUuid(PROJ);
		b.setIngestState(IngestState.INGESTED);
		b.getRefMap().put(canonical, canonical);
		b.setFindings(new HashMap<>()); // previously: no findings
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(b));
		when(integrationService.retrieveUnsyncedDtrackProjects(eq(ORG), any()))
				.thenReturn(java.util.Set.of(PROJ));
		when(dTrackService.syntheticFetchFindings(ORG, PROJ)).thenReturn(new SyntheticFindings(
				List.of(new VulnWithCpe(vuln(canonical, "CVE-NEW"), null)), List.of()));

		service.resyncFindingsForOrg(ORG, java.time.ZonedDateTime.now().minusDays(1));

		ArgumentCaptor<SyntheticDtrackBucket> cap = ArgumentCaptor.forClass(SyntheticDtrackBucket.class);
		verify(bucketRepository).save(cap.capture());
		assertTrue(cap.getValue().getFindings().containsKey(canonical));
	}

	@Test
	void resyncSkipsWhenNothingChangedOnDtrack() throws Exception {
		when(integrationService.retrieveUnsyncedDtrackProjects(eq(ORG), any()))
				.thenReturn(java.util.Set.of());

		service.resyncFindingsForOrg(ORG, java.time.ZonedDateTime.now().minusDays(1));

		verify(dTrackService, never()).syntheticFetchFindings(any(), any());
		verify(bucketRepository, never()).save(any());
	}

	@Test
	void fanOutStampsCanonicalPurlAndArtifactSource() throws Exception {
		UUID sc = UUID.randomUUID(), canonicalArtifact = UUID.randomUUID(), artifact = UUID.randomUUID();
		String canonical = "cpe:2.3:a:openssl:openssl:1.0.1:*:*:*:*:*:*:*";

		// Bucket finding for a CPE-canonical component: no purl, null-artifact source.
		Map<String, Object> nullSource = new HashMap<>();
		nullSource.put("artifact", null);
		Map<String, Object> v = new HashMap<>();
		v.put("vulnId", "CVE-2021-4044");
		v.put("purl", null);
		v.put("sources", new ArrayList<>(List.of(nullSource)));
		Map<String, Object> entry = new HashMap<>();
		entry.put("vulns", new ArrayList<>(List.of(v)));
		Map<String, Object> findings = new HashMap<>();
		findings.put(canonical, entry);
		SyntheticDtrackBucket b = ingestedBucket(findings, canonical);
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(b));

		SbomComponent scomp = new SbomComponent();
		scomp.setUuid(sc); scomp.setOrg(ORG); scomp.setCanonicalPurl(canonical);
		when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(eq(ORG.toString()), any()))
				.thenReturn(List.of(scomp));
		when(sbomComponentRepository.findAllById(any())).thenReturn(List.of(scomp));
		when(artifactSbomComponentRepository
				.findDistinctCanonicalArtifactUuidsByOrgAndSbomComponentUuidIn(eq(ORG), any()))
				.thenReturn(List.of(canonicalArtifact));
		ArtifactSbomComponent asc = mock(ArtifactSbomComponent.class);
		when(asc.getSbomComponentUuid()).thenReturn(sc);
		when(artifactSbomComponentRepository.findByOrgAndCanonicalArtifactUuid(ORG, canonicalArtifact))
				.thenReturn(List.of(asc));
		ArtifactCanonicalMap acm = mock(ArtifactCanonicalMap.class);
		when(acm.getArtifactUuid()).thenReturn(artifact);
		when(artifactCanonicalMapRepository.findByOrgAndCanonicalArtifactUuid(ORG, canonicalArtifact))
				.thenReturn(List.of(acm));
		when(sharedArtifactService.getArtifact(artifact)).thenReturn(Optional.of(mock(Artifact.class)));

		service.fanOutOrg(ORG);

		ArgumentCaptor<DependencyTrackIntegration> dti = ArgumentCaptor.forClass(DependencyTrackIntegration.class);
		verify(sharedArtifactService).updateArtifactDti(any(), dti.capture(), any());
		VulnerabilityDto applied = dti.getValue().getVulnerabilityDetails().get(0);
		// purl stamped with the canonical (the cpe) so "PURL or Location" is populated
		assertEquals(canonical, applied.purl());
		// source stamped with the target artifact so "Sources" is populated
		assertEquals(artifact, applied.sources().iterator().next().artifact());
	}

	@Test
	void fanOutMarksCleanArtifactScanned() throws Exception {
		// A covered component with NO findings must still mark its artifact scanned
		// (bumps lastScanned) so it flips from "Scan pending" to "Scan done".
		UUID sc = UUID.randomUUID(), canonicalArtifact = UUID.randomUUID(), artifact = UUID.randomUUID();
		String canonical = "pkg:npm/gate-test-widget@1.0.0";
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(ingestedBucket(new HashMap<>(), canonical)));
		SbomComponent scomp = new SbomComponent();
		scomp.setUuid(sc); scomp.setOrg(ORG); scomp.setCanonicalPurl(canonical);
		when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(eq(ORG.toString()), any())).thenReturn(List.of(scomp));
		when(sbomComponentRepository.findAllById(any())).thenReturn(List.of(scomp));
		when(artifactSbomComponentRepository.findDistinctCanonicalArtifactUuidsByOrgAndSbomComponentUuidIn(eq(ORG), any()))
				.thenReturn(List.of(canonicalArtifact));
		ArtifactSbomComponent asc = mock(ArtifactSbomComponent.class);
		when(asc.getSbomComponentUuid()).thenReturn(sc);
		when(artifactSbomComponentRepository.findByOrgAndCanonicalArtifactUuid(ORG, canonicalArtifact)).thenReturn(List.of(asc));
		ArtifactCanonicalMap acm = mock(ArtifactCanonicalMap.class);
		when(acm.getArtifactUuid()).thenReturn(artifact);
		when(artifactCanonicalMapRepository.findByOrgAndCanonicalArtifactUuid(ORG, canonicalArtifact)).thenReturn(List.of(acm));
		when(sharedArtifactService.getArtifact(artifact)).thenReturn(Optional.of(mock(Artifact.class)));

		service.fanOutOrg(ORG);

		ArgumentCaptor<DependencyTrackIntegration> dti = ArgumentCaptor.forClass(DependencyTrackIntegration.class);
		verify(sharedArtifactService).updateArtifactDti(any(), dti.capture(), any());
		// no findings, but lastScanned is set -> updateArtifactDti will stamp firstScanned ("Scan done")
		assertTrue(dti.getValue().getVulnerabilityDetails().isEmpty());
		assertEquals(true, dti.getValue().getLastScanned() != null);
	}

	@Test
	void fanOutSkipsArtifactWithUncoveredComponent() throws Exception {
		// Artifact has two matchable components but only one is covered (ingested);
		// the other is still gated -> the artifact must NOT be marked scanned yet.
		UUID covered = UUID.randomUUID(), uncovered = UUID.randomUUID(), canonicalArtifact = UUID.randomUUID();
		String coveredPurl = "pkg:npm/covered@1.0", uncoveredPurl = "pkg:npm/uncovered@1.0";
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(ingestedBucket(new HashMap<>(), coveredPurl)));
		SbomComponent c1 = new SbomComponent(); c1.setUuid(covered); c1.setOrg(ORG); c1.setCanonicalPurl(coveredPurl);
		SbomComponent c2 = new SbomComponent(); c2.setUuid(uncovered); c2.setOrg(ORG); c2.setCanonicalPurl(uncoveredPurl);
		when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(eq(ORG.toString()), any())).thenReturn(List.of(c1));
		when(sbomComponentRepository.findAllById(any())).thenReturn(List.of(c1, c2));
		when(artifactSbomComponentRepository.findDistinctCanonicalArtifactUuidsByOrgAndSbomComponentUuidIn(eq(ORG), any()))
				.thenReturn(List.of(canonicalArtifact));
		ArtifactSbomComponent a1 = mock(ArtifactSbomComponent.class); when(a1.getSbomComponentUuid()).thenReturn(covered);
		ArtifactSbomComponent a2 = mock(ArtifactSbomComponent.class); when(a2.getSbomComponentUuid()).thenReturn(uncovered);
		when(artifactSbomComponentRepository.findByOrgAndCanonicalArtifactUuid(ORG, canonicalArtifact)).thenReturn(List.of(a1, a2));

		service.fanOutOrg(ORG);

		verify(sharedArtifactService, never()).updateArtifactDti(any(), any(), any());
	}

	@Test
	void fanOutMarksArtifactWhenOnlyUncoveredComponentIsRoot() throws Exception {
		// The release's own root/self component is never submitted to DTrack and BEAR
		// never enriches it, so it is excluded from the coverage requirement. With the
		// real dependency covered, the artifact must STILL be marked scanned even
		// though the (uncovered) root component is also on it.
		UUID dep = UUID.randomUUID(), root = UUID.randomUUID(),
				canonicalArtifact = UUID.randomUUID(), artifact = UUID.randomUUID();
		String depPurl = "pkg:npm/dep@1.0", rootPurl = "pkg:generic/app@1.0";
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(ingestedBucket(new HashMap<>(), depPurl)));
		SbomComponent depC = new SbomComponent(); depC.setUuid(dep); depC.setOrg(ORG); depC.setCanonicalPurl(depPurl);
		SbomComponent rootC = new SbomComponent(); rootC.setUuid(root); rootC.setOrg(ORG); rootC.setCanonicalPurl(rootPurl);
		Map<String, Object> rootRd = new HashMap<>(); rootRd.put("isRoot", true); rootC.setRecordData(rootRd);
		when(sbomComponentRepository.findByOrgAndCanonicalPurlIn(eq(ORG.toString()), any())).thenReturn(List.of(depC));
		when(sbomComponentRepository.findAllById(any())).thenReturn(List.of(depC, rootC));
		when(artifactSbomComponentRepository.findDistinctCanonicalArtifactUuidsByOrgAndSbomComponentUuidIn(eq(ORG), any()))
				.thenReturn(List.of(canonicalArtifact));
		ArtifactSbomComponent aDep = mock(ArtifactSbomComponent.class); when(aDep.getSbomComponentUuid()).thenReturn(dep);
		ArtifactSbomComponent aRoot = mock(ArtifactSbomComponent.class); when(aRoot.getSbomComponentUuid()).thenReturn(root);
		when(artifactSbomComponentRepository.findByOrgAndCanonicalArtifactUuid(ORG, canonicalArtifact)).thenReturn(List.of(aDep, aRoot));
		ArtifactCanonicalMap acm = mock(ArtifactCanonicalMap.class); when(acm.getArtifactUuid()).thenReturn(artifact);
		when(artifactCanonicalMapRepository.findByOrgAndCanonicalArtifactUuid(ORG, canonicalArtifact)).thenReturn(List.of(acm));
		when(sharedArtifactService.getArtifact(artifact)).thenReturn(Optional.of(mock(Artifact.class)));

		service.fanOutOrg(ORG);

		// root excluded from coverage -> the single real dep is covered -> marked scanned
		verify(sharedArtifactService).updateArtifactDti(any(), any(), any());
	}

	/** A bucket in INGESTED state whose ref_map covers the given canonical purls. */
	private SyntheticDtrackBucket ingestedBucket(Map<String, Object> findings, String... coveredPurls) {
		SyntheticDtrackBucket b = new SyntheticDtrackBucket();
		b.setIngestState(IngestState.INGESTED);
		b.setFindings(findings);
		for (String p : coveredPurls) b.getRefMap().put(p, p);
		return b;
	}

	private static VulnerabilityDto vuln(String purl, String vulnId) {
		return new VulnerabilityDto(purl, vulnId, null, null, null, null, null, null, null,
				null, null, null, null, null, null);
	}

	private static ComponentIdentity idEntry(String scheme, String value) {
		return new ComponentIdentity(scheme, value);
	}
}
