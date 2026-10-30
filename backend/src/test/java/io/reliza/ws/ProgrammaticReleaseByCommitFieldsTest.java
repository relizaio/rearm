/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.ReleaseData;
import io.reliza.service.SharedReleaseService;

/**
 * TPF-98 (mutation round): the three fields of a {@code getReleasesByCommitProgrammatic} match are the
 * release's own. Case 10 of {@link ProgrammaticReleaseByCommitTest} orders the matches and reads the
 * uuids and versions; this one reads the creation time against the stored release, so a match that
 * carried the time of the read instead of the release's own fails.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ProgrammaticReleaseByCommitFieldsTest extends ProgrammaticReleaseWriteOrderTestBase {

	@Autowired private SharedReleaseService sharedReleaseService;

	@SuppressWarnings("unchecked")
	@Test
	public void aMatchCarriesTheReleasesOwnUuidVersionAndCreationTime() throws RelizaException, InterruptedException {
		World w = world();
		Tenant t = w.a();
		ReleaseData stored = sharedReleaseService.getReleaseData(t.release()).orElseThrow();
		// the read happens later than the release was made: a time taken at the read would differ
		Thread.sleep(1_100);

		List<Map<String, Object>> rels = (List<Map<String, Object>>) result(
				asKey(t.freeformKey(), row(16).op(), m("c", t.component().getUuid(), "commit", t.commit())), row(16).field());
		assertEquals(1, rels.size(), rels.toString());
		Map<String, Object> match = rels.get(0);
		assertEquals(stored.getUuid().toString(), match.get("uuid"));
		assertEquals(stored.getVersion(), match.get("version"));
		assertEquals(stored.getCreatedDate().toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS), ZonedDateTime.parse(String.valueOf(match.get("createdDate"))).toInstant(),
				"the creation time of the release, not the time of the read");
	}
}
