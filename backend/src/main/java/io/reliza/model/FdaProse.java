/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import io.reliza.exceptions.RelizaException;

/**
 * The single enforcement point for manufacturer-authored FDA prose, wherever it is stored.
 *
 * <p>Prose lives in two places by design: org-level on {@link OrganizationData.Settings} as
 * the default, and per-release on {@link ReleaseData} as the override. Those are different
 * records with different write paths, and a second copy of "trim it, bound it, empty means
 * clear" is a second place for the two to drift -- which for a bound that guards a row read
 * on the authz path every request is not a cosmetic difference.
 *
 * <p>The bound is defined HERE and re-exported by {@code Settings.FDA_PROSE_MAX_LENGTH},
 * rather than the other way round, so a release-scoped writer does not have to reach into
 * an organisation type to find out how long a release field may be.
 */
public final class FdaProse {

	private FdaProse() {}

	/**
	 * Per FIELD, not per record.
	 *
	 * <p>Deliberately not generous. Org settings are read on the authz path on every single
	 * request, so this text is loaded far more often than it is displayed; four fields of
	 * unbounded prose on that row is a cost paid by every caller to serve a document
	 * generated occasionally.
	 */
	public static final int MAX_LENGTH = 8000;

	/**
	 * Normalise one patch value: null means the field was OMITTED, and the caller must leave
	 * the stored value alone rather than passing null through to a setter.
	 *
	 * @param value the supplied value, or null if the field was omitted
	 * @param fieldName named in the error, so an operator sees which box was too long
	 * @return the trimmed text, or null when the value is blank -- a supplied-but-empty
	 *         field is a deliberate CLEAR, and null is how "not set" is stored
	 * @throws RelizaException if the value exceeds {@link #MAX_LENGTH}
	 */
	public static String normalize(String value, String fieldName) throws RelizaException {
		if (null == value) return null;
		// Raw length BEFORE strip(). Raw length is an upper bound on stripped length, so this
		// cannot reject anything valid, and it avoids allocating one more full copy of a
		// hostile body just to refuse it.
		if (value.length() > MAX_LENGTH) {
			throw new RelizaException(fieldName + " exceeds the " + MAX_LENGTH + " character limit");
		}
		String trimmed = value.strip();
		if (trimmed.length() > MAX_LENGTH) {
			throw new RelizaException(fieldName + " exceeds the " + MAX_LENGTH + " character limit");
		}
		return trimmed.isEmpty() ? null : trimmed;
	}
}
