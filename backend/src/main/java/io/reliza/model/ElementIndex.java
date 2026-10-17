/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * What a prose document says about its elements (gaps §2.A, task e200cb32): its requirements,
 * functions, interfaces, tests and the like, each with an id, a title, a parent, typed links and a
 * digest of its content. Parsed by the CLI from the committed markdown under the grammar in
 * {@code ai-plans/agentic/elements.md}, validated by {@code ElementIndexValidator}, stored on the
 * release's {@link ReleaseData.DocumentRef}.
 *
 * <p>Problems are {@link Warning}s carried on the index, never a refused publish: a trace to an input
 * its author is about to add upstream is normal work, and the checks that turn warnings into gate
 * evidence come later (child 2).
 *
 * @param grammarVersion the grammar the parser implemented: "1", "1.1" or "1.2"
 * @param elements in document order; under grammar 1.2 the definitions only
 * @param warnings what the parser and the server found wrong, in that order
 * @param digest sha256 of the exact JSON the CLI sent, which ties the index to one parse of the file
 * @param references grammar 1.2 (task RD4-6): headings and table rows that start with an id this document does not
 *        define -- its family is defined in other document types -- in document order; empty before 1.2
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ElementIndex(String grammarVersion, List<Element> elements, List<Warning> warnings, String digest,
		List<Reference> references) implements Serializable {

	private static final long serialVersionUID = 20260925L;

	public static final String GRAMMAR_VERSION = "1";

	/** Grammar 1.1 adds {@code terms}; an index of either version is accepted. */
	public static final String GRAMMAR_VERSION_TERMS = "1.1";

	/**
	 * Grammar 1.2 (task RD4-6) classifies headings by document type: an id defines only in a type on its family's
	 * {@code definedIn} list, and is a reference elsewhere; a lead-in bold span is emphasis, not a term.
	 */
	public static final String GRAMMAR_VERSION_TYPED = "1.2";

	/** The grammar versions the server accepts. */
	public static final List<String> GRAMMAR_VERSIONS = List.of(GRAMMAR_VERSION, GRAMMAR_VERSION_TERMS,
			GRAMMAR_VERSION_TYPED);

	public ElementIndex {
		if (null == elements) elements = List.of();
		if (null == warnings) warnings = List.of();
		if (null == references) references = List.of();
	}

	/** Without references: grammar 1 and 1.1, and every call site that predates them. */
	public ElementIndex(String grammarVersion, List<Element> elements, List<Warning> warnings, String digest) {
		this(grammarVersion, elements, warnings, digest, null);
	}

	/**
	 * Whether version is at least min, compared as dotted numbers ("1.10" after "1.9"); a null or unreadable
	 * version is grammar 1.
	 */
	public static boolean atLeast(String version, String min) {
		int[] v = parts(null == version ? GRAMMAR_VERSION : version);
		int[] m = parts(min);
		for (int i = 0; i < Math.max(v.length, m.length); i++) {
			int a = i < v.length ? v[i] : 0;
			int b = i < m.length ? m[i] : 0;
			if (a != b) return a > b;
		}
		return true;
	}

	private static int[] parts(String version) {
		String[] p = version.strip().split("\\.");
		int[] out = new int[p.length];
		for (int i = 0; i < p.length; i++) {
			try {
				out[i] = Integer.parseInt(p[i]);
			} catch (NumberFormatException e) {
				return new int[] {1};
			}
		}
		return out;
	}

	/**
	 * One element.
	 *
	 * @param family the family's name from the board's element families, e.g. requirement
	 * @param parent explicit {@code parent:}, else the enclosing element heading; null at the top
	 * @param speculative inputs cited below their floor (read by child 2)
	 * @param contentDigest sha256 of the element's content with line endings normalised
	 * @param line 1-based line of the heading or table row
	 * @param terms glossary terms the element's content uses, {@code **term**} (grammar 1.1); empty
	 *        under grammar 1, which did not carry them
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Element(String id, String family, String title, String parent, Integer level,
			List<Link> traces, List<String> assumes, List<String> speculative, String contentDigest,
			Integer line, List<String> terms) implements Serializable {

		private static final long serialVersionUID = 20260925L;

		public Element {
			if (null == traces) traces = List.of();
			if (null == assumes) assumes = List.of();
			if (null == speculative) speculative = List.of();
			if (null == terms) terms = List.of();
		}

		/** Without terms: grammar 1, and every call site that predates them. */
		public Element(String id, String family, String title, String parent, Integer level, List<Link> traces,
				List<String> assumes, List<String> speculative, String contentDigest, Integer line) {
			this(id, family, title, parent, level, traces, assumes, speculative, contentDigest, line, null);
		}
	}

	/**
	 * A heading or table row naming an id the document does not define (grammar 1.2): recorded for the trace checks
	 * and the reader, and defining nothing.
	 *
	 * @param family the family's name from the board; null for an unknown prefix
	 * @param line 1-based line of the heading or table row
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Reference(String id, String family, String title, Integer line) implements Serializable {
		private static final long serialVersionUID = 20260930L;
	}

	/** A typed link: {@code traces: derives_from REQ-1}. Verbs are kept as written. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Link(String verb, String target) implements Serializable {
		private static final long serialVersionUID = 20260925L;
	}

	/**
	 * @param code DUPLICATE_ID, UNKNOWN_FAMILY, UNRESOLVED_PARENT, UNRESOLVED_TARGET or MALFORMED_ATTRIBUTE
	 * @param elementId the element it is about; null when it is about a line no element owns
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Warning(String code, String elementId, String message) implements Serializable {
		private static final long serialVersionUID = 20260925L;
	}

	public static final String DUPLICATE_ID = "DUPLICATE_ID";
	public static final String UNKNOWN_FAMILY = "UNKNOWN_FAMILY";
	public static final String UNRESOLVED_PARENT = "UNRESOLVED_PARENT";
	public static final String UNRESOLVED_TARGET = "UNRESOLVED_TARGET";
	public static final String MALFORMED_ATTRIBUTE = "MALFORMED_ATTRIBUTE";

	/** The same index with its warnings replaced. */
	public ElementIndex withWarnings(List<Warning> replaced) {
		return new ElementIndex(grammarVersion, elements, replaced, digest, references);
	}
}
