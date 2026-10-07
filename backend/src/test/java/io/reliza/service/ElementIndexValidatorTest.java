/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.AgentBoardData;
import io.reliza.model.ElementCheckPolicy;
import io.reliza.model.ElementCheckReport;
import io.reliza.model.ElementCheckReport.ElementCheckResult;
import io.reliza.model.ElementIndex;
import io.reliza.model.ElementIndex.Element;
import io.reliza.model.ElementIndex.Link;
import io.reliza.model.ElementIndex.Warning;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData.DocumentRef;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.service.ElementCheckCatalogueService.RunScope;
import io.reliza.service.ElementCheckCatalogueService.ScopedDocument;

/** The element index checks (gaps §2.A, task e200cb32, design test 8), and old documents reading as before (test 10). */
public class ElementIndexValidatorTest {

	private static final Map<String, String> FAMILIES = new AgentBoardData().getEffectiveElementFamilies();

	private static Element el(String id, String parent, List<Link> traces, List<String> assumes) {
		return new Element(id, null, "title of " + id, parent, null, traces, assumes, List.of(), "d", 1);
	}

	private static List<String> codes(ElementIndex ix, String id) {
		return ix.warnings().stream().filter(w -> id.equals(w.elementId())).map(Warning::code).toList();
	}

	@Test
	public void duplicatesAndUnknownFamiliesAreWarnings() {
		ElementIndex in = new ElementIndex("1", List.of(el("REQ-1", null, null, null), el("REQ-1", null, null, null),
				el("ZZ-1", null, null, null)), List.of(), "abc");
		ElementIndex out = ElementIndexValidator.validate(in, FAMILIES, Set.of());
		assertEquals(List.of("DUPLICATE_ID"), codes(out, "REQ-1"));
		assertEquals(List.of("UNKNOWN_FAMILY"), codes(out, "ZZ-1"));
		assertEquals("requirement", out.elements().get(0).family(), "the family comes from the board");
		assertNull(out.elements().get(2).family());
		assertEquals("abc", out.digest());
	}

	@Test
	public void aParentResolvesInTheDocumentOrThroughAnInput() {
		ElementIndex in = new ElementIndex("1", List.of(el("REQ-1", null, null, null), el("REQ-2", "REQ-1", null, null),
				el("FN-1", "REQ-9", null, null), el("FN-2", "PBS-3", null, null)), List.of(), null);
		ElementIndex out = ElementIndexValidator.validate(in, FAMILIES, Set.of("PBS-3"));
		assertEquals(List.of(), codes(out, "REQ-2"), "in this document");
		assertEquals(List.of(), codes(out, "FN-2"), "in a pinned input's latest release");
		assertEquals(List.of("UNRESOLVED_PARENT"), codes(out, "FN-1"));
	}

	@Test
	public void linksAndAssumptionsResolveTheSameWay() {
		ElementIndex in = new ElementIndex("1", List.of(el("FN-1", null,
				List.of(new Link("satisfies", "REQ-1"), new Link("is_verified_by", "TEST-4")), List.of("ADR-2", "ADR-9"))),
				List.of(), null);
		ElementIndex out = ElementIndexValidator.validate(in, FAMILIES, Set.of("REQ-1", "ADR-2"));
		assertEquals(List.of("UNRESOLVED_TARGET", "UNRESOLVED_TARGET"), codes(out, "FN-1"));
		assertTrue(out.warnings().stream().anyMatch(w -> w.message().contains("is_verified_by TEST-4")));
		assertTrue(out.warnings().stream().anyMatch(w -> w.message().contains("assumes ADR-9")));
	}

	@Test
	public void theParsersMalformedLinesAreKeptAndItsOtherCodesAreRecomputed() {
		ElementIndex in = new ElementIndex("1", List.of(el("REQ-1", null, null, null)), List.of(
				new Warning("MALFORMED_ATTRIBUTE", "REQ-1", "level: two is not an integer"),
				new Warning("DUPLICATE_ID", "REQ-1", "a stale claim from the client")), null);
		ElementIndex out = ElementIndexValidator.validate(in, FAMILIES, Set.of());
		assertEquals(List.of("MALFORMED_ATTRIBUTE"), codes(out, "REQ-1"), "the server decides duplicates itself");
	}

	@Test
	public void familyPrefixes() {
		assertEquals("REQ", ElementIndexValidator.familyPrefix("REQ-F-012"));
		assertEquals("T", ElementIndexValidator.familyPrefix("T-3"));
		assertNull(ElementIndexValidator.familyPrefix("req-1"));
		assertNull(ElementIndexValidator.familyPrefix("-1"));
	}

	/**
	 * The wire contract with the CLI: this is rearm-cli's canonical output for its
	 * internal/elements/testdata/nested.md, byte for byte. It must digest to what the CLI computed and
	 * read into the server's model with nothing lost.
	 */
	@Test
	public void theCliCanonicalFormReadsIntoTheModel() throws Exception {
		byte[] wire;
		try (var in = getClass().getResourceAsStream("/elements/cli-grammar-v1-nested.json")) {
			wire = in.readAllBytes();
		}
		assertEquals("d079e908aded7e6f5a9d974aecf629940bcea8a78ca214e63b6fcb22e05390e9",
				java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(wire)),
				"the digest is over the bytes the CLI sent");
		ElementIndex parsed = Utils.OM.readValue(wire, ElementIndex.class);
		assertEquals("1", parsed.grammarVersion());
		assertEquals(List.of("REQ-1", "REQ-1.1", "REQ-1.1.a", "FN-1"), parsed.elements().stream().map(Element::id).toList());
		Element nested = parsed.elements().get(1);
		assertEquals("REQ-1", nested.parent());
		assertEquals(List.of(new Link("derives_from", "CONOPS-3"), new Link("is_verified_by", "TEST-7"),
				new Link("is_verified_by", "TEST-8")), nested.traces());
		assertEquals(List.of("ADR-2"), nested.assumes());
		assertEquals(Integer.valueOf(1), parsed.elements().get(0).level());
		assertEquals(Integer.valueOf(5), parsed.elements().get(0).line());

		ElementIndex checked = ElementIndexValidator.validate(parsed, FAMILIES, Set.of());
		assertEquals(4, checked.warnings().stream().filter(w -> ElementIndex.UNRESOLVED_TARGET.equals(w.code())).count(),
				"CONOPS-3, TEST-7, TEST-8 and ADR-2 are outside the document and there are no inputs");
		assertTrue(checked.warnings().stream().noneMatch(w -> ElementIndex.UNRESOLVED_PARENT.equals(w.code())),
				"REQ-1.1 and REQ-1.1.a's parents are in the document");
	}

	@Test
	public void anOldDocumentRefWithoutElementsReadsAsBefore() throws Exception {
		DocumentRef old = Utils.OM.readValue("{\"specification\":\"ARCHITECTURE\",\"path\":\"design/a.md\","
				+ "\"digest\":\"abc\",\"round\":1}", DocumentRef.class);
		assertEquals("design/a.md", old.path());
		assertNull(old.elements());
	}

	// ---------- a task key is not an element (task RD2-28) ----------

	@Test
	public void aTaskKeyIsDroppedAndDoesNotFailIdsFamily() {
		// What an older CLI sends for "# RD2-1 — Title" over "## REQ-1 ..." and a real unknown family.
		ElementIndex in = new ElementIndex("1", List.of(el("RD2-1", null, null, null), el("REQ-1", "RD2-1", null, null),
				el("RD-4", null, null, null), el("REQX-1", null, null, null)),
				List.of(new Warning(ElementIndex.UNKNOWN_FAMILY, "RD2-1", "sent by the CLI"),
						new Warning(ElementIndex.MALFORMED_ATTRIBUTE, "RD2-1", "line 2: bad level")), "abc");
		ElementIndex out = ElementIndexValidator.validate(in, FAMILIES, Set.of(), Set.of("RD2", "RD"));
		assertEquals(List.of("REQ-1", "REQX-1"), out.elements().stream().map(Element::id).toList(),
				"the current prefix and one held before are both task keys");
		assertNull(out.elements().get(0).parent(), "a task-key heading lends no parent");
		assertEquals(List.of(), codes(out, "RD2-1"), "no warning is kept for a task key");
		assertEquals(List.of(), codes(out, "REQ-1"), "and its orphaned parent is not unresolved");
		assertEquals(List.of("UNKNOWN_FAMILY"), codes(out, "REQX-1"), "a real unknown family still warns");
		assertEquals("abc", out.digest());

		UUID release = UUID.randomUUID();
		ElementCheckReport report = new ElementCheckCatalogueService().run(new RunScope(UUID.randomUUID(), release,
				List.of(new ScopedDocument(release, RearmSpecificationType.ARCHITECTURE, ReleaseLifecycle.DRAFT, out)),
				FAMILIES, ElementCheckPolicy.DEFAULTS, null));
		ElementCheckResult family = report.results().stream()
				.filter(r -> ElementCheckCatalogueService.IDS_FAMILY.equals(r.check())).findFirst().orElseThrow();
		assertEquals(List.of("REQX-1"), family.offences().stream().map(o -> o.elementId()).toList(),
				"ids.family fails on REQX-1 only, never on the task key");

		assertEquals(4, ElementIndexValidator.validate(in, FAMILIES, Set.of()).elements().size(),
				"the three-argument form reserves nothing, as before");
	}

	@Test
	public void theBoardReservesItsCurrentAndFormerTaskPrefixes() {
		AgentBoardData board = new AgentBoardData();
		board.setTaskPrefix("RD2");
		board.setTaskPrefixHistory(new java.util.ArrayList<>(List.of("RD", "RD2")));
		assertEquals(Set.of("RD2", "RD"), AgentDocumentService.reservedTaskPrefixes(board));
		assertEquals(Set.of(), AgentDocumentService.reservedTaskPrefixes(new AgentBoardData()));
	}
}
