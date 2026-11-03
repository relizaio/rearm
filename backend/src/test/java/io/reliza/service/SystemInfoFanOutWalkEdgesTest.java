/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
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
 * TPF-137 mutation round: the edges of the persisted fan-out walk state that SystemInfoFanOutWalkTest leaves
 * open. No org has no state; a value whose cutoff or cursor does not parse reads as absent; the stamp creates
 * the system_info row when it is absent; the stamp and the clear write the one row only. The row is shared by
 * every test of the fork's database, so the test restores its data.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class SystemInfoFanOutWalkEdgesTest {

	@Autowired private SystemInfoService systemInfoService;
	@Autowired private JdbcTemplate jdbcTemplate;

	private String originalData;

	@BeforeEach
	void remember() {
		systemInfoService.getDefaultOrg(); // creates the row when absent
		originalData = jdbcTemplate.queryForObject("select data::text from rearm.system_info where id = 1", String.class);
	}

	@AfterEach
	void restore() {
		jdbcTemplate.update("delete from rearm.system_info where id = 2");
		systemInfoService.getDefaultOrg(); // the row again, if a test removed it
		jdbcTemplate.update("update rearm.system_info set data = cast(? as jsonb) where id = 1", originalData);
	}

	private static ZonedDateTime cutoff() {
		return ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(5).truncatedTo(ChronoUnit.MICROS);
	}

	/** Writes the value under fanOutWalk, creating that map first: jsonb_set does nothing under an absent or null parent. */
	private void storeRaw(UUID org, String jsonValue) {
		jdbcTemplate.update("update rearm.system_info set data = jsonb_set(data || jsonb_build_object('fanOutWalk',"
				+ " case when jsonb_typeof(data->'fanOutWalk') = 'object' then data->'fanOutWalk' else '{}'::jsonb end),"
				+ " cast(? as text[]), cast(? as jsonb)) where id = 1",
				"{fanOutWalk," + org + "}", jsonValue);
		assertNotNull(jdbcTemplate.queryForObject("select data->'fanOutWalk'->>? from rearm.system_info where id = 1",
				String.class, org.toString()), "the raw state is stored, so the read below has something to reject");
	}

	@Test
	public void noOrgHasNoWalkState() {
		assertNull(systemInfoService.getFanOutWalk(null));
	}

	@Test
	public void aStateWhoseCutoffDoesNotParseReadsAsAbsent() {
		UUID org = UUID.randomUUID();
		storeRaw(org, "{\"cutoff\":\"not an instant\",\"cursor\":\"" + UUID.randomUUID() + "\"}");

		assertNull(systemInfoService.getFanOutWalk(org));
	}

	@Test
	public void aStateWhoseCursorIsNoUuidReadsAsAbsent() {
		UUID org = UUID.randomUUID();
		storeRaw(org, "{\"cutoff\":\"2026-10-29T00:00:00Z\",\"cursor\":\"not a uuid\"}");

		assertNull(systemInfoService.getFanOutWalk(org));
	}

	@Test
	public void aStateThatHoldsBothValuesReadsBackWithTheCutoffAndTheCursorInTheirOwnFields() {
		UUID org = UUID.randomUUID();
		UUID cursor = UUID.randomUUID();
		ZonedDateTime at = cutoff();

		systemInfoService.stampFanOutWalk(org, at, cursor);

		FanOutWalkState state = systemInfoService.getFanOutWalk(org);
		assertNotNull(state);
		assertEquals(at.toInstant().toString(), state.cutoff());
		assertEquals(cursor.toString(), state.cursor());
	}

	@Test
	public void theStampCreatesTheRowWhenItIsAbsentAndStillStamps() {
		UUID org = UUID.randomUUID();
		UUID cursor = UUID.randomUUID();
		jdbcTemplate.update("delete from rearm.system_info where id = 1");

		systemInfoService.stampFanOutWalk(org, cutoff(), cursor);

		assertEquals(1, jdbcTemplate.queryForObject("select count(*) from rearm.system_info where id = 1", Integer.class));
		assertEquals(cursor.toString(), systemInfoService.getFanOutWalk(org).cursor());
	}

	@Test
	public void theStampAndTheClearWriteTheOneRowOnly() {
		UUID org = UUID.randomUUID();
		String other = "{\"keep\": {\"cutoff\": \"2026-10-29T00:00:00Z\"}, \"fanOutWalk\": {\"" + org + "\": {\"cutoff\": \"x\", \"cursor\": \"y\"}}}";
		jdbcTemplate.update("insert into rearm.system_info (id, data) values (2, cast(? as jsonb))", other);
		String before = jdbcTemplate.queryForObject("select data::text from rearm.system_info where id = 2", String.class);

		systemInfoService.stampFanOutWalk(org, cutoff(), UUID.randomUUID());
		assertEquals(before, jdbcTemplate.queryForObject("select data::text from rearm.system_info where id = 2", String.class),
				"the stamp left the other row alone");

		systemInfoService.clearFanOutWalk(org);
		assertEquals(before, jdbcTemplate.queryForObject("select data::text from rearm.system_info where id = 2", String.class),
				"so did the clear");
		assertNull(systemInfoService.getFanOutWalk(org));
	}

	@Test
	public void theClearRemovesTheOneOrgsStateAndLeavesTheOthers() {
		UUID org1 = UUID.randomUUID();
		UUID org2 = UUID.randomUUID();
		UUID cursor2 = UUID.randomUUID();
		systemInfoService.stampFanOutWalk(org1, cutoff(), UUID.randomUUID());
		systemInfoService.stampFanOutWalk(org2, cutoff(), cursor2);

		systemInfoService.clearFanOutWalk(org1);

		assertNull(systemInfoService.getFanOutWalk(org1));
		assertEquals(cursor2.toString(), systemInfoService.getFanOutWalk(org2).cursor());
	}
}
