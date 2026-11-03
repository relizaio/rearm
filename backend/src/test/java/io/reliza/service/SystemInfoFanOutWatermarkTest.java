/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
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

import io.reliza.repositories.SystemInfoRepository;
import io.reliza.ws.App;

/**
 * TPF-114 architecture 4.16: the per-org fan-out drained-at watermark is persisted in place in
 * SystemInfoData.fanOutDrainedAt, survives the two locked whole-record setters, is per org,
 * stored as org uuid text to an ISO instant with Z, and a malformed value reads as absent.
 * The system_info row is shared by every test of the fork's database, so the test restores the
 * fanOutDrainedAt value and the two scheduler fields it touched.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class SystemInfoFanOutWatermarkTest {

	@Autowired private SystemInfoService systemInfoService;
	@Autowired private SystemInfoRepository systemInfoRepository;
	@Autowired private JdbcTemplate jdbcTemplate;

	private String originalMap;
	private boolean hadMap;
	private ZonedDateTime originalLastDtrackSync;
	private ZonedDateTime originalSweepWatermark;

	@BeforeEach
	void remember() {
		systemInfoService.getDefaultOrg(); // creates the row when absent
		hadMap = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
				"select data ? 'fanOutDrainedAt' from rearm.system_info where id = 1", Boolean.class));
		originalMap = jdbcTemplate.queryForObject(
				"select (data->'fanOutDrainedAt')::text from rearm.system_info where id = 1", String.class);
		originalLastDtrackSync = systemInfoService.getLastDtrackSync();
		originalSweepWatermark = systemInfoService.getUnmappedBomSweepWatermark();
	}

	@AfterEach
	void restore() {
		systemInfoService.setLastDtrackSync(originalLastDtrackSync);
		systemInfoService.setUnmappedBomSweepWatermark(originalSweepWatermark);
		if (hadMap) {
			jdbcTemplate.update("update rearm.system_info set data = jsonb_set(data, '{fanOutDrainedAt}',"
					+ " cast(? as jsonb)) where id = 1", originalMap);
		} else {
			jdbcTemplate.update("update rearm.system_info set data = data - 'fanOutDrainedAt' where id = 1");
		}
	}

	private static ZonedDateTime micros(ZonedDateTime t) {
		return t.withZoneSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

	private Set<String> storedKeys() {
		Map<String, String> m = systemInfoService.getSystemInfoData().getFanOutDrainedAt();
		return m == null ? Set.of() : new HashSet<>(m.keySet());
	}

	@Test
	public void theWatermarkIsPerOrgInPlaceAndSurvivesTheLockedSetters() {
		UUID org1 = UUID.randomUUID();
		UUID org2 = UUID.randomUUID();
		ZonedDateTime w1 = micros(ZonedDateTime.now().minusMinutes(10)).plusNanos(123_000);
		ZonedDateTime w2 = micros(ZonedDateTime.now().minusMinutes(5));
		Set<String> before = storedKeys();

		systemInfoService.stampFanOutDrainedAt(org1, w1);
		assertEquals(w1.toInstant(), systemInfoService.getFanOutDrainedAt(org1).toInstant(), "to the microsecond");
		assertNull(systemInfoService.getFanOutDrainedAt(org2));

		systemInfoService.setLastDtrackSync(ZonedDateTime.now());
		systemInfoService.setUnmappedBomSweepWatermark(ZonedDateTime.now());
		assertEquals(w1.toInstant(), systemInfoService.getFanOutDrainedAt(org1).toInstant(),
				"the whole-record setters carry the map");

		systemInfoService.stampFanOutDrainedAt(org2, w2);
		assertEquals(w1.toInstant(), systemInfoService.getFanOutDrainedAt(org1).toInstant());
		assertEquals(w2.toInstant(), systemInfoService.getFanOutDrainedAt(org2).toInstant());

		Set<String> added = storedKeys();
		added.removeAll(before);
		assertEquals(Set.of(org1.toString(), org2.toString()), added, "exactly the two keys were added");
		Object raw = systemInfoRepository.findSystemInfo().getData().get("fanOutDrainedAt");
		assertTrue(raw instanceof Map, "a map of org uuid text to an instant");
		String stored = (String) ((Map<?, ?>) raw).get(org1.toString());
		assertTrue(stored.endsWith("Z"), stored);
		assertEquals(w1.toInstant().toString(), stored);
	}

	@Test
	public void aMalformedValueReadsAsAbsent() {
		UUID org = UUID.randomUUID();
		systemInfoService.stampFanOutDrainedAt(org, micros(ZonedDateTime.now().minusMinutes(1)));
		jdbcTemplate.update("update rearm.system_info set data = jsonb_set(data, cast(? as text[]),"
				+ " to_jsonb(cast('x' as text))) where id = 1", "{fanOutDrainedAt," + org + "}");

		assertDoesNotThrow(() -> assertNull(systemInfoService.getFanOutDrainedAt(org)));
	}

	/**
	 * The whole-record setters write every SystemInfoData field, an absent map as
	 * "fanOutDrainedAt": null. The stamp must treat that JSON null as absent and create the
	 * map; a plain coalesce does not see it and would store an array that no replica can read
	 * back (found by this suite, TPF-114).
	 */
	@Test
	public void aJsonNullMapWrittenByAWholeRecordSetterIsReplacedByTheStamp() {
		jdbcTemplate.update("update rearm.system_info set data = jsonb_set(data, '{fanOutDrainedAt}',"
				+ " 'null'::jsonb) where id = 1");
		UUID org = UUID.randomUUID();
		ZonedDateTime w = micros(ZonedDateTime.now().minusMinutes(1));

		systemInfoService.stampFanOutDrainedAt(org, w);

		assertEquals("object", jdbcTemplate.queryForObject(
				"select jsonb_typeof(data->'fanOutDrainedAt') from rearm.system_info where id = 1", String.class));
		assertEquals(w.toInstant(), systemInfoService.getFanOutDrainedAt(org).toInstant());
		assertDoesNotThrow(() -> systemInfoService.getDefaultOrg(), "the row stays readable");
	}
}
