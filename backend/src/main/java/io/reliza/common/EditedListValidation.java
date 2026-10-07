/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import io.reliza.exceptions.RelizaException;

/**
 * Write-time checks for a stored list that a form sends back in full on every save -- dependency
 * patterns, ignoreViolation patterns, federated matcher globs.
 *
 * <p>Only entries that are new, not in the stored list being replaced, are checked; an entry
 * counts as kept only as many times as the stored list holds it, and any extra copy is new. A
 * legacy entry that predates a check (over a length cap, or no longer compiling) must not block
 * saving an unrelated change, and the form gives no way to leave a list out of a save. The size
 * cap works the same way: a list at or under it follows the normal rules; a list over it --
 * which only a legacy list can be -- may keep or drop entries it already has, but takes no new
 * ones, and so does not grow, until it is back under the cap. Blank entries are not checked: the
 * lists that hold them have always accepted them.
 */
public final class EditedListValidation {

	/** For a list with no size cap. */
	public static final int NO_CAP = Integer.MAX_VALUE;

	private EditedListValidation() {}

	/** The check a new entry must pass; {@code index} is its position in the submitted list. */
	@FunctionalInterface
	public interface EntryCheck {
		void check(String entry, int index) throws RelizaException;
	}

	/**
	 * @param entries the list as submitted; null leaves the stored list alone and checks nothing
	 * @param stored the list being replaced; null when there is none yet
	 * @param label how messages name the list, e.g. {@code "Matcher subjects"}
	 * @param maxEntries the size cap, or {@link #NO_CAP}
	 */
	public static void validateNewEntries(List<String> entries, List<String> stored, String label, int maxEntries,
			EntryCheck check) throws RelizaException {
		if (null == entries) return;
		List<String> before = null == stored ? List.of() : stored;
		// A multiset, not a set: an entry is kept only as many times as the stored list holds it,
		// so one legacy entry that predates a check cannot be multiplied past it by an edit.
		Map<String, Integer> keptLeft = new HashMap<>();
		for (String e : before) keptLeft.merge(e, 1, Integer::sum);
		List<Integer> added = new ArrayList<>();
		for (int i = 0; i < entries.size(); i++) {
			String entry = entries.get(i);
			Integer left = keptLeft.get(entry);
			if (null != left && left > 0) {
				keptLeft.put(entry, left - 1);
			} else {
				added.add(i);
			}
		}
		// Over the cap with nothing added is only ever a legacy list kept or trimmed: it cannot
		// have grown, since every entry is one the stored list already had.
		if (entries.size() > maxEntries && !added.isEmpty()) {
			throw new RelizaException(label + " holds " + entries.size() + " entries; at most " + maxEntries
					+ " are allowed" + (before.size() > maxEntries
							? " -- it is already over that, so until it is under it a save may only keep or drop"
									+ " entries it has"
							: ""));
		}
		for (int i : added) {
			String entry = entries.get(i);
			if (StringUtils.isBlank(entry)) continue;
			check.check(entry, i);
		}
	}
}
