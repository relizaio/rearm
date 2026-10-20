/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.common.CommonVariables;
import io.reliza.exceptions.SbomUpdateNotPermittedException;
import io.reliza.model.ApiKey;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.service.SbomUpdateGuard;
import io.reliza.service.VariantService;

/**
 * SCORE-52 T-19 (carried from SCORE-47 T-18), the refusals without a record: a rule B refusal of a
 * release create rolls the whole request back (no release row, no artifact row of an accepted
 * upload, a rebuild's outbound deliverables as they were), the source code entries it created
 * survive without the request's artifacts and a retry merges into them, the version a mint assigned
 * stays consumed, and the WARN line of {@code SbomUpdateGuard} is the record. {@code
 * addReleasesProgrammatic} writes no GUARD event and no release.
 */
class SbomUpdateGuardRefusalRollbackTest extends SbomUpdateGuardProgrammaticTestBase {

	private static final String REFUSED_LINE = "SBOM update refused:";

	@Autowired private ComponentDataFetcher componentDataFetcher;
	@Autowired private VariantService variantService;

	private Map<String, Object> releaseInput(World w, String version) {
		return map(CommonVariables.COMPONENT_FIELD, w.b().getUuid().toString(),
				CommonVariables.BRANCH_FIELD, branchService.getBaseBranchOfComponent(w.b().getUuid()).orElseThrow()
						.getUuid().toString(),
				CommonVariables.VERSION_FIELD, version, CommonVariables.LIFECYCLE_FIELD, ReleaseLifecycle.DRAFT.name());
	}

	/** Refused, with the WARN line of the refusing layer as its only record. */
	private void refusedWithWarn(Executable call) {
		try (CapturedLogLines log = new CapturedLogLines(SbomUpdateGuard.class)) {
			assertThrows(SbomUpdateNotPermittedException.class, call);
			assertTrue(log.anyContains(REFUSED_LINE), log.lines().toString());
		}
	}

	@Test
	void aRefusedNewReleaseLeavesNoReleaseRowWhereverTheRefusedDocumentIs() throws Exception {
		World w = world();
		onlySerialIsRefused(w);
		ApiKey keyB = componentKey(w, w.b());

		Map<String, Object> inbound = releaseInput(w, "47.18.1");
		inbound.put("artifacts", entries(superseding(w)));
		refusedWithWarn(() -> releaseDatafetcher.addReleaseProgrammatic(env(keyB, "release", inbound)));
		assertEquals(0, releaseRows(w.b(), "47.18.1"));

		Map<String, Object> outbound = releaseInput(w, "47.18.2");
		outbound.put("artifacts", entries(fresh()));
		outbound.put("outboundDeliverables", deliverables(superseding(w)));
		refusedWithWarn(() -> releaseDatafetcher.addReleaseProgrammatic(env(keyB, "release", outbound)));
		assertEquals(0, releaseRows(w.b(), "47.18.2"));
		assertEquals(1, accepted.size());
		assertEquals(0, artifactRows(accepted), "the accepted inbound artifact rolled back");
		assertEquals(List.of(), guardEvents(w.releaseB()), "no record without a release");
	}

	@Test
	void aRefusedRebuildLeavesTheReleaseAndItsOutboundDeliverablesAsTheyWere() throws Exception {
		World w = world();
		onlySerialIsRefused(w);
		ApiKey keyB = componentKey(w, w.b());
		Map<String, Object> first = releaseInput(w, "47.18.3");
		first.put("outboundDeliverables", deliverables(fresh()));
		ReleaseData created = releaseDatafetcher.addReleaseProgrammatic(env(keyB, "release", first));
		Set<UUID> outboundBefore = variantService.getBaseVariantForRelease(created.getUuid()).getOutboundDeliverables();
		List<UUID> artifactsBefore = artifactsOf(created.getUuid());

		Map<String, Object> rebuild = releaseInput(w, "47.18.3");
		rebuild.put("rebuildRelease", true);
		rebuild.put("outboundDeliverables", deliverables(superseding(w)));
		refusedWithWarn(() -> releaseDatafetcher.addReleaseProgrammatic(env(keyB, "release", rebuild)));

		assertEquals(outboundBefore, variantService.getBaseVariantForRelease(created.getUuid()).getOutboundDeliverables());
		assertEquals(artifactsBefore, artifactsOf(created.getUuid()));
		assertEquals(List.of(), guardEvents(created.getUuid()));
	}

	@Test
	void aCommitEntrySurvivesWithoutTheRequestsArtifactsAndARetryMergesIntoIt() throws Exception {
		World w = world();
		onlySerialIsRefused(w);
		ApiKey keyB = componentKey(w, w.b());
		String uri = uri();
		String commit = commit();
		Map<String, Object> refused = releaseInput(w, "47.18.4");
		refused.put(CommonVariables.COMMITS_FIELD, new ArrayList<>(List.of(sce(uri, commit, fresh()))));
		refused.put("outboundDeliverables", deliverables(superseding(w)));

		refusedWithWarn(() -> releaseDatafetcher.addReleaseProgrammatic(env(keyB, "release", refused)));

		assertEquals(List.of(), sceArtifacts(commit), "the entry survives with no artifact of the request");
		assertEquals(0, artifactRows(accepted));
		assertEquals(0, releaseRows(w.b(), "47.18.4"));

		accepted.clear();
		Map<String, Object> retry = releaseInput(w, "47.18.4");
		retry.put(CommonVariables.COMMITS_FIELD, new ArrayList<>(List.of(sce(uri, commit, fresh()))));
		releaseDatafetcher.addReleaseProgrammatic(env(keyB, "release", retry));

		assertEquals(1, releaseRows(w.b(), "47.18.4"));
		List<Object> listed = sceArtifacts(commit);
		assertEquals(1, listed.size(), listed.toString());
		assertEquals(accepted.get(0).toString(), ((Map<?, ?>) listed.get(0)).get("artifactUuid"));
	}

	@Test
	void aRefusedVersionMintLeavesNoReleaseAnEmptyCommitEntryAndTheVersionConsumed() throws Exception {
		World w = world();
		onlySerialIsRefused(w);
		ApiKey keyB = componentKey(w, w.b());
		String uri = uri();
		String commit = commit();
		String branchName = branchService.getBranchData(branchService.getBaseBranchOfComponent(w.b().getUuid())
				.orElseThrow().getUuid()).orElseThrow().getName();
		long releasesBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.releases"
				+ " WHERE record_data->>'component' = ?", Long.class, w.b().getUuid().toString());
		long assignedBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.version_assignments"
				+ " WHERE component = ?", Long.class, w.b().getUuid());
		Map<String, Object> input = map(CommonVariables.COMPONENT_FIELD, w.b().getUuid().toString(),
				CommonVariables.BRANCH_FIELD, branchName,
				CommonVariables.SOURCE_CODE_ENTRY_FIELD, sce(uri, commit(), superseding(w)),
				CommonVariables.COMMITS_FIELD, new ArrayList<>(List.of(sce(uri, commit, fresh()))));

		refusedWithWarn(() -> componentDataFetcher.getNewVersionProgrammatic(env(keyB, "newVersionInput", input)));

		assertEquals(releasesBefore, jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.releases"
				+ " WHERE record_data->>'component' = ?", Long.class, w.b().getUuid().toString()));
		assertEquals(List.of(), sceArtifacts(commit));
		assertEquals(assignedBefore + 1, jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.version_assignments"
				+ " WHERE component = ?", Long.class, w.b().getUuid()), "the version stays consumed");
	}

	@Test
	void aRefusedBatchLeavesNeitherReleaseAndNoArtifactOnTheFirstsCommit() throws Exception {
		World w = world();
		onlySerialIsRefused(w);
		ApiKey keyB = componentKey(w, w.b());
		String commit = commit();
		Map<String, Object> firstRelease = releaseInput(w, "47.18.5");
		firstRelease.put(CommonVariables.COMMITS_FIELD, new ArrayList<>(List.of(sce(uri(), commit, fresh()))));
		Map<String, Object> secondRelease = releaseInput(w, "47.18.6");
		secondRelease.put("artifacts", entries(superseding(w)));

		refusedWithWarn(() -> releaseDatafetcher.addReleasesProgrammatic(
				env(keyB, "releases", new ArrayList<>(List.of(firstRelease, secondRelease)))));

		assertEquals(0, releaseRows(w.b(), "47.18.5"));
		assertEquals(0, releaseRows(w.b(), "47.18.6"));
		assertEquals(List.of(), sceArtifacts(commit));
		assertEquals(List.of(), guardEvents(w.releaseB()), "a batch writes no GUARD event");
	}
}
