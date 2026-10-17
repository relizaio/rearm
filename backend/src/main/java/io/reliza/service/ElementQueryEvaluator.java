/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.List;
import java.util.Map;

import io.reliza.model.ElementCheckPolicy.CoverageGate;

/**
 * Evaluates a board's coverage gates (elements.md §7.5): expressions over one element {@code e} and
 * the scope it is checked in. An interface here so the check catalogue does not depend on the
 * expression engine, which lives in the Pro tree; without an implementation, every coverage gate is
 * a SKIP that says so.
 */
public interface ElementQueryEvaluator {

	/** How one element fared under one gate. */
	enum Outcome { SELECTED_PASS, SELECTED_FAIL, NOT_SELECTED, ERROR }

	/** @param message what went wrong, on ERROR */
	record Evaluation(Outcome outcome, String message) {
		public static Evaluation of(Outcome o) {
			return new Evaluation(o, null);
		}
	}

	/** The fields {@code e} carries; naming another is refused when a gate is saved. */
	List<String> ELEMENT_FIELDS = List.of("id", "family", "title", "parent", "level", "traces", "assumes",
			"speculative", "terms", "incoming", "document", "release");

	/** The fields {@code scope} carries. */
	List<String> SCOPE_FIELDS = List.of("elements", "families");

	/** What is wrong with a gate, for a refused board write; empty when it compiles and reads as boolean. */
	List<String> validate(String name, CoverageGate gate);

	/**
	 * @param e the element, as described in elements.md §7.5; keys without a value are left out, so
	 *        {@code has(e.level)} is how to ask
	 * @param scope {@code elements} (every element in scope, the same shape) and {@code families}
	 */
	Evaluation evaluate(CoverageGate gate, Map<String, Object> e, Map<String, Object> scope);
}
