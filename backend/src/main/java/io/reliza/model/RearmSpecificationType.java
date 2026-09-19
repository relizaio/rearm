/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * Well-known specification documents. When {@code idType} is
 * {@link RearmIdentifierType#SPECIFICATION}, the {@code idValue} is one of these values.
 *
 * <p>This is the design-stage counterpart of TEA's {@code compliance-document-type}
 * (transparency-exchange-api#216), and deliberately the same shape: an identifier type says
 * what a component IS, and a published vocabulary supplies the value, so the set is
 * discoverable by identifier rather than by naming convention. Where TEA leaves its values a
 * SHOULD, ours are enforced on write: the vocabulary is our own, so a typo would silently
 * make a document undiscoverable instead of interoperating with somebody else's list.
 *
 * <p>The values follow a system-engineering breakdown from the whole system down to the
 * component: what it is for, what it must do, what it is made of, and how it is verified.
 * A project that skips a level simply never mints that identifier.
 */
public enum RearmSpecificationType {

	/** Concept of operations: who uses the system, in what modes, to what end. */
	CONOPS,
	/** Use cases: the scenarios the system is judged against. */
	USE_CASES,
	/** Requirements the system must satisfy, functional or otherwise. */
	REQUIREMENTS,
	/** Functional breakdown: the functions the system performs. */
	FUNCTIONS,
	/** Product breakdown: the elements the system is composed of. */
	PRODUCT_BREAKDOWN,
	/** Interfaces across the system boundary and between its elements. */
	INTERFACES,
	/** Data model: the data the system holds and exchanges. */
	DATA_MODEL,
	/** Architecture / high-level design: the solution blocks and the technology decisions. */
	ARCHITECTURE,
	/** Detailed design of a component. */
	DETAILED_DESIGN,
	/** User-experience concept: the interaction design the product is built to. */
	UX_CONCEPT,
	/** Test plan or test specification. */
	TEST_PLAN,
	/** Glossary: the terms the other documents rest on. */
	GLOSSARY,
	/** A recorded architectural decision (ADR) and its rationale. */
	DECISION_RECORD;

	/**
	 * @param value candidate identifier value
	 * @return the matching type, or null when the value is not one of the well-known set
	 */
	public static RearmSpecificationType fromValue(String value) {
		if (null == value) return null;
		for (RearmSpecificationType t : values()) {
			if (t.name().equals(value)) return t;
		}
		return null;
	}
}
