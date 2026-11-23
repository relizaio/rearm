/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.ProgrammaticType;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AnalysisScope;
import io.reliza.model.ImportMode;
import io.reliza.model.IssuerClass;
import io.reliza.model.ProposalStatus;
import io.reliza.model.ReleaseData;
import io.reliza.model.SafetyContextData.GateRule;
import io.reliza.model.SafetyContextData.LevelChange;
import io.reliza.model.SafetyContextData.MatchStatus;
import io.reliza.model.SafetyContextData.Note;
import io.reliza.model.SafetyContextProposalData;
import io.reliza.model.SafetyContextRecordData;
import io.reliza.model.SafetyContextRecordData.AcceptedVia;
import io.reliza.model.SafetyContextRecordData.RecordStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ImportSummary;
import io.reliza.model.dto.MatchCandidate;
import io.reliza.ws.App;

/**
 * SAFETY_CONTEXT import against a real database. The release inventory and the scope target are
 * mocked; everything else (gate, no-op, supersede, audit path, unique indexes) is real.
 */
@SpringBootTest(classes = {App.class})
class SafetyContextImportIntegrationTest {

	private static final String PUMP = "pkg:generic/acme/acme-pump-firmware@3.2.0";
	private static final String TLS = "pkg:maven/com.globex/globex-tls@1.4.2";

	@Autowired private SafetyContextImportService handler;
	@Autowired private SafetyContextService service;
	@MockitoBean private SbomComponentService sbomComponentService;
	@MockitoBean private ScopeTargetResolver scopeTargetResolver;

	private UUID org;
	private UUID release;
	private UUID component;
	private UUID pumpComponent;
	private ReleaseData rd;
	private final WhoUpdated uploader = WhoUpdated.getWhoUpdated(ProgrammaticType.MANUAL, UUID.randomUUID(), "127.0.0.1");
	private final WhoUpdated reviewer = WhoUpdated.getWhoUpdated(ProgrammaticType.MANUAL, UUID.randomUUID(), "127.0.0.1");

	/** One asset: refId, component refId, purl, scheme (predefined name, or "custom:<name>"), level. */
	private record Asset(String refId, String componentRef, String purl, String scheme, String level) {}

	@BeforeEach
	void setUp() {
		org = UUID.randomUUID();
		release = UUID.randomUUID();
		component = UUID.randomUUID();
		pumpComponent = UUID.randomUUID();
		rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", release);
		ReflectionTestUtils.setField(rd, "org", org);
		Map<UUID, String> purls = new LinkedHashMap<>();
		purls.put(pumpComponent, PUMP);
		purls.put(UUID.randomUUID(), TLS);
		when(sbomComponentService.releaseHasSbomComponents(release, org)).thenReturn(true);
		when(sbomComponentService.resolveReleaseComponentPurls(release))
			.thenReturn(new SbomComponentService.ReleaseComponentPurls(org, purls, Map.of(), false));
		when(scopeTargetResolver.targets(eq(org), any(), any(), eq(release)))
			.thenReturn(List.of(new MatchCandidate(AnalysisScope.COMPONENT, component)));
	}

	private static String doc(List<Asset> assets) {
		Map<String, String> components = new LinkedHashMap<>();
		for (Asset a : assets) if (a.componentRef() != null && a.purl() != null) components.put(a.componentRef(), a.purl());
		String comps = components.entrySet().stream().map(e -> """
			{"refId":"%s","type":"firmware","name":"%s","identifiers":[{"identities":[{"scheme":"purl","value":"%s"}]}]}"""
			.formatted(e.getKey(), e.getKey(), e.getValue())).collect(Collectors.joining(","));
		String assetJson = assets.stream().map(a -> {
			String scheme = a.scheme().startsWith("custom:")
				? "{\"name\":\"" + a.scheme().substring(7) + "\",\"description\":\"d\"}"
				: "\"" + a.scheme() + "\"";
			String ref = a.componentRef() == null ? "" : ",\"componentRef\":\"" + a.componentRef() + "\"";
			return """
				{"refId":"%s","name":"%s"%s,"classification":{"safetyIntegrityLevels":[{"scheme":%s,"level":"%s"}]}}"""
				.formatted(a.refId(), a.refId(), ref, scheme, a.level());
		}).collect(Collectors.joining(","));
		return """
			{"specFormat":"CycloneDX","specVersion":"2.0","serialNumber":"urn:uuid:5d1c7a3e-2b4f-4c6a-9e8d-1f2a3b4c5d6e",
			 "version":1,"definitions":{"components":[%s]},
			 "blueprints":[{"refId":"bp-1","name":"bp","modelTypes":["safety"],"assets":[%s]}]}"""
			.formatted(comps, assetJson);
	}

	private ImportSummary upload(String json, ArtifactBelongsTo belongsTo, IssuerClass override, ImportMode mode,
			WhoUpdated wu) throws RelizaException {
		ArtifactDto dto = ArtifactDto.builder().userIssuerClassOverride(override).importMode(mode).build();
		ImportSummary s = handler.dispatch(UUID.randomUUID(), dto, rd, belongsTo, json.getBytes(StandardCharsets.UTF_8), wu);
		assertEquals(s.getStatementsTotal(), s.getProposalsCreated() + s.getProposalsAutoAccepted()
			+ s.getProposalsAutoRejected() + s.getStatementsUnchanged() + s.getStatementsErrored(), "summary invariant");
		assertTrue(s.getStatementsUnmatched() <= s.getProposalsCreated(), "unmatched is a subset of created");
		return s;
	}

	private ImportSummary upload(String json, ArtifactBelongsTo belongsTo) throws RelizaException {
		return upload(json, belongsTo, null, null, uploader);
	}

	private List<SafetyContextRecordData> active() {
		return service.recordsApplyingTo(org, release, null, component, Set.of(PUMP, TLS), false, 100, 0);
	}

	private List<SafetyContextProposalData> pending() {
		return service.listProposals(org, ProposalStatus.PENDING, 100, 0);
	}

	private static String twoAssets(String pumpLevel) {
		return doc(List.of(new Asset("asset-pump", "comp-pump", PUMP, "IEC-62304", pumpLevel),
			new Asset("asset-tls", "comp-tls", TLS, "IEC-61508", "SIL 2")));
	}

	@Test
	void selfAutoAcceptsWithProvenance() throws Exception {
		ImportSummary s = upload(twoAssets("class-c"), ArtifactBelongsTo.RELEASE);
		assertEquals(2, s.getProposalsAutoAccepted());
		List<SafetyContextRecordData> records = active();
		assertEquals(2, records.size());
		SafetyContextRecordData pump = records.stream().filter(r -> PUMP.equals(r.getComponentPurl())).findFirst().orElseThrow();
		assertEquals("Class C", pump.getLevelNormalized());
		assertEquals(2, pump.getLevelRank());
		assertEquals(AcceptedVia.AUTO_SELF, pump.getAcceptedVia());
		assertEquals(GateRule.SELF_AUTO, pump.getGateRule());
		assertEquals(IssuerClass.SELF, pump.getIssuerClassEffective());
		assertEquals(pumpComponent, pump.getSbomComponentUuid());
		assertNotNull(pump.getSpecCommit());
		assertNotNull(pump.getAcceptedAt());
		assertTrue(pump.getSourceAssertionJson().contains("asset-pump"));
		assertFalse(pump.getSourceAssertionJson().contains("asset-tls"), "only the asset's own JSON is stored");
	}

	@Test
	void thirdPartyStages() throws Exception {
		ImportSummary s = upload(twoAssets("class-c"), null);
		assertEquals(2, s.getProposalsCreated());
		assertEquals(2, pending().size());
		assertTrue(active().isEmpty());
	}

	@Test
	void reuploadIsANoOp() throws Exception {
		upload(twoAssets("class-c"), ArtifactBelongsTo.RELEASE);
		ImportSummary again = upload(twoAssets("class-c"), ArtifactBelongsTo.RELEASE);
		assertEquals(2, again.getStatementsUnchanged());
		assertEquals(0, again.getProposalsAutoAccepted());
		assertEquals(2, active().size());

		upload(twoAssets("class-c"), null);
		ImportSummary staged = upload(twoAssets("class-c"), null);
		assertEquals(2, staged.getStatementsUnchanged(), "identical to ACTIVE, so nothing is staged");
		assertTrue(pending().isEmpty());
	}

	@Test
	void downgradeStagesEvenForSelfAndNeedsACommentToAccept() throws Exception {
		upload(twoAssets("class-c"), ArtifactBelongsTo.RELEASE);
		ImportSummary s = upload(twoAssets("class-b"), ArtifactBelongsTo.RELEASE);
		assertEquals(1, s.getProposalsCreated());
		assertEquals(1, s.getStatementsUnchanged());
		SafetyContextProposalData p = pending().get(0);
		assertEquals(GateRule.DOWNGRADE, p.getGateRule());
		assertEquals(LevelChange.DOWNGRADE, p.getLevelChange());

		assertThrows(RelizaException.class, () -> service.accept(p.getUuid(), null, " ", reviewer));
		SafetyContextProposalData accepted = service.accept(p.getUuid(), null, "re-assessed", reviewer);
		assertEquals(ProposalStatus.ACCEPTED, accepted.getStatus());
		SafetyContextRecordData pump = active().stream().filter(r -> PUMP.equals(r.getComponentPurl())).findFirst().orElseThrow();
		assertEquals("Class B", pump.getLevelNormalized());
		assertEquals(AcceptedVia.MANUAL, pump.getAcceptedVia());
		assertFalse(pump.isAcceptedByUploader());
		assertEquals("re-assessed", pump.getAcceptComment());
		List<SafetyContextRecordData> all = service.recordsApplyingTo(org, release, null, component, Set.of(PUMP, TLS), true, 100, 0);
		assertEquals(1, all.stream().filter(r -> r.getStatus() == RecordStatus.SUPERSEDED
			&& pump.getUuid().equals(r.getSupersededBy())).count(), "the old record is kept and points at the new one");
	}

	@Test
	void upgradeSupersedes() throws Exception {
		upload(twoAssets("class-b"), ArtifactBelongsTo.RELEASE);
		ImportSummary s = upload(twoAssets("class-c"), ArtifactBelongsTo.RELEASE);
		assertEquals(1, s.getProposalsAutoAccepted());
		assertEquals(2, active().size());
		assertEquals(3, service.recordsApplyingTo(org, release, null, component, Set.of(PUMP, TLS), true, 100, 0).size());
	}

	@Test
	void differentAssetsOnOneComponentAreNotADowngrade() throws Exception {
		String json = doc(List.of(new Asset("asset-high", "comp-pump", PUMP, "IEC-62304", "C"),
			new Asset("asset-low", "comp-pump", PUMP, "IEC-62304", "A")));
		ImportSummary s = upload(json, ArtifactBelongsTo.RELEASE);
		assertEquals(2, s.getProposalsAutoAccepted());
		String reversed = doc(List.of(new Asset("asset-low", "comp-pump", PUMP, "IEC-62304", "A"),
			new Asset("asset-high", "comp-pump", PUMP, "IEC-62304", "C")));
		assertEquals(2, upload(reversed, ArtifactBelongsTo.RELEASE).getStatementsUnchanged(), "order does not matter");
	}

	@Test
	void vendorRuleAndIgnoredOverride() throws Exception {
		// The test uploader has no Finding Analysis write permission, so the VENDOR override is ignored.
		ImportSummary s = upload(twoAssets("class-c"), null, IssuerClass.VENDOR, null, uploader);
		assertEquals(2, s.getProposalsCreated());
		assertTrue(s.getErrorMessages().stream().anyMatch(m -> m.contains("ignored")));
		SafetyContextProposalData p = pending().get(0);
		assertEquals(IssuerClass.THIRD_PARTY, p.getIssuerClassEffective());
		assertEquals(IssuerClass.VENDOR, p.getIssuerOverride());
		assertTrue(p.getNotes().contains(Note.OVERRIDE_IGNORED));
	}

	@Test
	void rejectedProposalStaysRejectedOnReupload() throws Exception {
		upload(twoAssets("class-c"), null);
		for (SafetyContextProposalData p : pending()) service.reject(p.getUuid(), "not ours", reviewer);
		ImportSummary again = upload(twoAssets("class-c"), null);
		assertEquals(2, again.getStatementsUnchanged());
		assertTrue(pending().isEmpty());
	}

	@Test
	void unmatchedNeedsAReleaseComponentToAccept() throws Exception {
		String json = doc(List.of(new Asset("asset-x", "comp-x", "pkg:npm/unknown@1.0.0", "IEC-62304", "C")));
		ImportSummary s = upload(json, ArtifactBelongsTo.RELEASE);
		assertEquals(1, s.getStatementsUnmatched());
		SafetyContextProposalData p = pending().get(0);
		assertEquals(MatchStatus.UNMATCHED, p.getMatchStatus());
		assertEquals(GateRule.UNMATCHED, p.getGateRule());
		assertThrows(RelizaException.class, () -> service.accept(p.getUuid(), null, null, reviewer));
		assertThrows(RelizaException.class, () -> service.accept(p.getUuid(), UUID.randomUUID(), null, reviewer));
		assertEquals(ProposalStatus.ACCEPTED, service.accept(p.getUuid(), pumpComponent, null, reviewer).getStatus());
	}

	@Test
	void sameSchemeTwiceInOneAssetStagesOneConflict() throws Exception {
		String json = """
			{"specFormat":"CycloneDX","specVersion":"2.0","definitions":{"components":[
			 {"refId":"comp-pump","type":"firmware","name":"p","identifiers":[{"identities":[{"scheme":"purl","value":"%s"}]}]}]},
			 "blueprints":[{"refId":"bp-1","name":"bp","modelTypes":["safety"],"assets":[{"refId":"a","name":"a",
			 "componentRef":"comp-pump","classification":{"safetyIntegrityLevels":[
			 {"scheme":"IEC-62304","level":"C"},{"scheme":"IEC-62304","level":"A"}]}}]}]}""".formatted(PUMP);
		ImportSummary s = upload(json, ArtifactBelongsTo.RELEASE);
		assertEquals(1, s.getProposalsCreated());
		SafetyContextProposalData p = pending().get(0);
		assertEquals(GateRule.INTRA_ASSET_CONFLICT, p.getGateRule());
		assertEquals("C | A", p.getLevelRaw());
		assertThrows(RelizaException.class, () -> service.accept(p.getUuid(), null, "x", reviewer),
			"a conflicting declaration cannot be accepted as one level");
	}

	@Test
	void customSchemeAndMissingComponent() throws Exception {
		String json = doc(List.of(new Asset("asset-tier", "comp-tls", TLS, "custom:acme-hazard-tier", "tier-2"),
			new Asset("asset-orphan", null, null, "IEC-62304", "C")));
		ImportSummary s = upload(json, ArtifactBelongsTo.RELEASE);
		assertEquals(1, s.getProposalsAutoAccepted(), "SELF accepts a custom scheme");
		assertEquals(1, s.getStatementsErrored(), "an asset without a component is reported, not imported");
		assertEquals("CUSTOM:acme-hazard-tier", active().get(0).getSchemeKey());
	}

	@Test
	void userModeStageAndReject() throws Exception {
		ImportSummary staged = upload(twoAssets("class-c"), ArtifactBelongsTo.RELEASE, null, ImportMode.STAGE, uploader);
		assertEquals(2, staged.getProposalsCreated());
		assertTrue(pending().stream().allMatch(p -> p.getNotes().contains(Note.USER_MODE_STAGE)));
		ImportSummary rejected = upload(doc(List.of(new Asset("asset-z", "comp-tls", TLS, "EN-50128", "SIL 4"))),
			ArtifactBelongsTo.RELEASE, null, ImportMode.REJECT, uploader);
		assertEquals(1, rejected.getProposalsAutoRejected());
	}

	@Test
	void boundsRefuseTheWholeImport() throws Exception {
		String deep = "{\"a\":".repeat(70) + "1" + "}".repeat(70);
		ImportSummary s = upload(deep, ArtifactBelongsTo.RELEASE);
		assertEquals(1, s.getStatementsErrored());
		assertTrue(s.getErrorMessages().get(0).contains("limits"));

		List<String> levels = new ArrayList<>();
		for (int i = 0; i < 9; i++) levels.add("{\"scheme\":\"IEC-62304\",\"level\":\"C\"}");
		String tooMany = """
			{"specFormat":"CycloneDX","specVersion":"2.0","definitions":{"components":[
			 {"refId":"comp-pump","type":"firmware","name":"p","identifiers":[{"identities":[{"scheme":"purl","value":"%s"}]}]}]},
			 "blueprints":[{"refId":"bp-1","name":"bp","modelTypes":["safety"],"assets":[{"refId":"a","name":"a",
			 "componentRef":"comp-pump","classification":{"safetyIntegrityLevels":[%s]}}]}]}"""
			.formatted(PUMP, String.join(",", levels));
		ImportSummary refused = upload(tooMany, ArtifactBelongsTo.RELEASE);
		assertEquals(1, refused.getStatementsErrored());
		assertTrue(pending().isEmpty() && active().isEmpty(), "nothing is truncated or stored");
	}

	@Test
	void orgLevelUploadIsRefused() {
		assertThrows(RelizaException.class, () -> handler.verifyPreconditions(null));
	}

	@Test
	void emptyDocumentWarns() throws Exception {
		ImportSummary s = upload(doc(List.of()), ArtifactBelongsTo.RELEASE);
		assertEquals(0, s.getStatementsTotal());
		assertTrue(s.getErrorMessages().get(0).contains("no safety integrity levels"));
	}

	@Test
	void concurrentUploadsLeaveOneActivePerIdentityWithoutDeadlock() throws Exception {
		String forward = twoAssets("class-c");
		// Same identities in the opposite order with other levels: lock order must not depend on it.
		String backward = doc(List.of(new Asset("asset-tls", "comp-tls", TLS, "IEC-61508", "SIL 3"),
			new Asset("asset-pump", "comp-pump", PUMP, "IEC-62304", "class-c")));
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Callable<ImportSummary>> jobs = List.of(
				() -> upload(forward, ArtifactBelongsTo.RELEASE), () -> upload(forward, ArtifactBelongsTo.RELEASE),
				() -> upload(backward, ArtifactBelongsTo.RELEASE), () -> upload(forward, ArtifactBelongsTo.RELEASE));
			List<Future<ImportSummary>> results = pool.invokeAll(jobs, 120, TimeUnit.SECONDS);
			for (Future<ImportSummary> f : results) {
				ImportSummary s = f.get();
				assertEquals(0, s.getStatementsErrored(), "no import aborted: " + s.getErrorMessages());
			}
		} finally {
			pool.shutdownNow();
		}
		List<SafetyContextRecordData> records = active();
		assertEquals(2, records.size(), "one ACTIVE record per identity");
	}

	@Test
	void unrankableLevelDoesNotSilentlyReplaceARankedOne() throws Exception {
		upload(twoAssets("class-c"), ArtifactBelongsTo.RELEASE);
		ImportSummary s = upload(twoAssets("class-c-ish"), ArtifactBelongsTo.RELEASE);
		assertEquals(1, s.getProposalsCreated());
		SafetyContextProposalData p = pending().get(0);
		assertEquals(GateRule.UNCOMPARABLE, p.getGateRule());
		assertThrows(RelizaException.class, () -> service.accept(p.getUuid(), null, null, reviewer),
			"an uncomparable replacement needs a comment");
	}

	@Test
	void wideScopeWithoutWritePermissionStages() throws Exception {
		when(scopeTargetResolver.targets(eq(org), eq(AnalysisScope.ORG), any(), eq(release)))
			.thenReturn(List.of(new MatchCandidate(AnalysisScope.ORG, org)));
		ArtifactDto dto = ArtifactDto.builder().importScope(AnalysisScope.ORG).build();
		ImportSummary s = handler.dispatch(UUID.randomUUID(), dto, rd, ArtifactBelongsTo.RELEASE,
			twoAssets("class-c").getBytes(StandardCharsets.UTF_8), uploader);
		assertEquals(2, s.getProposalsCreated());
		assertTrue(pending().stream().allMatch(p -> p.getGateRule() == GateRule.WIDE_SCOPE));
		assertTrue(active().isEmpty());
	}

	@Test
	void sameLineageReuploadSupersedesItsPendingProposal() throws Exception {
		upload(twoAssets("class-b"), null);
		ImportSummary s = upload(twoAssets("class-c"), null);
		assertEquals(1, s.getProposalsCreated());
		assertEquals(1, s.getStatementsUnchanged());
		List<SafetyContextProposalData> pump = pending().stream().filter(p -> PUMP.equals(p.getComponentPurl())).toList();
		assertEquals(1, pump.size(), "the newer proposal replaces the older one of the same lineage");
		assertEquals("class-c", pump.get(0).getLevelRaw());
		assertEquals(1, service.listProposals(org, ProposalStatus.SUPERSEDED, 100, 0).size());
	}

	@Test
	void anotherUploaderStagesAlongsideAndBothAreFlagged() throws Exception {
		upload(twoAssets("class-b"), null);
		WhoUpdated other = WhoUpdated.getWhoUpdated(ProgrammaticType.MANUAL, UUID.randomUUID(), "127.0.0.1");
		upload(twoAssets("class-c"), null, null, null, other);
		List<SafetyContextProposalData> pump = pending().stream().filter(p -> PUMP.equals(p.getComponentPurl())).toList();
		assertEquals(2, pump.size(), "one uploader cannot clear another's pending proposal");
		assertTrue(pump.stream().allMatch(SafetyContextProposalData::isConflictFlag));
	}

	@Test
	void decisionsNeedAPendingProposal() throws Exception {
		upload(twoAssets("class-c"), null);
		SafetyContextProposalData p = pending().get(0);
		service.reject(p.getUuid(), "no", reviewer);
		assertThrows(RelizaException.class, () -> service.reject(p.getUuid(), "again", reviewer));
		assertThrows(RelizaException.class, () -> service.accept(p.getUuid(), null, "late", reviewer));
	}

	@Test
	void oversizedAssetRefusesTheImport() throws Exception {
		String big = "x".repeat(SafetyContextImportService.MAX_ASSET_JSON_CHARS);
		String json = doc(List.of(new Asset("asset-pump", "comp-pump", PUMP, "IEC-62304", "C")))
			.replace("\"name\":\"asset-pump\"", "\"name\":\"" + big + "\"");
		ImportSummary s = upload(json, ArtifactBelongsTo.RELEASE);
		assertEquals(1, s.getStatementsErrored());
		assertTrue(active().isEmpty());
	}

	@Test
	void aPendingReconcileDoesNotUnmatchAPurlTheInventoryHas() throws Exception {
		// Any artifact upload marks the release's inventory for reconcile; a purl already in it still matches.
		Map<UUID, String> purls = new LinkedHashMap<>();
		purls.put(pumpComponent, PUMP);
		when(sbomComponentService.resolveReleaseComponentPurls(release))
			.thenReturn(new SbomComponentService.ReleaseComponentPurls(org, purls, Map.of(), true));
		String json = doc(List.of(new Asset("asset-pump", "comp-pump", PUMP, "IEC-62304", "C"),
			new Asset("asset-new", "comp-new", "pkg:npm/not-yet@1.0.0", "IEC-62304", "B")));
		ImportSummary s = upload(json, ArtifactBelongsTo.RELEASE);
		assertEquals(1, s.getProposalsAutoAccepted(), "the inventory's purl matches and SELF accepts it");
		assertEquals(1, s.getProposalsCreated());
		SafetyContextProposalData waiting = pending().get(0);
		assertEquals(MatchStatus.SBOM_PENDING, waiting.getMatchStatus(), "a purl not found yet waits on the reconcile");
	}

	@Test
	void aComponentLevelAppliesToEveryReleaseOfTheComponentThatHasThePackage() throws Exception {
		upload(twoAssets("class-c"), ArtifactBelongsTo.RELEASE);
		UUID otherRelease = UUID.randomUUID();
		UUID branch = UUID.randomUUID();
		List<SafetyContextRecordData> both = service.recordsApplyingTo(org, otherRelease, branch, component,
			Set.of(PUMP, TLS), false, 100, 0);
		assertEquals(2, both.size(), "uploaded on one release, applies to another release of the component");
		assertEquals(release, both.get(0).getReleaseUuid(), "the record still names the release it came from");
		assertEquals(1, service.recordsApplyingTo(org, otherRelease, branch, component, Set.of(TLS), false, 100, 0).size(),
			"only packages in the release's inventory");
		assertTrue(service.recordsApplyingTo(org, otherRelease, branch, UUID.randomUUID(), Set.of(PUMP, TLS), false, 100, 0)
			.isEmpty(), "another component's release does not get them");
	}

	@Test
	void theMostSpecificScopeWins() throws Exception {
		upload(twoAssets("class-c"), ArtifactBelongsTo.RELEASE);
		when(scopeTargetResolver.targets(eq(org), eq(AnalysisScope.RELEASE), any(), eq(release)))
			.thenReturn(List.of(new MatchCandidate(AnalysisScope.RELEASE, release)));
		ArtifactDto dto = ArtifactDto.builder().importScope(AnalysisScope.RELEASE).build();
		handler.dispatch(UUID.randomUUID(), dto, rd, ArtifactBelongsTo.RELEASE,
			twoAssets("class-b").getBytes(StandardCharsets.UTF_8), uploader);
		UUID branch = UUID.randomUUID();
		SafetyContextRecordData onThisRelease = service.recordsApplyingTo(org, release, branch, component,
			Set.of(PUMP, TLS), false, 100, 0).stream().filter(r -> PUMP.equals(r.getComponentPurl())).findFirst().orElseThrow();
		assertEquals(AnalysisScope.RELEASE, onThisRelease.getScope());
		assertEquals("Class B", onThisRelease.getLevelNormalized(), "the release's own level overrides the component's");
		SafetyContextRecordData onAnother = service.recordsApplyingTo(org, UUID.randomUUID(), branch, component,
			Set.of(PUMP, TLS), false, 100, 0).stream().filter(r -> PUMP.equals(r.getComponentPurl())).findFirst().orElseThrow();
		assertEquals(AnalysisScope.COMPONENT, onAnother.getScope());
		assertEquals("Class C", onAnother.getLevelNormalized());
		assertEquals(2, service.recordsApplyingTo(org, release, branch, component, Set.of(PUMP, TLS), false, 100, 0).size(),
			"one level per package and scheme on a release, not one per scope");
	}
}
