/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.reliza.model.BranchData.BranchType;
import io.reliza.model.ReleaseData;

/**
 * Which release a deliverable resolves to when several releases carry it:
 * plan-preferred branch, then BASE branch, then newest.
 */
public class DeliverableReleasePickerTest {

	private static final UUID BASE = UUID.randomUUID();
	private static final UUID FEATURE = UUID.randomUUID();
	private static final UUID OTHER = UUID.randomUUID();
	private static final Function<UUID, Optional<BranchType>> TYPES = b -> Optional.ofNullable(
			Map.of(BASE, BranchType.BASE, FEATURE, BranchType.FEATURE, OTHER, BranchType.FEATURE).get(b));

	private static ReleaseData release(UUID branch, int daysAgo) throws Exception {
		ReleaseData rd = new ReleaseData();
		set(rd, "uuid", UUID.randomUUID());
		set(rd, "branch", branch);
		set(rd, "createdDate", ZonedDateTime.now().minusDays(daysAgo));
		return rd;
	}

	private static void set(Object target, String field, Object value) throws Exception {
		Class<?> c = target.getClass();
		while (c != null) {
			try {
				var f = c.getDeclaredField(field);
				f.setAccessible(true);
				f.set(target, value);
				return;
			} catch (NoSuchFieldException e) {
				c = c.getSuperclass();
			}
		}
		throw new NoSuchFieldException(field);
	}

	@Test
	void emptyAndSingleCandidate() throws Exception {
		assertTrue(DeliverableReleasePicker.pick(List.of(), Set.of(), TYPES).isEmpty());
		ReleaseData only = release(OTHER, 1);
		assertSame(only, DeliverableReleasePicker.pick(List.of(only), Set.of(BASE), TYPES).get());
	}

	@Test
	void planPreferredBranchWinsOverBaseAndNewer() throws Exception {
		ReleaseData onBase = release(BASE, 0);
		ReleaseData onFeature = release(FEATURE, 5);
		ReleaseData onOther = release(OTHER, 0);
		var picked = DeliverableReleasePicker.pick(List.of(onOther, onBase, onFeature), Set.of(FEATURE), TYPES);
		assertSame(onFeature, picked.get());
	}

	@Test
	void newestAmongPreferredBranches() throws Exception {
		ReleaseData older = release(FEATURE, 9);
		ReleaseData newer = release(FEATURE, 2);
		var picked = DeliverableReleasePicker.pick(List.of(older, newer), Set.of(FEATURE), TYPES);
		assertSame(newer, picked.get());
	}

	@Test
	void baseBranchWinsWhenNothingPreferredMatches() throws Exception {
		ReleaseData onBase = release(BASE, 10);
		ReleaseData onOther = release(OTHER, 0);
		var picked = DeliverableReleasePicker.pick(List.of(onOther, onBase), Set.of(FEATURE), TYPES);
		assertSame(onBase, picked.get());
		assertSame(onBase, DeliverableReleasePicker.pick(List.of(onOther, onBase), Set.of(), TYPES).get());
	}

	@Test
	void newestWhenNoPreferredAndNoBase() throws Exception {
		ReleaseData older = release(OTHER, 3);
		ReleaseData newer = release(FEATURE, 1);
		var picked = DeliverableReleasePicker.pick(List.of(older, newer), Set.of(), TYPES);
		assertSame(newer, picked.get());
	}

	@Test
	void unknownBranchTypeIsNotTreatedAsBase() throws Exception {
		UUID unknown = UUID.randomUUID();
		ReleaseData onUnknown = release(unknown, 0);
		ReleaseData onBase = release(BASE, 20);
		var picked = DeliverableReleasePicker.pick(List.of(onUnknown, onBase), Set.of(), TYPES);
		assertEquals(onBase.getUuid(), picked.get().getUuid());
	}
}
