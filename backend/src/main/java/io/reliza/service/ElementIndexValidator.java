/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.reliza.model.ElementFamilies;
import io.reliza.model.ElementIndex;
import io.reliza.model.ElementIndex.Element;
import io.reliza.model.ElementIndex.Link;
import io.reliza.model.ElementIndex.Reference;
import io.reliza.model.ElementIndex.Warning;
import io.reliza.model.RearmSpecificationType;

/**
 * Checks an element index against the board and the task's inputs (gaps §2.A, task e200cb32).
 *
 * <p>Nothing here refuses. Every problem becomes a {@link Warning} on the index: a trace to an element
 * its author is about to add upstream is normal work, and the checks that turn warnings into gate
 * evidence are a later step. What the server can decide it decides itself -- duplicate ids, unknown
 * families, unresolved parents and targets -- replacing whatever the CLI reported for those codes;
 * only the parser's MALFORMED_ATTRIBUTE warnings are kept as sent, since only the parser saw the
 * lines.
 *
 * <p>Pure and static, like {@link BoardReviewItemIndexValidator}: the caller gathers the ids the task's inputs
 * define, so the rules have no repository in them.
 */
public final class ElementIndexValidator {

	private ElementIndexValidator() {}

	/**
	 * @param families the board's effective element families, prefix to family name
	 * @param resolvable ids defined by the task's inputs, which a parent or a link may point at
	 * @return the index with each element's family set from the board and its warnings recomputed
	 */
	public static ElementIndex validate(ElementIndex index, Map<String, String> families, Set<String> resolvable) {
		return validate(index, families, resolvable, Set.of());
	}

	/**
	 * As above, with the board's task prefixes reserved (task RD2-28): a token whose family is one of
	 * them is a task key, not an element id, so an element a parser read from "# RD2-1 — Title" is
	 * dropped, with its warnings, and a parent it lent an element under it is cleared. The CLI no
	 * longer sends one; an older CLI still may, and the index and the checks agree with the parser.
	 *
	 * @param reserved the board's task prefix and every prefix it held before
	 */
	public static ElementIndex validate(ElementIndex index, Map<String, String> families, Set<String> resolvable,
			Set<String> reserved) {
		return validate(index, families, null, null, resolvable, reserved);
	}

	/**
	 * As above, for a document of a known type (grammar 1.2, task RD4-6). An index of grammar 1.2 is held to the
	 * board's {@code definedIn} lists: an element whose family does not list the document's type -- or, for a prefix
	 * the board does not know, a type that defines nothing -- is moved to the references, defining nothing and using
	 * no terms. The CLI already sorts them so; the server decides for itself, as it does for families, because the
	 * board may have changed its lists since the CLI read them. An index of grammar 1 or 1.1 is read as before.
	 *
	 * @param definedIn prefix to the board's effective defining types; null for the defaults of each family
	 * @param type the document's specification; null applies no type rule
	 */
	public static ElementIndex validate(ElementIndex index, Map<String, String> families,
			Map<String, List<RearmSpecificationType>> definedIn, RearmSpecificationType type, Set<String> resolvable,
			Set<String> reserved) {
		boolean typed = null != type && ElementFamilies.typed(index);
		Map<String, List<RearmSpecificationType>> lists = null != definedIn ? definedIn : ElementFamilies.defaultsFor(families);
		List<Reference> references = new ArrayList<>();
		for (Reference r : index.references()) {
			if (isTaskKey(r.id(), reserved)) continue;
			references.add(new Reference(r.id(), familyOf(r.id(), families), r.title(), r.line()));
		}
		List<Element> kept = new ArrayList<>();
		for (Element e : index.elements()) {
			if (isTaskKey(e.id(), reserved)) continue;
			if (typed && !ElementFamilies.defines(lists, familyPrefix(e.id()), type)) {
				references.add(new Reference(e.id(), familyOf(e.id(), families), e.title(), e.line()));
				continue;
			}
			kept.add(isTaskKey(e.parent(), reserved) ? new Element(e.id(), e.family(), e.title(), null, e.level(),
					e.traces(), e.assumes(), e.speculative(), e.contentDigest(), e.line(), e.terms()) : e);
		}
		List<Warning> warnings = new ArrayList<>();
		for (Warning w : index.warnings()) {
			if (ElementIndex.MALFORMED_ATTRIBUTE.equals(w.code()) && !isTaskKey(w.elementId(), reserved)) warnings.add(w);
		}
		Set<String> here = new HashSet<>();
		Set<String> duplicates = new LinkedHashSet<>();
		for (Element e : kept) {
			if (null != e.id() && !here.add(e.id())) duplicates.add(e.id());
		}
		for (String d : duplicates) {
			warnings.add(new Warning(ElementIndex.DUPLICATE_ID, d, d + " is defined more than once in this document"));
		}
		List<Element> elements = new ArrayList<>();
		for (Element e : kept) {
			String prefix = familyPrefix(e.id());
			String family = null == prefix ? null : families.get(prefix);
			if (null == family) {
				warnings.add(new Warning(ElementIndex.UNKNOWN_FAMILY, e.id(), "the board has no element family "
						+ (null == prefix ? "for " + e.id() : prefix) + "; known: " + String.join(", ", families.keySet())));
			}
			if (null != e.parent() && !here.contains(e.parent()) && !resolvable.contains(e.parent())) {
				warnings.add(new Warning(ElementIndex.UNRESOLVED_PARENT, e.id(),
						"parent " + e.parent() + " is neither in this document nor in the task's inputs"));
			}
			for (Link l : e.traces()) {
				if (null != l.target() && !here.contains(l.target()) && !resolvable.contains(l.target())) {
					warnings.add(new Warning(ElementIndex.UNRESOLVED_TARGET, e.id(), (null == l.verb() ? "" : l.verb() + " ")
							+ l.target() + " is neither in this document nor in the task's inputs"));
				}
			}
			for (String a : e.assumes()) {
				if (!here.contains(a) && !resolvable.contains(a)) {
					warnings.add(new Warning(ElementIndex.UNRESOLVED_TARGET, e.id(),
							"assumes " + a + ", which is neither in this document nor in the task's inputs"));
				}
			}
			elements.add(new Element(e.id(), family, e.title(), e.parent(), e.level(), e.traces(), e.assumes(),
					e.speculative(), e.contentDigest(), e.line(), e.terms()));
		}
		references.sort(java.util.Comparator.comparing(r -> null == r.line() ? Integer.MAX_VALUE : r.line()));
		return new ElementIndex(null == index.grammarVersion() ? ElementIndex.GRAMMAR_VERSION : index.grammarVersion(),
				elements, warnings, index.digest(), references);
	}

	private static String familyOf(String id, Map<String, String> families) {
		String prefix = familyPrefix(id);
		return null == prefix || null == families ? null : families.get(prefix);
	}

	/** Whether id is a task key: its family is one of the board's reserved task prefixes. */
	static boolean isTaskKey(String id, Set<String> reserved) {
		String prefix = familyPrefix(id);
		return null != prefix && null != reserved && reserved.contains(prefix);
	}

	/** {@code REQ} of {@code REQ-F-012}: the letters and digits before the first hyphen, starting with a letter. */
	static String familyPrefix(String id) {
		if (null == id) return null;
		int dash = id.indexOf('-');
		if (dash <= 0) return null;
		String prefix = id.substring(0, dash);
		return prefix.matches("[A-Z][A-Z0-9]*") ? prefix : null;
	}
}
