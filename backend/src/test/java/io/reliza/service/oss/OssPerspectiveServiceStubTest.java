/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service.oss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.service.oss.OssPerspectiveService.PerspectiveSidResolution;

/**
 * Task TEA-2, design 4.4 case 30: the CE perspective hook answers nothing, so TEA profiles never
 * resolve through a perspective in CE, and the sid walk keeps answering none().
 */
class OssPerspectiveServiceStubTest {

	private final OssPerspectiveService service = new OssPerspectiveService();

	@Test
	void theTeaMethodsAnswerEmpty() {
		ComponentData cd = new ComponentData();
		cd.setUuid(UUID.randomUUID());
		cd.setPerspectives(Set.of(UUID.randomUUID()));
		assertFalse(service.teaPerspectivesSupported());
		assertTrue(service.teaPerspectivesOfComponent(cd).isEmpty());
		assertTrue(service.teaPerspectiveRef(UUID.randomUUID()).isEmpty());
		assertTrue(service.teaPerspectiveObject(UUID.randomUUID()).isEmpty());
	}

	@Test
	void theSidWalkStillAnswersNone() throws RelizaException {
		ComponentData cd = new ComponentData();
		cd.setPerspectives(Set.of(UUID.randomUUID()));
		PerspectiveSidResolution r = service.resolvePerspectiveSidOverrides(cd);
		assertNull(r.enabled());
		assertNull(r.segments());
	}
}
