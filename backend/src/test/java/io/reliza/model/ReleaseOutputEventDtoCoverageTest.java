/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.ComponentData.EventScope;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.ReleaseOutputEventDto;

/**
 * The read view of an output event has to carry everything the stored event carries.
 *
 * <p>A persisted field missing from this DTO is worse than a field that was never added: it reads
 * back null, the Actions editor fills in its default, and the next save rewrites the stored policy
 * to that default without anybody touching it. That happened to the four lock fields and to
 * {@code includeSuppressed} -- a LOCK action configured COMPONENT / AGENT / NONE came back as
 * BRANCH / HUMAN / ANY, and saving the form would have made that true.
 *
 * <p>So this checks both halves: every field exists on the DTO, and {@code fromData} actually
 * copies it. A field added to the stored event and forgotten here fails the first check.
 */
public class ReleaseOutputEventDtoCoverageTest {

	/** Scope is supplied by the caller, not the event, so it is the one field with no counterpart. */
	private static final Set<String> NOT_ON_THE_EVENT = Set.of("scope");

	@Test
	public void theDtoCarriesEveryFieldTheStoredEventHas() {
		List<String> missing = new ArrayList<>();
		for (Field f : fieldsOf(ReleaseOutputEvent.class)) {
			if (!hasField(ReleaseOutputEventDto.class, f.getName())) missing.add(f.getName());
		}
		assertTrue(missing.isEmpty(), "fields stored on ReleaseOutputEvent and absent from its "
				+ "GraphQL view -- they will read back null and the editor will overwrite them "
				+ "with defaults on the next save: " + missing);

		for (Field f : fieldsOf(ReleaseOutputEventDto.class)) {
			if (NOT_ON_THE_EVENT.contains(f.getName())) continue;
			assertTrue(hasField(ReleaseOutputEvent.class, f.getName()),
					"the DTO exposes " + f.getName() + ", which nothing stores");
		}
	}

	@Test
	public void fromDataCopiesEveryOneOfThem() throws Exception {
		ReleaseOutputEvent event = ReleaseOutputEvent.builder()
				.uuid(UUID.randomUUID())
				.name("lock-on-unrecognized")
				.type(EventType.LOCK)
				.toReleaseLifecycle(ReleaseLifecycle.REJECTED)
				.integration(UUID.randomUUID())
				.users(Set.of(UUID.randomUUID()))
				.notificationMessage("message")
				.vcs(UUID.randomUUID())
				.schedule("schedule")
				.clientPayload("{}")
				.celClientPayload("release.version")
				.eventType("eventType")
				.includeSuppressed(Boolean.TRUE)
				.snapshotApprovalEntry(UUID.randomUUID())
				.snapshotLifecycle(ReleaseLifecycle.ASSEMBLED)
				.approvedEnvironment("UAT")
				.checkName("rearm/check")
				.lockScope(ComponentLock.Scope.COMPONENT)
				.lockUnlockLevel(ComponentLock.UnlockLevel.AGENT)
				.lockAttestationRequirement(ComponentLock.AttestationRequirement.NONE)
				.lockReason("a distinct reason nobody would default to")
				.build();

		ReleaseOutputEventDto dto = ReleaseOutputEventDto.fromData(event, EventScope.LOCAL);

		for (Field f : fieldsOf(ReleaseOutputEvent.class)) {
			Object stored = read(event, f.getName());
			assertNotNull(stored, "the fixture must set " + f.getName() + " or it proves nothing");
			assertEquals(stored, read(dto, f.getName()),
					f.getName() + " is stored and does not survive the read view");
		}
		assertEquals(EventScope.LOCAL, dto.getScope());
	}

	private static List<Field> fieldsOf(Class<?> type) {
		List<Field> out = new ArrayList<>();
		for (Field f : type.getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
			out.add(f);
		}
		return out;
	}

	private static boolean hasField(Class<?> type, String name) {
		for (Field f : fieldsOf(type)) if (f.getName().equals(name)) return true;
		return false;
	}

	private static Object read(Object target, String name) throws Exception {
		Field f = target.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(target);
	}
}
