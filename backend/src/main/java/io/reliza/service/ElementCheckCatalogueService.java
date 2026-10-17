/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import io.reliza.common.Utils;
import io.reliza.model.ElementCheckPolicy;
import io.reliza.model.ElementCheckReport;
import io.reliza.model.ElementCheckReport.ElementCheckResult;
import io.reliza.model.ElementCheckReport.Offence;
import io.reliza.model.ElementCheckReport.Result;
import io.reliza.model.ElementCheckReport.ScopedRelease;
import io.reliza.model.ElementFamilies;
import io.reliza.model.ElementIndex;
import io.reliza.model.ElementIndex.Element;
import io.reliza.model.ElementIndex.Link;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData.ReleaseLifecycle;

/**
 * The named checks over a document's elements (gaps §2.A, task 2e0fffa6; {@code elements.md} §7).
 *
 * <p>Pure: {@link #run} reads nothing but its scope, so the same scope gives the same report, digest
 * included, and the caller decides what the scope is. Every check looks at the elements of the
 * checked document and resolves what they point at across the whole scope: a trace into an input is
 * as good as one inside the document, and a problem that lives entirely in the inputs is the inputs'
 * report, not this one's.
 */
@Service
public class ElementCheckCatalogueService {

	/**
	 * Bumped on any change to what a check means, so an old report says which rules it was held to.
	 * 2026-09.2 (task 723e0178): glossary.terms_defined, the board's coverage gates, and
	 * tests.no_orphans judging tests only unless a board asks for requirements.
	 * 2026-09.3 (task RD4-6): under grammar 1.2 every check reads definitions only -- an id defines in a type its
	 * family lists, and a document of an earlier type on the list that defines it in scope owns it -- so ids.family
	 * judges headings in a defining position, ids.unique counts definitions, and terms are read only in them.
	 */
	public static final String CATALOGUE_VERSION = "2026-09.3";

	public static final String IDS_FAMILY = "ids.family";
	public static final String IDS_UNIQUE = "ids.unique";
	public static final String FIELDS_MANDATORY = "fields.mandatory";
	public static final String TRACE_PARENT_EXISTS = "trace.parent_exists";
	public static final String TRACE_VERBS_KNOWN = "trace.verbs_known";
	public static final String GRAPH_NO_CYCLES = "graph.no_cycles";
	public static final String TESTS_NO_ORPHANS = "tests.no_orphans";
	public static final String ASSUMES_LINKED = "assumes.linked";
	public static final String SPECULATIVE_INPUTS_RECORDED = "speculative.inputs_recorded";
	public static final String GLOSSARY_TERMS_DEFINED = "glossary.terms_defined";
	/** A board's coverage gate {@code x} is the check {@code coverage.x}. */
	public static final String COVERAGE_PREFIX = "coverage.";
	/** A coverage gate's name. */
	public static final java.util.regex.Pattern GATE_NAME = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]*");

	/** The fixed checks, in the order a report lists them; the board's coverage gates follow, by name. */
	public static final List<String> CHECKS = List.of(IDS_FAMILY, IDS_UNIQUE, FIELDS_MANDATORY,
			TRACE_PARENT_EXISTS, TRACE_VERBS_KNOWN, GRAPH_NO_CYCLES, TESTS_NO_ORPHANS, ASSUMES_LINKED,
			SPECULATIVE_INPUTS_RECORDED, GLOSSARY_TERMS_DEFINED);

	/** One entry of the catalogue, for the UI and for refusal messages. */
	public record CatalogueEntry(String name, String description, String skipsWhen) {}

	/** The catalogue: the fixed checks, then the pattern every coverage gate is named by. */
	public static final List<CatalogueEntry> CATALOGUE = List.of(
			new CatalogueEntry(IDS_FAMILY, "every id the document defines has one of the board's element families as its"
					+ " prefix; a heading in a type that defines nothing, or naming a family defined elsewhere, is a reference", null),
			new CatalogueEntry(IDS_UNIQUE, "no id of the document is defined twice in scope; a document of an earlier"
					+ " type on the family's definedIn list owns an id, and a later one references it", null),
			new CatalogueEntry(FIELDS_MANDATORY, "an element at level L carries the attributes elementChecks.mandatoryFields[L] names",
					"the board names no mandatory fields"),
			new CatalogueEntry(TRACE_PARENT_EXISTS, "every parent resolves in scope", null),
			new CatalogueEntry(TRACE_VERBS_KNOWN, "every traces verb is a recommended one", null),
			new CatalogueEntry(GRAPH_NO_CYCLES, "parent, decomposes and derives_from form no cycle through the document", null),
			new CatalogueEntry(TESTS_NO_ORPHANS, "tests verify something; with requirement in elementChecks.orphans, requirements are verified",
					"elementChecks.orphans is empty"),
			new CatalogueEntry(ASSUMES_LINKED, "every assumes target resolves in scope", null),
			new CatalogueEntry(SPECULATIVE_INPUTS_RECORDED, "an element citing an input below its floor lists it under speculative",
					"there is no assignment, so no floors"),
			new CatalogueEntry(GLOSSARY_TERMS_DEFINED, "every **term** the document uses has a glossary element in scope",
					"the index is grammar 1, which carries no terms"),
			new CatalogueEntry(COVERAGE_PREFIX + "<gate>", "the board's gate: where select is true, require is true",
					"the server has no expression evaluator"));

	/** A name {@code checks.blocking} may carry: a fixed check, or a coverage gate the policy declares. */
	public static boolean knownElementCheck(String name, java.util.Set<String> declaredGates) {
		if (CHECKS.contains(name)) return true;
		return null != name && name.startsWith(COVERAGE_PREFIX)
				&& declaredGates.contains(name.substring(COVERAGE_PREFIX.length()));
	}

	/** Absent where no implementation is deployed: coverage gates then SKIP and say why. */
	@org.springframework.beans.factory.annotation.Autowired(required = false)
	private ElementQueryEvaluator evaluator;

	public ElementCheckCatalogueService() {}

	/** For tests: a catalogue with the given evaluator, or none. */
	public ElementCheckCatalogueService(ElementQueryEvaluator evaluator) {
		this.evaluator = evaluator;
	}

	/** The recommended trace verbs (elements.md §3). */
	public static final Set<String> RECOMMENDED_VERBS = Set.of("derives_from", "satisfies", "decomposes",
			"allocated_to", "is_verified_by", "verifies", "exposes", "consumes", "depends_on", "traces_to",
			"invalidates", "constrains", "supports");

	/** Attributes {@code fields.mandatory} can require. */
	public static final Set<String> MANDATORY_ATTRIBUTES = Set.of("parent", "traces", "assumes", "speculative");

	/** Families {@code tests.no_orphans} knows how to judge. */
	public static final Set<String> ORPHAN_FAMILIES = Set.of("test", "requirement");

	/** Edges {@code graph.no_cycles} follows, besides parent. */
	private static final Set<String> HIERARCHY_VERBS = Set.of("decomposes", "derives_from");

	/**
	 * One release the checks can see.
	 *
	 * @param lifecycle where the release stood when the checks ran, for the floor comparison
	 */
	public record ScopedDocument(UUID release, RearmSpecificationType specification, ReleaseLifecycle lifecycle,
			ElementIndex elements) {}

	/**
	 * Everything a run reads.
	 *
	 * @param documents the checked document first, then the rest of the resolution universe
	 * @param families the board's effective element families, prefix to family
	 * @param floors the lowest lifecycle the current assignment binds per specification; null when
	 *        there is no assignment, which is when {@code speculative.inputs_recorded} cannot run
	 */
	public record RunScope(UUID task, UUID checked, List<ScopedDocument> documents, Map<String, String> families,
			ElementCheckPolicy policy, Map<RearmSpecificationType, ReleaseLifecycle> floors,
			Map<String, List<RearmSpecificationType>> definedIn) {

		/** With each family's default {@code definedIn}: every caller that predates grammar 1.2. */
		public RunScope(UUID task, UUID checked, List<ScopedDocument> documents, Map<String, String> families,
				ElementCheckPolicy policy, Map<RearmSpecificationType, ReleaseLifecycle> floors) {
			this(task, checked, documents, families, policy, floors, null);
		}
	}

	/** Where an element is defined. */
	private record Defined(Element element, ScopedDocument in) {}

	public ElementCheckReport run(RunScope scope) {
		ScopedDocument checked = scope.documents().stream().filter(d -> d.release().equals(scope.checked()))
				.findFirst().orElseThrow(() -> new IllegalArgumentException("the checked release is not in the scope"));
		ElementCheckPolicy policy = null == scope.policy() ? ElementCheckPolicy.DEFAULTS : scope.policy();
		Map<UUID, List<Element>> definitions = definitions(scope);
		List<Element> mine = definitions.getOrDefault(checked.release(), List.of());
		Map<String, List<Defined>> defined = new LinkedHashMap<>();
		for (ScopedDocument d : scope.documents()) {
			for (Element e : definitions.getOrDefault(d.release(), List.of())) {
				if (null != e.id()) defined.computeIfAbsent(e.id(), k -> new ArrayList<>()).add(new Defined(e, d));
			}
		}
		UUID here = checked.release();

		List<ElementCheckResult> results = new ArrayList<>();
		results.add(result(IDS_FAMILY, policy, idsFamily(mine, here, scope.families())));
		results.add(result(IDS_UNIQUE, policy, idsUnique(mine, here, defined)));
		results.add(policy.mandatoryFields().isEmpty()
				? skip(FIELDS_MANDATORY, policy, "the board names no mandatory fields")
				: result(FIELDS_MANDATORY, policy, fieldsMandatory(mine, here, policy.mandatoryFields())));
		results.add(result(TRACE_PARENT_EXISTS, policy, parentExists(mine, here, defined)));
		results.add(result(TRACE_VERBS_KNOWN, policy, verbsKnown(mine, here)));
		results.add(result(GRAPH_NO_CYCLES, policy, noCycles(mine, here, defined)));
		results.add(policy.orphans().isEmpty()
				? skip(TESTS_NO_ORPHANS, policy, "the board judges no family for orphans")
				: result(TESTS_NO_ORPHANS, policy, noOrphans(mine, here, scope.documents(), definitions, policy.orphans())));
		results.add(result(ASSUMES_LINKED, policy, assumesLinked(mine, here, defined)));
		results.add(null == scope.floors()
				? skip(SPECULATIVE_INPUTS_RECORDED, policy,
						"no assignment: the floors are the ones the current assignment binds")
				: result(SPECULATIVE_INPUTS_RECORDED, policy,
						speculativeRecorded(mine, here, defined, scope.floors())));
		results.add(null == checked.elements()
				|| !ElementIndex.atLeast(checked.elements().grammarVersion(), ElementIndex.GRAMMAR_VERSION_TERMS)
				? skip(GLOSSARY_TERMS_DEFINED, policy, "the index is grammar "
						+ (null == checked.elements() ? "none" : checked.elements().grammarVersion())
						+ ", which carries no terms; the CLI emits them from grammar 1.1")
				: result(GLOSSARY_TERMS_DEFINED, policy, termsDefined(mine, here, scope.documents(), definitions)));
		results.addAll(coverage(mine, here, scope, policy, definitions));

		List<ScopedRelease> releases = scope.documents().stream()
				.map(d -> new ScopedRelease(d.release(), d.specification(),
						null == d.elements() ? null : d.elements().digest(),
						null == d.lifecycle() ? null : d.lifecycle().name()))
				.toList();
		ElementCheckReport report = new ElementCheckReport(CATALOGUE_VERSION,
				null == checked.elements() ? null : checked.elements().grammarVersion(),
				new ElementCheckReport.Scope(scope.task(), here, releases), results, null);
		return report.withDigest(digest(report));
	}

	/**
	 * The elements each document of the scope defines (grammar 1.2, elements.md §1.1). An index of grammar 1 or 1.1
	 * is read as before: everything it carries is a definition. Under 1.2 an element is a definition when its
	 * family lists the document's type -- or, for a prefix the board does not know, when the type defines anything
	 * -- and no document of an earlier type on that list defines the same id in scope; otherwise it is read as a
	 * reference and no check judges it. So a test plan owns the test ids its reports repeat, and a report without a
	 * plan owns them itself.
	 */
	static Map<UUID, List<Element>> definitions(RunScope scope) {
		Map<String, List<RearmSpecificationType>> lists = null != scope.definedIn() ? scope.definedIn()
				: ElementFamilies.defaultsFor(scope.families());
		// For each id, the best (lowest) place on its family's list among the documents that carry it there.
		Map<String, Integer> owner = new HashMap<>();
		for (ScopedDocument d : scope.documents()) {
			if (null == d.elements()) continue;
			for (Element e : d.elements().elements()) {
				if (null == e.id()) continue;
				int at = ElementFamilies.precedence(lists, ElementIndexValidator.familyPrefix(e.id()), d.specification());
				if (at >= 0) owner.merge(e.id(), at, Math::min);
			}
		}
		Map<UUID, List<Element>> out = new LinkedHashMap<>();
		for (ScopedDocument d : scope.documents()) {
			if (null == d.elements()) continue;
			if (!ElementFamilies.typed(d.elements()) || null == d.specification()) {
				out.put(d.release(), d.elements().elements());
				continue;
			}
			List<Element> kept = new ArrayList<>();
			for (Element e : d.elements().elements()) {
				String prefix = ElementIndexValidator.familyPrefix(e.id());
				if (!ElementFamilies.defines(lists, prefix, d.specification())) continue;
				int at = ElementFamilies.precedence(lists, prefix, d.specification());
				Integer best = null == e.id() ? null : owner.get(e.id());
				if (at >= 0 && null != best && best < at) continue;
				kept.add(e);
			}
			out.put(d.release(), kept);
		}
		return out;
	}

	/**
	 * Each term an element of this document uses needs a glossary element in scope: one whose title is
	 * the term, case aside, or whose id's local part is, with {@code -} and {@code _} read as spaces
	 * ({@code GLOSS-rework-point} for "rework point").
	 */
	private static List<Offence> termsDefined(List<Element> mine, UUID here, List<ScopedDocument> documents,
			Map<UUID, List<Element>> definitions) {
		Set<String> glossary = new LinkedHashSet<>();
		for (ScopedDocument d : documents) {
			for (Element g : definitions.getOrDefault(d.release(), List.of())) {
				if (!"glossary".equals(g.family())) continue;
				if (null != g.title()) glossary.add(g.title().strip().toLowerCase(java.util.Locale.ROOT));
				if (null != g.id() && g.id().indexOf('-') > 0) {
					glossary.add(g.id().substring(g.id().indexOf('-') + 1).replace('-', ' ').replace('_', ' ')
							.strip().toLowerCase(java.util.Locale.ROOT));
				}
			}
		}
		List<Offence> out = new ArrayList<>();
		for (Element e : mine) {
			for (String term : e.terms()) {
				if (!glossary.contains(term.strip().toLowerCase(java.util.Locale.ROOT))) {
					out.add(new Offence(e.id(), here, "term '" + term + "' has no glossary entry"));
				}
			}
		}
		return out;
	}

	/** The board's coverage gates, by name, over this document's elements (elements.md §7.5). */
	private List<ElementCheckResult> coverage(List<Element> mine, UUID here, RunScope scope, ElementCheckPolicy policy,
			Map<UUID, List<Element>> definitions) {
		List<ElementCheckResult> out = new ArrayList<>();
		if (policy.coverage().isEmpty()) return out;
		List<String> names = new ArrayList<>(policy.coverage().keySet());
		java.util.Collections.sort(names);
		if (null == evaluator) {
			for (String name : names) {
				out.add(skip(COVERAGE_PREFIX + name, policy, "coverage gates need the CEL evaluator, which this server does not have"));
			}
			return out;
		}
		Map<String, List<Map<String, Object>>> incoming = new HashMap<>();
		List<Map<String, Object>> all = new ArrayList<>();
		List<Map.Entry<Element, ScopedDocument>> scoped = new ArrayList<>();
		for (ScopedDocument d : scope.documents()) {
			for (Element e : definitions.getOrDefault(d.release(), List.of())) {
				if (null == e.id()) continue;
				scoped.add(Map.entry(e, d));
				if (null != e.parent()) link(incoming, e.parent(), e.id(), "parent");
				for (Link l : e.traces()) if (null != l.target()) link(incoming, l.target(), e.id(), null == l.verb() ? "traces" : l.verb());
				for (String a : e.assumes()) link(incoming, a, e.id(), "assumes");
			}
		}
		for (Map.Entry<Element, ScopedDocument> x : scoped) all.add(activation(x.getKey(), x.getValue(), incoming));
		Map<String, Object> scopeMap = Map.of("elements", all, "families",
				null == scope.families() ? Map.of() : scope.families());
		ScopedDocument checked = scope.documents().stream().filter(d -> here.equals(d.release())).findFirst().orElseThrow();
		for (String name : names) {
			ElementCheckPolicy.CoverageGate gate = policy.coverage().get(name);
			String check = COVERAGE_PREFIX + name;
			List<Offence> offences = new ArrayList<>();
			for (Element e : mine) {
				ElementQueryEvaluator.Evaluation ev = evaluator.evaluate(gate, activation(e, checked, incoming), scopeMap);
				switch (ev.outcome()) {
					case SELECTED_FAIL -> offences.add(new Offence(e.id(), here,
							StringUtils.isNotBlank(gate.description()) ? gate.description() : "require is false"));
					case ERROR -> offences.add(new Offence(e.id(), here, "evaluation error: " + ev.message()));
					default -> { }
				}
			}
			// The gate's text rides on the result, so a changed gate is a changed report.
			String text = "select: " + gate.select() + "; require: " + gate.require();
			boolean blocking = policy.blocking().contains(check);
			out.add(offences.isEmpty() ? new ElementCheckResult(check, Result.PASS, blocking, text, List.of())
					: new ElementCheckResult(check, Result.FAIL, blocking, offences.size() + " offence(s); " + text, offences));
		}
		return out;
	}

	private static void link(Map<String, List<Map<String, Object>>> incoming, String to, String from, String verb) {
		incoming.computeIfAbsent(to, k -> new ArrayList<>()).add(Map.of("from", from, "verb", verb));
	}

	/** An element as a gate sees it; keys without a value are left out, so {@code has()} asks. */
	static Map<String, Object> activation(Element e, ScopedDocument in, Map<String, List<Map<String, Object>>> incoming) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", e.id());
		if (null != e.family()) m.put("family", e.family());
		if (null != e.title()) m.put("title", e.title());
		if (null != e.parent()) m.put("parent", e.parent());
		if (null != e.level()) m.put("level", e.level().longValue());
		List<Map<String, Object>> traces = new ArrayList<>();
		for (Link l : e.traces()) {
			Map<String, Object> t = new LinkedHashMap<>();
			if (null != l.verb()) t.put("verb", l.verb());
			if (null != l.target()) t.put("target", l.target());
			traces.add(t);
		}
		m.put("traces", traces);
		m.put("assumes", e.assumes());
		m.put("speculative", e.speculative());
		m.put("terms", e.terms());
		m.put("incoming", incoming.getOrDefault(e.id(), List.of()));
		if (null != in.specification()) m.put("document", in.specification().name());
		m.put("release", in.release().toString());
		return m;
	}

	/** sha256 of the report's canonical JSON with the digest left out: its identity. */
	public static String digest(ElementCheckReport report) {
		try {
			byte[] json = Utils.OM.writeValueAsBytes(report.withDigest(null));
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static ElementCheckResult result(String check, ElementCheckPolicy policy, List<Offence> offences) {
		boolean blocking = policy.blocking().contains(check);
		if (offences.isEmpty()) return new ElementCheckResult(check, Result.PASS, blocking, null, List.of());
		return new ElementCheckResult(check, Result.FAIL, blocking, offences.size() + " offence(s)", offences);
	}

	private static ElementCheckResult skip(String check, ElementCheckPolicy policy, String why) {
		return new ElementCheckResult(check, Result.SKIP, policy.blocking().contains(check), why, List.of());
	}

	private static List<Offence> idsFamily(List<Element> mine, UUID here, Map<String, String> families) {
		List<Offence> out = new ArrayList<>();
		Map<String, String> known = null == families ? Map.of() : families;
		for (Element e : mine) {
			String prefix = ElementIndexValidator.familyPrefix(e.id());
			if (null == prefix || !known.containsKey(prefix)) {
				out.add(new Offence(e.id(), here, (null == prefix ? e.id() + " has no family prefix"
						: prefix + " is not an element family on this board")));
			}
		}
		return out;
	}

	private static List<Offence> idsUnique(List<Element> mine, UUID here, Map<String, List<Defined>> defined) {
		List<Offence> out = new ArrayList<>();
		Set<String> reported = new LinkedHashSet<>();
		for (Element e : mine) {
			List<Defined> defs = defined.getOrDefault(e.id(), List.of());
			if (defs.size() > 1 && reported.add(e.id())) {
				Set<UUID> where = new LinkedHashSet<>();
				defs.forEach(d -> where.add(d.in().release()));
				out.add(new Offence(e.id(), here, e.id() + " is defined " + defs.size() + " times"
						+ (where.size() > 1 ? ", across releases " + where : " in this document")));
			}
		}
		return out;
	}

	private static List<Offence> fieldsMandatory(List<Element> mine, UUID here, Map<Integer, List<String>> mandatory) {
		List<Offence> out = new ArrayList<>();
		for (Element e : mine) {
			if (null == e.level()) continue;
			for (String attribute : mandatory.getOrDefault(e.level(), List.of())) {
				if (!carries(e, attribute)) {
					out.add(new Offence(e.id(), here, "a level " + e.level() + " element needs " + attribute));
				}
			}
		}
		return out;
	}

	private static boolean carries(Element e, String attribute) {
		return switch (attribute) {
			case "parent" -> null != e.parent();
			case "traces" -> !e.traces().isEmpty();
			case "assumes" -> !e.assumes().isEmpty();
			case "speculative" -> !e.speculative().isEmpty();
			default -> true;
		};
	}

	private static List<Offence> parentExists(List<Element> mine, UUID here, Map<String, List<Defined>> defined) {
		List<Offence> out = new ArrayList<>();
		for (Element e : mine) {
			if (null != e.parent() && !defined.containsKey(e.parent())) {
				out.add(new Offence(e.id(), here, e.id() + " → " + e.parent() + " not found"));
			}
		}
		return out;
	}

	private static List<Offence> verbsKnown(List<Element> mine, UUID here) {
		List<Offence> out = new ArrayList<>();
		for (Element e : mine) {
			for (Link l : e.traces()) {
				if (null == l.verb() || !RECOMMENDED_VERBS.contains(l.verb())) {
					out.add(new Offence(e.id(), here, "traces " + l.verb() + " " + l.target()
							+ ": not a recommended verb"));
				}
			}
		}
		return out;
	}

	/**
	 * Cycles through parent, decomposes and derives_from edges over the whole scope, reported when they
	 * pass through this document: a cycle entirely inside the inputs is the inputs' to fix.
	 */
	private static List<Offence> noCycles(List<Element> mine, UUID here, Map<String, List<Defined>> defined) {
		Map<String, List<String>> edges = new LinkedHashMap<>();
		for (List<Defined> defs : defined.values()) {
			for (Defined d : defs) {
				Element e = d.element();
				List<String> to = edges.computeIfAbsent(e.id(), k -> new ArrayList<>());
				if (null != e.parent()) to.add(e.parent());
				for (Link l : e.traces()) {
					if (null != l.verb() && HIERARCHY_VERBS.contains(l.verb()) && null != l.target()) to.add(l.target());
				}
			}
		}
		Set<String> mineIds = new LinkedHashSet<>();
		mine.forEach(e -> mineIds.add(e.id()));
		List<Offence> out = new ArrayList<>();
		for (List<String> component : stronglyConnected(edges)) {
			boolean cycle = component.size() > 1
					|| edges.getOrDefault(component.get(0), List.of()).contains(component.get(0));
			if (!cycle) continue;
			String anchor = component.stream().filter(mineIds::contains).findFirst().orElse(null);
			if (null == anchor) continue;
			out.add(new Offence(anchor, here, String.join(" → ", component) + " → " + component.get(0)
					+ " form a cycle"));
		}
		return out;
	}

	/**
	 * Tarjan's strongly connected components, iterative, in a deterministic order: nodes are visited
	 * in insertion order and each component is rotated to start at its earliest node.
	 */
	static List<List<String>> stronglyConnected(Map<String, List<String>> edges) {
		Map<String, Integer> index = new HashMap<>();
		Map<String, Integer> low = new HashMap<>();
		Set<String> onStack = new LinkedHashSet<>();
		List<String> stack = new ArrayList<>();
		List<List<String>> out = new ArrayList<>();
		Map<String, Integer> order = new HashMap<>();
		int n = 0;
		for (String v : edges.keySet()) order.put(v, n++);
		int[] counter = {0};
		for (String root : edges.keySet()) {
			if (index.containsKey(root)) continue;
			List<Object[]> work = new ArrayList<>();
			work.add(new Object[] {root, 0});
			while (!work.isEmpty()) {
				Object[] frame = work.get(work.size() - 1);
				String v = (String) frame[0];
				int i = (Integer) frame[1];
				if (i == 0 && !index.containsKey(v)) {
					index.put(v, counter[0]);
					low.put(v, counter[0]);
					counter[0]++;
					stack.add(v);
					onStack.add(v);
				}
				List<String> next = edges.getOrDefault(v, List.of());
				if (i < next.size()) {
					frame[1] = i + 1;
					String w = next.get(i);
					if (!index.containsKey(w)) {
						work.add(new Object[] {w, 0});
					} else if (onStack.contains(w)) {
						low.put(v, Math.min(low.get(v), index.get(w)));
					}
					continue;
				}
				work.remove(work.size() - 1);
				if (!work.isEmpty()) {
					String parent = (String) work.get(work.size() - 1)[0];
					low.put(parent, Math.min(low.get(parent), low.get(v)));
				}
				if (low.get(v).equals(index.get(v))) {
					List<String> component = new ArrayList<>();
					String w;
					do {
						w = stack.remove(stack.size() - 1);
						onStack.remove(w);
						component.add(w);
					} while (!w.equals(v));
					java.util.Collections.reverse(component);
					int first = 0;
					for (int k = 1; k < component.size(); k++) {
						if (order.getOrDefault(component.get(k), Integer.MAX_VALUE)
								< order.getOrDefault(component.get(first), Integer.MAX_VALUE)) first = k;
					}
					List<String> rotated = new ArrayList<>(component.subList(first, component.size()));
					rotated.addAll(component.subList(0, first));
					out.add(rotated);
				}
			}
		}
		return out;
	}

	private static List<Offence> noOrphans(List<Element> mine, UUID here, List<ScopedDocument> documents,
			Map<UUID, List<Element>> definitions, List<String> orphans) {
		Set<String> verifiedBySomeone = new LinkedHashSet<>();
		Set<String> verifiesSomething = new LinkedHashSet<>();
		for (ScopedDocument d : documents) {
			for (Element e : definitions.getOrDefault(d.release(), List.of())) {
				for (Link l : e.traces()) {
					if ("verifies".equals(l.verb())) {
						verifiesSomething.add(e.id());
						verifiedBySomeone.add(l.target());
					} else if ("is_verified_by".equals(l.verb())) {
						verifiedBySomeone.add(e.id());
						verifiesSomething.add(l.target());
					}
				}
			}
		}
		List<Offence> out = new ArrayList<>();
		for (Element e : mine) {
			if ("test".equals(e.family()) && orphans.contains("test") && !verifiesSomething.contains(e.id())) {
				out.add(new Offence(e.id(), here, "test " + e.id() + " verifies nothing"));
			}
			if ("requirement".equals(e.family()) && orphans.contains("requirement")
					&& !verifiedBySomeone.contains(e.id())) {
				out.add(new Offence(e.id(), here, "requirement " + e.id() + " is verified by nothing"));
			}
		}
		return out;
	}

	private static List<Offence> assumesLinked(List<Element> mine, UUID here, Map<String, List<Defined>> defined) {
		List<Offence> out = new ArrayList<>();
		for (Element e : mine) {
			for (String a : e.assumes()) {
				if (!defined.containsKey(a)) out.add(new Offence(e.id(), here, e.id() + " assumes " + a + ", not found"));
			}
		}
		return out;
	}

	/**
	 * An element citing an input below the floor the assignment binds must say so under
	 * {@code speculative:}, and may list only ids that exist: the list is what a later round checks
	 * when the input matures.
	 */
	private static List<Offence> speculativeRecorded(List<Element> mine, UUID here,
			Map<String, List<Defined>> defined, Map<RearmSpecificationType, ReleaseLifecycle> floors) {
		List<Offence> out = new ArrayList<>();
		for (Element e : mine) {
			Set<String> cited = new LinkedHashSet<>();
			if (null != e.parent()) cited.add(e.parent());
			e.traces().forEach(l -> { if (null != l.target()) cited.add(l.target()); });
			cited.addAll(e.assumes());
			for (String c : cited) {
				List<Defined> defs = defined.getOrDefault(c, List.of());
				if (defs.isEmpty() || defs.stream().anyMatch(d -> here.equals(d.in().release()))) continue;
				ScopedDocument in = defs.get(0).in();
				ReleaseLifecycle floor = floors.get(in.specification());
				if (null == floor || AgentTaskInputService.maturity(in.lifecycle()) >= AgentTaskInputService.maturity(floor)) {
					continue;
				}
				if (!e.speculative().contains(c)) {
					out.add(new Offence(e.id(), here, e.id() + " cites " + c + " from " + in.specification() + " at "
							+ in.lifecycle() + ", below the floor " + floor + "; list it under speculative:"));
				}
			}
			for (String s : e.speculative()) {
				if (!defined.containsKey(s)) {
					out.add(new Offence(e.id(), here, e.id() + " lists " + s + " as speculative, but " + s
							+ " is defined nowhere in scope"));
				}
			}
		}
		return out;
	}
}
