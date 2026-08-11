/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * DB-backed regression tests for the approval-queue candidate scan
 * {@code ReleaseRepository.findApprovalCandidateReleases} (SQL constant
 * {@code VariableQueries.FIND_APPROVAL_CANDIDATE_RELEASES}, BUG 13). The
 * predicate, ordering and LIMIT/OFFSET pagination are pure native SQL, so
 * Mockito-level tests can't pin them; these run against the real test Postgres
 * (each test gets a fresh org from {@link TestInitializer} and a fresh component
 * UUID, so no cross-test row bleeds through the org+component filter).
 *
 * <p>Pins:
 * <ul>
 *   <li><b>Filtering</b>: only releases matching org AND component-in-set AND
 *       lifecycle-in-set AND not-ARCHIVED are returned; a null {@code status}
 *       (ACTIVE-by-omission) is INCLUDED.</li>
 *   <li><b>Pagination</b>: LIMIT/OFFSET returns disjoint, complete pages.</li>
 *   <li><b>Stable tiebreaker</b> (the key one): the {@code , r.uuid DESC}
 *       tiebreaker makes paging deterministic even when many releases share the
 *       same {@code created_date}; every row is returned exactly once with no
 *       duplicates and no omissions. This test fails if the tiebreaker is
 *       removed and the ordering degenerates to {@code created_date DESC} alone,
 *       under which LIMIT/OFFSET can skip or duplicate tied rows.</li>
 * </ul>
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ReleaseApprovalCandidateQueryTest {

	@Autowired private ReleaseRepository releaseRepository;
	@Autowired private TestInitializer testInitializer;

	// Postgres orders the `uuid` type as unsigned bytes; each 8-byte half is
	// big-endian, so unsigned-long comparison of (msb, then lsb) reproduces it
	// exactly (java.util.UUID.compareTo does NOT — it compares the halves as
	// signed longs). Descending, to mirror `ORDER BY ... r.uuid DESC`.
	private static final java.util.Comparator<UUID> PG_UUID_DESC =
			((java.util.Comparator<UUID>) (a, b) -> {
				int c = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
				return c != 0 ? c
						: Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
			}).reversed();

	// The four pending lifecycles ApprovalNeedsService.PENDING_LIFECYCLES hands
	// the query; mirror them here as the lifecycle IN-set argument.
	private static final List<String> PENDING_LIFECYCLES = List.of(
			ReleaseLifecycle.PENDING.name(), ReleaseLifecycle.DRAFT.name(),
			ReleaseLifecycle.ASSEMBLED.name(), ReleaseLifecycle.READY_TO_SHIP.name());

	@Test
	public void filtersByOrgComponentLifecycleAndArchivedStatus() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID otherOrg = testInitializer.obtainOrganization().getUuid();
		UUID componentIn = UUID.randomUUID();
		UUID componentOut = UUID.randomUUID();
		ZonedDateTime now = ZonedDateTime.now();

		// Two positives: an ACTIVE-status pending release and a null-status one
		// (ACTIVE-by-omission must still be surfaced).
		Release matchActive = saveRelease(org, componentIn, ReleaseLifecycle.PENDING, ReleaseStatus.ACTIVE, now);
		Release matchNullStatus = saveRelease(org, componentIn, ReleaseLifecycle.DRAFT, null, now);
		// Negatives, one per filter clause.
		saveRelease(otherOrg, componentIn, ReleaseLifecycle.PENDING, ReleaseStatus.ACTIVE, now);      // wrong org
		saveRelease(org, componentOut, ReleaseLifecycle.PENDING, ReleaseStatus.ACTIVE, now);          // component not in set
		saveRelease(org, componentIn, ReleaseLifecycle.GENERAL_AVAILABILITY, ReleaseStatus.ACTIVE, now); // non-pending lifecycle
		saveRelease(org, componentIn, ReleaseLifecycle.PENDING, ReleaseStatus.ARCHIVED, now);         // archived status

		Set<UUID> got = uuidsOf(query(org, List.of(componentIn.toString()), 100, 0));

		assertEquals(Set.of(matchActive.getUuid(), matchNullStatus.getUuid()), got,
				"only org+component-in-set+lifecycle-in-set+non-archived releases are returned; "
				+ "a null status (ACTIVE-by-omission) must be included");
	}

	@Test
	public void paginationReturnsDisjointCompletePages() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID component = UUID.randomUUID();

		// Five matching releases with strictly-increasing created_date, so the
		// newest-first ordering is fully determined before the tiebreaker even
		// matters; page across them with limit 3.
		Set<UUID> all = new java.util.HashSet<>();
		ZonedDateTime base = ZonedDateTime.now().minusMinutes(10);
		for (int i = 0; i < 5; i++) {
			all.add(saveRelease(org, component, ReleaseLifecycle.PENDING, ReleaseStatus.ACTIVE,
					base.plusSeconds(i)).getUuid());
		}

		List<UUID> page0 = uuidsList(query(org, List.of(component.toString()), 3, 0));
		List<UUID> page1 = uuidsList(query(org, List.of(component.toString()), 3, 3));

		assertEquals(3, page0.size(), "first page holds exactly LIMIT rows");
		assertEquals(2, page1.size(), "second page holds the remainder");
		Set<UUID> union = new java.util.HashSet<>(page0);
		assertTrue(union.addAll(page1), "pages must not overlap");
		assertEquals(all, union, "page0 union page1 must equal the full set (no omissions, no overlap)");
	}

	/**
	 * THE KEY ONE. Insert several releases sharing the SAME {@code created_date}
	 * (as bulk inserts at now() do) and page through them with a LIMIT below the
	 * total. With {@code ORDER BY created_date DESC} alone the row order on ties
	 * is unspecified: the SQL standard gives no guarantee and LIMIT/OFFSET can
	 * skip or duplicate tied rows across independent page queries. The
	 * {@code , r.uuid DESC} tiebreaker imposes a TOTAL order so paging is
	 * deterministic and every row is returned exactly once.
	 *
	 * <p>The discriminating assertion pins that total order directly: the paged
	 * sequence must equal the rows sorted by uuid DESCENDING (Postgres compares
	 * the {@code uuid} type as unsigned bytes, mirrored here by
	 * {@link #PG_UUID_DESC}). Without the tiebreaker the rows come back in heap /
	 * insertion order — uncorrelated with their random uuids — so this assertion
	 * fails; a weaker "no skip / no duplicate" set check would NOT, because on a
	 * small freshly-inserted table Postgres happens to return ties in a stable
	 * per-query order, masking the defect.
	 */
	@Test
	public void stableTiebreakerPagesTiedTimestampsInDeterministicUuidOrder() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID component = UUID.randomUUID();

		// Five releases, ALL with the identical created_date instant.
		ZonedDateTime tied = ZonedDateTime.now();
		List<UUID> all = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			all.add(saveRelease(org, component, ReleaseLifecycle.PENDING, ReleaseStatus.ACTIVE, tied).getUuid());
		}

		// Walk every page of size 2 (offsets 0, 2, 4) and collect, in order, what
		// each page returns.
		List<UUID> collected = new ArrayList<>();
		for (int offset = 0; offset < 5; offset += 2) {
			collected.addAll(uuidsList(query(org, List.of(component.toString()), 2, offset)));
		}

		// No skip / no duplicate / complete (the behavioural guarantee).
		Set<UUID> distinct = new java.util.HashSet<>(collected);
		assertEquals(collected.size(), distinct.size(),
				"no tied-timestamp release may be returned twice across pages");
		assertEquals(new java.util.HashSet<>(all), distinct,
				"every tied-timestamp release must appear exactly once across the pages (no omissions)");
		assertEquals(5, collected.size(), "paging 5 tied rows at limit 2 must yield exactly 5 rows");

		// The tiebreaker's actual contract: on a created_date tie the total order
		// is uuid DESC, so the paged sequence equals the uuids sorted that way.
		// This is what breaks if `, r.uuid DESC` is removed.
		List<UUID> expectedOrder = new ArrayList<>(all);
		expectedOrder.sort(PG_UUID_DESC);
		assertEquals(expectedOrder, collected,
				"tied rows must page in uuid-DESC order (the , r.uuid DESC tiebreaker); "
				+ "without it the order degenerates to non-deterministic heap order");
	}

	// ---- helpers (local; these fixtures are not shared across test classes) ----

	private List<Release> query(UUID org, List<String> componentUuids, int limit, int offset) {
		return releaseRepository.findApprovalCandidateReleases(org.toString(), componentUuids,
				PENDING_LIFECYCLES, String.valueOf(limit), String.valueOf(offset));
	}

	private static Set<UUID> uuidsOf(List<Release> releases) {
		return releases.stream().map(Release::getUuid).collect(Collectors.toSet());
	}

	private static List<UUID> uuidsList(List<Release> releases) {
		return releases.stream().map(Release::getUuid).collect(Collectors.toList());
	}

	private Release saveRelease(UUID orgUuid, UUID componentUuid, ReleaseLifecycle lifecycle,
			ReleaseStatus status, ZonedDateTime createdDate) {
		Release r = new Release();
		r.setUuid(UUID.randomUUID());
		r.setCreatedDate(createdDate);
		r.setLastUpdatedDate(ZonedDateTime.now());
		r.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("component", componentUuid.toString());
		recordData.put("lifecycle", lifecycle.name());
		// A null status leaves the JSONB key absent, exercising the
		// "status IS NULL" branch of the predicate (ACTIVE-by-omission).
		if (null != status) {
			recordData.put("status", status.name());
		}
		r.setRecordData(recordData);
		return releaseRepository.save(r);
	}
}
