/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateDependencyRow;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateEvaluation;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateOutcome;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateRef;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateReleaseRef;
import io.reliza.model.dto.AutoIntegrateDtos.AutoIntegrateReleaseSource;

/**
 * TPF-119 mutation round: the copy methods of the auto-integrate DTOs, which the service tests reach only
 * through the paths that happen to call them. What each one drops, keeps and sets is pinned here.
 */
public class AutoIntegrateDtosTest {

	private static final AutoIntegrateReleaseRef RELEASE = new AutoIntegrateReleaseRef(UUID.randomUUID(), "1.2.3",
			ReleaseLifecycle.ASSEMBLED, ZonedDateTime.now());
	private static final AutoIntegrateReleaseRef EXISTING = new AutoIntegrateReleaseRef(UUID.randomUUID(), "9.9.9",
			ReleaseLifecycle.ASSEMBLED, ZonedDateTime.now());
	private static final AutoIntegrateDependencyRow ROW = new AutoIntegrateDependencyRow(
			new AutoIntegrateRef(UUID.randomUUID(), "component"), new AutoIntegrateRef(UUID.randomUUID(), "branch"),
			StatusEnum.REQUIRED, AutoIntegrateReleaseSource.PINNED, RELEASE, "pinned");

	private static AutoIntegrateEvaluation full() {
		return new AutoIntegrateEvaluation(AutoIntegrateOutcome.WOULD_CREATE, EXISTING, "4.5.6", List.of(ROW),
				"a note", "a refusal");
	}

	@Test
	public void aRedactedRowKeepsEverythingButTheReleaseAndSaysWhy() {
		AutoIntegrateDependencyRow r = ROW.redacted();

		assertEquals(ROW.component(), r.component());
		assertEquals(ROW.branch(), r.branch());
		assertEquals(ROW.status(), r.status());
		assertEquals(ROW.source(), r.source());
		assertNull(r.release());
		assertEquals("release not shown: you have no read permission on this component", r.note());
		assertEquals(AutoIntegrateDependencyRow.NOT_READABLE_NOTE, r.note());
		assertEquals("pinned", ROW.note(), "the original row is left as it was");
		assertSame(RELEASE, ROW.release());
	}

	@Test
	public void withOutcomeSetsTheOutcomeAndTheNoteAndDropsTheVersionAndTheRefusal() {
		AutoIntegrateEvaluation e = full().withOutcome(AutoIntegrateOutcome.CREATE_FAILED, "declined");

		assertEquals(AutoIntegrateOutcome.CREATE_FAILED, e.outcome());
		assertEquals("declined", e.note());
		assertNull(e.projectedVersion(), "the version is WOULD_CREATE only");
		assertNull(e.lockRefusal(), "a plain failure is not a lock");
		assertSame(EXISTING, e.existingRelease());
		assertEquals(List.of(ROW), e.dependencies());
	}

	@Test
	public void withLockRefusalIsAFailureWhoseNoteAndRefusalAreTheTextAndDropsTheVersion() {
		AutoIntegrateEvaluation e = full().withLockRefusal("component is locked");

		assertEquals(AutoIntegrateOutcome.CREATE_FAILED, e.outcome());
		assertEquals("component is locked", e.note());
		assertEquals("component is locked", e.lockRefusal());
		assertNull(e.projectedVersion());
		assertSame(EXISTING, e.existingRelease());
		assertEquals(List.of(ROW), e.dependencies());
	}

	@Test
	public void withDependenciesChangesTheRowsAndNothingElse() {
		AutoIntegrateDependencyRow other = ROW.redacted();
		AutoIntegrateEvaluation e = full().withDependencies(List.of(other));

		assertEquals(List.of(other), e.dependencies());
		assertEquals(AutoIntegrateOutcome.WOULD_CREATE, e.outcome());
		assertSame(EXISTING, e.existingRelease());
		assertEquals("4.5.6", e.projectedVersion());
		assertEquals("a note", e.note());
		assertEquals("a refusal", e.lockRefusal());
	}
}
