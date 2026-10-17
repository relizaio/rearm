/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.ComponentDto;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.service.AgentTaskInputService.InputVerdict;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * A board's document components (board-documents.md §2, tests 1-3): kind BOARD_DOCUMENT and named after
 * the board, recorded in the board's map, adopted once from a board that predates the map, and the
 * only components a role's document inputs resolve to.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentDocumentComponentsIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/doc-components";
	private static final String DOCS_SOURCE = "github:acme/doc-components-docs";
	private static final String DOCS = "https://github.com/acme/doc-components-docs";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AtomicInteger ISSUE = new AtomicInteger(4200);
	private static final RearmSpecificationType ARCH = RearmSpecificationType.ARCHITECTURE;

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private AgentTaskInputService agentTaskInputService;
	@Autowired private ComponentService componentService;
	@Autowired private DeclarativeConfigService declarativeConfigService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private BranchService branchService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private BoardDocumentComponentService boardDocumentComponentService;
	@Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

	private record Rig(Organization org, Component target, AgentBoardData board, AgentData worker,
			AgentSessionData session) {}

	/** A board with an architect role on a target, publishing to the shared documents repository. */
	private Rig rig(Organization org, Component target, String boardName) throws RelizaException {
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), boardName, "documents",
				List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("architect", "design", 10,
				null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(ARCH, InputScope.TASK, false)), null, null), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentData w = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"w-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData s = agentSessionService.initialize(org.getUuid(), w.getUuid(), null,
				"s-" + UUID.randomUUID(), "worker", null, null, WU);
		return new Rig(org, target, board, w, s);
	}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		return rig(org, newTarget(org), "Doc Board " + UUID.randomUUID());
	}

	private Component newTarget(Organization org) throws RelizaException {
		return componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private ComponentData component(UUID uuid) {
		return getComponentService.getComponentData(uuid).orElseThrow();
	}

	/** A task held by the rig's architect session. */
	private AgentTaskData withArchitect(Rig r) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "work",
				null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "architect", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		return agentTaskService.getTaskData(t.getUuid()).orElseThrow();
	}

	private ReleaseData publishArchitecture(Rig r) throws RelizaException {
		AgentTaskData t = withArchitect(r);
		String path = "design/" + UUID.randomUUID() + "/architecture-1.md";
		return agentDocumentService.publish(r.session(), new PublishRequest(t.getUuid(), ARCH, null, path,
				"digest-of-" + path, "text/markdown", null, null, null, "c-" + UUID.randomUUID(), DOCS, "doc",
				ZonedDateTime.now(), null), WU);
	}

	/**
	 * A component the way boards made them before the map: GENERIC, named after the target, carrying
	 * the specification identifier and composed into the target's base feature set.
	 */
	private UUID legacyComponent(Component target, Organization org) throws RelizaException {
		return legacyComponent(target, org, ARCH);
	}

	private UUID legacyComponent(Component target, Organization org, RearmSpecificationType spec) throws RelizaException {
		Component legacy = componentService.createComponent(CreateComponentDto.builder()
				.name(component(target.getUuid()).getName() + "-" + spec.name().toLowerCase())
				.organization(org.getUuid())
				.type(ComponentType.COMPONENT)
				.versionSchema("Micro")
				.featureBranchVersioning("Branch.Micro")
				.identifiers(List.of(new RearmIdentifier(RearmIdentifierType.SPECIFICATION, spec.name())))
				.build(), WU);
		compose(target.getUuid(), legacy.getUuid(), true);
		return legacy.getUuid();
	}

	/**
	 * A board from before the flag (RD2-33): the field taken off its stored record, as every board
	 * created before the fix has it, so it adopts its target's legacy components.
	 */
	private Rig legacy(Rig r) {
		jdbcTemplate.update("UPDATE rearm.agent_boards SET record_data = record_data - 'adoptsLegacyDocuments' WHERE uuid = ?",
				r.board().getUuid());
		assertTrue(board(r).getAdoptsLegacyDocuments(), "a board without the field reads as legacy");
		return r;
	}

	/** Add a component to, or take it out of, the target's base feature set. */
	private void compose(UUID target, UUID component, boolean add) throws RelizaException {
		Branch base = branchService.getBaseBranchOfComponent(target).orElseThrow();
		BranchData bd = BranchData.branchDataFromDbRecord(base);
		List<ChildComponent> deps = null != bd.getDependencies() ? new LinkedList<>(bd.getDependencies())
				: new LinkedList<>();
		deps.removeIf(cc -> component.equals(cc.getUuid()));
		if (add) {
			deps.add(ChildComponent.builder().uuid(component)
					.branch(branchService.getBaseBranchOfComponent(component).map(Branch::getUuid).orElse(null))
					.status(StatusEnum.IGNORED).build());
		}
		branchService.updateBranch(BranchDto.builder().uuid(bd.getUuid()).dependencies(deps).build(), WU);
	}

	/** A component-scoped ARCHITECTURE requirement, evaluated for a task on the board. */
	private InputVerdict architectureInput(AgentBoardData board, AgentTaskData td) {
		td.setRequiredInputs(List.of(new RequiredInput(InputKind.DOCUMENT, ARCH, InputScope.COMPONENT,
				null, null, null)));
		return agentTaskInputService.evaluate(board, null, td).get(0);
	}

	// ---------- 1. kind and name ----------

	@Test
	public void aFirstPublishCreatesAComponentOfKindDocumentNamedAfterTheBoard() throws RelizaException {
		Rig r = rig();
		ReleaseData rd = publishArchitecture(r);

		ComponentData cd = component(rd.getComponent());
		assertEquals(ComponentKind.BOARD_DOCUMENT, cd.getKind());
		assertEquals(AgentBoardData.slug(r.board().getName()) + "-architecture", cd.getName(),
				"named after the board, not after its target");
		assertEquals(rd.getComponent(), board(r).getDocumentComponents().get(ARCH), "and recorded on the board");
	}

	@Test
	public void theDocumentsPrefixNamesTheComponentInsteadOfTheBoard() throws RelizaException {
		Rig r = rig();
		String prefix = "Team Docs " + UUID.randomUUID();
		agentBoardService.setDocumentsPrefix(r.board().getUuid(), prefix, WU);

		ReleaseData rd = publishArchitecture(r);

		assertEquals(AgentBoardData.slug(prefix) + "-architecture", component(rd.getComponent()).getName());
	}

	@Test
	public void aPrefixThatSlugsToNothingIsRefused() throws RelizaException {
		Rig r = rig();
		RelizaException e = assertThrows(RelizaException.class,
				() -> agentBoardService.setDocumentsPrefix(r.board().getUuid(), " -- ", WU));
		assertTrue(e.getMessage().contains("documents.prefix"), e.getMessage());
	}

	@Test
	public void aComponentAlreadyCarryingTheNameIsRefusedWithTheWayOut() throws RelizaException {
		Rig r = rig();
		componentService.createComponent(AgentBoardData.slug(r.board().getName()) + "-architecture",
				r.org().getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);

		RelizaException e = assertThrows(RelizaException.class, () -> publishArchitecture(r));
		assertTrue(e.getMessage().contains("set documents.prefix"), e.getMessage());
		assertTrue(null == board(r).getDocumentComponents() || board(r).getDocumentComponents().isEmpty(),
				"nothing is recorded for a refused create");
	}

	@Test
	public void theUpdatePathRefusesAKindChangeToOrFromDocument() throws RelizaException {
		Rig r = rig();
		UUID document = publishArchitecture(r).getComponent();
		Component generic = componentService.createComponent("plain_" + UUID.randomUUID(), r.org().getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);

		assertThrows(RelizaException.class, () -> componentService.updateComponent(
				ComponentDto.builder().uuid(generic.getUuid()).kind(ComponentKind.BOARD_DOCUMENT).build(), WU));
		assertThrows(RelizaException.class, () -> componentService.updateComponent(
				ComponentDto.builder().uuid(document).kind(ComponentKind.GENERIC).build(), WU));
		assertEquals(ComponentKind.BOARD_DOCUMENT, component(document).getKind());
		assertEquals(ComponentKind.GENERIC, component(generic.getUuid()).getKind());
	}

	/**
	 * Only a board creates a BOARD_DOCUMENT component (task fceb1e57 T-5): not the create call, not a catalog
	 * file's plan or apply (the provider's rearm_component path). A document component a board made
	 * still reads and re-declares as BOARD_DOCUMENT.
	 */
	@Test
	public void onlyABoardCreatesADocumentComponent() throws RelizaException {
		Rig r = rig();
		String name = "fake-doc-" + UUID.randomUUID();
		RelizaException refused = assertThrows(RelizaException.class, () -> componentService.createComponent(
				CreateComponentDto.builder().name(name).organization(r.org().getUuid()).type(ComponentType.COMPONENT)
						.kind(ComponentKind.BOARD_DOCUMENT).versionSchema("Micro").featureBranchVersioning("Branch.Micro")
						.build(), WU));
		assertTrue(refused.getMessage().startsWith("Boards set the BOARD_DOCUMENT kind"), refused.getMessage());
		assertTrue(named(r, name).isEmpty(), "nothing is created");

		for (boolean dryRun : new boolean[] {true, false}) {
			DeclarativeConfigService.CatalogSpecDto spec = new DeclarativeConfigService.CatalogSpecDto();
			DeclarativeConfigService.CatalogComponentDto c = new DeclarativeConfigService.CatalogComponentDto();
			c.setName(name);
			c.setType(ComponentType.COMPONENT);
			c.setKind(ComponentKind.BOARD_DOCUMENT);
			spec.getComponents().add(c);
			DeclarativeConfigService.ApplyResult result = declarativeConfigService.applyCatalog(r.org().getUuid(), spec,
					dryRun, null, WU);
			assertEquals(1, result.getErrors(), "dry run " + dryRun);
			assertEquals(0, result.getCreated(), "dry run " + dryRun);
			assertTrue(result.getChanges().get(0).getMessage().startsWith("Boards set the BOARD_DOCUMENT kind"),
					result.getChanges().get(0).getMessage());
			assertTrue(named(r, name).isEmpty(), "nothing is created, dry run " + dryRun);
		}

		// A board's own document component, declared back as it reads, is no change and no error.
		UUID document = publishArchitecture(r).getComponent();
		DeclarativeConfigService.CatalogSpecDto again = new DeclarativeConfigService.CatalogSpecDto();
		DeclarativeConfigService.CatalogComponentDto same = new DeclarativeConfigService.CatalogComponentDto();
		same.setName(component(document).getName());
		same.setType(ComponentType.COMPONENT);
		same.setKind(ComponentKind.BOARD_DOCUMENT);
		again.getComponents().add(same);
		DeclarativeConfigService.ApplyResult kept = declarativeConfigService.applyCatalog(r.org().getUuid(), again,
				false, null, WU);
		assertEquals(0, kept.getErrors(), String.valueOf(kept.getChanges()));
		assertEquals(ComponentKind.BOARD_DOCUMENT, component(document).getKind());
	}

	private List<ComponentData> named(Rig r, String name) {
		return componentService.listComponentDataByOrganization(r.org().getUuid(), ComponentType.COMPONENT).stream()
				.filter(c -> name.equals(c.getName())).toList();
	}

	// ---------- 2. adoption ----------

	@Test
	public void aBoardAdoptsItsTargetsExistingComponentOnceAndThenReadsItsMap() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = newTarget(org);
		UUID legacy = legacyComponent(target, org);
		Rig r = legacy(rig(org, target, "Adopting " + UUID.randomUUID()));

		ReleaseData first = publishArchitecture(r);
		assertEquals(legacy, first.getComponent(), "the existing series is kept, with its history");
		assertEquals(legacy, board(r).getDocumentComponents().get(ARCH), "and recorded");
		assertEquals(ComponentKind.GENERIC, component(legacy).getKind(), "adoption leaves the kind as it was");

		// Out of the target's composition, a scan would find nothing and create a new component; the
		// second publish lands on the adopted one, so it read the map and did neither.
		compose(target.getUuid(), legacy, false);
		ReleaseData second = publishArchitecture(r);
		assertEquals(legacy, second.getComponent());
	}

	@Test
	public void twoBoardsOnOneTargetEndWithTwoArchitectureComponents() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = newTarget(org);
		UUID legacy = legacyComponent(target, org);
		Rig a = legacy(rig(org, target, "First " + UUID.randomUUID()));
		Rig b = legacy(rig(org, target, "Second " + UUID.randomUUID()));

		UUID onA = publishArchitecture(a).getComponent();
		UUID onB = publishArchitecture(b).getComponent();

		assertEquals(legacy, onA, "the first board adopts the existing series");
		assertNotEquals(onA, onB, "the second board gets its own, not the one the first adopted");
		assertEquals(ComponentKind.BOARD_DOCUMENT, component(onB).getKind());
		assertEquals(onB, publishArchitecture(b).getComponent(), "and keeps it");
	}

	// ---------- 3. inputs ----------

	@Test
	public void aDocumentInputResolvesToTheBoardsOwnComponentOnly() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = newTarget(org);
		Rig a = rig(org, target, "Inputs A " + UUID.randomUUID());
		Rig b = rig(org, target, "Inputs B " + UUID.randomUUID());
		ReleaseData onA = publishArchitecture(a);
		AgentTaskData taskOnB = withArchitect(b);

		// Board A's series hangs off the same target and lives in the same documents repository.
		// Before the map, board B's role would have been handed it.
		InputVerdict before = architectureInput(board(b), taskOnB);
		assertFalse(before.met(), "board B has published no architecture, and A's is not B's");

		ReleaseData onB = publishArchitecture(b);
		InputVerdict after = architectureInput(board(b), taskOnB);
		assertTrue(after.met(), String.valueOf(after.unmetReason()));
		assertEquals(onB.getComponent(), after.resolved().component());
		assertNotEquals(onA.getComponent(), after.resolved().component());
	}

	@Test
	public void aForeignSpecificationComponentOnTheRepositoryIsNotOffered() throws RelizaException {
		Rig r = rig();
		UUID own = publishArchitecture(r).getComponent();
		// Planted: GENERIC, carrying ARCHITECTURE, in the board's documents repository and composed
		// into its target -- exactly what the VCS-wide match used to offer.
		Component foreign = componentService.createComponent(CreateComponentDto.builder()
				.name("foreign_" + UUID.randomUUID())
				.organization(r.org().getUuid())
				.type(ComponentType.COMPONENT)
				.versionSchema("Micro")
				.featureBranchVersioning("Branch.Micro")
				.vcs(board(r).getDocumentsRepo())
				.identifiers(List.of(new RearmIdentifier(RearmIdentifierType.SPECIFICATION, ARCH.name())))
				.build(), WU);
		compose(r.target().getUuid(), foreign.getUuid(), true);

		InputVerdict v = architectureInput(board(r), withArchitect(r));
		assertTrue(v.met(), String.valueOf(v.unmetReason()));
		assertEquals(own, v.resolved().component());
		List<UUID> ofRepo = agentTaskInputService.documentComponentsOfRepo(board(r));
		assertTrue(ofRepo.contains(own));
		assertFalse(ofRepo.contains(foreign.getUuid()), "the lock check covers the board's own components");
	}

	// ---------- 4. a board created since the map never adopts (RD2-33) ----------

	/** The specifications a target's legacy components carry in these cases. */
	private static final List<RearmSpecificationType> LEGACY_SPECS = List.of(ARCH, RearmSpecificationType.DETAILED_DESIGN,
			RearmSpecificationType.BOARD_TEST_REPORT, RearmSpecificationType.BOARD_REVIEW_ITEMS, RearmSpecificationType.BOARD_QUESTIONS);

	@Test
	public void aBoardCreatedNowAdoptsNoLegacyComponentAndCreatesItsOwn() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = newTarget(org);
		List<UUID> legacies = new LinkedList<>();
		for (RearmSpecificationType spec : LEGACY_SPECS) legacies.add(legacyComponent(target, org, spec));
		Rig r = rig(org, target, "Created Now " + UUID.randomUUID());
		assertFalse(board(r).getAdoptsLegacyDocuments(), "createBoard writes the flag");

		// Nothing is offered for any specification: not to adopt, not as an input, not to the lock check.
		for (RearmSpecificationType spec : RearmSpecificationType.values()) {
			assertTrue(boardDocumentComponentService.legacyComponentOf(board(r), spec).isEmpty(), spec.name());
			assertTrue(boardDocumentComponentService.candidatesOf(board(r), spec).isEmpty(), spec.name());
		}
		assertTrue(boardDocumentComponentService.componentsOf(board(r)).isEmpty());
		AgentTaskData task = withArchitect(r);
		InputVerdict before = architectureInput(board(r), task);
		assertFalse(before.met(), "the target's legacy architecture is not this board's input");

		UUID created = publishArchitecture(r).getComponent();
		assertFalse(legacies.contains(created), "its first publish creates, it does not adopt");
		assertEquals(ComponentKind.BOARD_DOCUMENT, component(created).getKind());
		assertEquals(board(r).documentComponentName(ARCH), component(created).getName());
		assertEquals(created, board(r).getDocumentComponents().get(ARCH));
		assertEquals(1, board(r).getDocumentComponents().size(), "and adopted nothing for the other types either");
		assertEquals(created, boardDocumentComponentService.componentOf(board(r), ARCH).orElseThrow());
		for (RearmSpecificationType spec : LEGACY_SPECS) {
			if (spec == ARCH) continue;
			assertTrue(boardDocumentComponentService.componentOf(board(r), spec).isEmpty(), spec.name());
		}
		InputVerdict after = architectureInput(board(r), task);
		assertTrue(after.met(), String.valueOf(after.unmetReason()));
		assertEquals(created, after.resolved().component());
	}

	@Test
	public void twoNewBoardsOnATargetWhoseFirstBoardHasNotPublishedEndWithDistinctSeries() throws RelizaException {
		// The incident: Dogfood 1's series sat unrecorded on the target, and Dogfood 2 took it.
		Organization org = testInitializer.obtainOrganization();
		Component target = newTarget(org);
		UUID firstBoardsSeries = legacyComponent(target, org);
		Rig second = rig(org, target, "Second " + UUID.randomUUID());
		Rig third = rig(org, target, "Third " + UUID.randomUUID());

		UUID onSecond = publishArchitecture(second).getComponent();
		UUID onThird = publishArchitecture(third).getComponent();
		assertNotEquals(firstBoardsSeries, onSecond);
		assertNotEquals(firstBoardsSeries, onThird);
		assertNotEquals(onSecond, onThird);
		assertEquals(ComponentKind.GENERIC, component(firstBoardsSeries).getKind(), "the legacy series is untouched");

		// The first board, from before the map, still adopts its own series.
		Rig first = legacy(rig(org, target, "First " + UUID.randomUUID()));
		assertEquals(firstBoardsSeries, publishArchitecture(first).getComponent());
	}

	@Test
	public void everyCreatePathWritesTheFlagAndTheExportDoesNotCarryIt() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = newTarget(org);
		// The form and the programmatic create go through createBoard (the rig); the file apply too.
		Rig form = rig(org, target, "Form " + UUID.randomUUID());
		assertEquals("false", storedFlag(form.board().getUuid()));
		String name = "File " + UUID.randomUUID();
		java.util.Map<String, Object> file = new java.util.LinkedHashMap<>();
		file.put("kind", "BOARD");
		file.put("version", 1);
		file.put("name", name);
		file.put("target", component(target.getUuid()).getName());
		DeclarativeConfigService.ApplyResult applied = agentBoardService.applyBoard(org.getUuid(),
				AgentBoardService.boardSpecFromInput(file), false, null, COORD, WU);
		assertEquals(0, applied.getErrors(), applied.getChanges().toString());
		AgentBoardData fromFile = agentBoardService.listByOrg(org.getUuid()).stream()
				.filter(b -> name.equals(b.getName())).findFirst().orElseThrow();
		assertEquals("false", storedFlag(fromFile.getUuid()));
		assertFalse(fromFile.getAdoptsLegacyDocuments());

		java.util.Map<?, ?> export = io.reliza.common.Utils.OM.convertValue(agentBoardService.exportBoard(fromFile.getUuid()),
				java.util.Map.class);
		assertFalse(export.containsKey("adoptsLegacyDocuments"), "state, not configuration");
		// Taken off the record, as a board from before has it: absent reads true (legacy asserts it).
		legacy(form);
		assertNull(storedFlag(form.board().getUuid()));
	}

	@Test
	public void aNewBoardStillTakesItsOwnUnrecordedDocumentComponent() throws RelizaException {
		// What the answer and decision rounds leave, and a rolled-back publish: the board's own
		// component, created and composed but not recorded. Only that is taken, never a legacy one.
		Organization org = testInitializer.obtainOrganization();
		Component target = newTarget(org);
		UUID foreign = legacyComponent(target, org);
		Rig r = rig(org, target, "Own Orphan " + UUID.randomUUID());
		UUID own = agentDocumentService.createDocumentComponentIsolated(board(r), ARCH, WU);
		assertNull(board(r).getDocumentComponents().get(ARCH), "made without recording");
		assertEquals(List.of(own), boardDocumentComponentService.candidatesOf(board(r), ARCH));

		assertEquals(own, publishArchitecture(r).getComponent());
		assertEquals(own, board(r).getDocumentComponents().get(ARCH));
		assertNotEquals(foreign, own);
	}

	private String storedFlag(UUID board) {
		return jdbcTemplate.queryForObject("SELECT record_data->>'adoptsLegacyDocuments' FROM rearm.agent_boards WHERE uuid = ?",
				String.class, board);
	}
}
