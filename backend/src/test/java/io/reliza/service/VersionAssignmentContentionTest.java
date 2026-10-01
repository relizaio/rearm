/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.VersionAssignmentContendedException;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.repositories.VersionAssignmentRepository;

/**
 * A mint that keeps losing to concurrent ones says so: a clear "contended, retry" error on the
 * getversion path, where "Not authorized" used to surface, and the historic empty result for the
 * callers that report a failed mint themselves.
 */
class VersionAssignmentContentionTest {

	private final UUID branch = UUID.randomUUID();
	private final VersionAssignmentService service = new VersionAssignmentService(mock(VersionAssignmentRepository.class));
	private final VersionAssignmentService self = mock(VersionAssignmentService.class);

	VersionAssignmentContentionTest() throws RelizaException {
		ReflectionTestUtils.setField(service, "componentLockService", mock(ComponentLockService.class));
		ReflectionTestUtils.setField(service, "self", self);
		when(self.getSetNewVersion(eq(branch), any(), any(), any(), any(), any(), anyBoolean()))
				.thenThrow(new DataIntegrityViolationException("duplicate key"));
	}

	@Test
	void exhaustedRetriesAreAContentionErrorNotARefusal() throws RelizaException {
		VersionAssignmentContendedException e = assertThrows(VersionAssignmentContendedException.class,
				() -> service.getSetNewVersionWrapper(branch, null, null, null, VersionTypeEnum.DEV, "abc123", false));
		assertEquals("Version assignment on this branch is contended by concurrent requests; retry", e.getMessage());
		verify(self, times(10)).getSetNewVersion(eq(branch), any(), any(), any(), any(), any(), anyBoolean());
	}

	@Test
	void commitLessCallersStillGetAnEmptyResult() {
		assertTrue(service.getSetNewVersionWrapper(branch, null, null, null, VersionTypeEnum.DEV).isEmpty());
	}

	@Test
	void aLockWaitThatTimesOutIsAContentionErrorWithoutRetrying() throws RelizaException {
		UUID locked = UUID.randomUUID();
		when(self.getSetNewVersion(eq(locked), any(), any(), any(), any(), any(), anyBoolean()))
				.thenThrow(new CannotAcquireLockException("lock timeout"));
		assertThrows(VersionAssignmentContendedException.class,
				() -> service.getSetNewVersionWrapper(locked, null, null, null, VersionTypeEnum.DEV, null, false));
		verify(self, times(1)).getSetNewVersion(eq(locked), any(), any(), any(), any(), any(), anyBoolean());
	}
}
