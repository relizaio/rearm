/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputResolution;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.ReleaseData.ReleaseLifecycle;

/**
 * TPF-162 mutation round: a requirement pinned to one release keeps everything else about the requirement, its floor
 * and its resolution included, because the carried round is held to the same threshold as the round it stands for.
 */
public class RequiredInputPinnedToEdgesTest {

	@Test
	public void aPinKeepsEverythingButTheRelease() {
		UUID component = UUID.randomUUID();
		UUID release = UUID.randomUUID();
		RequiredInput ri = new RequiredInput(InputKind.DOCUMENT, RearmSpecificationType.ARCHITECTURE, InputScope.TASK, component,
				ReleaseLifecycle.READY_TO_SHIP, InputResolution.STRICT_LATEST);

		RequiredInput pinned = ri.pinnedTo(release);

		assertEquals(release, pinned.release());
		assertEquals(InputKind.DOCUMENT, pinned.kind());
		assertEquals(RearmSpecificationType.ARCHITECTURE, pinned.specification());
		assertEquals(InputScope.TASK, pinned.scope());
		assertEquals(component, pinned.component());
		assertEquals(ReleaseLifecycle.READY_TO_SHIP, pinned.minLifecycle());
		assertEquals(InputResolution.STRICT_LATEST, pinned.resolution());
		assertNull(ri.release(), "the requirement itself is not changed");
	}
}
