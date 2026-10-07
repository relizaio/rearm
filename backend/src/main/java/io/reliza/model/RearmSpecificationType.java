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
	DECISION_RECORD,
	/**
	 * One review round of one task: the review items a reviewer hop produced.
	 *
	 * <p>Task-scoped, unlike everything above it. One component per board target holds every
	 * task's rounds, and each release names the task it belongs to, so resolution has to filter
	 * by task as well as by type.
	 */
	BOARD_REVIEW_ITEMS,
	/** One test run of one task: counts and an entry per failed case. Task-scoped like the above. */
	BOARD_TEST_REPORT,
	/**
	 * Questions one hop asked about one of its inputs, as an index. Task-scoped like the two above.
	 *
	 * <p>The index's {@code about} names the input, which is what sends the questions to the role
	 * that produces it; the answer is a new release of that input whose index closes the ids. A
	 * BOARD_QUESTIONS release usually has no file at all -- the items are the document.
	 */
	BOARD_QUESTIONS,
	/**
	 * What the element checks found on one document of one task (gaps §2.A): a {@link ElementCheckReport}.
	 * Task-scoped like the three above, and always index-only: the board cuts it when an
	 * element-bearing document is published or re-checked, no agent publishes it, and it has no
	 * path. It is evidence, not an index the router reads.
	 */
	BOARD_ELEMENT_CHECK_REPORT,
	/**
	 * What one investigation found (task RD4-12): the deliverable of an INVESTIGATION task, which a role
	 * commissions from another and which comes back to the asker pinned. Task-scoped prose, like a design
	 * round, but its element grammar is references only: it defines no ids, it cites them.
	 */
	BOARD_INVESTIGATION_REPORT;

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
