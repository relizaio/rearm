/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static io.reliza.service.FanOutWalkSlice.START;
import static io.reliza.service.FanOutWalkSlice.endOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.SbomComponent;
import io.reliza.repositories.FanOutWalkRepository;
import io.reliza.ws.App;

/**
 * TPF-137 mutation round, against the real database: the two slice statements at their edges (the cursor
 * excluded, the slice end included, ascending order, one row per canonical, the room as the row limit), in
 * both the changed-component form and the full form, and the stamp check answering each canonical once.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class FanOutWalkEdgesIntegrationTest extends FanOutCandidateTestBase {

	@Autowired private FanOutWalkRepository fanOutWalkRepository;

	/** A canonical uuid inside slice 0, the n-th of the sorted ones. */
	private static UUID inSliceZero(long n) {
		return new UUID(0x0000000000001000L + n * 0x100L, 0x8000000000000001L);
	}

	private void hold(UUID org, UUID canonical, SbomComponent... comps) {
		for (SbomComponent sc : comps) holdComponent(org, canonical, sc);
	}

	// ---------------- the stamp check ----------------

	@Test
	public void theStampCheckAnswersACanonicalOnceHoweverManyMapRowsItHas() {
		UUID org = newOrg();
		SbomComponent sc = component(org, uniquePurl("once"), 0);
		Mapped m = mapBom(org, null, null, sc);
		mapRow(org, saveArtifactWithLastScanned(org, null).getUuid(), m.canonical(), null);
		mapRow(org, saveArtifactWithLastScanned(org, null).getUuid(), m.canonical(), null);

		List<UUID> found = artifactCanonicalMapRepository.findCanonicalsStampedBefore(org, List.of(m.canonical()),
				ZonedDateTime.now().plusHours(1));

		assertEquals(List.of(m.canonical()), found);
	}

	// ---------------- the changed-component statement ----------------

	@Test
	public void theChangedStatementIncludesTheSliceEndExcludesTheCursorAndStopsAtTheRoom() {
		UUID org = newOrg();
		SbomComponent changed = component(org, uniquePurl("changed"), 0);
		UUID end = endOf(0);
		List<UUID> five = List.of(inSliceZero(1), inSliceZero(2), inSliceZero(3), inSliceZero(4), inSliceZero(5));
		for (UUID c : five) hold(org, c, changed);
		hold(org, end, changed);
		UUID[] array = new UUID[] { changed.getUuid() };

		assertEquals(List.of(five.get(0), five.get(1), five.get(2), five.get(3), five.get(4), end),
				fanOutWalkRepository.findChangedCanonicalsInSlice(org, START, end, array, 50), "the slice end is in");
		assertEquals(List.of(five.get(2), five.get(3), five.get(4), end),
				fanOutWalkRepository.findChangedCanonicalsInSlice(org, five.get(1), end, array, 50), "the cursor is out");
		assertEquals(List.of(five.get(0), five.get(1), five.get(2), five.get(3)),
				fanOutWalkRepository.findChangedCanonicalsInSlice(org, START, end, array, 4), "the room is the row limit");
		assertEquals(List.of(five.get(0), five.get(1), five.get(2), five.get(3), five.get(4)),
				fanOutWalkRepository.findChangedCanonicalsInSlice(org, START, end, array, 5));
	}

	// ---------------- the full statement ----------------

	@Test
	public void theFullStatementIsAscendingDistinctExcludesTheCursorIncludesTheSliceEndAndStopsAtTheRoom() {
		UUID org = newOrg();
		SbomComponent one = component(org, uniquePurl("one"), 0);
		SbomComponent two = component(org, uniquePurl("two"), 0);
		UUID end = endOf(0);
		UUID a = inSliceZero(1);
		UUID b = inSliceZero(2);
		UUID c = inSliceZero(3);
		// inserted out of order, and the first with two component rows
		hold(org, end, one);
		hold(org, c, one);
		hold(org, a, one, two);
		hold(org, b, one);

		assertEquals(List.of(a, b, c, end), fanOutWalkRepository.findCanonicalsInSlice(org, START, end, 50),
				"ascending, one row for the canonical with two component rows, the slice end in");
		assertEquals(List.of(c, end), fanOutWalkRepository.findCanonicalsInSlice(org, b, end, 50), "the cursor is out");
		assertEquals(List.of(a, b, c), fanOutWalkRepository.findCanonicalsInSlice(org, START, end, 3), "the room is the row limit");
		assertEquals(List.of(a, b), fanOutWalkRepository.findCanonicalsInSlice(org, START, end, 2));
	}
}
