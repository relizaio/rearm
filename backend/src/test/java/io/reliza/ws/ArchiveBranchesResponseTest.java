/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.BranchArchiveOutcome;
import io.reliza.model.BranchArchiveResult;
import io.reliza.model.UserData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.BranchBulkArchiveService;
import io.reliza.service.UserService;

/**
 * The archiveBranches response and its bound at the resolver (task TPF-77, mutation round), plain JUnit:
 * every result carries its own uuid, outcome and message; the three counts are derived from the list, each
 * outcome in its own bucket; the bound refuses 201 ids and admits 200.
 */
public class ArchiveBranchesResponseTest {

	@SuppressWarnings("unchecked")
	private static Map<String, Object> dto(List<BranchArchiveResult> results) {
		return (Map<String, Object>) ReflectionTestUtils.invokeMethod(ComponentDataFetcher.class, "archiveBulkDto", results);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void everyResultCarriesItsOwnFieldsAndEachOutcomeCountsInItsOwnBucket() {
		UUID archived = UUID.randomUUID();
		UUID base = UUID.randomUUID();
		UUID again = UUID.randomUUID();
		UUID gone = UUID.randomUUID();
		UUID denied = UUID.randomUUID();
		UUID failed = UUID.randomUUID();
		Map<String, Object> out = dto(List.of(
				BranchArchiveResult.archived(archived),
				BranchArchiveResult.skipped(base, BranchArchiveOutcome.SKIPPED_BASE),
				BranchArchiveResult.skipped(again, BranchArchiveOutcome.SKIPPED_ARCHIVED),
				BranchArchiveResult.skipped(gone, BranchArchiveOutcome.NOT_FOUND),
				BranchArchiveResult.skipped(denied, BranchArchiveOutcome.FORBIDDEN),
				BranchArchiveResult.failed(failed, "could not be archived")));

		assertEquals(1, out.get("archivedCount"));
		assertEquals(2, out.get("skippedCount"));
		assertEquals(3, out.get("failedCount"));
		List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("results");
		assertEquals(List.of(archived, base, again, gone, denied, failed), items.stream().map(i -> i.get("branchUuid")).toList());
		assertEquals(List.of("ARCHIVED", "SKIPPED_BASE", "SKIPPED_ARCHIVED", "NOT_FOUND", "FORBIDDEN", "FAILED"),
				items.stream().map(i -> i.get("outcome")).toList());
		assertEquals("could not be archived", items.get(5).get("message"));
		assertEquals(null, items.get(0).get("message"));
	}

	@Test
	public void theResolverRefusesMoreThanTheBoundAndAdmitsTheBound() {
		List<UUID> over = new ArrayList<>();
		while (over.size() <= BranchBulkArchiveService.BULK_ARCHIVE_MAX_IDS) over.add(UUID.randomUUID());
		RelizaException refused = assertThrows(RelizaException.class, () -> new ComponentDataFetcher().archiveBranches(over));
		assertTrue(refused.getMessage().contains("limited to 200"), refused.getMessage());

		// exactly the bound passes the check: with no collaborators wired the call fails later, and not on the bound
		List<UUID> exactly = over.subList(0, BranchBulkArchiveService.BULK_ARCHIVE_MAX_IDS);
		Exception later = assertThrows(Exception.class, () -> new ComponentDataFetcher().archiveBranches(exactly));
		assertFalse(String.valueOf(later.getMessage()).contains("limited to"), String.valueOf(later.getMessage()));
	}

	@Test
	public void theSealedSystemRefusalReachesTheCallerAndTheServiceIsHandedTheCaller() throws Exception {
		ComponentDataFetcher fetcher = new ComponentDataFetcher();
		UserService users = mock(UserService.class);
		BranchBulkArchiveService bulk = mock(BranchBulkArchiveService.class);
		UserData ud = mock(UserData.class);
		UUID caller = UUID.randomUUID();
		when(ud.getUuid()).thenReturn(caller);
		when(users.getUserDataByAuth(any())).thenReturn(Optional.of(ud));
		when(bulk.archiveBranches(any(), any(), any())).thenThrow(new RelizaException("System is sealed. Please unseal the system first."));
		ReflectionTestUtils.setField(fetcher, "userService", users);
		ReflectionTestUtils.setField(fetcher, "branchBulkArchiveService", bulk);
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claim("sub", "u").build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
		try {
			RelizaException refused = assertThrows(RelizaException.class,
					() -> fetcher.archiveBranches(List.of(UUID.randomUUID())));
			assertTrue(refused.getMessage().contains("System is sealed"), refused.getMessage());
			ArgumentCaptor<WhoUpdated> wu = ArgumentCaptor.forClass(WhoUpdated.class);
			verify(bulk).archiveBranches(any(), eq(ud), wu.capture());
			assertEquals(caller, wu.getValue().getLastUpdatedBy(), "the service is handed the caller");
		} finally {
			SecurityContextHolder.clearContext();
		}
	}
}
