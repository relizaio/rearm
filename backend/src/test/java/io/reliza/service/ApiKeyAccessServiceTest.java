/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.reliza.model.ApiKeyAccess;
import io.reliza.repositories.ApiKeyAccessRepository;
import io.reliza.repositories.ApiKeyRepository;

/**
 * The last-access touch is an in-memory accumulator drained by a scheduler
 * tick, NOT a per-request row UPDATE — a hot key's row lock serialized every
 * concurrent request using that key and timed out under production load.
 * These tests pin the contract: no api_keys write on the request path, one
 * guarded write per key per flush, failed flushes re-queue, and the audit
 * path never throws into the authentication flow.
 */
class ApiKeyAccessServiceTest {

	private ApiKeyAccessRepository accessRepository;
	private ApiKeyRepository apiKeyRepository;
	private ApiKeyAccessService service;

	private final UUID keyUuid = UUID.randomUUID();
	private final UUID org = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		accessRepository = mock(ApiKeyAccessRepository.class);
		apiKeyRepository = mock(ApiKeyRepository.class);
		service = new ApiKeyAccessService(accessRepository, apiKeyRepository);
	}

	@Test
	void recordAccessDoesNotTouchApiKeysRow() {
		when(accessRepository.existsRecentAccess(eq(keyUuid), anyString())).thenReturn(true);
		service.recordApiKeyAccess(keyUuid, "10.0.0.1", org, "KEY_ID");
		verifyNoMoreInteractions(apiKeyRepository);
	}

	@Test
	void flushWritesOneGuardedTouchPerKeyAndDrains() {
		when(accessRepository.existsRecentAccess(any(), anyString())).thenReturn(true);
		UUID secondKey = UUID.randomUUID();
		service.recordApiKeyAccess(keyUuid, "10.0.0.1", org, "KEY_ID");
		service.recordApiKeyAccess(keyUuid, "10.0.0.2", org, "KEY_ID");
		service.recordApiKeyAccess(secondKey, "10.0.0.1", org, "KEY_ID2");

		assertEquals(2, service.flushPendingLastAccessTouches());
		verify(apiKeyRepository, times(1)).touchLastAccessDateIfNewer(eq(keyUuid), any(ZonedDateTime.class));
		verify(apiKeyRepository, times(1)).touchLastAccessDateIfNewer(eq(secondKey), any(ZonedDateTime.class));

		// Drained: an immediate second flush writes nothing.
		assertEquals(0, service.flushPendingLastAccessTouches());
		verifyNoMoreInteractions(apiKeyRepository);
	}

	@Test
	void failedFlushRequeuesForNextTick() {
		when(accessRepository.existsRecentAccess(any(), anyString())).thenReturn(true);
		service.recordApiKeyAccess(keyUuid, "10.0.0.1", org, "KEY_ID");

		doThrow(new RuntimeException("db down"))
				.when(apiKeyRepository).touchLastAccessDateIfNewer(eq(keyUuid), any(ZonedDateTime.class));
		assertEquals(0, service.flushPendingLastAccessTouches());

		// Next tick retries the same key.
		reset(apiKeyRepository);
		assertEquals(1, service.flushPendingLastAccessTouches());
		verify(apiKeyRepository, times(1)).touchLastAccessDateIfNewer(eq(keyUuid), any(ZonedDateTime.class));
	}

	@Test
	void auditFailureNeverThrowsAndStillQueuesTouch() {
		when(accessRepository.existsRecentAccess(any(), anyString())).thenReturn(false);
		when(accessRepository.save(any(ApiKeyAccess.class))).thenThrow(new RuntimeException("insert failed"));

		assertDoesNotThrow(() -> service.recordApiKeyAccess(keyUuid, "10.0.0.1", org, "KEY_ID"));

		// The touch was queued before the audit insert blew up.
		assertEquals(1, service.flushPendingLastAccessTouches());
		verify(apiKeyRepository, times(1)).touchLastAccessDateIfNewer(eq(keyUuid), any(ZonedDateTime.class));
	}

	@Test
	void dedupedAuditStillQueuesTouchWithoutInsert() {
		when(accessRepository.existsRecentAccess(eq(keyUuid), anyString())).thenReturn(true);
		service.recordApiKeyAccess(keyUuid, "10.0.0.1", org, "KEY_ID");
		verify(accessRepository, never()).save(any(ApiKeyAccess.class));
		assertEquals(1, service.flushPendingLastAccessTouches());
	}
}
