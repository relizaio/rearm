/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;
import io.reliza.service.BranchBulkArchiveService;

/**
 * The server-side bound on one archiveBranches call (TPF-77).
 *
 * <p>The fetcher is built with every collaborator null on purpose: the bound must fire before
 * the user lookup, the sealed-system gate and any repository call, so reaching any of them
 * would NPE rather than throw the named error.
 */
class ArchiveBranchesCapTest {

	private static final int CAP = BranchBulkArchiveService.BULK_ARCHIVE_MAX_IDS;

	private static List<UUID> ids(int n) {
		List<UUID> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) out.add(UUID.randomUUID());
		return out;
	}

	@Test
	void refusesMoreThanTheCapBeforeTouchingAnything() {
		RelizaException e = assertThrows(RelizaException.class,
				() -> new ComponentDataFetcher().archiveBranches(ids(CAP + 1)));
		assertTrue(e.getMessage().contains("200"), "the refusal must name the limit: " + e.getMessage());
		assertTrue(e.getMessage().contains("201"), "and what was supplied: " + e.getMessage());
	}

	@Test
	void anEmptyListAnswersAnEmptyResponse() throws Exception {
		Map<String, Object> out = new ComponentDataFetcher().archiveBranches(List.of());
		assertEquals(0, ((List<?>) out.get("results")).size());
		assertEquals(0, out.get("archivedCount"));
		assertEquals(0, out.get("skippedCount"));
		assertEquals(0, out.get("failedCount"));
	}

	@Test
	void theCapCountsTheIdsSuppliedNotTheDistinctOnes() {
		UUID one = UUID.randomUUID();
		List<UUID> dupes = new ArrayList<>();
		for (int i = 0; i < CAP + 1; i++) dupes.add(one);
		RelizaException e = assertThrows(RelizaException.class,
				() -> new ComponentDataFetcher().archiveBranches(dupes));
		assertTrue(e.getMessage().contains("limited to"), "201 copies of one uuid are 201 ids: " + e.getMessage());
	}
}
