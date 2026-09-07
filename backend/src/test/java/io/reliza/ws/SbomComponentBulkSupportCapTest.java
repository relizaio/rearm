/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;
import io.reliza.service.SbomComponentService;

/**
 * The server-side bound on one bulk support attestation.
 *
 * <p>This is a SERVER control and not the client's 5,000-id walk cap. That one is ergonomics
 * on a UI; the mutation is directly callable and the UI's opinion is not a limit. What this
 * bounds is amplification: {@code RateLimitingFilter} charges ONE TOKEN PER REQUEST, not per
 * item, so without a cap a single 100k-id mutation costs a caller exactly what a 1-id
 * mutation costs, while doing 100k components of work and holding a connection long enough to
 * starve the pool for the whole tenant. Per-item rate limiting is deliberately NOT the answer
 * -- the amplification factor is the problem, so bound the factor.
 *
 * <p>The fetcher is built with every collaborator null on purpose. That is the assertion:
 * the guard must fire before authorization, before the org lookup and before any repository
 * call, so reaching any of them would NPE rather than throw the named error. A cap enforced
 * after the components are loaded would already have spent what it exists to protect.
 */
class SbomComponentBulkSupportCapTest {

	/** The real constant, not a retyped copy -- so it cannot drift from what the server enforces. */
	private static final int CAP = SbomComponentService.BULK_SUPPORT_MAX_IDS;

	private static List<UUID> ids(int n) {
		List<UUID> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) out.add(UUID.randomUUID());
		return out;
	}

	private static RelizaException callWith(int idCount) {
		SbomComponentDataFetcher fetcher = new SbomComponentDataFetcher();
		return assertThrows(RelizaException.class, () -> fetcher.bulkSetSbomComponentSupport(
				ids(idCount), null, null, null, null, null, null, null, null, null, null));
	}

	@Test
	void refusesMoreThanTheCapBeforeTouchingAnything() {
		RelizaException e = callWith(CAP + 1);
		assertTrue(e.getMessage().contains(String.valueOf(CAP)),
				"the refusal must name the limit, or a caller cannot batch to it: " + e.getMessage());
		assertTrue(e.getMessage().contains(String.valueOf(CAP + 1)),
				"and must name what was actually supplied: " + e.getMessage());
		assertTrue(e.getMessage().contains("batchId"),
				"a caller told to split a sweep must be told how to keep it ONE sweep in the"
						+ " audit record, otherwise the cap silently fragments the history it"
						+ " was added alongside: " + e.getMessage());
	}


	/**
	 * An empty or absent list returns the empty response rather than throwing -- the cap is
	 * an upper bound, not a requirement that a caller send work.
	 */
	@Test
	void anEmptyListIsNotACapViolation() {
		SbomComponentDataFetcher fetcher = new SbomComponentDataFetcher();
		assertEquals(0, ((List<?>) assertDoesNotThrowResults(fetcher)).size());
	}

	private static Object assertDoesNotThrowResults(SbomComponentDataFetcher fetcher) {
		try {
			return fetcher.bulkSetSbomComponentSupport(List.of(), null, null, null, null, null,
					null, null, null, null, null).get("results");
		} catch (RelizaException e) {
			throw new AssertionError("an empty bulk call must not be treated as a violation", e);
		}
	}


	/**
	 * The cap counts what the CALLER sent, before deduplication.
	 *
	 * <p>Deliberate, and worth pinning because the opposite is defensible-sounding: the cost
	 * this bounds is parsing and holding the request, which 1001 copies of one uuid incurs in
	 * full. Deduplicating first would let a caller send an arbitrarily large body for free.
	 */
	/**
	 * Exactly at the cap is allowed. Not cosmetic: our client batches at 200, so the only
	 * caller who ever meets this boundary is one who read the number in the error message and
	 * batched to it -- an off-by-one refuses precisely the person who did as they were told.
	 */
	@Test
	void acceptsExactlyTheCap() {
		SbomComponentDataFetcher fetcher = new SbomComponentDataFetcher();
		// Collaborators are null, so passing the guard fails LATER with an NPE. Asserting on
		// that distinction is what makes this a unit test of the guard alone.
		Throwable t = assertThrows(Throwable.class, () -> fetcher.bulkSetSbomComponentSupport(
				ids(CAP), null, null, null, null, null, null, null, null, null, null));
		assertInstanceOf(NullPointerException.class, t,
				"a call of exactly " + CAP + " ids must pass the guard and reach a collaborator,"
						+ " not be refused: " + t);
	}

	@Test
	void theCapCountsTheIdsSuppliedNotTheDistinctOnes() {
		List<UUID> dupes = new ArrayList<>();
		UUID one = UUID.randomUUID();
		for (int i = 0; i < CAP + 1; i++) dupes.add(one);
		SbomComponentDataFetcher fetcher = new SbomComponentDataFetcher();
		RelizaException e = assertThrows(RelizaException.class,
				() -> fetcher.bulkSetSbomComponentSupport(dupes, null, null, null, null, null,
						null, null, null, null, null));
		assertTrue(e.getMessage().contains("limited to"),
				"1001 copies of one uuid is still 1001 ids on the wire: " + e.getMessage());
	}

	/**
	 * The mint-on-null path, which is the only genuinely new logic in the resolver and which
	 * every other test bypasses by passing an explicit id.
	 *
	 * <p>The regression this exists to catch ships green otherwise: returning the raw
	 * {@code batchId} argument instead of the minted one makes every non-paged sweep write
	 * NULL batch ids while the response advertises a fresh uuid. The feature is then inert and
	 * nothing fails.
	 */
	@Test
	void anEmptyCallStillMintsAndReturnsABatchId() throws Exception {
		SbomComponentDataFetcher fetcher = new SbomComponentDataFetcher();
		Object first = fetcher.bulkSetSbomComponentSupport(List.of(), null, null, null, null,
				null, null, null, null, null, null).get("batchId");
		assertNotNull(first, "batchId is declared ID! -- it must be present on every response");
		assertInstanceOf(UUID.class, first, "the response must carry a real uuid, not a string"
				+ " that happens to look like one");

		Object second = fetcher.bulkSetSbomComponentSupport(List.of(), null, null, null, null,
				null, null, null, null, null, null).get("batchId");
		assertNotEquals(first, second, "each unpaged call is its own sweep and must mint its"
				+ " own id -- a shared or constant id would merge unrelated sweeps in the audit");
	}

	/** A supplied id is echoed back unchanged, or a paged client cannot trust what it got. */
	@Test
	void aSuppliedBatchIdIsReturnedVerbatim() throws Exception {
		UUID supplied = UUID.randomUUID();
		SbomComponentDataFetcher fetcher = new SbomComponentDataFetcher();
		assertEquals(supplied, fetcher.bulkSetSbomComponentSupport(List.of(), null, null, null,
				null, null, null, null, null, null, supplied).get("batchId"));
	}
}
