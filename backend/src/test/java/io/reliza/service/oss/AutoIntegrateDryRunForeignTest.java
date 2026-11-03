/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.AutoIntegrateState;
import io.reliza.model.Component;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateDependencyRow;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateEvaluation;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateOutcome;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateReleaseSource;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateResult;
import io.reliza.ws.App;

/**
 * The auto-integrate dry run over a dependency of another organization (TPF-119 design rows 11 and 15):
 * nothing of the foreign release leaves the evaluator, and a foreign chosen release stops the create
 * whatever the dependency's status.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class, TestAsyncConfig.class})
public class AutoIntegrateDryRunForeignTest extends AutoIntegrateDryRunTestBase {

	private static final String FOREIGN_NOTE = "release belongs to another organization and cannot be a parent";

	/** Row 11: REQUIRED foreign blocks; a foreign DRAFT-only branch gets the plain note. */
	@Test
	public void row11_crossOrganizationDependency() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Organization otherOrg = testInitializer.obtainOrganization();
		Comp foreign = component(otherOrg, "dryRunForeign");
		ReleaseData foreignRelease = assembled(otherOrg, foreign, "7.7.7");
		Comp foreignDraft = component(otherOrg, "dryRunForeignDraft");
		release(otherOrg, foreignDraft, "0.5.0", ReleaseLifecycle.DRAFT);
		Comp own = component(org, "dryRunOwn");
		UUID fsUuid = featureSet(product(org), "fs");
		configure(fsUuid, AutoIntegrateState.ENABLED, dep(own, StatusEnum.REQUIRED), dep(foreign, StatusEnum.REQUIRED),
				dep(foreignDraft, StatusEnum.TRANSIENT));
		ReleaseData ownRelease = assembled(org, own, "1.0.0");
		BranchData fs = read(fsUuid);
		Snapshot before = snapshot(fsUuid, own, foreign, foreignDraft);

		AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(fs);
		assertEquals(AutoIntegrateOutcome.REQUIRED_DEPENDENCY_WITHOUT_RELEASE, ev.outcome());
		AutoIntegrateDependencyRow foreignRow = row(ev, foreign);
		assertEquals(AutoIntegrateReleaseSource.LATEST_ASSEMBLED, foreignRow.source());
		assertNull(foreignRow.release());
		assertEquals(FOREIGN_NOTE, foreignRow.note());
		AutoIntegrateDependencyRow draftRow = row(ev, foreignDraft);
		assertEquals(AutoIntegrateReleaseSource.NONE, draftRow.source());
		assertNull(draftRow.release());
		assertEquals("no release on branch " + foreignDraft.branchName(), draftRow.note());
		assertRowRelease(row(ev, own), ownRelease);
		assertEquals(AutoIntegrateReleaseSource.LATEST_ASSEMBLED, row(ev, own).source());
		assertNull(ev.existingRelease());
		String s = ev.toString();
		assertFalse(s.contains(foreignRelease.getUuid().toString()), s);
		assertFalse(s.contains("7.7.7"), s);
		assertFalse(s.contains("0.5.0"), s);
		assertFalse(s.contains("DRAFT"), s);

		AutoIntegrateResult explained = releaseService.autoIntegrateFeatureSetOnDemandExplained(fs);
		assertEquals(ev, explained.evaluation());
		assertTrue(explained.release().isEmpty());
		assertTrue(releaseService.autoIntegrateFeatureSetOnDemand(fs).isEmpty(),
				"a null instead of the NoSuchElementException of today");
		assertEquals(0, count(fsUuid));
		assertTrue(latestAssignment(fsUuid).isEmpty(), "no mint");
		assertEquals(before, snapshot(fsUuid, own, foreign, foreignDraft));
	}

	/** Row 15: a foreign TRANSIENT, then JOB, dependency makes the set CREATE_FAILED with no product release. */
	@Test
	public void row15_foreignTransientAndJobStopTheCreate() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Organization otherOrg = testInitializer.obtainOrganization();
		Comp own = component(org, "dryRunOwn");
		ReleaseData ownRelease = assembled(org, own, "1.0.0");
		Comp foreign = component(otherOrg, "dryRunForeign");
		ReleaseData foreignRelease = assembled(otherOrg, foreign, "7.7.7");
		Component product = product(org);
		for (StatusEnum status : new StatusEnum[] { StatusEnum.TRANSIENT, StatusEnum.JOB }) {
			UUID fsUuid = featureSet(product, "fs" + status);
			BranchData fs = configure(fsUuid, AutoIntegrateState.ENABLED, dep(own, StatusEnum.REQUIRED),
					dep(foreign, status));
			Snapshot before = snapshot(fsUuid, own, foreign);

			AutoIntegrateEvaluation ev = releaseService.evaluateAutoIntegrate(fs);
			assertEquals(AutoIntegrateOutcome.CREATE_FAILED, ev.outcome(), status.name());
			assertEquals(FOREIGN_NOTE, ev.note());
			assertNull(ev.lockRefusal());
			AutoIntegrateDependencyRow foreignRow = row(ev, foreign);
			assertEquals(status, foreignRow.status());
			assertEquals(AutoIntegrateReleaseSource.LATEST_ASSEMBLED, foreignRow.source());
			assertNull(foreignRow.release());
			assertEquals(FOREIGN_NOTE, foreignRow.note());
			assertRowRelease(row(ev, own), ownRelease);
			assertNull(ev.existingRelease());
			assertNull(ev.projectedVersion());
			String s = ev.toString();
			assertFalse(s.contains(foreignRelease.getUuid().toString()), s);
			assertFalse(s.contains("7.7.7"), s);

			AutoIntegrateResult explained = releaseService.autoIntegrateFeatureSetOnDemandExplained(fs);
			assertTrue(explained.release().isEmpty());
			assertEquals(ev, explained.evaluation());
			assertTrue(releaseService.autoIntegrateFeatureSetOnDemand(fs).isEmpty());
			assertEquals(0, count(fsUuid), "no product release with a single parent");
			assertTrue(latestAssignment(fsUuid).isEmpty(), "no mint");
			assertEquals(before, snapshot(fsUuid, own, foreign));
		}
	}
}
