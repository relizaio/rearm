/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class BranchBaseUniquenessTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private BranchService branchService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@Test
	public void aComponentKeepsExactlyOneBaseBranch() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component c = componentService.createComponent("base_guard_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		ComponentData cd = getComponentService.getComponentData(c.getUuid()).orElseThrow();

		// creation already made one, and getBaseBranchOfComponent answers unambiguously
		UUID firstBase = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow().getUuid();

		RelizaException e = assertThrows(RelizaException.class, () -> branchService.createBranch(
				"second-base", cd, BranchType.BASE, null, null, null, null, WU),
				"a second base branch is refused rather than silently created");
		assertTrue(e.getMessage().contains("already has a base"), "the refusal says why");

		// the incumbent is untouched, so every later lookup still resolves
		assertEquals(firstBase, branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow().getUuid());

		// a regular branch is unaffected by the guard
		branchService.createBranch("feature-x", cd, BranchType.REGULAR, null, null, null, null, WU);
		assertEquals(firstBase, branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow().getUuid());
	}
}
