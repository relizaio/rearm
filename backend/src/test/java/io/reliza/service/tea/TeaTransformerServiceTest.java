/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.tea.TeaCle;
import io.reliza.model.tea.TeaCleEvent;
import io.reliza.model.tea.TeaCleEventType;
import io.reliza.service.SharedReleaseService;

/**
 * Pins CLE document construction: which lifecycle transitions become which event
 * types, how date-derived endOfSupport/endOfLife events are synthesised from a
 * declared support window, how same-minute events merge, deterministic id
 * ordering, and -- since PR #474 -- that the SERIALISED document carries
 * ISO-8601 UTC timestamps rather than epoch decimals.
 *
 * <p>The supportId/definitions assertions this class used to make were removed
 * with the support-policy catalog itself; see
 * ai-plans/fda-readiness-1-plan.md P1.
 *
 * <p>Exercises {@link TeaTransformerService} directly with mocked collaborators
 * rather than through a real release, since {@code buildReleaseCleCandidates}
 * only reads {@code ReleaseData} fields.
 */
class TeaTransformerServiceTest {

	private TeaTransformerService service;
	private SharedReleaseService sharedReleaseService;

	@BeforeEach
	void wire() {
		service = new TeaTransformerService();
		sharedReleaseService = mock(SharedReleaseService.class);
		ReflectionTestUtils.setField(service, "sharedReleaseService", sharedReleaseService);
	}

	private static ReleaseData releaseEndingSupport(UUID org, ZonedDateTime date) {
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "org", org);
		ReflectionTestUtils.setField(rd, "version", "1.0.0");
		rd.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.LIFECYCLE, ReleaseUpdateAction.CHANGED,
				ReleaseLifecycle.GENERAL_AVAILABILITY.name(), ReleaseLifecycle.END_OF_SUPPORT.name(),
				null, date, mock(WhoUpdated.class)));
		return rd;
	}

	private static TeaCleEvent onlyEndOfSupportEvent(TeaCle cle) {
		List<TeaCleEvent> matches = cle.getEvents().stream()
				.filter(e -> e.getType() == TeaCleEventType.END_OF_SUPPORT).toList();
		assertEquals(1, matches.size(), "expected exactly one endOfSupport event; got " + cle.getEvents());
		return matches.get(0);
	}

	// ---- half B: date-derived endOfSupport/endOfLife (board t20260830-164643-29472) ----

	private static ReleaseData releaseWithDeclaredWindow(UUID org, LocalDate eos, LocalDate eol,
			ZonedDateTime supportWindowAssessedAt) {
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "org", org);
		ReflectionTestUtils.setField(rd, "version", "1.0.0");
		ReflectionTestUtils.setField(rd, "createdDate", ZonedDateTime.now().minusDays(30));
		if (null != eos) rd.setEos(eos);
		if (null != eol) rd.setEol(eol);
		if (null != supportWindowAssessedAt) {
			rd.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.SUPPORT_WINDOW, ReleaseUpdateAction.ADDED,
					null, ReleaseData.supportWindowValueString(eos, eol), null, supportWindowAssessedAt,
					mock(WhoUpdated.class)));
		}
		return rd;
	}

	@Test
	void aDeclaredEosWithNoLifecycleTransitionStillProducesAnEndOfSupportEvent() {
		UUID org = UUID.randomUUID();
		LocalDate eos = LocalDate.parse("2033-06-15");

		TeaCle cle = service.transformReleaseToCle(
				releaseWithDeclaredWindow(org, eos, null, ZonedDateTime.now()));

		assertEquals(eos, onlyEndOfSupportEvent(cle).getEffective().toLocalDate(),
				"a device declaring eos must publish the event without waiting for a manual lifecycle flip");
	}

	@Test
	void aDeclaredEolWithNoLifecycleTransitionStillProducesAnEndOfLifeEvent() {
		UUID org = UUID.randomUUID();
		LocalDate eol = LocalDate.parse("2035-12-31");

		TeaCle cle = service.transformReleaseToCle(
				releaseWithDeclaredWindow(org, null, eol, ZonedDateTime.now()));

		List<TeaCleEvent> endOfLife = cle.getEvents().stream()
				.filter(e -> e.getType() == TeaCleEventType.END_OF_LIFE).toList();
		assertEquals(1, endOfLife.size());
		assertEquals(eol, endOfLife.get(0).getEffective().toLocalDate());
	}

	/**
	 * The design decision from review: a recorded LIFECYCLE transition is
	 * authoritative. A release that has BOTH a real transition into
	 * END_OF_SUPPORT and a (possibly different) declared eos must publish only
	 * the transition's event, never a second one from the date.
	 */
	@Test
	void aRecordedLifecycleTransitionWinsOverADeclaredDate() {
		UUID org = UUID.randomUUID();
		ZonedDateTime transitionDate = ZonedDateTime.parse("2030-01-01T00:00:00Z");

		ReleaseData rd = releaseEndingSupport(org, transitionDate);
		rd.setEos(LocalDate.parse("2099-01-01")); // deliberately disagrees with the transition date

		TeaCle cle = service.transformReleaseToCle(rd);

		assertEquals(1, cle.getEvents().stream().filter(e -> e.getType() == TeaCleEventType.END_OF_SUPPORT).count(),
				"must never publish two endOfSupport events for one release");
		assertEquals(LocalDate.parse("2030-01-01"), onlyEndOfSupportEvent(cle).getEffective().toLocalDate(),
				"the recorded transition, not the declared date, is authoritative");
	}

	@Test
	void dateDerivedEventsPublishedComesFromWhenTheWindowWasAsserted() {
		UUID org = UUID.randomUUID();
		ZonedDateTime assertedAt = ZonedDateTime.parse("2026-08-31T12:00:00Z");

		TeaCle cle = service.transformReleaseToCle(
				releaseWithDeclaredWindow(org, LocalDate.parse("2033-06-15"), null, assertedAt));

		TeaCleEvent ev = onlyEndOfSupportEvent(cle);
		assertEquals(assertedAt.toOffsetDateTime().toLocalDate(), ev.getPublished().toLocalDate(),
				"published is when we learned/asserted the window, not the effective (eos) date itself");
		assertEquals(LocalDate.parse("2033-06-15"), ev.getEffective().toLocalDate());
	}

	/**
	 * A release whose window predates the SUPPORT_WINDOW provenance (created before
	 * board #29472 shipped, or built by hand in this test with no such event at all)
	 * must not crash -- fall back to the release's own createdDate for "published".
	 */
	@Test
	void dateDerivedEventFallsBackToCreatedDateWhenThereIsNoSupportWindowProvenance() {
		UUID org = UUID.randomUUID();

		TeaCle cle = service.transformReleaseToCle(
				releaseWithDeclaredWindow(org, LocalDate.parse("2033-06-15"), null, null));

		assertEquals(1, cle.getEvents().stream().filter(e -> e.getType() == TeaCleEventType.END_OF_SUPPORT).count());
	}

	/**
	 * {@code RelizaDataParent.createdDate} defaults to null too -- a release read back
	 * with neither SUPPORT_WINDOW provenance NOR a populated createdDate must not NPE.
	 * Falls back to {@code effective} itself as the last resort rather than crash.
	 */
	@Test
	void dateDerivedEventFallsBackToEffectiveWhenNeitherProvenanceNorCreatedDateExist() {
		UUID org = UUID.randomUUID();

		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "org", org);
		ReflectionTestUtils.setField(rd, "version", "1.0.0");
		// createdDate deliberately left null -- no reflection set here.
		rd.setEos(LocalDate.parse("2033-06-15"));

		TeaCle cle = service.transformReleaseToCle(rd);

		assertEquals(LocalDate.parse("2033-06-15"), onlyEndOfSupportEvent(cle).getPublished().toLocalDate());
	}

	/**
	 * The component/product-level aggregate runs candidates through
	 * mergeNonReleasedByMinute, which used to unconditionally snap published down to
	 * effective's minute -- correct when the two were always equal (every
	 * LIFECYCLE-transition-derived event before this feature), silently wrong now that
	 * a date-derived event deliberately has published != effective.
	 */
	@Test
	void componentLevelMergePreservesPublishedForADateDerivedEvent() {
		UUID org = UUID.randomUUID();
		ZonedDateTime assertedAt = ZonedDateTime.parse("2026-08-31T12:00:00Z");

		ComponentData cd = new ComponentData();
		ReflectionTestUtils.setField(cd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(cd, "org", org);
		when(sharedReleaseService.listReleaseDatasOfComponent(any(UUID.class), any(Integer.class), any(Integer.class)))
				.thenReturn(List.of(releaseWithDeclaredWindow(org, LocalDate.parse("2033-06-15"), null, assertedAt)));

		TeaCle cle = service.transformComponentToCle(cd);

		TeaCleEvent ev = cle.getEvents().stream()
				.filter(e -> e.getType() == TeaCleEventType.END_OF_SUPPORT).findFirst().orElseThrow();
		assertEquals(assertedAt.toOffsetDateTime().toLocalDate(), ev.getPublished().toLocalDate(),
				"published must survive the component-level merge, not collapse to effective's far-future minute");
		assertEquals(LocalDate.parse("2033-06-15"), ev.getEffective().toLocalDate());
	}

	/**
	 * The realistic case the earlier fix's "keep the first sighting's published"
	 * left broken: a device family where two releases declare the SAME eos, asserted
	 * months apart. CLE defines published as when the event was FIRST published, so
	 * the merge must keep the EARLIER of the two -- not whichever release
	 * listReleaseDatasOfComponent happened to return first, which would make the same
	 * data produce a different document depending on DB return order.
	 */
	@Test
	void componentLevelMergeOfTheSameDeclaredEosKeepsTheEarliestPublished() {
		UUID org = UUID.randomUUID();
		LocalDate sharedEos = LocalDate.parse("2030-01-01");
		ZonedDateTime earlierAssertion = ZonedDateTime.parse("2026-03-01T00:00:00Z");
		ZonedDateTime laterAssertion = ZonedDateTime.parse("2026-08-01T00:00:00Z");
		ReleaseData v1 = releaseWithDeclaredWindow(org, sharedEos, null, earlierAssertion);
		ReleaseData v2 = releaseWithDeclaredWindow(org, sharedEos, null, laterAssertion);

		ComponentData cd = new ComponentData();
		ReflectionTestUtils.setField(cd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(cd, "org", org);

		// DB order must not matter -- try both orderings.
		for (List<ReleaseData> order : List.of(List.of(v1, v2), List.of(v2, v1))) {
			when(sharedReleaseService.listReleaseDatasOfComponent(any(UUID.class), any(Integer.class), any(Integer.class)))
					.thenReturn(order);

			TeaCle cle = service.transformComponentToCle(cd);

			List<TeaCleEvent> endOfSupport = cle.getEvents().stream()
					.filter(e -> e.getType() == TeaCleEventType.END_OF_SUPPORT).toList();
			assertEquals(1, endOfSupport.size(), "same eos, same supportId -> one merged event, not two");
			assertEquals(earlierAssertion.toOffsetDateTime().toLocalDate(), endOfSupport.get(0).getPublished().toLocalDate(),
					"must keep the EARLIER published regardless of which release the DB returned first");
		}
	}

	/**
	 * A release with eos == eol produces two date-derived events with
	 * byte-identical effective (both land on date.atStartOfDay(UTC)).
	 * renumberAndSortNewestFirst must not leave their order (and so their
	 * document-local id) to List.sort's stability / whatever order they happened
	 * to be built in -- the type tiebreak makes it deterministic.
	 */
	@Test
	void equalEosAndEolProduceDeterministicallyOrderedIds() {
		UUID org = UUID.randomUUID();
		LocalDate sameDate = LocalDate.parse("2030-01-01");

		TeaCle cle = service.transformReleaseToCle(
				releaseWithDeclaredWindow(org, sameDate, sameDate, ZonedDateTime.now()));

		List<TeaCleEvent> events = cle.getEvents();
		assertEquals(2, events.size());
		assertEquals(TeaCleEventType.END_OF_SUPPORT, events.get(0).getType(),
				"tied on effective -- type is the deterministic tiebreak");
		assertEquals(TeaCleEventType.END_OF_LIFE, events.get(1).getType());
	}

	/**
	 * The CLE spec requires {@code effective} and {@code published} to be ISO-8601
	 * strings in UTC ending in Z. {@code wrapAsCleDocument} serialises through
	 * {@code Utils.OM}, which enables WRITE_DATES_AS_TIMESTAMPS (and
	 * WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS), and {@code TeaCleEvent} carried only
	 * Spring's {@code @DateTimeFormat}, which Jackson ignores -- so both fields
	 * emitted as bare numbers and every exported document was invalid.
	 *
	 * <p>No test called {@code wrapAsCleDocument} before this one, which is why the
	 * defect shipped. Assert on the SERIALISED JSON, never the POJO: the POJO was
	 * always correct.
	 */
	@Test
	void wrapAsCleDocumentEmitsIso8601StringTimestamps() {
		TeaCleEvent ev = new TeaCleEvent(0, TeaCleEventType.END_OF_SUPPORT,
				OffsetDateTime.parse("2027-06-30T00:00:00Z"),
				OffsetDateTime.parse("2026-09-02T10:15:00Z"));
		var doc = service.wrapAsCleDocument(new TeaCle(List.of(ev)), null);
		var emitted = doc.get("events").get(0);

		assertTrue(emitted.get("effective").isString(),
				"CLE effective must serialise as an ISO-8601 STRING, got: " + emitted.get("effective"));
		assertTrue(emitted.get("published").isString(),
				"CLE published must serialise as an ISO-8601 STRING, got: " + emitted.get("published"));
		assertEquals("2027-06-30T00:00:00Z", emitted.get("effective").asString());
		assertEquals("2026-09-02T10:15:00Z", emitted.get("published").asString());

		// updatedAt is emitted by wrapAsCleDocument itself, not by the event mapper,
		// and used to render in the server's local offset.
		String updatedAt = doc.get("updatedAt").asString();
		assertTrue(updatedAt.endsWith("Z"),
				"CLE updatedAt must be UTC with a trailing Z, got: " + updatedAt);
		// RFC-3339 requires seconds. OffsetDateTime.toString() drops ":00" when
		// second-of-minute and nanos are both zero, which happened 1 export in 60.
		assertTrue(updatedAt.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"),
				"CLE updatedAt must carry seconds (RFC-3339), got: " + updatedAt);
	}

	/**
	 * @JsonFormat(shape = STRING) renders whatever offset the value carries, so a
	 * non-UTC input would serialise as ...-04:00 and violate the spec's "ending in
	 * Z". The normalisation lives in TeaTransformerService.utc(), applied at every
	 * CLE event construction site. Without it this test fails while the one above
	 * still passes, which is why both exist.
	 */
	@Test
	void cleEventTimestampsAreNormalisedToUtcRegardlessOfInputOffset() {
		ReleaseData rd = mock(ReleaseData.class);
		when(rd.getVersion()).thenReturn("1.0.0");
		when(rd.getOrg()).thenReturn(UUID.randomUUID());
		when(rd.getLifecycle()).thenReturn(ReleaseLifecycle.GENERAL_AVAILABILITY);
		when(rd.getUpdateEvents()).thenReturn(List.of());
		// created in a NON-UTC zone -- the emitted document must still say Z
		when(rd.getCreatedDate()).thenReturn(
				ZonedDateTime.parse("2026-03-01T09:00:00-05:00[America/New_York]"));

		var doc = service.wrapAsCleDocument(service.transformReleaseToCle(rd), null);
		var effective = doc.get("events").get(0).get("effective").asString();

		assertTrue(effective.endsWith("Z"),
				"CLE effective must be normalised to UTC, got: " + effective);
		assertEquals("2026-03-01T14:00:00Z", effective);
	}
}
