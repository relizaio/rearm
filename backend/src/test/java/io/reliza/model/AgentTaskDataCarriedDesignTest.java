/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.AgentTaskData.CarriedDesign;
import io.reliza.model.AgentTaskData.CarriedRole;
import io.reliza.model.AgentTaskData.CarriedRound;

/**
 * T-18 (task TPF-161, built by TPF-162): the carried design's record in JSONB. Rows written before it read as no
 * record; the record round-trips; {@code carriedRoundOf} finds a round by document; an unknown field is ignored.
 */
public class AgentTaskDataCarriedDesignTest {

	static CarriedDesign record() {
		CarriedRound arch = new CarriedRound(RearmSpecificationType.ARCHITECTURE, UUID.randomUUID(), 2,
				"design/X-1/architecture-2.md");
		CarriedRound tm = new CarriedRound(RearmSpecificationType.THREAT_MODEL, UUID.randomUUID(), 1,
				"design/X-1/threat_model-1.md");
		ZonedDateTime signed = ZonedDateTime.parse("2026-11-06T10:15:30.123Z");
		CarriedRole architect = new CarriedRole("architect", UUID.randomUUID(), signed, UUID.randomUUID(), null, false);
		CarriedRole security = new CarriedRole("security_reviewer", UUID.randomUUID(), signed.plusMinutes(5),
				UUID.randomUUID(), null, true);
		return new CarriedDesign(UUID.randomUUID(), "X-1", List.of(arch, tm), List.of(architect, security),
				signed.plusMinutes(9), AgentActor.ofSession(UUID.randomUUID()));
	}

	@Test
	public void aTaskWrittenBeforeTheRecordReadsNone() {
		AgentTaskData td = Utils.OM.convertValue(Map.of("title", "old", "status", "QUEUED"), AgentTaskData.class);
		assertNull(td.getCarriedDesign());
		assertFalse(td.isDesignCarried());
		assertNull(td.carriedRoundOf(RearmSpecificationType.ARCHITECTURE));
	}

	@Test
	public void theRecordRoundTrips() {
		CarriedDesign cd = record();
		AgentTaskData td = new AgentTaskData();
		td.setCarriedDesign(cd);
		AgentTaskData back = Utils.OM.convertValue(Utils.OM.convertValue(td, Map.class), AgentTaskData.class);
		CarriedDesign read = back.getCarriedDesign();
		assertTrue(back.isDesignCarried());
		assertEquals(cd.parentTask(), read.parentTask());
		assertEquals(cd.parentKey(), read.parentKey());
		assertEquals(cd.rounds(), read.rounds());
		assertEquals(cd.by(), read.by());
		assertTrue(cd.at().isEqual(read.at()));
		assertEquals(cd.roles().size(), read.roles().size());
		for (int i = 0; i < cd.roles().size(); i++) {
			CarriedRole w = cd.roles().get(i);
			CarriedRole r = read.roles().get(i);
			assertEquals(w.role(), r.role());
			assertEquals(w.roleUuid(), r.roleUuid());
			assertEquals(w.session(), r.session());
			assertEquals(w.reviewedBy(), r.reviewedBy());
			assertEquals(w.decidedOver(), r.decidedOver());
			assertTrue(w.signedOffAt().isEqual(r.signedOffAt()));
		}
		assertEquals(cd.rounds().get(1), back.carriedRoundOf(RearmSpecificationType.THREAT_MODEL));
		assertNull(back.carriedRoundOf(RearmSpecificationType.DETAILED_DESIGN));
		assertEquals("security_reviewer", read.roleFor(null, "SECURITY_REVIEWER").role(), "by name, ignoring case");
		assertEquals("architect", read.roleFor(cd.roles().get(0).roleUuid(), "renamed").role(), "by uuid first");
		assertNull(read.roleFor(UUID.randomUUID(), "architect"), "a uuid on both sides decides");
	}

	@SuppressWarnings("unchecked")
	@Test
	public void anUnknownFieldIsIgnored() {
		AgentTaskData td = new AgentTaskData();
		td.setCarriedDesign(record());
		Map<String, Object> raw = new HashMap<>(Utils.OM.convertValue(td, Map.class));
		Map<String, Object> carried = new HashMap<>((Map<String, Object>) raw.get("carriedDesign"));
		carried.put("aFieldFromLater", "x");
		Map<String, Object> role = new HashMap<>(((List<Map<String, Object>>) carried.get("roles")).get(0));
		role.put("anotherFieldFromLater", 1);
		carried.put("roles", List.of(role));
		raw.put("carriedDesign", carried);
		AgentTaskData back = Utils.OM.convertValue(raw, AgentTaskData.class);
		assertEquals("architect", back.getCarriedDesign().roles().get(0).role());
		assertEquals(2, back.getCarriedDesign().rounds().size());
	}

	@Test
	public void absentListsReadAsEmpty() {
		CarriedDesign cd = new CarriedDesign(UUID.randomUUID(), "X-1", null, null, ZonedDateTime.now(), null);
		assertTrue(cd.rounds().isEmpty());
		assertTrue(cd.roles().isEmpty());
	}
}
