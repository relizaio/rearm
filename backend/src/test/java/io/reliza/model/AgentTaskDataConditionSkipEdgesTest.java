/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.AgentTaskData.ConditionSkip;
import io.reliza.model.AgentTaskData.Reopen;
import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.AgentTaskData.SignOffOutcome;

/**
 * TPF-155 mutation round: the record of routing's pass-overs on the task, on the model alone. An entry with no time is
 * never current, a reopen to the role after the entry ends it and its waiver, a role is the same role by uuid when both
 * sides carry one and by name without case otherwise, an entry with no waived types holds an empty list, and a row
 * written with a null list takes a new entry.
 */
public class AgentTaskDataConditionSkipEdgesTest {

	private static final UUID ROLE = UUID.randomUUID();
	private static final RearmSpecificationType BTR = RearmSpecificationType.BOARD_TEST_REPORT;

	private static ConditionSkip skip(ZonedDateTime at, List<RearmSpecificationType> waives) {
		return new ConditionSkip("mutant", ROLE, "runWhen not met: no mutation tag", at, AgentActor.system("routing"), waives);
	}

	@Test
	public void anEntryWithNoTimeIsNeverCurrent() {
		AgentTaskData td = new AgentTaskData();
		td.addConditionSkip(skip(null, List.of(BTR)));
		assertNull(td.liveConditionSkip(ROLE, "mutant"));
		assertEquals(Set.of(), td.waivedByConditionSkips());
	}

	@Test
	public void aReopenToTheRoleAfterTheEntryEndsItAndItsWaiver() {
		AgentTaskData td = new AgentTaskData();
		ZonedDateTime at = ZonedDateTime.now().minusMinutes(5);
		ConditionSkip entry = skip(at, List.of(BTR));
		td.addConditionSkip(entry);
		assertSame(entry, td.liveConditionSkip(ROLE, "mutant"));
		assertEquals(Set.of(BTR), td.waivedByConditionSkips());
		assertSame(entry, td.conditionSkipWaiving(BTR));

		td.getReopens().add(new Reopen("mutant", ROLE, at.plusMinutes(1), AgentActor.system("seat"), "again"));

		assertNull(td.liveConditionSkip(ROLE, "mutant"));
		assertEquals(Set.of(), td.waivedByConditionSkips(), "an ended entry waives nothing");
		assertNull(td.conditionSkipWaiving(BTR));
	}

	@Test
	public void aReopenBeforeTheEntryOrToAnotherRoleLeavesItCurrent() {
		AgentTaskData td = new AgentTaskData();
		ZonedDateTime at = ZonedDateTime.now().minusMinutes(5);
		td.addConditionSkip(skip(at, List.of(BTR)));
		td.getReopens().add(new Reopen("mutant", ROLE, at.minusMinutes(1), AgentActor.system("seat"), "earlier"));
		td.getReopens().add(new Reopen("coder", UUID.randomUUID(), at.plusMinutes(1), AgentActor.system("seat"), "other role"));
		assertNotNull(td.liveConditionSkip(ROLE, "mutant"));
		assertEquals(Set.of(BTR), td.waivedByConditionSkips());
	}

	@Test
	public void aRoleIsTheSameByUuidWhenBothCarryOneElseByNameWithoutCase() {
		UUID u = UUID.randomUUID();
		assertTrue(AgentTaskData.sameRole(u, "renamed", u, "original"), "the uuid decides when both carry one");
		assertFalse(AgentTaskData.sameRole(UUID.randomUUID(), "mutant", UUID.randomUUID(), "mutant"),
				"two uuids that differ are two roles whatever they are called");
		assertTrue(AgentTaskData.sameRole(null, "Mutant", null, "mutant"));
		assertTrue(AgentTaskData.sameRole(u, "Mutant", null, "mutant"), "one side without a uuid compares the names");
		assertFalse(AgentTaskData.sameRole(null, "mutant", null, "tester"));
		assertFalse(AgentTaskData.sameRole(null, null, null, "mutant"));
	}

	@Test
	public void aReopenIsForTheSignOffOfItsRoleByNameWhenAUuidIsMissing() {
		SignOff coder = new SignOff("coder", null, null, null, null, ZonedDateTime.now(), SignOffOutcome.PASSED, "n", null,
				null, null, List.of(), null, null, null, null, null, null, null);
		Reopen toCoder = new Reopen("Coder", null, ZonedDateTime.now(), AgentActor.system("seat"), "r");
		assertTrue(toCoder.isFor(coder));
		assertFalse(new Reopen("tester", null, ZonedDateTime.now(), AgentActor.system("seat"), "r").isFor(coder));
		UUID u = UUID.randomUUID();
		SignOff withUuid = new SignOff("coder", u, null, null, null, ZonedDateTime.now(), SignOffOutcome.PASSED, "n", null,
				null, null, List.of(), null, null, null, null, null, null, null);
		assertTrue(new Reopen("anything", u, ZonedDateTime.now(), AgentActor.system("seat"), "r").isFor(withUuid));
	}

	@Test
	public void anEntryWithNoWaivedTypesHoldsAnEmptyList() {
		assertEquals(List.of(), skip(ZonedDateTime.now(), null).waives());
	}

	@Test
	public void aRowWrittenWithANullListTakesANewEntry() {
		AgentTaskData td = new AgentTaskData();
		td.setRolesSkippedByCondition(null);
		assertNull(td.liveConditionSkip(ROLE, "mutant"));
		assertEquals(Set.of(), td.waivedByConditionSkips());
		td.addConditionSkip(skip(ZonedDateTime.now(), List.of(BTR)));
		assertNotNull(td.liveConditionSkip(ROLE, "mutant"));
	}

	@Test
	public void aNullTypeWaivesNothingEvenWithAnEntryHoldingTheList() {
		AgentTaskData td = new AgentTaskData();
		td.addConditionSkip(skip(ZonedDateTime.now(), List.of()));
		assertNull(td.conditionSkipWaiving(null));
		assertNull(td.conditionSkipWaiving(BTR));
	}
}
