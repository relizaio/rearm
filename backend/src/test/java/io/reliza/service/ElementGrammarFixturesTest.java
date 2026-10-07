/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.AgentBoardData;
import io.reliza.model.ElementCheckReport;
import io.reliza.model.ElementCheckReport.ElementCheckResult;
import io.reliza.model.ElementIndex;
import io.reliza.model.ElementIndex.Element;
import io.reliza.model.ElementIndex.Link;
import io.reliza.model.ElementIndex.Reference;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.service.ElementCheckCatalogueService.RunScope;
import io.reliza.service.ElementCheckCatalogueService.ScopedDocument;

/**
 * Grammar 1.2 (task RD4-6): the fixtures rearm-cli ships under {@code internal/elements/testdata/grammar-1.2}, kept
 * here byte for byte. Each names a markdown file, the type it is published as, a board override, and the index the
 * CLI's extractor emits for it (its own test compares byte for byte). This side checks that the server agrees: its
 * validator keeps the CLI's definitions and references apart the same way, sorts an index that did not sort them the
 * same way, and the checks the manifest names come out as it says.
 */
class ElementGrammarFixturesTest {

	private static final String DIR = "/elements/grammar-1.2/";
	private static final UUID TASK = UUID.randomUUID();

	private record Fixture(String name, String file, RearmSpecificationType spec, ElementIndex index,
			AgentBoardData board, Map<String, Object> checks, Map<String, Object> offences) {}

	private static String read(String name) throws IOException {
		try (InputStream in = ElementGrammarFixturesTest.class.getResourceAsStream(DIR + name)) {
			assertNotNull(in, "fixture " + name);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** A board with the fixture's declared elementFamilies, read the way a board file is. */
	private static AgentBoardData board(Map<String, Object> declared) throws Exception {
		AgentBoardData bd = new AgentBoardData();
		if (null == declared) return bd;
		AgentBoardService.DeclaredFamilies families = AgentBoardService.elementFamilies(declared);
		bd.setElementFamilies(families.names());
		bd.setElementFamilyDefinedIn(families.definedIn());
		return bd;
	}

	@SuppressWarnings("unchecked")
	private static List<Fixture> fixtures() throws Exception {
		List<Fixture> out = new ArrayList<>();
		for (Map<String, Object> f : (List<Map<String, Object>>) Utils.OM.readValue(read("manifest.json"), List.class)) {
			out.add(new Fixture((String) f.get("name"), (String) f.get("file"),
					RearmSpecificationType.valueOf((String) f.get("specification")),
					Utils.OM.readValue(read((String) f.get("index")).strip(), ElementIndex.class),
					board((Map<String, Object>) f.get("elementFamilies")), (Map<String, Object>) f.get("checks"),
					(Map<String, Object>) f.get("offences")));
		}
		return out;
	}

	private static ElementIndex validated(Fixture f, ElementIndex index) {
		return ElementIndexValidator.validate(index, f.board().getEffectiveElementFamilies(),
				f.board().getEffectiveElementFamilyDefinedIn(), f.spec(), Set.of(), Set.of());
	}

	private static List<String> ids(ElementIndex ix) {
		return ix.elements().stream().map(Element::id).toList();
	}

	private static List<String> ids(List<Element> elements) {
		return elements.stream().map(Element::id).toList();
	}

	private static List<String> refs(ElementIndex ix) {
		return ix.references().stream().map(Reference::id).toList();
	}

	@Test
	void theFixturesCoverEveryCaseTheDesignLists() throws Exception {
		List<Fixture> all = fixtures();
		assertEquals(10, all.size(), "the manifest lists ten fixtures");
		for (Fixture f : all) {
			assertEquals(ElementIndex.GRAMMAR_VERSION_TYPED, f.index().grammarVersion(), f.name());
			assertTrue(read(f.file()).length() > 0, f.name());
		}
	}

	@Test
	void theServerKeepsWhatTheCliDefinedAndReferenced() throws Exception {
		for (Fixture f : fixtures()) {
			ElementIndex v = validated(f, f.index());
			assertEquals(ids(f.index()), ids(v), f.name() + ": definitions");
			assertEquals(refs(f.index()), refs(v), f.name() + ": references");
			for (int i = 0; i < v.elements().size(); i++) {
				assertEquals(f.index().elements().get(i).family(), v.elements().get(i).family(), f.name() + ": family");
				assertEquals(f.index().elements().get(i).terms(), v.elements().get(i).terms(), f.name() + ": terms");
			}
			for (int i = 0; i < v.references().size(); i++) {
				assertEquals(f.index().references().get(i).family(), v.references().get(i).family(), f.name());
				assertEquals(f.index().references().get(i).line(), v.references().get(i).line(), f.name());
			}
		}
	}

	/**
	 * The agreement the other way: an index that sent every id heading as an element (the CLI read the board's lists
	 * differently, or not at all) comes out of the server sorted exactly as the CLI's extractor sorts it.
	 */
	@Test
	void anIndexThatDefinesEveryHeadingIsSortedTheWayTheCliSortsIt() throws Exception {
		for (Fixture f : fixtures()) {
			List<Element> all = new ArrayList<>(f.index().elements());
			for (Reference r : f.index().references()) {
				all.add(new Element(r.id(), null, r.title(), null, null, List.of(), List.of(), List.of(), "d-" + r.id(),
						r.line(), List.of()));
			}
			all.sort(java.util.Comparator.comparing(Element::line));
			ElementIndex flat = new ElementIndex(ElementIndex.GRAMMAR_VERSION_TYPED, all, List.of(), null);
			ElementIndex v = validated(f, flat);
			assertEquals(ids(f.index()), ids(v), f.name() + ": definitions");
			assertEquals(refs(f.index()), refs(v), f.name() + ": references");
		}
	}

	@Test
	void theNamedElementChecksComeOutAsTheManifestSays() throws Exception {
		for (Fixture f : fixtures()) {
			UUID release = UUID.randomUUID();
			ScopedDocument doc = new ScopedDocument(release, f.spec(), ReleaseLifecycle.DRAFT, validated(f, f.index()));
			ElementCheckReport r = new ElementCheckCatalogueService().run(new RunScope(TASK, release, List.of(doc),
					f.board().getEffectiveElementFamilies(), f.board().getEffectiveElementCheckPolicy(), null,
					f.board().getEffectiveElementFamilyDefinedIn()));
			for (Map.Entry<String, Object> c : f.checks().entrySet()) {
				String check = c.getKey();
				ElementCheckResult result = r.results().stream().filter(x -> check.equals(x.check())).findFirst().orElseThrow();
				assertEquals(c.getValue(), result.result().name(), f.name() + ": " + check + " " + result.offences());
				if (null != f.offences() && f.offences().containsKey(check)) {
					assertEquals(((Number) f.offences().get(check)).intValue(), result.offences().size(),
							f.name() + ": " + check + " " + result.offences());
				}
			}
		}
	}

	/** A test plan and a report on one board: the plan owns the test ids, and the report references them. */
	@Test
	void aPlanOwnsTheTestIdsItsReportRepeats() throws Exception {
		Fixture report = fixtures().stream().filter(f -> "report-t-heading.md".equals(f.file())
				&& null == f.board().getElementFamilyDefinedIn()).findFirst().orElseThrow();
		Fixture plan = fixtures().stream().filter(f -> "plan.md".equals(f.file())).findFirst().orElseThrow();
		UUID reportRelease = UUID.randomUUID();
		UUID planRelease = UUID.randomUUID();
		AgentBoardData bd = new AgentBoardData();
		ScopedDocument r = new ScopedDocument(reportRelease, RearmSpecificationType.BOARD_TEST_REPORT, ReleaseLifecycle.DRAFT,
				validated(report, report.index()));
		ScopedDocument p = new ScopedDocument(planRelease, RearmSpecificationType.TEST_PLAN, ReleaseLifecycle.ASSEMBLED,
				validated(plan, plan.index()));
		// Plan and report both carry T-1. Without the plan the report owns it, and its T-1 verifies nothing.
		RunScope alone = new RunScope(TASK, reportRelease, List.of(r), bd.getEffectiveElementFamilies(), null, null,
				bd.getEffectiveElementFamilyDefinedIn());
		assertEquals(List.of("T-1"), ids(ElementCheckCatalogueService.definitions(alone).get(reportRelease)));
		assertEquals("FAIL", result(alone, ElementCheckCatalogueService.TESTS_NO_ORPHANS));
		// With the plan in scope the plan owns T-1: no duplicate, and the report is not judged for it.
		RunScope both = new RunScope(TASK, reportRelease, List.of(r, p), bd.getEffectiveElementFamilies(), null, null,
				bd.getEffectiveElementFamilyDefinedIn());
		assertEquals(List.of(), ids(ElementCheckCatalogueService.definitions(both).get(reportRelease)));
		assertEquals(List.of("T-1"), ids(ElementCheckCatalogueService.definitions(both).get(planRelease)));
		assertEquals("PASS", result(both, ElementCheckCatalogueService.IDS_UNIQUE));
		assertEquals("PASS", result(both, ElementCheckCatalogueService.TESTS_NO_ORPHANS));
		// The plan checked in the same scope still owns and judges its T-1.
		RunScope planChecked = new RunScope(TASK, planRelease, List.of(p, r), bd.getEffectiveElementFamilies(), null,
				null, bd.getEffectiveElementFamilyDefinedIn());
		assertEquals("PASS", result(planChecked, ElementCheckCatalogueService.IDS_UNIQUE));
	}

	/** Grammar 1 and 1.1 are read as before: a note's T heading stays a definition, at publish and in the checks. */
	@Test
	void anOlderIndexIsReadAsBefore() {
		AgentBoardData bd = new AgentBoardData();
		Element t1 = new Element("T-1", null, "the flaky publish test is fixed", null, null,
				List.of(new Link("verifies", "REQ-1")), List.of(), List.of(), "d", 3, List.of());
		ElementIndex old = new ElementIndex(ElementIndex.GRAMMAR_VERSION_TERMS, List.of(t1), List.of(), null);
		ElementIndex v = ElementIndexValidator.validate(old, bd.getEffectiveElementFamilies(),
				bd.getEffectiveElementFamilyDefinedIn(), RearmSpecificationType.DETAILED_DESIGN, Set.of(), Set.of());
		assertEquals(List.of("T-1"), ids(v));
		assertTrue(v.references().isEmpty());
		UUID release = UUID.randomUUID();
		RunScope scope = new RunScope(TASK, release, List.of(new ScopedDocument(release,
				RearmSpecificationType.DETAILED_DESIGN, ReleaseLifecycle.DRAFT, v)), bd.getEffectiveElementFamilies(),
				null, null, bd.getEffectiveElementFamilyDefinedIn());
		assertEquals(List.of("T-1"), ids(ElementCheckCatalogueService.definitions(scope).get(release)));
	}

	/**
	 * Every type but BOARD_ELEMENT_CHECK_REPORT and BOARD_INVESTIGATION_REPORT (references only, task RD4-12) defines something by
	 * default.
	 */
	@Test
	void theDefaultsCoverEveryType() {
		Map<String, List<RearmSpecificationType>> lists = new AgentBoardData().getEffectiveElementFamilyDefinedIn();
		for (RearmSpecificationType t : RearmSpecificationType.values()) {
			assertEquals(RearmSpecificationType.BOARD_ELEMENT_CHECK_REPORT != t && RearmSpecificationType.BOARD_INVESTIGATION_REPORT != t,
					io.reliza.model.ElementFamilies.definesAnything(lists, t), t.name());
		}
		assertEquals(List.of(RearmSpecificationType.TEST_PLAN, RearmSpecificationType.BOARD_TEST_REPORT), lists.get("T"));
		assertEquals(List.of(RearmSpecificationType.BOARD_QUESTIONS), lists.get("Q"));
		assertEquals(List.of(RearmSpecificationType.BOARD_REVIEW_ITEMS), lists.get("F"));
		assertFalse(lists.get("GLOSS").contains(RearmSpecificationType.BOARD_TEST_REPORT));
		assertEquals(RearmSpecificationType.GLOSSARY, lists.get("GLOSS").get(0));
	}

	private static String result(RunScope scope, String check) {
		return new ElementCheckCatalogueService().run(scope).results().stream().filter(x -> check.equals(x.check()))
				.findFirst().orElseThrow().result().name();
	}
}
