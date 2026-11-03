/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.SystemInfoData.FanOutWalkState;
import io.reliza.ws.App;

/**
 * TPF-137 architecture 6.2 case 12, the shape of SystemInfoFanOutWatermarkTest: the per-org walk
 * state in SystemInfoData.fanOutWalk is stamped and cleared in place, per org, read back to the
 * microsecond, survives the two locked whole-record setters, and a corrupted value reads as absent
 * without breaking the row. The system_info row is shared by every test of the fork's database, so
 * the keys this test adds are removed and the two scheduler fields restored.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class SystemInfoFanOutWalkTest {

	@Autowired private SystemInfoService systemInfoService;
	@Autowired private JdbcTemplate jdbcTemplate;

	private final Set<UUID> orgs = new HashSet<>();
	private ZonedDateTime originalLastDtrackSync;
	private ZonedDateTime originalSweepWatermark;

	@BeforeEach
	void remember() {
		systemInfoService.getDefaultOrg(); // creates the row when absent
		originalLastDtrackSync = systemInfoService.getLastDtrackSync();
		originalSweepWatermark = systemInfoService.getUnmappedBomSweepWatermark();
	}

	@AfterEach
	void restore() {
		systemInfoService.setLastDtrackSync(originalLastDtrackSync);
		systemInfoService.setUnmappedBomSweepWatermark(originalSweepWatermark);
		for (UUID org : orgs) {
			jdbcTemplate.update("update rearm.system_info set data = data #- cast(? as text[]) where id = 1",
					"{fanOutWalk," + org + "}");
		}
	}

	private UUID org() {
		UUID org = UUID.randomUUID();
		orgs.add(org);
		return org;
	}

	@Test
	public void theWalkStateIsPerOrgInPlaceAndSurvivesTheLockedSetters() {
		UUID org1 = org();
		UUID org2 = org();
		ZonedDateTime cutoff = ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS).minusMinutes(3)
				.plusNanos(123_000);
		UUID cursor = UUID.randomUUID();

		systemInfoService.stampFanOutWalk(org1, cutoff, cursor);

		FanOutWalkState read = systemInfoService.getFanOutWalk(org1);
		assertEquals(cutoff.toInstant(), Instant.parse(read.cutoff()), "to the microsecond");
		assertEquals(cursor.toString(), read.cursor());
		assertNull(systemInfoService.getFanOutWalk(org2));

		systemInfoService.setLastDtrackSync(ZonedDateTime.now());
		systemInfoService.setUnmappedBomSweepWatermark(ZonedDateTime.now());
		assertEquals(read, systemInfoService.getFanOutWalk(org1), "the whole-record setters carry the map");

		UUID moved = UUID.randomUUID();
		systemInfoService.stampFanOutWalk(org2, cutoff, moved);
		systemInfoService.stampFanOutWalk(org1, cutoff, moved);
		assertEquals(moved.toString(), systemInfoService.getFanOutWalk(org1).cursor(), "advanced in place");
		assertEquals(moved.toString(), systemInfoService.getFanOutWalk(org2).cursor());

		systemInfoService.clearFanOutWalk(org1);
		assertNull(systemInfoService.getFanOutWalk(org1));
		assertEquals(moved.toString(), systemInfoService.getFanOutWalk(org2).cursor(), "the other org's state stays");
		assertDoesNotThrow(() -> systemInfoService.clearFanOutWalk(org1), "clearing an absent state is a no-op");
	}

	@Test
	public void aCorruptedValueReadsAsAbsentAndTheRowStaysReadable() {
		UUID org = org();
		systemInfoService.stampFanOutWalk(org, ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(1), UUID.randomUUID());
		jdbcTemplate.update("update rearm.system_info set data = jsonb_set(data, cast(? as text[]),"
				+ " to_jsonb(cast('x' as text))) where id = 1", "{fanOutWalk," + org + "}");

		assertDoesNotThrow(() -> assertNull(systemInfoService.getFanOutWalk(org)));
		assertDoesNotThrow(() -> systemInfoService.getDefaultOrg(), "the row stays readable");

		UUID badCursor = org();
		jdbcTemplate.update("update rearm.system_info set data = jsonb_set(data, cast(? as text[]),"
				+ " jsonb_build_object('cutoff', 'not-an-instant', 'cursor', 'not-a-uuid')) where id = 1",
				"{fanOutWalk," + badCursor + "}");
		assertNull(systemInfoService.getFanOutWalk(badCursor));

		UUID cursor = UUID.randomUUID();
		systemInfoService.stampFanOutWalk(org, ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(1), cursor);
		assertEquals(cursor.toString(), systemInfoService.getFanOutWalk(org).cursor(), "a stamp overwrites the corruption");
	}

	@Test
	public void aJsonNullMapWrittenByAWholeRecordSetterIsReplacedByTheStamp() {
		jdbcTemplate.update("update rearm.system_info set data = jsonb_set(data, '{fanOutWalk}', 'null'::jsonb)"
				+ " where id = 1 and (data->'fanOutWalk' is null or jsonb_typeof(data->'fanOutWalk') <> 'object'"
				+ " or data->'fanOutWalk' = '{}'::jsonb)");
		UUID org = org();
		UUID cursor = UUID.randomUUID();

		systemInfoService.stampFanOutWalk(org, ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(1), cursor);

		assertEquals("object", jdbcTemplate.queryForObject(
				"select jsonb_typeof(data->'fanOutWalk') from rearm.system_info where id = 1", String.class));
		assertEquals(cursor.toString(), systemInfoService.getFanOutWalk(org).cursor());
	}
}
