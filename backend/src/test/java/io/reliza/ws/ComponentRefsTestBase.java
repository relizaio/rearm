/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.VcsType;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.BranchDto;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AgentDocumentService;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.AgentTaskService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.GetComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.service.VcsRepositoryService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.service.saas.PerspectiveService;
import io.reliza.ws.oss.TestInitializer;
import jakarta.persistence.EntityManagerFactory;

/**
 * TPF-100 (TPF-74 child 1), architecture round 1 section 9.1: the shared fixture and calls of the
 * componentRefs integration tests. Organization A holds c1..c4 (c3 with a VCS), c5 archived, c6 without
 * status and kind keys, product p1 (c1 composed into it, the board's target), the BOARD_DOCUMENT d1 and
 * perspective P (c2, p1); organization B holds b1 and its own perspective; seven caller classes.
 */
@SpringBootTest(classes = {App.class})
public abstract class ComponentRefsTestBase {

	protected static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	protected static final String SRC = "github:acme/component-refs";
	protected static final String DOCS_SOURCE = "github:acme/component-refs-docs";
	protected static final String DOCS = "https://github.com/acme/component-refs-docs";
	protected static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	protected static final String REQUIREMENTS = "{\"grammarVersion\":\"1\",\"elements\":["
			+ "{\"id\":\"REQ-1\",\"family\":\"requirement\",\"title\":\"Refs\",\"contentDigest\":\"a1\",\"line\":3}],"
			+ "\"warnings\":[]}";
	protected static final String SIX = "{ uuid name type status org vcs }";
	protected static final String ARGS = "query($o: ID!, $t: ComponentType!, $p: ID, $k: [ComponentKind]) { %s(orgUuid: $o,"
			+ " componentType: $t, perspective: $p, kinds: $k) %s }";
	protected static final List<String> TYPES = List.of("COMPONENT", "PRODUCT", "ANY");
	protected static final List<String> SOFTWARE = List.of("GENERIC", "HELM");

	@MockitoSpyBean protected UserService userService;
	@Autowired protected DgsQueryExecutor dgsQueryExecutor;
	@Autowired protected TestInitializer testInitializer;
	@Autowired protected AgentBoardService agentBoardService;
	@Autowired protected AgentTaskService agentTaskService;
	@Autowired protected AgentDocumentService agentDocumentService;
	@Autowired protected AgentService agentService;
	@Autowired protected AgentSessionService agentSessionService;
	@Autowired protected ComponentService componentService;
	@Autowired protected GetComponentService getComponentService;
	@Autowired protected BranchService branchService;
	@Autowired protected OssReleaseService ossReleaseService;
	@Autowired protected VcsRepositoryService vcsRepositoryService;
	@Autowired protected PerspectiveService perspectiveService;
	@Autowired protected AuthorizationService authorizationService;
	@Autowired protected LicenseStatus licenseStatus;
	@Autowired protected JdbcTemplate jdbcTemplate;
	@Autowired protected EntityManagerFactory entityManagerFactory;

	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void operational() {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		SecurityContextHolder.clearContext();
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
		statistics().setStatisticsEnabled(false);
	}

	/** Organization A with c1..c6, p1, d1 and perspective P; organization B with b1 and its own perspective. */
	protected record Fx(UUID a, UUID b, UUID c1, UUID c2, UUID c3, UUID c4, UUID c5, UUID c6, UUID p1, UUID d1,
			Set<UUID> documents, UUID perspective, UUID b1, UUID perspectiveOfB, UUID vcs, Map<String, UserData> callers) {
		Set<UUID> live() {
			Set<UUID> live = new java.util.HashSet<>(Set.of(c1, c2, c3, c4, c6, p1));
			live.addAll(documents);
			return live;
		}
	}

	protected Fx fixture() throws Exception {
		UUID a = testInitializer.obtainOrganization().getUuid();
		UUID b = testInitializer.obtainOrganization().getUuid();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		UUID c1 = component("c1-" + tag, a, ComponentType.COMPONENT, null);
		UUID c2 = component("c2-" + tag, a, ComponentType.COMPONENT, null);
		UUID vcs = vcsRepositoryService.provisionVcsRepository(a, "github.com/example/refs-" + UUID.randomUUID(),
				VcsType.GIT, WU);
		UUID c3 = component("c3-" + tag, a, ComponentType.COMPONENT, vcs);
		UUID c4 = component("c4-" + tag, a, ComponentType.COMPONENT, null);
		UUID c5 = component("c5-" + tag, a, ComponentType.COMPONENT, null);
		componentService.archiveComponent(c5, WU);
		UUID c6 = component("c6-" + tag, a, ComponentType.COMPONENT, null);
		jdbcTemplate.update("UPDATE rearm.components SET record_data = record_data - 'status' - 'kind' WHERE uuid = ?", c6);
		UUID p1 = component("p1-" + tag, a, ComponentType.PRODUCT, null);
		compose(p1, c1);
		UUID d1 = boardDocument(a, p1, tag);
		// the publish creates d1 and, for its elements, the board's check-report series: both BOARD_DOCUMENT
		Set<UUID> documents = componentService.listComponentDataByOrganization(a, ComponentType.ANY).stream()
				.filter(c -> ComponentKind.BOARD_DOCUMENT == c.getKind()).map(ComponentData::getUuid)
				.collect(Collectors.toSet());
		assertTrue(documents.contains(d1), "d1 is a BOARD_DOCUMENT component");
		UUID perspective = perspectiveService.createPerspective(a, "P-" + tag, WU).getUuid();
		componentService.setPerspectives(c2, List.of(perspective), WU);
		componentService.setPerspectives(p1, List.of(perspective), WU);
		UUID b1 = component("b1-" + tag, b, ComponentType.COMPONENT, null);
		UUID perspectiveOfB = perspectiveService.createPerspective(b, "PB-" + tag, WU).getUuid();
		componentService.setPerspectives(b1, List.of(perspectiveOfB), WU);

		Map<String, UserData> callers = new LinkedHashMap<>();
		callers.put("uAdmin", globalAdmin(user(a, null, null)));
		callers.put("uOrg", user(a, PermissionScope.ORGANIZATION, a));
		callers.put("uComp", user(a, PermissionScope.COMPONENT, c2));
		callers.put("uProd", user(a, PermissionScope.COMPONENT, p1));
		callers.put("uPersp", user(a, PermissionScope.PERSPECTIVE, perspective));
		callers.put("uNone", user(a, null, null));
		callers.put("uB", user(b, PermissionScope.ORGANIZATION, b));
		return new Fx(a, b, c1, c2, c3, c4, c5, c6, p1, d1, documents, perspective, b1, perspectiveOfB, vcs, callers);
	}

	protected UUID component(String name, UUID org, ComponentType type, UUID vcs) throws Exception {
		return componentService.createComponent(name, org, type, "semver", "Branch.Micro", vcs, WU).getUuid();
	}

	/** c1 as a manual dependency of p1's base feature set (AgentBoardPerspectivesIntegrationTest.compose). */
	protected void compose(UUID parent, UUID child) throws Exception {
		Branch base = branchService.getBaseBranchOfComponent(parent).orElseThrow();
		BranchData bd = BranchData.branchDataFromDbRecord(base);
		List<ChildComponent> deps = null != bd.getDependencies() ? new LinkedList<>(bd.getDependencies()) : new LinkedList<>();
		deps.add(ChildComponent.builder().uuid(child)
				.branch(branchService.getBaseBranchOfComponent(child).map(Branch::getUuid).orElse(null))
				.status(StatusEnum.REQUIRED).build());
		branchService.updateBranch(BranchDto.builder().uuid(bd.getUuid()).dependencies(deps).build(), WU);
	}

	/** A BOARD_DOCUMENT component: one ARCHITECTURE publish of a board on the organization targeting p1. */
	protected UUID boardDocument(UUID org, UUID target, String tag) throws Exception {
		AgentBoardData board = agentBoardService.createBoard(org, "Refs " + tag, "refs", List.of(SRC, DOCS_SOURCE),
				"coordinate", 4, null, target, null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("architect", "design", 10,
				null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(RearmSpecificationType.ARCHITECTURE, InputScope.TASK, false)), null, null), true, WU);
		agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentData agent = agentService.findOrRegisterRootAgent(org, UUID.randomUUID(), "refs-" + tag, null, null, null, WU);
		AgentSessionData session = agentSessionService.initialize(org, agent.getUuid(), null, "refs-" + UUID.randomUUID(),
				"refs", null, null, WU);
		AgentBoardData fresh = agentBoardService.getBoardData(board.getUuid()).orElseThrow();
		AgentTaskData t = agentTaskService.register(fresh, null, "work", null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), fresh, "architect", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), fresh, agent.getUuid(), session.getUuid(), WU);
		String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(REQUIREMENTS.getBytes(StandardCharsets.UTF_8)));
		return agentDocumentService.publish(session, new PublishRequest(t.getUuid(), RearmSpecificationType.ARCHITECTURE,
				null, "design/refs.md", "d-" + tag, "text/markdown", null, null, null, "c1" + tag, DOCS, "design",
				ZonedDateTime.now(), null, REQUIREMENTS, sha), WU).getComponent();
	}

	/** A member of org with one READ_ONLY grant (none when scope is null). */
	protected UserData user(UUID org, PermissionScope scope, UUID object) throws Exception {
		String utag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Refs " + utag, "refs-" + utag + "@refs.io", true, List.of(org),
				"refs-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		if (null != scope) {
			userService.setUserPermission(u.getUuid(), org, scope, object, PermissionType.READ_ONLY, List.of(), null, WU);
		}
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	/** OrganizationArchiveIntegrationTest.globalAdmin. */
	protected UserData globalAdmin(UserData u) {
		jdbcTemplate.update("UPDATE rearm.users SET record_data = jsonb_set(record_data, '{" + CommonVariables.GLOBAL_ADMIN_FIELD
				+ "}', 'true'::jsonb) WHERE uuid = ?", u.getUuid());
		UserData g = userService.getUserData(u.getUuid()).orElseThrow();
		assertTrue(g.isGlobalAdmin());
		return g;
	}

	// ---------- calls ----------

	protected ExecutionResult as(UserData user, String query, Map<String, Object> vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("component-refs-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(query, vars);
	}

	protected ExecutionResult call(UserData u, String field, String selection, Object org, String type, UUID perspective,
			List<String> kinds) {
		Map<String, Object> vars = new LinkedHashMap<>();
		vars.put("o", org.toString());
		vars.put("t", type);
		vars.put("p", null == perspective ? null : perspective.toString());
		vars.put("k", kinds);
		return as(u, ARGS.formatted(field, selection), vars);
	}

	/** The rows of a successful call, by uuid, each with its six scalars. */
	@SuppressWarnings("unchecked")
	protected Map<String, Map<String, Object>> rows(UserData u, String field, UUID org, String type, UUID perspective,
			List<String> kinds) {
		ExecutionResult r = call(u, field, SIX, org, type, perspective, kinds);
		assertTrue(r.getErrors().isEmpty(), field + ": " + r.getErrors());
		List<Map<String, Object>> list = (List<Map<String, Object>>) ((Map<String, Object>) r.getData()).get(field);
		return list.stream().collect(Collectors.toMap(m -> (String) m.get("uuid"), m -> m, (x, y) -> x, TreeMap::new));
	}

	/**
	 * The first error message of a refused call, the field carrying no data. An internal error's message
	 * carries a per-occurrence reference ("Internal server error (ref 1a2b3c4d)"); it is blanked so two
	 * refusals of the same kind compare equal.
	 */
	@SuppressWarnings("unchecked")
	protected static String refusal(ExecutionResult r, String field) {
		assertFalse(r.getErrors().isEmpty(), field + " was not refused");
		Map<String, Object> d = (Map<String, Object>) r.getData();
		assertTrue(null == d || null == d.get(field), field + " carried data on a refusal");
		return r.getErrors().get(0).getErrorType() + ": "
				+ r.getErrors().get(0).getMessage().replaceAll("\\(ref [0-9a-fA-F-]+\\)", "(ref)");
	}

	protected static Set<String> str(Stream<UUID> s) {
		return s.map(UUID::toString).collect(Collectors.toSet());
	}

	protected ComponentType typeOf(UUID c) {
		return getComponentService.getComponentData(c).orElseThrow().getType();
	}

	/** Architecture 9.1 explicit expectations: the caller's readable live rows of the type and kinds. */
	protected Set<String> expected(Fx f, String caller, String type, List<String> kinds) {
		Set<UUID> visible = switch (caller) {
			case "uAdmin", "uOrg" -> f.live();
			case "uComp" -> Set.of(f.c2());
			// p1 is the board's target: the board composes its document series into p1, so a reader of p1 reads them
			case "uProd" -> Stream.concat(Stream.of(f.p1(), f.c1()), f.documents().stream()).collect(Collectors.toSet());
			case "uPersp" -> Set.of(f.c2(), f.p1());
			default -> Set.of();
		};
		return str(visible.stream()
				.filter(c -> "ANY".equals(type) || typeOf(c).name().equals(type))
				.filter(c -> null == kinds || !f.documents().contains(c)));
	}

	protected Statistics statistics() {
		return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
	}

	/** Prepared statements of one call, the fewest of three runs (a scheduled job only ever adds). */
	protected long statements(Supplier<ExecutionResult> run) {
		Statistics st = statistics();
		st.setStatisticsEnabled(true);
		long fewest = Long.MAX_VALUE;
		for (int i = 0; i < 3; i++) {
			st.clear();
			ExecutionResult r = run.get();
			long n = st.getPrepareStatementCount();
			assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
			fewest = Math.min(fewest, n);
		}
		return fewest;
	}
}
