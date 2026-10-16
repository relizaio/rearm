/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.service.RebomService.BomStructureType;

/**
 * The merge options of {@code releaseSbomExportProgrammatic} and {@code releaseSbomScoreProgrammatic}
 * reach the merge as the caller sent them, as the user export's do (task SCORE-24, design 3.1 and
 * 5.3, test T-1). Rebom is a mock, so the document does not depend on the options;
 * what the merge request carries does: the BOMs merged (the coverage exclusion) and the
 * {@link RebomOptions} (tldOnly, ignoreDev, structure, belongsTo, excludeFileComponents).
 *
 * <p>The release carries two stored BOMs, one plain and one tagged as a TEST SBOM, so a coverage
 * exclusion of TEST merges the plain one only. Both option sets keep a coverage exclusion, so every
 * request merges (an exclusion bypasses the merged-BOM cache).
 */
class ReleaseSbomProgrammaticOptionsIntegrationTest extends ProgrammaticReleaseSbomTestBase {

	/** Every option away from its default; TEST excluded. */
	private static final Object[] AWAY = {"t", false, "i", true, "s", "HIERARCHICAL", "b", "RELEASE",
			"x", List.of("TEST"), "fc", true};
	/** The other value of each; DEV excluded, so the TEST SBOM is merged too. */
	private static final Object[] BACK = {"t", true, "i", false, "s", "FLAT", "b", null, "x", List.of("DEV"), "fc", false};

	private Rel tagged;
	private UUID plainBom;
	private UUID testBom;

	/** One merge request: the BOMs merged and the options rebom was given. */
	private record Merge(Set<UUID> boms, RebomOptions options) {}

	@BeforeEach
	void releaseWithATestSbom() throws Exception {
		if (null != tagged) return;
		plainBom = UUID.randomUUID();
		testBom = UUID.randomUUID();
		UUID plain = storedBom(plainBom, List.of());
		UUID test = storedBom(testBom, List.of(new TagRecord(CommonVariables.ARTIFACT_COVERAGE_TYPE_TAG_KEY, ArtifactCoverageType.TEST.name())));
		String name = "score24-options-" + UUID.randomUUID();
		Component c = componentService.createComponent(name, w.org().getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		var branch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid())
				.org(w.org().getUuid()).branch(branch.getUuid()).version("24.1.0")
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED)
				.artifacts(new ArrayList<>(List.of(plain, test))).build(), WU).getUuid();
		tagged = new Rel(c, name, release, "24.1.0");
	}

	private UUID storedBom(UUID internalBom, List<TagRecord> tags) throws Exception {
		return artifactService.createArtifact(ArtifactDto.builder().org(w.org().getUuid()).type(ArtifactType.BOM)
				.bomFormat(BomFormat.CYCLONEDX).displayIdentifier("score24-options-" + UUID.randomUUID())
				.internalBom(new InternalBom(internalBom, ArtifactBelongsTo.RELEASE)).tags(new ArrayList<>(tags))
				.build(), WU).getUuid();
	}

	/** The one merge the call made. */
	private Merge merge(Executable call) throws Throwable {
		clearInvocations(rebomService);
		call.execute();
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<UUID>> boms = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<RebomOptions> options = ArgumentCaptor.forClass(RebomOptions.class);
		verify(rebomService, times(1)).mergeAndStoreBoms(boms.capture(), options.capture(), eq(w.org().getUuid()));
		return new Merge(new HashSet<>(boms.getValue()), options.getValue());
	}

	private void assertMergedAsAway(Merge m, String what) {
		assertEquals(Set.of(plainBom), m.boms(), what + ": the TEST SBOM is left out");
		assertEquals(Boolean.FALSE, m.options().tldOnly(), what + ": tldOnly");
		assertEquals(Boolean.TRUE, m.options().ignoreDev(), what + ": ignoreDev");
		assertEquals(BomStructureType.HIERARCHICAL, m.options().structure(), what + ": structure");
		assertEquals(ArtifactBelongsTo.RELEASE, m.options().belongsTo(), what + ": belongsTo");
		assertEquals(Boolean.TRUE, m.options().excludeFileComponents(), what + ": excludeFileComponents");
	}

	private void assertMergedAsBack(Merge m, String what) {
		assertEquals(Set.of(plainBom, testBom), m.boms(), what + ": only DEV is left out");
		assertEquals(Boolean.TRUE, m.options().tldOnly(), what + ": tldOnly");
		assertEquals(Boolean.FALSE, m.options().ignoreDev(), what + ": ignoreDev");
		assertEquals(BomStructureType.FLAT, m.options().structure(), what + ": structure");
		assertNull(m.options().belongsTo(), what + ": belongsTo");
		// off is carried as null to rebom (SCORE-11)
		assertNull(m.options().excludeFileComponents(), what + ": excludeFileComponents");
	}

	// T-1
	@Test
	void theExportMergesWithTheCallersOptionsAsTheUserExportDoes() throws Throwable {
		for (Object[] options : List.of(AWAY, BACK)) {
			Map<String, Object> vars = byUuid(tagged, options);
			Merge programmatic = merge(() -> value(gql(basic(w.orgKey()), EXPORT, vars), "releaseSbomExportProgrammatic"));
			Merge user = merge(() -> userExport(vars));
			String what = "export " + Arrays.toString(options);
			if (options == AWAY) assertMergedAsAway(programmatic, what);
			else assertMergedAsBack(programmatic, what);
			assertEquals(user, programmatic, what + ": the user export's merge");
			assertEquals(programmatic, merge(() -> value(gql(basic(w.orgKey()), EXPORT, byComponent(tagged, options)),
					"releaseSbomExportProgrammatic")), what + ": named by component and version");
		}
	}

	// T-5
	@Test
	void theScoreMergesWithTheCallersOptionsAsTheExportDoes() throws Throwable {
		for (Object[] options : List.of(AWAY, BACK)) {
			Map<String, Object> vars = byUuid(tagged, options);
			vars.put("p", List.of("cisa-2026"));
			Merge scored = merge(() -> value(gql(basic(w.orgKey()), SCORE, vars), "releaseSbomScoreProgrammatic"));
			String what = "score " + Arrays.toString(options);
			if (options == AWAY) assertMergedAsAway(scored, what);
			else assertMergedAsBack(scored, what);
			vars.remove("p");
			assertEquals(merge(() -> userExport(vars)), scored, what + ": the user export's merge");
		}
	}
}
