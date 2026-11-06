/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.JustifiedUnknownReason;
import io.reliza.model.LevelOfSupport;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.ReleaseSbomJustificationService;
import io.reliza.service.SbomComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;

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
				ids(idCount), null, null, null, null, null, null, null, null, null, null, null));
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
					null, null, null, null, null, null).get("results");
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
				ids(CAP), null, null, null, null, null, null, null, null, null, null, null));
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
						null, null, null, null, null, null));
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
				null, null, null, null, null, null, null).get("batchId");
		assertNotNull(first, "batchId is declared ID! -- it must be present on every response");
		assertInstanceOf(UUID.class, first, "the response must carry a real uuid, not a string"
				+ " that happens to look like one");

		Object second = fetcher.bulkSetSbomComponentSupport(List.of(), null, null, null, null,
				null, null, null, null, null, null, null).get("batchId");
		assertNotEquals(first, second, "each unpaged call is its own sweep and must mint its"
				+ " own id -- a shared or constant id would merge unrelated sweeps in the audit");
	}

	/** A supplied id is echoed back unchanged, or a paged client cannot trust what it got. */
	@Test
	void aSuppliedBatchIdIsReturnedVerbatim() throws Exception {
		UUID supplied = UUID.randomUUID();
		SbomComponentDataFetcher fetcher = new SbomComponentDataFetcher();
		assertEquals(supplied, fetcher.bulkSetSbomComponentSupport(List.of(), null, null, null,
				null, null, null, null, null, null, supplied, null).get("batchId"));
	}

	// ------------------------------------------------------------------ FDAW-14 row 9: bounds, gate, count order

	private static final String LONG = "x".repeat(2049);
	private static final String BELL = "a\u0007";

	private static Map<String, Object> selector(String form, String type, String namespace) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("form", form);
		m.put("type", type);
		if (null != namespace) m.put("namespace", namespace);
		return m;
	}

	private static Map<String, Object> unknown(String code, String text) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("code", code);
		m.put("text", text);
		return m;
	}

	/** One-argument-at-a-time selector mutation call; null collaborators, so reaching any is an NPE. */
	private static Object selectorCall(SbomComponentDataFetcher f, Map<String, Object> sel, String justification,
			String notes, Map<String, Object> unknown, String reason, boolean level) throws RelizaException {
		return f.bulkSetSbomComponentSupportBySelector(UUID.randomUUID(), sel, null,
				level ? LevelOfSupport.ACTIVELY_MAINTAINED : null, justification, null, null, null, null,
				notes, null, unknown, reason);
	}

	@Test
	void theSelectorMutationAndCountRefuseBadInputBeforeAnyCollaborator() {
		SbomComponentDataFetcher f = new SbomComponentDataFetcher();
		Map<Map<String, Object>, String> bad = new LinkedHashMap<>();
		bad.put(selector("TYPE", "np%", null), "bulk selector: type must match [a-z0-9.+-]{1,64}");
		bad.put(selector("NAMESPACE", "npm", null), "bulk selector: namespace is required for the NAMESPACE form");
		bad.put(selector("NAMESPACE", "npm", "n".repeat(257)),
				"bulk selector: namespace is longer than 256 characters or holds a control character");
		bad.put(selector("NAMESPACE", "npm", "a\u0001"),
				"bulk selector: namespace is longer than 256 characters or holds a control character");
		bad.forEach((sel, text) -> {
			assertEquals(text, assertThrows(RelizaException.class,
					() -> selectorCall(f, sel, null, null, null, null, true)).getMessage());
			assertEquals(text, assertThrows(RelizaException.class,
					() -> f.supportBulkSelectorCount(UUID.randomUUID(), sel, null)).getMessage());
		});
		assertEquals("a bulk assessment needs a level of support, a milestone date or a justified unknown",
				assertThrows(RelizaException.class, () -> selectorCall(f, selector("TYPE", "deb", null), "basis", null,
						null, null, false)).getMessage());
	}

	@Test
	void everyTextBoundRefusesBeforeAuthorisationOnAllThreeSupportVerbs() {
		SbomComponentDataFetcher f = new SbomComponentDataFetcher();
		Map<String, Object> sel = selector("TYPE", "deb", null);
		record Case(String justification, String notes, Map<String, Object> unknown, String reason, String text) {}
		List<Case> cases = List.of(
				new Case(LONG, null, null, null, "justification is longer than 2048 characters"),
				new Case(null, LONG, null, null, "supportNotes is longer than 2048 characters"),
				new Case(null, null, null, LONG, "reason is longer than 2048 characters"),
				new Case(null, null, null, BELL, "reason holds a control character"),
				new Case(null, null, unknown("OTHER", "t".repeat(513)), null,
						"justified unknown: the text is longer than 512 characters or holds a control character"));
		for (Case c : cases) {
			assertEquals(c.text(), assertThrows(RelizaException.class,
					() -> selectorCall(f, sel, c.justification(), c.notes(), c.unknown(), c.reason(), true)).getMessage());
			// ahead of the explicit form's id-count cap: CAP + 1 ids still get the bound's text
			assertEquals(c.text(), assertThrows(RelizaException.class, () -> f.bulkSetSbomComponentSupport(ids(CAP + 1),
					LevelOfSupport.ACTIVELY_MAINTAINED, c.justification(), null, null, null, null,
					c.notes(), null, c.reason(), null, c.unknown())).getMessage());
			assertEquals(c.text(), assertThrows(RelizaException.class, () -> f.setSbomComponentSupport(UUID.randomUUID(),
					LevelOfSupport.ACTIVELY_MAINTAINED, c.justification(), null, null, null, null, null,
					c.notes(), null, null, c.reason(), c.unknown(), null)).getMessage());
		}
		// at the limit the bound passes and the next step (a null collaborator) is reached
		assertInstanceOf(NullPointerException.class, assertThrows(Throwable.class,
				() -> selectorCall(f, sel, "x".repeat(2048), null, null, "r".repeat(2048), true)));
	}

	/** Mocked collaborators: the gate, then the scope, then the count, in that order. */
	private record Wired(SbomComponentDataFetcher fetcher, AuthorizationService auth, SbomComponentService service,
			SbomComponentRepository repository, SharedReleaseService releases, UUID org) {}

	private static Wired wired() throws Exception {
		SbomComponentDataFetcher f = new SbomComponentDataFetcher();
		UserService users = mock(UserService.class);
		when(users.getUserDataByAuth(any())).thenReturn(Optional.of(mock(UserData.class)));
		GetOrganizationService orgs = mock(GetOrganizationService.class);
		UUID org = UUID.randomUUID();
		OrganizationData od = new OrganizationData();
		od.setUuid(org);
		when(orgs.getOrganizationData(org)).thenReturn(Optional.of(od));
		AuthorizationService auth = mock(AuthorizationService.class);
		SbomComponentRepository repo = mock(SbomComponentRepository.class);
		// real methods, collaborators injected: the service's own count-then-cap order is under test too
		SbomComponentService service = mock(SbomComponentService.class, CALLS_REAL_METHODS);
		ReflectionTestUtils.setField(service, "sbomComponentRepository", repo);
		SharedReleaseService releases = mock(SharedReleaseService.class);
		ReflectionTestUtils.setField(f, "userService", users);
		ReflectionTestUtils.setField(f, "getOrganizationService", orgs);
		ReflectionTestUtils.setField(f, "authorizationService", auth);
		ReflectionTestUtils.setField(f, "sbomComponentService", service);
		ReflectionTestUtils.setField(f, "sharedReleaseService", releases);
		return new Wired(f, auth, service, repo, releases, org);
	}

	@Test
	void theGateRunsBeforeTheCountAndTheCapBeforeAnyRow() throws Exception {
		Wired w = wired();
		doThrow(new AccessDeniedException("forbidden")).when(w.auth()).isUserAuthorizedForObjectGraphQL(any(), any(),
				eq(PermissionScope.ORGANIZATION), any(), any(), eq(CallType.WRITE));
		Map<String, Object> sel = selector("TYPE", "deb", null);
		assertThrows(AccessDeniedException.class, () -> w.fetcher().supportBulkSelectorCount(w.org(), sel, null));
		assertThrows(AccessDeniedException.class, () -> w.fetcher().bulkSetSbomComponentSupportBySelector(w.org(), sel,
				null, LevelOfSupport.ACTIVELY_MAINTAINED, null, null, null, null, null, null, null, null,
				null));
		verifyNoInteractions(w.repository());

		Wired ok = wired();
		List<String> overCap = new ArrayList<>();
		for (int i = 0; i <= CAP; i++) overCap.add(UUID.randomUUID().toString());
		when(ok.repository().findUuidsBySupportSelector(anyString(), anyString(), any(), any(), anyInt()))
				.thenReturn(overCap);
		RelizaException e = assertThrows(RelizaException.class, () -> ok.fetcher().bulkSetSbomComponentSupportBySelector(
				ok.org(), sel, null, LevelOfSupport.ACTIVELY_MAINTAINED, null, null, null, null, null,
				null, null, null, null));
		assertEquals("a bulk support attestation is limited to 1000 components per call; the selector TYPE deb matches"
				+ " more than 1000 in the organization. Narrow it by namespace or to this release, or use Shown components.",
				e.getMessage());
		verify(ok.repository()).findUuidsBySupportSelector(anyString(), eq("deb"), any(), any(), eq(CAP + 1));
		verify(ok.repository(), never()).findAllById(any());

		// a release of another org: refused after the org gate, before the count
		Wired other = wired();
		UUID release = UUID.randomUUID();
		ReleaseData rd = mock(ReleaseData.class);
		when(rd.getUuid()).thenReturn(release);
		when(rd.getOrg()).thenReturn(UUID.randomUUID());
		when(other.releases().getReleaseData(release)).thenReturn(Optional.of(rd));
		e = assertThrows(RelizaException.class, () -> other.fetcher().supportBulkSelectorCount(other.org(), sel, release));
		assertEquals("release not found in this organization: " + release, e.getMessage());
		verify(other.auth()).isUserAuthorizedForObjectGraphQL(any(), any(), eq(PermissionScope.ORGANIZATION), any(),
				any(), eq(CallType.WRITE));
		verifyNoInteractions(other.repository());
	}

	/**
	 * Exactly at the cap is allowed for the SELECTOR form too, same reasoning as
	 * {@link #acceptsExactlyTheCap()} for the explicit form: an off-by-one at the selector's
	 * {@code ids.size() > BULK_SUPPORT_MAX_IDS} check (or at the count preview's
	 * {@code matched > BULK_SUPPORT_MAX_IDS}) would refuse the exact boundary a caller who
	 * narrowed to the advertised limit is entitled to reach.
	 */
	@Test
	void theSelectorFormAcceptsExactlyTheCap() throws Exception {
		Wired w = wired();
		Map<String, Object> sel = selector("TYPE", "deb", null);
		List<String> exactlyCap = new ArrayList<>();
		for (int i = 0; i < CAP; i++) exactlyCap.add(UUID.randomUUID().toString());
		when(w.repository().findUuidsBySupportSelector(anyString(), anyString(), any(), any(), anyInt()))
				.thenReturn(exactlyCap);

		Map<String, Object> count = w.fetcher().supportBulkSelectorCount(w.org(), sel, null);
		assertEquals(CAP, count.get("matched"));
		assertEquals(false, count.get("overCap"));

		// Collaborators beyond the resolved id list are not fully wired, so passing the cap
		// guard reaches a later NPE rather than succeeding outright -- the same shape as
		// acceptsExactlyTheCap(). The assertion here is that it is NOT the "limited to" refusal.
		Throwable t = assertThrows(Throwable.class, () -> w.fetcher().bulkSetSbomComponentSupportBySelector(
				w.org(), sel, null, LevelOfSupport.ACTIVELY_MAINTAINED, null, null, null, null, null,
				null, null, null, null));
		assertFalse(t instanceof RelizaException && t.getMessage().contains("limited to"),
				"exactly " + CAP + " matched ids must pass the cap guard, not be refused: " + t);
	}

	@Test
	void theHashVerbsBoundKeysAndTextsBeforeTheHead() throws Exception {
		ReleaseSbomJustificationDataFetcher f = new ReleaseSbomJustificationDataFetcher();
		ReleaseSbomJustificationService service = mock(ReleaseSbomJustificationService.class);
		ReflectionTestUtils.setField(f, "releaseSbomJustificationService", service);
		UUID head = UUID.randomUUID();
		String keysRequired = "keys: at least one component key is required";
		String keyRefused = "component key is longer than 2048 characters or holds a control character";
		JustifiedUnknownReason code = JustifiedUnknownReason.DISTRIBUTION_PUBLISHES_NO_HASH;
		Map<String, Object> sel = selector("TYPE", "deb", null);
		assertEquals(keysRequired, assertThrows(RelizaException.class,
				() -> f.setReleaseSbomHashJustification(head, List.of(), code, null, null)).getMessage());
		assertEquals(keysRequired, assertThrows(RelizaException.class,
				() -> f.clearReleaseSbomHashJustification(head, List.of(), "r")).getMessage());
		for (String key : List.of("k".repeat(2049), "pkg:deb/a\u0001@1")) {
			assertEquals(keyRefused, assertThrows(RelizaException.class,
					() -> f.setReleaseSbomHashJustification(head, List.of(key), code, null, null)).getMessage());
			assertEquals(keyRefused, assertThrows(RelizaException.class,
					() -> f.clearReleaseSbomHashJustification(head, List.of(key), "r")).getMessage());
		}
		assertEquals("reason is longer than 2048 characters", assertThrows(RelizaException.class,
				() -> f.setReleaseSbomHashJustification(head, List.of("pkg:deb/a@1"), code, null, LONG)).getMessage());
		assertEquals("reason is longer than 2048 characters", assertThrows(RelizaException.class,
				() -> f.clearReleaseSbomHashJustification(head, List.of("pkg:deb/a@1"), LONG)).getMessage());
		assertEquals("reason is longer than 2048 characters", assertThrows(RelizaException.class,
				() -> f.bulkSetReleaseSbomHashJustificationBySelector(head, sel, code, null, LONG)).getMessage());
		assertEquals("justified unknown: the code OTHER needs a text", assertThrows(RelizaException.class,
				() -> f.bulkSetReleaseSbomHashJustificationBySelector(head, sel, JustifiedUnknownReason.OTHER, null,
						null)).getMessage());
		List<String> overCap = new ArrayList<>();
		for (int i = 0; i <= CAP; i++) overCap.add("pkg:deb/debian/p" + i + "@1");
		RelizaException e = assertThrows(RelizaException.class,
				() -> f.setReleaseSbomHashJustification(head, overCap, code, null, null));
		assertEquals(ReleaseSbomJustificationService.explicitOverCap(CAP + 1), e.getMessage());
		verifyNoInteractions(service);
		// a 2048-character key and reason pass the bound: the head lookup (the next step) is reached
		assertThrows(NullPointerException.class, () -> f.setReleaseSbomHashJustification(head,
				List.of("k".repeat(2048)), code, null, "r".repeat(2048)));
	}
}
