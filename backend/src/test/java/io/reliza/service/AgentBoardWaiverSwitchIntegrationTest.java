/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
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
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.service.DeclarativeConfigService.Action;
import io.reliza.service.DeclarativeConfigService.ApplyResult;
import io.reliza.service.DeclarativeConfigService.Change;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The board switch {@code settings.architectCheckpointWaiver} (task TPF-152, W6, 4.5; T-38 a-d, f, g): a declarative
 * apply changes its effective value only from a person (the D11 person rule), a file that repeats the stored value or
 * leaves the key out passes under any actor, and the board form sets and restores it. The key-kind half (e) is
 * {@code ws/BoardWaiverSwitchApplyKeyKindIntegrationTest}.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
@SuppressWarnings("unchecked")
public class AgentBoardWaiverSwitchIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor PERSON = AgentActor.ofUser(UUID.randomUUID(), "operator");
	private static final AgentActor SYSTEM = AgentActor.system("declarative apply");
	private static final String KEY = "architectCheckpointWaiver";

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private ComponentService componentService;

	private record Org(UUID uuid, String target) {}

	private Org org() throws RelizaException {
		Organization o = testInitializer.obtainOrganization();
		String target = "ws-node-" + UUID.randomUUID();
		componentService.createComponent(target, o.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		return new Org(o.getUuid(), target);
	}

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	private static Map<String, Object> board(String name, Object... more) {
		Map<String, Object> m = map("kind", "BOARD", "version", 1, "name", name);
		m.putAll(map(more));
		return m;
	}

	private static Map<String, Object> settings(Boolean value) {
		Map<String, Object> s = new HashMap<>();
		s.put(KEY, value);
		return s;
	}

	private ApplyResult apply(Org o, Map<String, Object> spec, AgentActor actor) throws RelizaException {
		return agentBoardService.applyBoard(o.uuid(), AgentBoardService.boardSpecFromInput(spec), false, null, actor, WU);
	}

	private AgentBoardData boardNamed(Org o, String name) {
		return agentBoardService.listByOrg(o.uuid()).stream().filter(b -> name.equals(b.getName())).findFirst()
				.orElseThrow();
	}

	private boolean exists(Org o, String name) {
		return agentBoardService.listByOrg(o.uuid()).stream().anyMatch(b -> name.equals(b.getName()));
	}

	/** A board a person applied, with the switch as given. */
	private String boardWith(Org o, Boolean value) throws RelizaException {
		String name = "ws-" + UUID.randomUUID();
		ApplyResult r = apply(o, board(name, "description", "plain", "target", o.target(), "settings", settings(value)),
				PERSON);
		assertEquals(0, r.getErrors(), r.getChanges().toString());
		assertEquals(value, boardNamed(o, name).getArchitectCheckpointWaiver());
		return name;
	}

	private static void refusedOnTheBoard(ApplyResult r, String board) {
		assertTrue(r.getErrors() > 0, r.getChanges().toString());
		List<Change> errors = r.getChanges().stream().filter(c -> Action.ERROR == c.getAction()).toList();
		Change c = errors.stream().filter(e -> board.equals(e.getName())).findFirst()
				.orElseThrow(() -> new AssertionError("no error on " + board + ": " + r.getChanges()));
		assertEquals(AgentBoardService.SWITCH_OPERATOR_ONLY, c.getMessage());
		assertTrue(c.getMessage().contains(KEY) && c.getMessage().contains("operator-only"), c.getMessage());
	}

	/** (a) and (b): the system actor's file turning it off or on is refused whole; a person's is applied. */
	@Test
	public void aNonPersonsFileThatTurnsTheSwitchIsRefusedWhole() throws RelizaException {
		Org o = org();
		String on = boardWith(o, null);
		ApplyResult off = apply(o, board(on, "description", "changed too", "settings", settings(false)), SYSTEM);
		refusedOnTheBoard(off, on);
		AgentBoardData kept = boardNamed(o, on);
		assertNull(kept.getArchitectCheckpointWaiver());
		assertTrue(kept.getEffectiveArchitectCheckpointWaiver());
		assertEquals("plain", kept.getDescription(), "nothing of the file is applied");

		String wasOff = boardWith(o, false);
		ApplyResult backOn = apply(o, board(wasOff, "settings", settings(true)), SYSTEM);
		refusedOnTheBoard(backOn, wasOff);
		assertEquals(Boolean.FALSE, boardNamed(o, wasOff).getArchitectCheckpointWaiver());
		ApplyResult restored = apply(o, board(wasOff, "settings", settings(null)), SYSTEM);
		refusedOnTheBoard(restored, wasOff);

		ApplyResult byPerson = apply(o, board(on, "settings", settings(false)), PERSON);
		assertEquals(0, byPerson.getErrors(), byPerson.getChanges().toString());
		assertFalse(boardNamed(o, on).getEffectiveArchitectCheckpointWaiver());
		assertTrue(byPerson.getChanges().stream().anyMatch(c -> null != c.getFields()
				&& c.getFields().contains("settings." + KEY)), byPerson.getChanges().toString());
		assertEquals(0, apply(o, board(wasOff, "settings", settings(true)), PERSON).getErrors());
		assertTrue(boardNamed(o, wasOff).getEffectiveArchitectCheckpointWaiver());
	}

	/** (c) and (d): the export re-applied, a repeat of the stored value, or no key at all, pass under the system actor. */
	@Test
	public void aFileThatChangesNothingPassesUnderAnyActor() throws RelizaException {
		Org o = org();
		String name = boardWith(o, false);
		Map<String, Object> exported = Utils.OM.convertValue(agentBoardService.exportBoard(boardNamed(o, name).getUuid()),
				Map.class);
		assertEquals(false, ((Map<String, Object>) exported.get("settings")).get(KEY), "the export names it");
		ApplyResult again = apply(o, exported, SYSTEM);
		assertEquals(0, again.getErrors(), again.getChanges().toString());
		assertTrue(again.getChanges().stream().allMatch(c -> Action.UNCHANGED == c.getAction()), again.getChanges().toString());

		assertEquals(0, apply(o, board(name, "settings", settings(false)), SYSTEM).getErrors(), "the stored value repeated");
		assertEquals(0, apply(o, board(name, "description", "no settings"), SYSTEM).getErrors(), "settings left out");
		assertEquals(0, apply(o, board(name, "settings", map("cycleCap", 7)), SYSTEM).getErrors(), "the key left out");
		AgentBoardData after = boardNamed(o, name);
		assertEquals(Boolean.FALSE, after.getArchitectCheckpointWaiver(), "unchanged");
		assertEquals(Integer.valueOf(7), after.getCycleCap());
		String on = boardWith(o, null);
		assertEquals(0, apply(o, board(on, "settings", settings(true)), SYSTEM).getErrors(),
				"true where null is on: the effective value does not change");
	}

	/** (f): a creating apply with the switch off is refused from the system actor and created from a person. */
	@Test
	public void aCreatingApplyWithTheSwitchOffIsAPersonsCall() throws RelizaException {
		Org o = org();
		String name = "ws-" + UUID.randomUUID();
		ApplyResult refused = apply(o, board(name, "description", "new", "target", o.target(), "settings", settings(false)),
				SYSTEM);
		refusedOnTheBoard(refused, name);
		assertFalse(exists(o, name), "rolled back");
		ApplyResult created = apply(o, board(name, "description", "new", "target", o.target(), "settings",
				settings(false)), PERSON);
		assertEquals(0, created.getErrors(), created.getChanges().toString());
		assertFalse(boardNamed(o, name).getEffectiveArchitectCheckpointWaiver());
		String plain = "ws-" + UUID.randomUUID();
		assertEquals(0, apply(o, board(plain, "description", "new", "target", o.target()), SYSTEM).getErrors(),
				"a create that leaves it at the default passes");
	}

	/** (g): the board form sets it, null restores the default, and a created board takes it. */
	@Test
	public void theBoardFormSetsAndRestoresIt() throws RelizaException {
		Org o = org();
		String name = boardWith(o, null);
		AgentBoardData bd = boardNamed(o, name);
		agentBoardService.updateSettingsFromInput(bd.getUuid(), settings(false), WU);
		assertEquals(Boolean.FALSE, boardNamed(o, name).getArchitectCheckpointWaiver());
		agentBoardService.updateSettingsFromInput(bd.getUuid(), settings(null), WU);
		assertNull(boardNamed(o, name).getArchitectCheckpointWaiver());
		assertTrue(boardNamed(o, name).getEffectiveArchitectCheckpointWaiver());

		Component target = componentService.createComponent("ws-form-" + UUID.randomUUID(), o.uuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData created = agentBoardService.createBoard(o.uuid(), "ws-" + UUID.randomUUID(), "form", List.of(),
				"coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.updateSettingsFromInput(created.getUuid(), settings(false), WU);
		assertFalse(agentBoardService.getBoardData(created.getUuid()).orElseThrow().getEffectiveArchitectCheckpointWaiver());
	}
}
