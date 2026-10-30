/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.RecordComponent;
import java.time.ZonedDateTime;
import java.util.*;

import org.junit.jupiter.api.Test;

import io.reliza.model.AgentTaskData.*;
import io.reliza.model.ReleaseData.ReleaseLifecycle;

/**
 * HopUsageRefresher rewrites a task's signOffs and returns whole, in place and without an audit row
 * (TPF-71 / TPF-105): withUsage must copy every other component, which it once did not (task
 * fda2c9f1, commit a51c4d2c dropped reviewedInputs). The ordered component lists and the all-set
 * fixtures fail when a component is added, so withUsage is updated with the record.
 */
public class AgentTaskHopRecordsTest {

	/** SignOff's components in declaration order (AgentTaskData 183-191); extend together with withUsage and the fixture. */
	static final List<String> SIGN_OFF = List.of("role", "roleUuid", "agent", "session", "assignedAt", "signedOffAt",
			"outcome", "note", "promptVersion", "reviewedBy", "usage", "outputs", "reviewedInputs", "promotions",
			"noChange", "answered", "noCode", "baseHeads");

	/** TaskReturn's components in declaration order (AgentTaskData 462-464). */
	static final List<String> TASK_RETURN = List.of("role", "roleUuid", "agent", "session", "reason", "description",
			"returnedAt", "usage", "outputs");

	@Test
	void signOffWithUsageKeepsEveryOtherComponent() throws Exception {
		assertEquals(SIGN_OFF, names(SignOff.class),
				"SignOff changed shape: extend SIGN_OFF, the fixture below and SignOff.withUsage together");
		ZonedDateTime at = ZonedDateTime.now();
		SignOff full = new SignOff("coder", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), at.minusHours(1), at,
				SignOffOutcome.PASSED, "note", "prompt-1", AgentActor.ofUser(UUID.randomUUID(), "operator"),
				new HopUsage(1, 2, 3, 4, 1, 1, 1, 5L, List.of(UUID.randomUUID()), true, 7L, at),
				List.of(UUID.randomUUID()), List.of(UUID.randomUUID()),
				List.of(new Promotion(UUID.randomUUID(), ReleaseLifecycle.ASSEMBLED, null)),
				Boolean.TRUE, UUID.randomUUID(), Boolean.FALSE,
				Map.of("rearm-saas", new BaseHead(UUID.randomUUID(), "main", "0123abcd", at)));
		assertEveryComponentSet(full);
		HopUsage filled = new HopUsage(10, 20, 30, 40, 2, 2, 2, 50L, List.of(UUID.randomUUID()), false, 7L, at.plusMinutes(1));
		assertOnlyUsageReplaced(full, full.withUsage(filled), filled);
	}

	@Test
	void taskReturnWithUsageKeepsEveryOtherComponent() throws Exception {
		assertEquals(TASK_RETURN, names(TaskReturn.class),
				"TaskReturn changed shape: extend TASK_RETURN, the fixture below and TaskReturn.withUsage together");
		ZonedDateTime at = ZonedDateTime.now();
		TaskReturn full = new TaskReturn("coder", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				TaskReturnReason.OTHER, "description", at, HopUsage.empty(), List.of(UUID.randomUUID()));
		assertEveryComponentSet(full);
		HopUsage filled = new HopUsage(10, 20, 30, 40, 2, 2, 2, 50L, List.of(UUID.randomUUID()), false, null, at);
		assertOnlyUsageReplaced(full, full.withUsage(filled), filled);
	}

	static List<String> names(Class<? extends Record> record) {
		return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
	}

	/** The fixture must set every component, or a component withUsage drops would compare null to null. */
	static void assertEveryComponentSet(Record r) throws ReflectiveOperationException {
		for (RecordComponent c : r.getClass().getRecordComponents()) {
			Object v = c.getAccessor().invoke(r);
			assertNotNull(v, c.getName() + " must be set by the fixture");
			if (v instanceof Collection<?> col) assertFalse(col.isEmpty(), c.getName() + " must be non-empty");
			if (v instanceof Map<?, ?> m) assertFalse(m.isEmpty(), c.getName() + " must be non-empty");
		}
	}

	static void assertOnlyUsageReplaced(Record before, Record after, HopUsage filled) throws ReflectiveOperationException {
		assertSame(before.getClass(), after.getClass());
		for (RecordComponent c : before.getClass().getRecordComponents()) {
			Object was = c.getAccessor().invoke(before);
			Object is = c.getAccessor().invoke(after);
			if ("usage".equals(c.getName())) assertSame(filled, is, "usage is the one component withUsage replaces");
			else assertEquals(was, is, c.getName() + " must survive withUsage");
		}
	}
}
