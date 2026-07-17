/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Assertions;

import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

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
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.oss.TestInitializer;

/**
 * Reproduces (pre-fix) / guards (post-fix) the scheduled-path transaction
 * bug: when {@link OssReleaseService#processRelease} is invoked WITHOUT an
 * ambient transaction — as the scheduled metrics recompute does via
 * {@code ReleaseService.computeMetricsForReleaseList} — a
 * {@code RELEASE_LIFECYCLE_CHANGE} output trigger reaches the
 * {@code MANDATORY} {@code ReleaseChangeHook.onReleaseLifecycleChanged} hook.
 *
 * <p>Before the fix this threw {@code IllegalTransactionStateException}
 * ("No existing transaction found for transaction marked with propagation
 * 'mandatory'") so the lifecycle transition was aborted. After making
 * {@code processRelease} {@code @Transactional} the call opens its own
 * transaction and the transition completes.
 *
 * <p>The trigger is attached to the component AFTER the release is created,
 * so it does not fire on the (already-transactional) create path and only
 * fires on this bare {@code processRelease} call — mirroring the scheduled
 * recompute where the release already exists and is re-processed with no
 * surrounding transaction.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
public class ScheduledLifecycleTriggerTxTest {

	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private TestInitializer testInitializer;

	@Test
	public void schedulingPathLifecycleTriggerOpensItsOwnTransaction() throws RelizaException {
		WhoUpdated wu = WhoUpdated.getTestWhoUpdated();
		Organization org = testInitializer.obtainOrganization();
		Component comp = componentService.createComponent("schedLifecycleTxComp-" + UUID.randomUUID(),
				org.getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null, wu);
		Branch branch = branchService.getBaseBranchOfComponent(comp.getUuid()).get();

		// Release first (no triggers yet) -> nothing fires on the create path.
		ReleaseDto releaseDto = ReleaseDto.builder()
				.version("0.0.1-sched-lifecycle-tx")
				.branch(branch.getUuid())
				.org(org.getUuid())
				.build();
		Release r = ossReleaseService.createRelease(releaseDto, wu);
		Assertions.assertEquals(ReleaseLifecycle.DRAFT,
				ReleaseData.dataFromRecord(sharedReleaseService.getRelease(r.getUuid()).get()).getLifecycle());

		// Now attach an always-fire input trigger bound to a RELEASE_LIFECYCLE_CHANGE
		// output trigger that rejects the release.
		ReleaseOutputEvent reject = ReleaseOutputEvent.builder()
				.uuid(UUID.randomUUID())
				.name("reject-on-vuln")
				.type(EventType.RELEASE_LIFECYCLE_CHANGE)
				.toReleaseLifecycle(ReleaseLifecycle.REJECTED)
				.build();
		ReleaseInputEvent always = new ReleaseInputEvent();
		always.setUuid(UUID.randomUUID());
		always.setName("always-fire");
		always.setCelExpression("true");
		always.setEnabled(true);
		always.setOutputEvents(Set.of(reject.getUuid()));
		componentService.updateComponent(ComponentDto.builder()
				.uuid(comp.getUuid())
				.releaseInputTriggers(List.of(always))
				.outputTriggers(List.of(reject))
				.build(), wu);

		// Bare call, exactly like the scheduled metrics path: no ambient tx.
		// Pre-fix the MANDATORY hook threw IllegalTransactionStateException;
		// post-fix the @Transactional on processRelease supplies the tx.
		Assertions.assertDoesNotThrow(() -> ossReleaseService.processRelease(r.getUuid()));

		ReleaseData after = ReleaseData.dataFromRecord(sharedReleaseService.getRelease(r.getUuid()).get());
		Assertions.assertEquals(ReleaseLifecycle.REJECTED, after.getLifecycle(),
				"scheduled-path lifecycle trigger should have transitioned the release to REJECTED");

		// Sensitive specifically to the transaction fix (fix #1), not just to
		// the per-trigger try/catch (fix #2): the lifecycle trigger only records
		// its TRIGGER update event AFTER updateReleaseLifecycle returns. Without
		// the transaction the MANDATORY onReleaseLifecycleChanged hook throws
		// inside updateReleaseLifecycle, so this event is never recorded (the
		// throw is then merely swallowed by the per-trigger catch). Its presence
		// proves the hook completed inside an open transaction.
		boolean lifecycleTriggerEventRecorded = after.getUpdateEvents().stream()
				.anyMatch(ue -> ue.rus() == ReleaseUpdateScope.TRIGGER && reject.getUuid().equals(ue.objectId()));
		Assertions.assertTrue(lifecycleTriggerEventRecorded,
				"the RELEASE_LIFECYCLE_CHANGE trigger must complete (and record its TRIGGER event) inside the "
				+ "transaction processRelease opens — its absence means the MANDATORY hook threw for lack of a tx");
	}
}
