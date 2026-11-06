/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.model.JustifiedUnknownInput;
import io.reliza.model.JustifiedUnknownReason;
import io.reliza.model.LevelOfSupport;
import io.reliza.model.SbomComponent;
import io.reliza.model.SupportAttestationRequest;
import io.reliza.model.SupportBulkOutcome;
import io.reliza.model.SupportBulkResult;
import io.reliza.model.SupportBulkSelector;
import io.reliza.model.SupportBulkSelectorForm;
import io.reliza.model.SupportData;
import io.reliza.model.SupportSource;
import io.reliza.repositories.SbomComponentSupportRepository;
import io.reliza.service.SbomComponentService.SupportBulkRun;

/** FDAW-14 row 8 (REQ-5, REQ-6, REQ-13): the selector sweeps, their skip rules and their one receipt. */
class SbomComponentBulkSelectorServiceIntegrationTest extends ReleaseSbomJustificationTestBase {

	@Autowired SbomComponentSupportRepository supportRepository;

	private static final SupportBulkSelector DEBIAN =
			SupportBulkSelector.validated(SupportBulkSelectorForm.NAMESPACE, "deb", "debian");

	private SupportData support(UUID component) {
		return supportRepository.findBySbomComponentUuid(component).orElseThrow().getSupportData();
	}

	private static SupportAttestationRequest level(ZonedDateTime at) {
		return new SupportAttestationRequest(LevelOfSupport.ACTIVELY_MAINTAINED, null, null, null, at, null, null, null,
				null, Set.of(), null);
	}

	private static SupportAttestationRequest unknownOnly(ZonedDateTime at) {
		return new SupportAttestationRequest(null, null, null, null, at, null, null, null, null, Set.of(), null,
				new JustifiedUnknownInput(JustifiedUnknownReason.NO_COMMITTED_END_OF_SUPPORT, null), false);
	}

	private Map<String, Object> act(UUID uuid) {
		return jdbc.queryForMap("SELECT kind, release, release_sbom, matched_count, applied_count, list_hash, "
				+ "selector::text AS sel, record_data::text AS rec FROM rearm.support_bulk_acts WHERE uuid = ?", uuid);
	}

	private static String hashOf(List<SbomComponent> rows) {
		return SupportBulkActService.listHash(rows.stream().map(c -> c.getUuid().toString()).toList());
	}

	@Test
	void anAssessmentSweepWritesTheUnattestedRowsUnderOneAct() throws Exception {
		List<SbomComponent> rows = components(org, null, List.of("pkg:deb/debian/a@1", "pkg:deb/debian/b@1",
				"pkg:deb/debian/c@1", "pkg:deb/debian/attested@1"), false);
		components(org, null, List.of("pkg:deb/debian/root@1"), true);
		UUID attester = admin().getUuid();
		sbomComponentService.setSbomComponentSupport(rows.get(3).getUuid(), level(null), attester);

		SupportBulkRun run = sbomComponentService.bulkSetSbomComponentSupportBySelector(org.getUuid(), DEBIAN, null, null,
				level(null), attester);
		assertEquals(4, run.matchedCount());
		assertEquals(3, run.results().stream().filter(r -> SupportBulkOutcome.APPLIED == r.outcome()).count());
		assertEquals(1, run.results().stream().filter(r -> SupportBulkOutcome.SKIPPED_ATTESTED == r.outcome()).count());
		Map<String, Object> a = act(run.bulkAct());
		assertEquals("LEVEL_OF_SUPPORT", a.get("kind"));
		assertNull(a.get("release"));
		assertEquals(4, a.get("matched_count"));
		assertEquals(3, a.get("applied_count"));
		assertEquals(hashOf(rows), a.get("list_hash"));
		assertTrue(((String) a.get("sel")).contains("\"namespace\": \"debian\""), (String) a.get("sel"));
		assertTrue(((String) a.get("rec")).contains("ACTIVELY_MAINTAINED"), (String) a.get("rec"));
		for (SbomComponent c : rows.subList(0, 3)) assertEquals(run.bulkAct(), support(c.getUuid()).bulkAct());
		assertNull(support(rows.get(3).getUuid()).bulkAct());
		assertEquals(3, count("sbom_component_support_audit", "batch_id = ?", run.bulkAct()));
	}

	@Test
	void anEndOfSupportSweepKeepsEachLevelClaimAndSkipsRowsHoldingAnEndOfSupport() throws Exception {
		List<SbomComponent> rows = components(org, null, List.of("pkg:deb/debian/l1@1", "pkg:deb/debian/l2@1",
				"pkg:deb/debian/dated@1", "pkg:deb/debian/unknown@1", "pkg:deb/debian/unassessed@1",
				"pkg:deb/debian/fresh@1"), false);
		UUID a = admin().getUuid();
		UUID b = admin().getUuid();
		ZonedDateTime t0a = ZonedDateTime.now(ZoneOffset.UTC).minusDays(20).truncatedTo(ChronoUnit.SECONDS);
		ZonedDateTime t0b = ZonedDateTime.now(ZoneOffset.UTC).minusDays(15).truncatedTo(ChronoUnit.SECONDS);
		ZonedDateTime t1 = ZonedDateTime.now(ZoneOffset.UTC).minusDays(1).truncatedTo(ChronoUnit.SECONDS);
		sbomComponentService.setSbomComponentSupport(rows.get(0).getUuid(), level(t0a), a);
		sbomComponentService.setSbomComponentSupport(rows.get(1).getUuid(), level(t0b), a);
		sbomComponentService.setSbomComponentSupport(rows.get(2).getUuid(), new SupportAttestationRequest(
				LevelOfSupport.ACTIVELY_MAINTAINED, null, null, null, null, null, LocalDate.now().plusYears(1), null, null,
				Set.of(), null), a);
		sbomComponentService.setSbomComponentSupport(rows.get(3).getUuid(), unknownOnly(null), a);
		// unassessed: a row with milestones only (no level), so the sweep has a level-less row to date
		sbomComponentService.setSbomComponentSupport(rows.get(4).getUuid(), new SupportAttestationRequest(null, null,
				null, null, null, null, null, LocalDate.now().plusYears(5), null, Set.of(), null), a);
		SupportData l1 = support(rows.get(0).getUuid());
		SupportData l2 = support(rows.get(1).getUuid());

		SupportBulkRun run = sbomComponentService.bulkSetSbomComponentSupportBySelector(org.getUuid(), DEBIAN, null, null,
				unknownOnly(t1), b);
		Map<UUID, SupportBulkOutcome> outcomes = new HashMap<>();
		for (SupportBulkResult r : run.results()) outcomes.put(r.sbomComponentUuid(), r.outcome());
		assertEquals(SupportBulkOutcome.APPLIED, outcomes.get(rows.get(0).getUuid()));
		assertEquals(SupportBulkOutcome.APPLIED, outcomes.get(rows.get(1).getUuid()));
		assertEquals(SupportBulkOutcome.SKIPPED_HAS_END_OF_SUPPORT, outcomes.get(rows.get(2).getUuid()));
		assertEquals(SupportBulkOutcome.SKIPPED_HAS_END_OF_SUPPORT, outcomes.get(rows.get(3).getUuid()));
		assertEquals(SupportBulkOutcome.APPLIED, outcomes.get(rows.get(4).getUuid()));
		assertEquals(SupportBulkOutcome.APPLIED, outcomes.get(rows.get(5).getUuid()));
		for (int i = 0; i < 2; i++) {
			SupportData before = 0 == i ? l1 : l2;
			SupportData after = support(rows.get(i).getUuid());
			assertEquals(before.assessedAt(), after.assessedAt(), "the level's assessedAt is byte-equal");
			assertEquals(before.levelOfSupport(), after.levelOfSupport());
			assertEquals(before.party(), after.party());
			assertEquals(before.justification(), after.justification());
			assertEquals(a, after.assertedBy(), "the level's asserter is kept");
			assertEquals(SupportSource.MANUAL, after.assessmentSource());
			assertEquals(b, after.endOfSupportUnknown().assertedBy());
			assertEquals(t1.toInstant(), Instant.parse(after.endOfSupportUnknown().assertedAt()));
			assertEquals(run.bulkAct(), after.endOfSupportUnknown().bulkAct());
		}
		for (int i = 4; i < 6; i++) {
			SupportData after = support(rows.get(i).getUuid());
			assertEquals(b, after.assertedBy());
			assertEquals(t1.toInstant(), Instant.parse(after.assessedAt()), "no level to keep");
		}
		List<Map<String, Object>> audits = jdbc.queryForList("SELECT asserted_by, asserted_date FROM "
				+ "rearm.sbom_component_support_audit WHERE batch_id = ?", run.bulkAct());
		assertEquals(4, audits.size());
		for (Map<String, Object> au : audits) {
			assertEquals(b, au.get("asserted_by"));
			assertNotEquals(t1.toInstant(), ((Timestamp) au.get("asserted_date")).toInstant(),
					"the audit row carries the system clock");
		}
		Map<String, Object> act = act(run.bulkAct());
		assertEquals("END_OF_SUPPORT_UNKNOWN", act.get("kind"));
		assertEquals(6, act.get("matched_count"));
		assertEquals(4, act.get("applied_count"));
		assertTrue(((String) act.get("rec")).contains(t1.toInstant().toString()), (String) act.get("rec"));

		SupportBulkRun again = sbomComponentService.bulkSetSbomComponentSupportBySelector(org.getUuid(), DEBIAN, null,
				null, unknownOnly(t1.plusHours(1)), b);
		assertEquals(0, act(again.bulkAct()).get("applied_count"));
		assertEquals(t1.toInstant(), Instant.parse(support(rows.get(0).getUuid()).endOfSupportUnknown().assertedAt()));
	}

	@Test
	void theReleaseScopeReachesOnlyTheReleasesComponentsAndIsRecorded() throws Exception {
		World w = componentRelease(null);
		List<SbomComponent> inRelease = components(org, w.release(), List.of("pkg:deb/debian/r1@1",
				"pkg:deb/debian/r2@1"), false);
		components(org, null, List.of("pkg:deb/debian/elsewhere@1"), false);
		SupportBulkRun run = sbomComponentService.bulkSetSbomComponentSupportBySelector(org.getUuid(), DEBIAN, w.release(),
				sbomComponentService.findReleaseComponentUuids(w.release()), level(null), admin().getUuid());
		assertEquals(Set.of(inRelease.get(0).getUuid(), inRelease.get(1).getUuid()),
				Set.copyOf(run.results().stream().map(SupportBulkResult::sbomComponentUuid).toList()));
		Map<String, Object> act = act(run.bulkAct());
		assertEquals(w.release(), act.get("release"));
		assertTrue(((String) act.get("sel")).contains("\"releaseUuid\": \"" + w.release() + "\""), (String) act.get("sel"));
	}

	@Test
	void theExplicitFormRecordsOneActPerCallAndKeepsTheEchoedBatchIdOnTheAuditOnly() throws Exception {
		List<SbomComponent> rows = components(org, null, List.of("pkg:deb/debian/e1@1", "pkg:deb/debian/e2@1"), false);
		UUID echoed = UUID.randomUUID();
		SupportAttestationRequest withUnknown = new SupportAttestationRequest(LevelOfSupport.ACTIVELY_MAINTAINED, null,
				null, null, null, null, null, null, null, Set.of(), null,
				new JustifiedUnknownInput(JustifiedUnknownReason.COMMUNITY_PROJECT, null), false);
		SupportBulkRun first = sbomComponentService.bulkSetSbomComponentSupportRun(org.getUuid(),
				List.of(rows.get(0).getUuid()), withUnknown, admin().getUuid(), echoed);
		SupportBulkRun second = sbomComponentService.bulkSetSbomComponentSupportRun(org.getUuid(),
				List.of(rows.get(1).getUuid()), withUnknown, admin().getUuid(), echoed);
		assertNotEquals(first.bulkAct(), second.bulkAct(), "one act per call");
		for (SupportBulkRun run : List.of(first, second)) {
			Map<String, Object> act = act(run.bulkAct());
			assertTrue(((String) act.get("sel")).contains("\"form\": \"EXPLICIT\""), (String) act.get("sel"));
			assertTrue(((String) act.get("rec")).contains(echoed.toString()), (String) act.get("rec"));
		}
		SupportData s = support(rows.get(0).getUuid());
		assertEquals(first.bulkAct(), s.bulkAct());
		assertEquals(first.bulkAct(), s.endOfSupportUnknown().bulkAct());
		assertNotEquals(echoed, s.bulkAct(), "the echoed id never reaches a stored regulatory row");
		assertEquals(2, count("sbom_component_support_audit", "batch_id = ?", echoed));
	}
}
