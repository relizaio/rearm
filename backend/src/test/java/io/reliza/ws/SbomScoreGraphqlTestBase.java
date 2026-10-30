/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.service.ArtifactService;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.DownloadLogService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.RebomService;
import io.reliza.service.ReleaseService;
import io.reliza.service.SharedArtifactService;
import io.reliza.service.UserService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.oss.TestInitializer;

/**
 * Shared fixture of the SBOM score query tests (task SCORE-5, design section 4.2): a real database,
 * the GraphQL layer through {@link DgsQueryExecutor}, and rebom replaced by a mock that merges,
 * serves and scores fixed documents.
 *
 * <p>Split by topic, release and artifact, over this base so both classes share one Spring context.
 */
@SpringBootTest(classes = {App.class})
abstract class SbomScoreGraphqlTestBase {

	protected static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AtomicInteger VERSION = new AtomicInteger();

	/** The stored component BOM rebom serves; non-ASCII on purpose, so a charset slip shows. */
	protected static final String DOC = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,"
			+ "\"metadata\":{\"component\":{\"name\":\"caf\u00e9\"}},"
			+ "\"components\":[{\"type\":\"library\",\"name\":\"lib-\u00e9\u2192\",\"version\":\"1.0\"}]}";
	protected static final String DOC_V1 = DOC.replace("\"1.0\"", "\"0.9-first-version\"");
	protected static final String RAW_DOC = DOC.replace("\"1.0\"", "\"1.0-as-uploaded\"");
	protected static final String REPORT = "{\"reportVersion\":1,\"input\":{\"sha256\":\"x\"},\"profiles\":[]}";
	protected static final List<String> PROFILES = List.of("cisa-2026", "fda");

	protected static final String SCORE_RELEASE = "query($r: ID!, $t: Boolean, $i: Boolean, $s: BomStructureType,"
			+ " $b: ArtifactBelongsToEnum, $x: [ArtifactCoverageType], $sm: Boolean, $im: Boolean, $fc: Boolean,"
			+ " $p: [String!]!) {"
			+ " releaseSbomScore(release: $r, tldOnly: $t, ignoreDev: $i, structure: $s, belongsTo: $b,"
			+ " excludeCoverageTypes: $x, includeSupportMetadata: $sm, includeInternalMetadata: $im,"
			+ " excludeFileComponents: $fc, profiles: $p) }";
	protected static final String EXPORT_RELEASE = "mutation($r: ID!, $t: Boolean, $i: Boolean, $s: BomStructureType,"
			+ " $b: ArtifactBelongsToEnum, $x: [ArtifactCoverageType], $sm: Boolean, $im: Boolean, $fc: Boolean) {"
			+ " releaseSbomExport(release: $r, tldOnly: $t, ignoreDev: $i, structure: $s, belongsTo: $b,"
			+ " excludeCoverageTypes: $x, includeSupportMetadata: $sm, includeInternalMetadata: $im,"
			+ " excludeFileComponents: $fc, mediaType: JSON) }";
	protected static final String SCORE_ARTIFACT = "query($a: ID!, $raw: Boolean, $v: Int, $p: [String!]!) {"
			+ " artifactSbomScore(artifact: $a, raw: $raw, version: $v, profiles: $p) }";

	@MockitoBean protected RebomService rebomService;
	@MockitoSpyBean protected UserService userService;
	@MockitoSpyBean protected ReleaseService releaseService;
	@MockitoSpyBean protected DownloadLogService downloadLogService;
	@MockitoSpyBean protected SharedArtifactService sharedArtifactService;

	@Autowired protected DgsQueryExecutor dgsQueryExecutor;
	@Autowired protected TestInitializer testInitializer;
	@Autowired protected ComponentService componentService;
	@Autowired protected BranchService branchService;
	@Autowired protected OssReleaseService ossReleaseService;
	@Autowired protected ArtifactService artifactService;
	@Autowired protected JdbcTemplate jdbcTemplate;
	@Autowired private ApplicationContext executorContext;
	@Autowired private LicenseStatus licenseStatus;

	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void operationalAndRebomStubbed() throws Exception {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
		// A fresh tree on every call: the export path edits the node it is given.
		when(rebomService.findBomByIdJson(any(), any())).thenAnswer(inv -> Utils.OM.readTree(DOC));
		when(rebomService.findBomByVersion(any(), any(), anyInt())).thenAnswer(inv -> Utils.OM.readTree(DOC_V1));
		when(rebomService.findRawBomById(any(), any())).thenAnswer(inv -> Utils.OM.readTree(RAW_DOC));
		when(rebomService.findRawBomByVersion(any(), any(), anyInt())).thenAnswer(inv -> Utils.OM.readTree(DOC_V1));
		when(rebomService.mergeAndStoreBoms(anyList(), any(), any())).thenAnswer(inv -> UUID.randomUUID());
		when(rebomService.scoreBom(any(), any())).thenReturn(REPORT);
	}

	@AfterEach
	void restore() {
		SecurityContextHolder.clearContext();
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	// ---- fixture -------------------------------------------------------------------------------

	protected record Fixture(Organization org, Component component, UUID release, UUID bomArtifact,
			UserData downloader, UserData reader, UserData componentDownloader) {}

	/** One organisation, one component release carrying one stored CycloneDX BOM, and three users. */
	protected Fixture fixture() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component component = componentService.createComponent("score5-" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		UUID bom = artifact(org.getUuid(), ArtifactType.BOM, BomFormat.CYCLONEDX, true);
		UUID release = release(org, component, List.of(bom));
		UserData downloader = user(org, PermissionScope.ORGANIZATION, org.getUuid(),
				List.of(PermissionFunction.RESOURCE, PermissionFunction.ARTIFACT_DOWNLOAD));
		UserData reader = user(org, PermissionScope.ORGANIZATION, org.getUuid(), List.of(PermissionFunction.RESOURCE));
		UserData componentDownloader = user(org, PermissionScope.COMPONENT, component.getUuid(),
				List.of(PermissionFunction.RESOURCE, PermissionFunction.ARTIFACT_DOWNLOAD));
		return new Fixture(org, component, release, bom, downloader, reader, componentDownloader);
	}

	protected UUID release(Organization org, Component c, List<UUID> artifacts) throws RelizaException {
		var branch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		return ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid())
				.org(org.getUuid()).branch(branch.getUuid()).version("5.0." + VERSION.incrementAndGet())
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED)
				.artifacts(new ArrayList<>(artifacts)).build(), WU).getUuid();
	}

	/** An artifact row as an upload leaves it; {@code stored} gives it a rebom BOM id. */
	protected UUID artifact(UUID org, ArtifactType type, BomFormat format, boolean stored) throws RelizaException {
		return artifactService.createArtifact(ArtifactDto.builder().org(org).type(type).bomFormat(format)
				.displayIdentifier("score5-" + type + "-" + UUID.randomUUID())
				.internalBom(stored ? new InternalBom(UUID.randomUUID(), ArtifactBelongsTo.RELEASE) : null)
				.build(), WU).getUuid();
	}

	protected UserData user(Organization org, PermissionScope scope, UUID object, List<PermissionFunction> functions)
			throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Score " + tag, "score5-" + tag + "@score.io", true, List.of(org.getUuid()),
				"score5-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), scope, object, PermissionType.READ_ONLY,
				functions, null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	// ---- GraphQL -------------------------------------------------------------------------------

	protected ExecutionResult as(UserData user, String query, Map<String, Object> vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("score-5-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(query, vars);
	}

	/** Variables of the release score and the export; {@code null} values are sent as absent. */
	protected static Map<String, Object> releaseVars(UUID release, Object... pairs) {
		Map<String, Object> vars = new LinkedHashMap<>();
		vars.put("r", release.toString());
		for (int i = 0; i < pairs.length; i += 2) {
			vars.put((String) pairs[i], pairs[i + 1]);
		}
		return vars;
	}

	protected static Map<String, Object> withProfiles(Map<String, Object> vars, List<String> profiles) {
		Map<String, Object> out = new LinkedHashMap<>(vars);
		out.put("p", profiles);
		return out;
	}

	protected static Map<String, Object> artifactVars(UUID artifact, Boolean raw, Integer version) {
		Map<String, Object> vars = new LinkedHashMap<>();
		vars.put("a", artifact.toString());
		if (null != raw) vars.put("raw", raw);
		if (null != version) vars.put("v", version);
		vars.put("p", PROFILES);
		return vars;
	}

	protected static String data(ExecutionResult r, String field) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		Map<String, Object> d = r.getData();
		return (String) d.get(field);
	}

	/** The field's value, or null when the field or the whole of data is null. */
	protected static Object fieldOf(ExecutionResult r, String field) {
		Map<String, Object> d = r.getData();
		return null == d ? null : d.get(field);
	}

	protected static GraphQLError onlyError(ExecutionResult r) {
		assertEquals(1, r.getErrors().size(), r.getErrors().toString());
		return r.getErrors().get(0);
	}

	protected static String errorMessage(ExecutionResult r) {
		return onlyError(r).getMessage();
	}

	// ---- row counts ----------------------------------------------------------------------------

	/** Row count of every table of the application schemas, enumerated, not listed. */
	protected Map<String, Long> rowCounts() {
		List<Map<String, Object>> tables = jdbcTemplate.queryForList("select table_schema, table_name"
				+ " from information_schema.tables where table_type = 'BASE TABLE'"
				+ " and table_schema not in ('pg_catalog', 'information_schema')");
		assertFalse(tables.isEmpty(), "no tables found");
		Map<String, Long> counts = new TreeMap<>();
		for (Map<String, Object> t : tables) {
			String name = "\"" + t.get("table_schema") + "\".\"" + t.get("table_name") + "\"";
			counts.put(name, jdbcTemplate.queryForObject("select count(*) from " + name, Long.class));
		}
		return counts;
	}

	/**
	 * Row counts once the context's background work has drained and two reads in a row agree: the
	 * application context runs after-commit work that writes on its own, and a snapshot taken
	 * mid-write is not a baseline. No @Scheduled tick runs in the test build
	 * (relizaprops.schedulingEnabled=false) and unqualified @Async runs inline (TestAsyncConfig), so
	 * what is left writes from the context's ThreadPoolTaskExecutors (the after-commit auto-integrate
	 * and TEA act hops); see {@link #awaitBackgroundExecutorsIdle}. The two equal reads stay as the
	 * backstop for anything else. This replaced a fixed one-second quiet window per snapshot.
	 */
	protected Map<String, Long> settledRowCounts() throws InterruptedException {
		long deadline = System.currentTimeMillis() + 30_000;
		awaitBackgroundExecutorsIdle(deadline);
		Map<String, Long> previous = rowCounts();
		while (true) {
			awaitBackgroundExecutorsIdle(deadline);
			Map<String, Long> now = rowCounts();
			if (now.equals(previous)) return now;
			previous = now;
			if (System.currentTimeMillis() > deadline) fail("row counts did not settle within 30 s");
			Thread.sleep(100);
		}
	}

	/** Until no ThreadPoolTaskExecutor bean of the context has a task queued or running. */
	private void awaitBackgroundExecutorsIdle(long deadline) throws InterruptedException {
		Collection<ThreadPoolTaskExecutor> pools = executorContext.getBeansOfType(ThreadPoolTaskExecutor.class).values();
		assertFalse(pools.isEmpty(), "no ThreadPoolTaskExecutor beans found; the drain would wait for nothing");
		while (pools.stream().anyMatch(p -> p.getActiveCount() > 0 || p.getQueueSize() > 0)) {
			if (System.currentTimeMillis() > deadline) fail("background executors did not drain within 30 s");
			Thread.sleep(20);
		}
	}

	/** Per-table change from {@code before} to {@code after}; tables that did not change are left out. */
	protected static Map<String, Long> delta(Map<String, Long> before, Map<String, Long> after) {
		Map<String, Long> d = new TreeMap<>();
		after.forEach((table, n) -> {
			long change = n - before.getOrDefault(table, 0L);
			if (0 != change) d.put(table, change);
		});
		before.keySet().stream().filter(t -> !after.containsKey(t)).forEach(t -> d.put(t, -before.get(t)));
		return d;
	}

	/**
	 * The change {@code action} makes to every table, measured between settled snapshots. Tried up
	 * to three times and the attempt with the fewest changed tables kept: unrelated background work
	 * can only add changes, and a write the action itself makes shows in every attempt.
	 */
	protected Map<String, Long> changeMadeBy(Supplier<Object> action) throws InterruptedException {
		Map<String, Long> best = null;
		for (int attempt = 0; attempt < 3; attempt++) {
			Map<String, Long> before = settledRowCounts();
			action.get();
			Map<String, Long> d = delta(before, settledRowCounts());
			if (null == best || d.size() < best.size()) best = d;
			if (best.isEmpty()) break;
		}
		return best;
	}
}
