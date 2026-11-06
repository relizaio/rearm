/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * The SBOM element a justified unknown stands in for (FDAW-1 D5, FDAW-14 3.2), and which
 * {@link JustifiedUnknownReason} codes ReARM accepts for it. The CLI keeps one list for both
 * properties; which code ReARM writes where is enforced here, at every write (FDAW-13 3.1).
 *
 * <p>{@link #END_OF_SUPPORT} is per component ({@code SupportData.unknowns}); {@link #HASH} is per
 * release SBOM identity head ({@code release_sbom_justifications}), never per component.
 */
public enum JustifiedUnknownField {
	END_OF_SUPPORT(EnumSet.of(JustifiedUnknownReason.NO_COMMITTED_END_OF_SUPPORT,
			JustifiedUnknownReason.COMMUNITY_PROJECT, JustifiedUnknownReason.SUPPLIER_PUBLISHES_NO_DATE,
			JustifiedUnknownReason.OTHER)),
	HASH(EnumSet.of(JustifiedUnknownReason.DISTRIBUTION_PUBLISHES_NO_HASH,
			JustifiedUnknownReason.VENDORED_SOURCE_NO_HASH, JustifiedUnknownReason.OTHER));

	private final Set<JustifiedUnknownReason> accepted;

	JustifiedUnknownField(Set<JustifiedUnknownReason> accepted) {
		this.accepted = accepted;
	}

	/** Whether {@code code} may justify this field. */
	public boolean accepts(JustifiedUnknownReason code) {
		return null != code && accepted.contains(code);
	}

	/** The accepted codes in declaration order, for the refusal text. */
	public Set<JustifiedUnknownReason> accepted() {
		return EnumSet.copyOf(accepted);
	}

	/**
	 * Refuses a code this field does not accept and {@code OTHER} without a text (3.11). The text is
	 * expected already bounded ({@link SupportTextBounds}).
	 */
	public void validate(JustifiedUnknownReason code, String boundedText) {
		if (!accepts(code)) {
			throw new IllegalArgumentException("justified unknown: the code " + code + " is not accepted for "
					+ name() + " (accepted: " + String.join(", ", accepted().stream().map(Enum::name).toList()) + ")");
		}
		if (JustifiedUnknownReason.OTHER == code && (null == boundedText || boundedText.isBlank())) {
			throw new IllegalArgumentException("justified unknown: the code OTHER needs a text");
		}
	}
}
