/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ElementFamilies;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.DeclarativeConfigService.Action;
import io.reliza.service.DeclarativeConfigService.ApplyResult;
import io.reliza.ws.oss.TestInitializer;

/**
 * A board's element families with {@code definedIn} (grammar 1.2, task RD4-6): declared in the board file, stored,
 * served as effective entries, exported in the declared form, re-applied unchanged, seen as a change when only the
 * lists move (the apply's drift), and refused when a list names no document type.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {io.reliza.ws.App.class})
@SuppressWarnings("unchecked")
public class BoardElementFamiliesDefinedInIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor PERSON = AgentActor.ofUser(UUID.randomUUID(), "operator");

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private ComponentService componentService;

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	private record Org(UUID uuid, String target) {}

	private Org org() throws RelizaException {
		Organization o = testInitializer.obtainOrganization();
		String target = "families-node-" + UUID.randomUUID();
		componentService.createComponent(target, o.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		return new Org(o.getUuid(), target);
	}

	private static Map<String, Object> board(String name, Object... more) {
		Map<String, Object> m = map("kind", "BOARD", "version", 1, "name", name);
		m.putAll(map(more));
		return m;
	}

	private static Map<String, Object> designer() {
		return map("name", "designer", "prompt", "design it",
				"producesOutputs", List.of(map("specification", "ARCHITECTURE", "scope", "TASK", "required", true)));
	}

	private ApplyResult apply(Org o, Map<String, Object> spec, boolean dryRun) throws RelizaException {
		return agentBoardService.applyBoard(o.uuid(), AgentBoardService.boardSpecFromInput(spec), dryRun, null, PERSON, WU);
	}

	private AgentBoardData boardNamed(Org o, String name) {
		return agentBoardService.listByOrg(o.uuid()).stream().filter(b -> name.equals(b.getName())).findFirst()
				.orElse(null);
	}

	private static String messages(ApplyResult r) {
		return r.getChanges().stream().map(c -> String.valueOf(c.getMessage())).reduce("", String::concat);
	}

	private static ElementFamilies.Entry entry(AgentBoardData b, String prefix) {
		return b.getEffectiveElementFamilyEntries().stream().filter(e -> prefix.equals(e.prefix())).findFirst().orElseThrow();
	}

	@Test
	public void aBoardFileDeclaresWhereItsFamiliesAreDefined() throws RelizaException {
		Org o = org();
		String name = "families-" + UUID.randomUUID();
		Map<String, Object> declared = map(
				"T", map("definedIn", List.of("TEST_PLAN")),
				"SAF", "safety",
				"RISK", map("family", "risk", "definedIn", List.of()));
		ApplyResult r = apply(o, board(name, "target", o.target(), "elementFamilies", declared,
				"roles", List.of(designer())), false);
		assertEquals(0, r.getErrors(), r.getChanges().toString());
		AgentBoardData b = boardNamed(o, name);

		// Stored: the names the file gives, and the lists it sets.
		assertEquals(Map.of("SAF", "safety", "RISK", "risk"), b.getElementFamilies());
		assertEquals(Map.of("T", List.of(RearmSpecificationType.TEST_PLAN), "RISK", List.of()), b.getElementFamilyDefinedIn());
		// Effective: a set list wins, an absent one is the family's default, a family of the board's own
		// without a list is defined in the prose types, and an empty list defines nowhere.
		assertEquals(List.of(RearmSpecificationType.TEST_PLAN), entry(b, "T").definedIn());
		assertEquals(List.of(RearmSpecificationType.TEST_PLAN, RearmSpecificationType.BOARD_TEST_REPORT), entry(b, "TEST").definedIn());
		assertEquals(ElementFamilies.PROSE_TYPES, entry(b, "SAF").definedIn());
		assertEquals(List.of(), entry(b, "RISK").definedIn());
		assertEquals("risk", entry(b, "RISK").family());
		assertEquals("test", entry(b, "T").family(), "a default prefix keeps its family when the entry names none");

		// Export: the declared form -- a name, or {family, definedIn} -- and a re-apply changes nothing.
		Map<String, Object> exported = Utils.OM.convertValue(agentBoardService.exportBoard(b.getUuid()), Map.class);
		assertEquals(map("SAF", "safety",
				"RISK", map("family", "risk", "definedIn", List.of()),
				"T", map("family", "test", "definedIn", List.of("TEST_PLAN"))), exported.get("elementFamilies"));
		ApplyResult again = apply(o, exported, false);
		assertEquals(0, again.getErrors(), again.getChanges().toString());
		assertTrue(again.getChanges().stream().allMatch(c -> Action.UNCHANGED == c.getAction()), again.getChanges().toString());

		// Drift: a file that moves only a list is a change of elementFamilies.
		ApplyResult moved = apply(o, board(name, "elementFamilies", map("T", map("definedIn", List.of("BOARD_TEST_REPORT")),
				"SAF", "safety", "RISK", map("family", "risk", "definedIn", List.of()))), true);
		assertEquals(0, moved.getErrors(), moved.getChanges().toString());
		assertTrue(moved.getChanges().stream().anyMatch(c -> c.getFields().contains("elementFamilies")),
				moved.getChanges().toString());

		// Declared null restores the defaults, lists included.
		apply(o, board(name, "elementFamilies", null), false);
		AgentBoardData cleared = boardNamed(o, name);
		assertNull(cleared.getElementFamilyDefinedIn());
		assertEquals(List.of(RearmSpecificationType.TEST_PLAN, RearmSpecificationType.BOARD_TEST_REPORT),
				entry(cleared, "T").definedIn());
	}

	@Test
	public void aListThatNamesNoDocumentTypeIsRefusedAndNothingIsWritten() throws RelizaException {
		Org o = org();
		String name = "families-" + UUID.randomUUID();
		assertEquals(0, apply(o, board(name, "target", o.target(), "roles", List.of(designer())), false).getErrors());
		for (Object bad : List.of(List.of("NOPE"), List.of("BOARD_ELEMENT_CHECK_REPORT"), List.of("TEST_PLAN", "TEST_PLAN"), "TEST_PLAN")) {
			ApplyResult r = apply(o, board(name, "elementFamilies", map("T", map("definedIn", bad))), false);
			assertTrue(r.getErrors() > 0, bad + ": " + r.getChanges());
			assertTrue(messages(r).contains("elementFamilies: T"), messages(r));
		}
		ApplyResult noFamily = apply(o, board(name, "elementFamilies", map("RISK", map("definedIn", List.of()))), false);
		assertTrue(messages(noFamily).contains("elementFamilies: RISK names no family"), messages(noFamily));
		ApplyResult unknownKey = apply(o, board(name, "elementFamilies", map("T", map("defined", List.of()))), false);
		assertTrue(messages(unknownKey).contains("an entry is a family name, or {family, definedIn}"), messages(unknownKey));
		assertNull(boardNamed(o, name).getElementFamilyDefinedIn(), "a refused file writes nothing");
	}

	/** The same declared form through the board input (create and update), as the UI and the API send it. */
	@Test
	public void theBoardInputTakesTheSameEntries() throws RelizaException {
		Org o = org();
		String name = "families-" + UUID.randomUUID();
		assertEquals(0, apply(o, board(name, "target", o.target(), "roles", List.of(designer())), false).getErrors());
		AgentBoardData b = boardNamed(o, name);
		AgentBoardData set = agentBoardService.setElementFamilies(b.getUuid(),
				map("Q", map("definedIn", List.of("BOARD_QUESTIONS", "ARCHITECTURE"))), WU);
		assertEquals(List.of(RearmSpecificationType.BOARD_QUESTIONS, RearmSpecificationType.ARCHITECTURE),
				entry(set, "Q").definedIn());
		assertEquals(map("Q", map("family", "question", "definedIn", List.of("BOARD_QUESTIONS", "ARCHITECTURE"))),
				set.getDeclaredElementFamilies());
		assertThrows(RelizaException.class, () -> agentBoardService.setElementFamilies(b.getUuid(),
				map("Q", map("definedIn", List.of("SOMETHING"))), WU));
		AgentBoardData restored = agentBoardService.setElementFamilies(b.getUuid(), map(), WU);
		assertNull(restored.getDeclaredElementFamilies());
		assertEquals(List.of(RearmSpecificationType.BOARD_QUESTIONS), entry(restored, "Q").definedIn());
	}
}
