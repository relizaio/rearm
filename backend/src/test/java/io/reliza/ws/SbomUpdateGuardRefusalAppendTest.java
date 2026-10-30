/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import graphql.GraphQLError;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.SbomUpdateNotPermittedException;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.testsupport.StampAssertions;
import io.reliza.testsupport.StampAssertions.RowSnapshot;

/**
 * TPF-71 / TPF-106, R2: a refused SBOM upload's GUARD note is appended to update_events in place, in
 * the REQUIRES_NEW transaction of SbomUpdateGuard.recordRefusal: no revision, no last_updated_date,
 * no who-updated keys and no audit row, while the refused request itself rolls back.
 */
class SbomUpdateGuardRefusalAppendTest extends SbomUpdateGuardProgrammaticTestBase {

	@Test
	void anSbomRefusalIsAppendedInItsOwnTransaction() throws Exception {
		World w = world();
		var writerB = writer(w.org(), PermissionScope.COMPONENT, w.b().getUuid());
		RowSnapshot before = StampAssertions.snapshot(jdbcTemplate, TableName.RELEASES, w.releaseB());
		List<Map<String, Object>> eventsBefore = StampAssertions.updateEvents(jdbcTemplate, w.releaseB());

		GraphQLError error = onlyError(manualUpload(writerB, w.releaseB(), superseding(w)));

		assertEquals(SbomUpdateNotPermittedException.ERROR_CODE, error.getExtensions().get("code"));
		RowSnapshot after = StampAssertions.snapshot(jdbcTemplate, TableName.RELEASES, w.releaseB());
		List<Map<String, Object>> eventsAfter = StampAssertions.updateEvents(jdbcTemplate, w.releaseB());
		StampAssertions.assertAppendedOnly(before, after, eventsBefore, eventsAfter, 1);
		Map<String, Object> note = eventsAfter.get(eventsAfter.size() - 1);
		assertEquals("GUARD", note.get("rus"));
		assertEquals("CHANGED", note.get("rua"));
		assertEquals(w.b().getUuid().toString(), note.get("objectId"));
		assertEquals("urn:uuid:" + w.serial(), note.get("newValue"));
		assertEquals(SbomUpdateNotPermittedException.messageFor("urn:uuid:" + w.serial()), note.get("message"));
		@SuppressWarnings("unchecked")
		Map<String, Object> wu = (Map<String, Object>) note.get("wu");
		assertEquals(writerB.getUuid().toString(), wu.get("lastUpdatedBy"), "the refusing principal stays on the event");
		assertTrue(!String.valueOf(note.get("message")).contains(w.a().getUuid().toString()), "no holder in the note");
		assertEquals(List.of(w.y()), artifactsOf(w.releaseB()), "the refused request rolled back");
		assertEquals(1, guardEvents(w.releaseB()).size(), "the readback through ReleaseData finds the one note");

		// no dedupe, as before: a second refusal is a second note, appended the same way
		onlyError(manualUpload(writerB, w.releaseB(), superseding(w)));
		RowSnapshot again = StampAssertions.snapshot(jdbcTemplate, TableName.RELEASES, w.releaseB());
		List<Map<String, Object>> eventsAgain = StampAssertions.updateEvents(jdbcTemplate, w.releaseB());
		StampAssertions.assertAppendedOnly(after, again, eventsAfter, eventsAgain, 1);
		assertEquals(2, guardEvents(w.releaseB()).size());
	}
}
