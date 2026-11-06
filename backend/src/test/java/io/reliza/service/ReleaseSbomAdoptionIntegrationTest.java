/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.model.ReleaseSbomRef;
import io.reliza.model.ReleaseSbomWriter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * FDAW-7 row 4: a TEA aggregate stored before the identity existed is adopted with its serial and version
 * (D1 "adoption, not re-minting"), and the three refusals mint a fresh identity instead.
 */
class ReleaseSbomAdoptionIntegrationTest extends ReleaseSbomIntegrationTestBase {

	static final String SHA = "4d".repeat(32);

	final List<LogEvent> logs = Collections.synchronizedList(new ArrayList<>());
	AbstractAppender appender;

	@BeforeEach
	void capture() {
		appender = new AbstractAppender("fdaw7-adopt-" + UUID.randomUUID(), null, null, true, null) {
			@Override
			public void append(LogEvent event) {
				logs.add(event.toImmutable());
			}
		};
		appender.start();
		((org.apache.logging.log4j.core.Logger) LogManager.getLogger(ReleaseSbomService.class)).addAppender(appender);
	}

	@AfterEach
	void release() {
		((org.apache.logging.log4j.core.Logger) LogManager.getLogger(ReleaseSbomService.class)).removeAppender(appender);
		appender.stop();
	}

	long logged(Level level, String text) {
		return logs.stream().filter(e -> level == e.getLevel() && e.getMessage().getFormattedMessage().contains(text))
				.count();
	}

	static List<TagRecord> teaTags(Removable removable, String digest) {
		List<TagRecord> tags = new ArrayList<>(List.of(new TagRecord(CommonVariables.TEA_AGGREGATE_TAG_KEY, "true",
				removable), new TagRecord(CommonVariables.TEA_PROFILE_NAME_TAG_KEY, PROFILE, removable)));
		if (null != digest) tags.add(new TagRecord(CommonVariables.TEA_CONTENT_DIGEST_TAG_KEY, digest, removable));
		return tags;
	}

	@Test
	void aTaggedRowIsAdoptedWithItsSerialAndVersionAndTheNextChangeBumpsIt() throws Exception {
		World w = componentRelease("pkg:generic/pump@1.0.0");
		JsonNode d = doc("pkg:generic/pump@1.0.0");
		UUID serial = UUID.randomUUID();
		UUID row = legacyAggregate(w.release(), teaTags(Removable.NO, TeaCollectionAssembler.contentDigest(d)), serial,
				3, SHA);
		clearInvocations(rebomService);

		var adopted = ensure(w.release(), d, ReleaseSbomWriter.pkg(null));

		assertEquals(new ReleaseSbomRef(serial, 3, SHA, row), adopted.pin());
		assertEquals(false, adopted.created(), "equal content: the adopted version is reused");
		Map<String, Object> head = headRows(w.release()).get(0);
		assertEquals(serial, head.get("uuid"));
		assertEquals(3, head.get("latest_version"));
		assertEquals(row, head.get("artifact"));
		List<Map<String, Object>> rows = versionRows(serial);
		assertEquals(1, rows.size());
		assertEquals(3, rows.get(0).get("version"));
		assertEquals(SHA, rows.get(0).get("sha256"));
		assertEquals(TeaCollectionAssembler.contentDigest(d), rows.get(0).get("content_digest"));
		verify(rebomService, never()).uploadSbom(any(), any(), any(), any(), any());
		verify(rebomService, never()).findBomByVersion(any(), any(), any());
		assertEquals(1, logged(Level.INFO, "adopted serial " + serial + " version 3 from artifact " + row));

		var bumped = ensure(w.release(), doc("pkg:generic/pump@1.0.0", "pkg:generic/seal@2"),
				ReleaseSbomWriter.pkg(null));
		assertEquals(serial, bumped.pin().serial());
		assertEquals(4, bumped.pin().version());
		assertEquals(row, bumped.pin().artifact(), "the same row, revised");
	}

	@Test
	void userTagsAreNeverAdopted() throws Exception {
		World w = componentRelease("pkg:generic/pump@1.0.0");
		JsonNode d = doc("pkg:generic/pump@1.0.0");
		UUID serial = UUID.randomUUID();
		legacyAggregate(w.release(), teaTags(Removable.YES, TeaCollectionAssembler.contentDigest(d)), serial, 3, SHA);

		ReleaseSbomRef pin = ensure(w.release(), d, ReleaseSbomWriter.pkg(null)).pin();
		assertNotEquals(serial, pin.serial(), "a fresh serial");
		assertEquals(1, pin.version());
	}

	@Test
	void aRowWithoutAStoredSerialIsSkippedWithOneWarn() throws Exception {
		World w = componentRelease("pkg:generic/pump@1.0.0");
		JsonNode d = doc("pkg:generic/pump@1.0.0");
		UUID row = legacyAggregate(w.release(), teaTags(Removable.NO, TeaCollectionAssembler.contentDigest(d)), null, 3,
				SHA);

		ReleaseSbomRef pin = ensure(w.release(), d, ReleaseSbomWriter.pkg(null)).pin();
		assertEquals(1, pin.version(), "a fresh identity");
		assertNotEquals(row, pin.artifact());
		assertEquals(1, logged(Level.WARN, "artifact " + row + " has no readable serial or version; a fresh identity"));
	}

	@Test
	void aRowWithANonIntegerStoredVersionIsSkippedWithOneWarn() throws Exception {
		World w = componentRelease("pkg:generic/pump@1.0.0");
		JsonNode d = doc("pkg:generic/pump@1.0.0");
		UUID serial = UUID.randomUUID();
		UUID row = legacyAggregate(w.release(), teaTags(Removable.NO, TeaCollectionAssembler.contentDigest(d)), serial,
				"not-a-number", SHA);

		ReleaseSbomRef pin = ensure(w.release(), d, ReleaseSbomWriter.pkg(null)).pin();
		assertEquals(1, pin.version(), "a fresh identity");
		assertNotEquals(serial, pin.serial());
		assertEquals(1, logged(Level.WARN, "artifact " + row + " has no readable serial or version; a fresh identity"));
	}

	@Test
	void aSerialAnotherReleaseHoldsIsNotAdoptedAndOneErrorNamesBoth() throws Exception {
		World holder = componentRelease("pkg:generic/pump@1.0.0");
		ReleaseSbomRef held = ensure(holder.release(), doc("pkg:generic/pump@1.0.0"), ReleaseSbomWriter.pkg(null)).pin();
		List<Map<String, Object>> holderRows = versionRows(held.serial());
		List<Map<String, Object>> holderHead = headRows(holder.release());

		World w = componentRelease("pkg:generic/pump@1.0.0");
		JsonNode d = doc("pkg:generic/pump@1.0.0");
		UUID row = legacyAggregate(w.release(), teaTags(Removable.NO, TeaCollectionAssembler.contentDigest(d)),
				held.serial(), 1, SHA);

		ReleaseSbomRef pin = ensure(w.release(), d, ReleaseSbomWriter.pkg(null)).pin();
		assertNotEquals(held.serial(), pin.serial(), "a fresh identity is minted");
		assertEquals(1, logged(Level.ERROR, "artifact " + row + " names serial " + held.serial() + ", which release "
				+ holder.release() + " holds"));
		assertTrue(logs.stream().anyMatch(e -> Level.ERROR == e.getLevel()
				&& e.getMessage().getFormattedMessage().contains(w.release().toString())), "names this release too");
		assertEquals(holderRows, versionRows(held.serial()), "the other release's rows are unchanged");
		assertEquals(holderHead, headRows(holder.release()));
	}

	@Test
	void withoutTheDigestTagTheDigestIsRecomputedFromTheStoredBytes() throws Exception {
		World w = componentRelease("pkg:generic/pump@1.0.0");
		JsonNode d = doc("pkg:generic/pump@1.0.0");
		UUID serial = UUID.randomUUID();
		JsonNode storedBytes = d.deepCopy();
		((ObjectNode) storedBytes).put("serialNumber", "urn:uuid:" + serial)
				.put("version", 2);
		uploadedVersions.put(serial + ":2", storedBytes);
		legacyAggregate(w.release(), teaTags(Removable.NO, null), serial, 2, SHA);

		var adopted = ensure(w.release(), d, ReleaseSbomWriter.pkg(null));
		assertEquals(serial, adopted.pin().serial());
		assertEquals(2, adopted.pin().version());
		assertEquals(false, adopted.created(), "the recomputed digest equals the document's");
		verify(rebomService).findBomByVersion(serial, org.getUuid(), 2);
	}
}
