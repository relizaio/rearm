/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What a board does with the element checks (gaps §2.A, task 2e0fffa6): which ones block a hand-over,
 * which attributes an element at each level must carry, and which families must be verified.
 *
 * @param blocking checks whose failure refuses the sign-off that hands the document over; empty
 *        means every check only reports
 * @param mandatoryFields level to the attributes an element at that level must carry (parent, traces,
 *        assumes, speculative)
 * @param orphans families that must take part in verification: test (verifies something) and
 *        requirement (verified by something)
 * @param coverage the board's own coverage gates by name, each a check named {@code coverage.<name>}
 *        (elements.md §7.5)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ElementCheckPolicy(List<String> blocking, Map<Integer, List<String>> mandatoryFields,
		List<String> orphans, Map<String, CoverageGate> coverage) implements Serializable {

	private static final long serialVersionUID = 20260925L;

	/**
	 * Tests must verify something. Requirements are not held to it by default: at design time nothing
	 * verifies a requirement yet, so the rule failed every healthy design, and a coverage gate with a
	 * level bound says the same thing with intent (task 723e0178).
	 */
	public static final List<String> DEFAULT_ORPHANS = List.of("test");

	/** Report only, nothing mandatory, tests must verify something, no coverage gates. */
	public static final ElementCheckPolicy DEFAULTS = new ElementCheckPolicy(List.of(), Map.of(), DEFAULT_ORPHANS, Map.of());

	public ElementCheckPolicy {
		if (null == blocking) blocking = List.of();
		if (null == mandatoryFields) mandatoryFields = Map.of();
		if (null == orphans) orphans = DEFAULT_ORPHANS;
		if (null == coverage) coverage = Map.of();
	}

	/** Without coverage gates: every policy written before them. */
	public ElementCheckPolicy(List<String> blocking, Map<Integer, List<String>> mandatoryFields, List<String> orphans) {
		this(blocking, mandatoryFields, orphans, null);
	}

	/**
	 * A board's coverage gate: for every element of the checked document where {@code select} is
	 * true, {@code require} must be true. Both are CEL over {@code e} and {@code scope}.
	 *
	 * @param description what the gate means, shown as the offence message
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CoverageGate(String select, String require, String description) implements Serializable {
		private static final long serialVersionUID = 20260925L;
	}
}
