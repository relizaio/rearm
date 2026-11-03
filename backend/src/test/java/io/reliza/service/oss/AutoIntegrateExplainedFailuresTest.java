/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.ActionRefusedException;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.AutoIntegrateState;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateEvaluation;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateOutcome;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateResult;
import io.reliza.versioning.VersionApi.ActionEnum;
import io.reliza.ws.App;

/**
 * TPF-119 mutation round: the failure paths of the explained mutation that a real create never reaches
 * (the create declining, the mint giving up, a lock taken between the evaluation and the mint, anything
 * else), with the create step of OssReleaseService replaced by a spy that fails on demand; and the
 * evaluator's fallback when the version action cannot be calculated.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class, TestAsyncConfig.class})
public class AutoIntegrateExplainedFailuresTest extends AutoIntegrateDryRunTestBase {

	@MockitoSpyBean private OssReleaseService spiedOssReleaseService;

	/** An ENABLED feature set with one REQUIRED dependency that has a release; returns it re-read. */
	private BranchData ready() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c1 = component(org, "failC1");
		assembled(org, c1, "1.0.0");
		UUID fsUuid = featureSet(product(org), "fs");
		return configure(fsUuid, AutoIntegrateState.ENABLED, dep(c1, StatusEnum.REQUIRED));
	}

	private void assertFailedWith(AutoIntegrateResult r, String note, int rows) {
		assertTrue(r.release().isEmpty());
		AutoIntegrateEvaluation e = r.evaluation();
		assertEquals(AutoIntegrateOutcome.CREATE_FAILED, e.outcome());
		assertEquals(note, e.note());
		assertNull(e.projectedVersion(), "the version is WOULD_CREATE only");
		assertNull(e.existingRelease());
		assertEquals(rows, e.dependencies().size(), "the rows are the evaluation's");
	}

	@Test
	public void aCreateThatDeclinesIsCreateFailedWithTheFixedNote() throws Exception {
		BranchData fs = ready();
		doReturn(Optional.empty()).when(spiedOssReleaseService).createProductRelease(any(), any(), any());

		AutoIntegrateResult r = releaseService.autoIntegrateFeatureSetOnDemandExplained(fs);

		assertFailedWith(r, "the product release was not created: see the server log"
				+ " (cycle, unreferenceable parent or create error)", 1);
		assertNull(r.evaluation().lockRefusal(), "a decline is not a lock");
		assertTrue(releaseService.autoIntegrateFeatureSetOnDemand(fs).isEmpty(), "the old mutation: nothing, no error");
		assertEquals(0, count(fs.getUuid()));
	}

	@Test
	public void aMintThatGivesUpIsCreateFailedWithItsMessageAndTheOldMutationReturnsNothing() throws Exception {
		BranchData fs = ready();
		doThrow(new IllegalStateException("Could not obtain a version for feature set " + fs.getUuid()))
				.when(spiedOssReleaseService).createProductRelease(any(), any(), any());

		AutoIntegrateResult r = releaseService.autoIntegrateFeatureSetOnDemandExplained(fs);

		assertFailedWith(r, "Could not obtain a version for feature set " + fs.getUuid(), 1);
		assertNull(r.evaluation().lockRefusal());
		assertTrue(releaseService.autoIntegrateFeatureSetOnDemand(fs).isEmpty());
	}

	@Test
	public void aLockTakenBetweenTheEvaluationAndTheMintIsCreateFailedAndStillAnErrorOnTheOldMutation() throws Exception {
		BranchData fs = ready();
		doThrow(new ActionRefusedException("the product is locked, so ReARM will not assign a version (race)"))
				.when(spiedOssReleaseService).createProductRelease(any(), any(), any());

		AutoIntegrateResult r = releaseService.autoIntegrateFeatureSetOnDemandExplained(fs);

		assertFailedWith(r, "the product is locked, so ReARM will not assign a version (race)", 1);
		assertEquals(r.evaluation().note(), r.evaluation().lockRefusal());
		ActionRefusedException thrown = assertThrows(ActionRefusedException.class,
				() -> releaseService.autoIntegrateFeatureSetOnDemand(fs));
		assertEquals("the product is locked, so ReARM will not assign a version (race)", thrown.getMessage());
	}

	@Test
	public void anyOtherFailureOfTheCreateSurfacesAsAServerError() throws Exception {
		BranchData fs = ready();
		doThrow(new UnsupportedOperationException("not an expected create failure"))
				.when(spiedOssReleaseService).createProductRelease(any(), any(), any());

		assertThrows(UnsupportedOperationException.class, () -> releaseService.autoIntegrateFeatureSetOnDemandExplained(fs));
	}

	@Test
	public void aVersionActionThatCannotBeCalculatedProjectsAPlainBump() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Comp c1 = component(org, "failAction");
		assembled(org, c1, "1.0.0");
		UUID fsUuid = featureSet(componentService.createComponent("failSemver_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "semver", null, WU), "fs");
		configure(fsUuid, AutoIntegrateState.ENABLED, dep(c1, StatusEnum.REQUIRED));
		releaseService.autoIntegrateFeatureSetOnDemand(read(fsUuid)).orElseThrow();
		assembled(org, c1, "2.0.0");
		BranchData fs = read(fsUuid);
		assertEquals("1.0.0-fs", releaseService.evaluateAutoIntegrate(fs).projectedVersion(), "the control: the calculated action is major");
		doThrow(new RelizaException("no action")).when(spiedOssReleaseService).getLargestActionFromComponents(any(), any(), any());

		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(fs);

		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, ev.outcome());
		assertEquals(versionAssignmentService.getCurrentNextVersion(fsUuid, ActionEnum.BUMP, VersionTypeEnum.DEV),
				ev.projectedVersion(), "the mint falls back to a plain bump, so does the projection");
		assertNotEquals("1.0.0-fs", ev.projectedVersion());
	}
}
