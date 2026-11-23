/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputResolution;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData.ReleaseLifecycle;

/**
 * TPF-172 mutation round (TPF-171 D2): the floor a base round must clear is the lowest floor among the active roles
 * that read the document as a required input, a role's own floor being the highest of its inputs of it, and never
 * below ASSEMBLED, which is what a sign-off hands over.
 */
class AgentRoutingServiceAnswerBaseFloorTest {

	private static final RearmSpecificationType ARCH = RearmSpecificationType.ARCHITECTURE;
	private static final int ASSEMBLED = AgentTaskInputService.maturity(ReleaseLifecycle.ASSEMBLED);
	private static final int READY = AgentTaskInputService.maturity(ReleaseLifecycle.READY_TO_SHIP);

	private static RequiredInput input(InputKind kind, RearmSpecificationType spec, ReleaseLifecycle floor) {
		return new RequiredInput(kind, spec, InputScope.TASK, null, floor, InputResolution.LATEST_PASSING);
	}

	private static AgentTaskRoleConfigData role(boolean active, RequiredInput... inputs) {
		AgentTaskRoleConfigData rc = new AgentTaskRoleConfigData();
		rc.setName("r");
		rc.setActive(active);
		rc.setRequiredInputs(List.of(inputs));
		return rc;
	}

	private static int floor(RearmSpecificationType spec, AgentTaskRoleConfigData... roles) {
		return (Integer) ReflectionTestUtils.invokeMethod(AgentRoutingService.class, "baseFloor", List.of(roles), spec);
	}

	@Test
	public void noReaderMeansAssembled() {
		assertEquals(ASSEMBLED, floor(ARCH));
		assertEquals(ASSEMBLED, floor(ARCH, role(true, input(InputKind.DOCUMENT, RearmSpecificationType.THREAT_MODEL, ReleaseLifecycle.GENERAL_AVAILABILITY))));
	}

	@Test
	public void aReadersFloorRaisesItAboveAssembled() {
		assertEquals(READY, floor(ARCH, role(true, input(InputKind.DOCUMENT, ARCH, ReleaseLifecycle.READY_TO_SHIP))));
	}

	@Test
	public void theLowestReadersFloorDecidesNotTheHighest() {
		assertEquals(ASSEMBLED, floor(ARCH, role(true, input(InputKind.DOCUMENT, ARCH, ReleaseLifecycle.READY_TO_SHIP)),
				role(true, input(InputKind.DOCUMENT, ARCH, ReleaseLifecycle.ASSEMBLED))));
		assertEquals(ASSEMBLED, floor(ARCH, role(true, input(InputKind.DOCUMENT, ARCH, ReleaseLifecycle.ASSEMBLED)),
				role(true, input(InputKind.DOCUMENT, ARCH, ReleaseLifecycle.READY_TO_SHIP))));
	}

	@Test
	public void aRolesOwnFloorIsTheHighestOfItsInputsOfTheDocument() {
		assertEquals(READY, floor(ARCH, role(true, input(InputKind.DOCUMENT, ARCH, ReleaseLifecycle.ASSEMBLED),
				input(InputKind.DOCUMENT, ARCH, ReleaseLifecycle.READY_TO_SHIP))));
		assertEquals(READY, floor(ARCH, role(true, input(InputKind.DOCUMENT, ARCH, ReleaseLifecycle.READY_TO_SHIP),
				input(InputKind.DOCUMENT, ARCH, ReleaseLifecycle.ASSEMBLED))));
	}

	@Test
	public void aFloorBelowAssembledIsRaisedToIt() {
		assertEquals(ASSEMBLED, floor(ARCH, role(true, input(InputKind.DOCUMENT, ARCH, ReleaseLifecycle.DRAFT))));
		assertEquals(ASSEMBLED, floor(ARCH, role(true, input(InputKind.DOCUMENT, ARCH, null))));
	}

	@Test
	public void anInactiveRoleAndAReleaseKindInputAreNotReaders() {
		assertEquals(ASSEMBLED, floor(ARCH, role(false, input(InputKind.DOCUMENT, ARCH, ReleaseLifecycle.GENERAL_AVAILABILITY))));
		assertEquals(ASSEMBLED, floor(ARCH, role(true, input(InputKind.RELEASE, ARCH, ReleaseLifecycle.GENERAL_AVAILABILITY))));
		assertEquals(READY, floor(ARCH, role(false, input(InputKind.DOCUMENT, ARCH, ReleaseLifecycle.ASSEMBLED)),
				role(true, input(InputKind.DOCUMENT, ARCH, ReleaseLifecycle.READY_TO_SHIP))));
	}
}
