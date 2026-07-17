/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.EnvironmentType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseInputEvent;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.service.saas.SaasIntegrationService;
import io.reliza.ws.oss.TestInitializer;

/**
 * Guards the per-trigger isolation half of the fix: a single output trigger
 * that throws must NOT abort {@code processRelease}'s output-trigger loop, so
 * the remaining triggers still fire. Pre-fix the un-guarded {@code forEach}
 * propagated the first throw and silently skipped every later trigger (the
 * symptom that swallowed a PR_COMMENT queued after a failing
 * RELEASE_LIFECYCLE_CHANGE trigger).
 *
 * <p>Here an INTEGRATION_TRIGGER is forced to throw (its SCM dispatch is
 * mocked to fail, as a real network error would) and an
 * ADD_APPROVED_ENVIRONMENT trigger is the survivor — its effect
 * ({@code approvedEnvironments += DEV}) is persisted and observable, so the
 * test fails if the loop aborted before reaching it.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
public class TriggerFailureIsolationTest {

	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private TestInitializer testInitializer;

	@MockitoBean private SaasIntegrationService saasIntegrationService;

	@Test
	public void oneFailingTriggerDoesNotAbortTheRest() throws RelizaException {
		Mockito.doThrow(new RuntimeException("simulated SCM dispatch failure"))
				.when(saasIntegrationService)
				.triggerComponentBuildIntegration(Mockito.any(), Mockito.any(), Mockito.any());

		WhoUpdated wu = WhoUpdated.getTestWhoUpdated();
		Organization org = testInitializer.obtainOrganization();
		Component comp = componentService.createComponent("triggerIsolationComp-" + UUID.randomUUID(),
				org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null, wu);
		Branch branch = branchService.getBaseBranchOfComponent(comp.getUuid()).get();

		Release r = ossReleaseService.createRelease(ReleaseDto.builder()
				.version("0.0.1-trigger-isolation")
				.branch(branch.getUuid())
				.org(org.getUuid())
				.build(), wu);

		ReleaseOutputEvent failing = ReleaseOutputEvent.builder()
				.uuid(UUID.randomUUID()).name("failing-integration")
				.type(EventType.INTEGRATION_TRIGGER).build();
		ReleaseOutputEvent survivor = ReleaseOutputEvent.builder()
				.uuid(UUID.randomUUID()).name("approve-env")
				.type(EventType.ADD_APPROVED_ENVIRONMENT).approvedEnvironment("DEV").build();
		ReleaseInputEvent always = new ReleaseInputEvent();
		always.setUuid(UUID.randomUUID());
		always.setName("always-fire");
		always.setCelExpression("true");
		always.setEnabled(true);
		always.setOutputEvents(Set.of(failing.getUuid(), survivor.getUuid()));
		componentService.updateComponent(ComponentDto.builder()
				.uuid(comp.getUuid())
				.releaseInputTriggers(List.of(always))
				.outputTriggers(List.of(failing, survivor))
				.build(), wu);

		// The failing trigger throws regardless of forEach iteration order;
		// the loop must continue and still fire the survivor.
		Assertions.assertDoesNotThrow(() -> ossReleaseService.processRelease(r.getUuid()));

		ReleaseData after = ReleaseData.dataFromRecord(sharedReleaseService.getRelease(r.getUuid()).get());
		Assertions.assertTrue(after.getApprovedEnvironments().contains(EnvironmentType.forValue("DEV")),
				"the surviving ADD_APPROVED_ENVIRONMENT trigger must still fire even though a sibling "
				+ "INTEGRATION_TRIGGER threw — the output-trigger loop must not abort on the first failure");
	}
}
