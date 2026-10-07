/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which document types define an element family's ids (grammar 1.2, task RD4-6; {@code elements.md} §1.1).
 *
 * <p>Each family carries {@code definedIn}, an ordered list of specification types. A heading or table row whose id
 * belongs to family F, in a document of type T, defines the id only when T is on F's list, and only when no document
 * of an earlier type on the list defines the same id in scope; otherwise it is a reference. So a test plan owns the
 * test ids and the reports reference them, and a coder note titled by the review item it answers defines nothing.
 *
 * <p>Pure and static: the board supplies its overrides, and everything else is the defaults below.
 */
public final class ElementFamilies {

	private ElementFamilies() {}

	/** The types that carry an index rather than prose; a BOARD_ELEMENT_CHECK_REPORT is cut by the board and defines nothing. */
	public static final Set<RearmSpecificationType> INDEX_TYPES = EnumSet.of(RearmSpecificationType.BOARD_REVIEW_ITEMS,
			RearmSpecificationType.BOARD_TEST_REPORT, RearmSpecificationType.BOARD_QUESTIONS, RearmSpecificationType.BOARD_ELEMENT_CHECK_REPORT);

	/**
	 * Prose types whose element grammar is references only (task RD4-12): no family defines its ids in them, and a
	 * board may not name them in definedIn. An investigation report cites the elements it looked at; it owns none.
	 */
	public static final Set<RearmSpecificationType> REFERENCE_ONLY_TYPES = EnumSet.of(
			RearmSpecificationType.BOARD_INVESTIGATION_REPORT);

	/** Every prose type, in the enum's order: what a board's own family defines in unless it says otherwise. */
	public static final List<RearmSpecificationType> PROSE_TYPES = java.util.Arrays.stream(RearmSpecificationType.values())
			.filter(t -> !INDEX_TYPES.contains(t) && !REFERENCE_ONLY_TYPES.contains(t)).toList();

	private static final RearmSpecificationType ARCHITECTURE = RearmSpecificationType.ARCHITECTURE;
	private static final RearmSpecificationType DETAILED_DESIGN = RearmSpecificationType.DETAILED_DESIGN;

	/**
	 * The defaults, family name to its defining types in order of precedence. Together they cover every type but
	 * two: BOARD_ELEMENT_CHECK_REPORT and BOARD_INVESTIGATION_REPORT define nothing, UX_CONCEPT defines only glossary terms.
	 */
	public static final Map<String, List<RearmSpecificationType>> DEFAULT_DEFINED_IN = java.util.Collections
			.unmodifiableMap(new LinkedHashMap<>() {{
				put("test", List.of(RearmSpecificationType.TEST_PLAN, RearmSpecificationType.BOARD_TEST_REPORT));
				put("requirement", List.of(RearmSpecificationType.REQUIREMENTS, ARCHITECTURE, DETAILED_DESIGN));
				put("decision", List.of(RearmSpecificationType.DECISION_RECORD, ARCHITECTURE));
				put("function", List.of(RearmSpecificationType.FUNCTIONS, ARCHITECTURE, DETAILED_DESIGN));
				put("interface", List.of(RearmSpecificationType.INTERFACES, ARCHITECTURE, DETAILED_DESIGN));
				put("data", List.of(RearmSpecificationType.DATA_MODEL, ARCHITECTURE, DETAILED_DESIGN));
				put("product", List.of(RearmSpecificationType.PRODUCT_BREAKDOWN, ARCHITECTURE, DETAILED_DESIGN));
				put("concept", List.of(RearmSpecificationType.CONOPS, ARCHITECTURE, DETAILED_DESIGN));
				put("use-case", List.of(RearmSpecificationType.USE_CASES, ARCHITECTURE, DETAILED_DESIGN));
				List<RearmSpecificationType> glossary = new ArrayList<>();
				glossary.add(RearmSpecificationType.GLOSSARY);
				PROSE_TYPES.stream().filter(t -> RearmSpecificationType.GLOSSARY != t).forEach(glossary::add);
				put("glossary", List.copyOf(glossary));
				put("question", List.of(RearmSpecificationType.BOARD_QUESTIONS));
				put("review-item", List.of(RearmSpecificationType.BOARD_REVIEW_ITEMS));
			}});

	/**
	 * One effective family entry of a board: a prefix, the family it names, and where the family's ids are defined.
	 *
	 * @param definedIn in order of precedence; empty means the prefix's ids are only ever referenced
	 */
	public record Entry(String prefix, String family, List<RearmSpecificationType> definedIn) implements Serializable {
		private static final long serialVersionUID = 20260930L;
	}

	/** A family's default list: its own when it is one of the defaults, else every prose type. */
	public static List<RearmSpecificationType> defaultDefinedIn(String family) {
		List<RearmSpecificationType> known = null == family ? null : DEFAULT_DEFINED_IN.get(family);
		return null != known ? known : PROSE_TYPES;
	}

	/**
	 * The effective entries: every family prefix with its list, the board's own list where it declares one.
	 *
	 * @param families prefix to family name, the defaults with the board's entries over them
	 * @param declared prefix to the board's list; a prefix absent here takes its family's default
	 */
	public static List<Entry> entries(Map<String, String> families, Map<String, List<RearmSpecificationType>> declared) {
		List<Entry> out = new ArrayList<>();
		for (Map.Entry<String, String> f : families.entrySet()) {
			List<RearmSpecificationType> own = null == declared ? null : declared.get(f.getKey());
			out.add(new Entry(f.getKey(), f.getValue(), List.copyOf(null != own ? own : defaultDefinedIn(f.getValue()))));
		}
		return out;
	}

	/** The entries as prefix to list, for the rules below. */
	public static Map<String, List<RearmSpecificationType>> definedInByPrefix(List<Entry> entries) {
		Map<String, List<RearmSpecificationType>> out = new LinkedHashMap<>();
		for (Entry e : entries) out.put(e.prefix(), e.definedIn());
		return out;
	}

	/** Prefix to list from the families alone, every list its family's default. */
	public static Map<String, List<RearmSpecificationType>> defaultsFor(Map<String, String> families) {
		return definedInByPrefix(entries(null == families ? Map.of() : families, null));
	}

	/**
	 * Whether a type defines anything on the board: it is on some family's list. A heading with an unknown prefix
	 * is in a defining position only in such a type; elsewhere it is a reference like any other.
	 */
	public static boolean definesAnything(Map<String, List<RearmSpecificationType>> definedIn, RearmSpecificationType type) {
		if (null == type || null == definedIn) return false;
		return definedIn.values().stream().anyMatch(l -> null != l && l.contains(type));
	}

	/**
	 * Whether an id with this prefix, in a document of this type, is in a defining position: its family lists the
	 * type, or -- for a prefix the board does not know -- the type defines anything at all.
	 */
	public static boolean defines(Map<String, List<RearmSpecificationType>> definedIn, String prefix,
			RearmSpecificationType type) {
		if (null == type || null == definedIn) return false;
		List<RearmSpecificationType> list = null == prefix ? null : definedIn.get(prefix);
		if (null == list) return definesAnything(definedIn, type);
		return list.contains(type);
	}

	/** Where a type stands on a prefix's list: 0 first; -1 when it is not on it or the prefix is unknown. */
	public static int precedence(Map<String, List<RearmSpecificationType>> definedIn, String prefix,
			RearmSpecificationType type) {
		List<RearmSpecificationType> list = null == prefix || null == definedIn ? null : definedIn.get(prefix);
		return null == list || null == type ? -1 : list.indexOf(type);
	}

	/**
	 * Whether an index is read under grammar 1.2 or later, which classifies its headings by document type. An index
	 * of grammar 1 or 1.1 is read as before: every element it carries is a definition.
	 */
	public static boolean typed(ElementIndex index) {
		return null != index && ElementIndex.atLeast(index.grammarVersion(), ElementIndex.GRAMMAR_VERSION_TYPED);
	}
}
