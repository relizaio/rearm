/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.NoMatchableComponentsReason;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.oss.OssReleaseService;

/**
 * TPF-132 architecture-4 section 6 test 2: DependencyTrackMetrics.bomScan end to end through DGS (which
 * discovers ReleaseBomScanDataFetcher) on releases seeded and computed as TPF-129 computes them: the four
 * states on Release.metrics, null on a legacy row until the next compute backfills it, null on a document
 * release, and (F-2) the same stored tally on Query.findingsPerDayForBranch under BRANCH READ, refused to a
 * reader of another organisation.
 */
public class ReleaseBomScanGraphqlIntegrationTest extends ReleaseScanSelfHealTestBase {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AtomicInteger VERSION = new AtomicInteger();
	private static final String BOM_SCAN = "bomScan { state scannableBoms notScannableBoms reasons }";
	private static final String RELEASES = "query($o: ID, $r: [ID]) { releases(orgFilter: $o, releaseFilter: $r) {"
			+ " uuid metrics { dtrackFetchStatus firstScanned " + BOM_SCAN + " } } }";
	private static final String FOR_BRANCH = "query($b: ID!, $d: String!) { findingsPerDayForBranch(branchUuid: $b,"
			+ " date: $d) { " + BOM_SCAN + " } }";

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private LicenseStatus licenseStatus;

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
	}

	// ---- fixture ------------------------------------------------------------------------------------------

	private UserData user(Organization org, PermissionType type, List<PermissionFunction> functions)
			throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("BomScan " + tag, "tpf132-" + tag + "@bom-scan.io", true,
				List.of(org.getUuid()), "tpf132-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				type, functions, null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private UserData admin(Organization org) throws RelizaException {
		return user(org, PermissionType.ADMIN, List.of());
	}

	/** A member of the organisation with RESOURCE READ at organisation scope. */
	private UserData reader(Organization org) throws RelizaException {
		return user(org, PermissionType.READ_ONLY, List.of(PermissionFunction.RESOURCE));
	}

	private ExecutionResult as(UserData user, String query, Map<String, Object> vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tpf132-bom-scan").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(query, vars);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Map<String, Object>> metricsAs(UserData user, Organization org, UUID... releases) {
		Map<String, Object> vars = new LinkedHashMap<>();
		vars.put("o", org.getUuid().toString());
		vars.put("r", Arrays.stream(releases).map(UUID::toString).toList());
		ExecutionResult r = as(user, RELEASES, vars);
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		List<Map<String, Object>> rows = (List<Map<String, Object>>) ((Map<String, Object>) r.getData()).get("releases");
		Map<String, Map<String, Object>> out = new LinkedHashMap<>();
		rows.forEach(row -> out.put((String) row.get("uuid"), (Map<String, Object>) row.get("metrics")));
		assertEquals(releases.length, out.size(), "every seeded release is read");
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> bomScanOf(Map<String, Object> metrics) {
		assertNotNull(metrics);
		return (Map<String, Object>) metrics.get("bomScan");
	}

	private static void assertServed(Map<String, Object> served, String state, int scannable, int notScannable,
			List<String> reasons) {
		assertNotNull(served, "a computed release serves its tally");
		assertEquals(state, served.get("state"));
		assertEquals(scannable, served.get("scannableBoms"));
		assertEquals(notScannable, served.get("notScannableBoms"));
		assertEquals(reasons, served.get("reasons"));
	}

	private UUID scannedBom(UUID org) {
		UUID bom = saveUnscannedBomArtifact(org);
		markBomScanned(bom, 1);
		return bom;
	}

	private UUID markedBom(UUID org) {
		UUID bom = saveUnscannedBomArtifact(org);
		markBomNotScannable(bom, NoMatchableComponentsReason.NO_PURL_OR_CPE);
		return bom;
	}

	private UUID computedRelease(UUID org, List<UUID> boms) {
		Release r = saveScanPendingRelease(org, new ArrayList<>());
		if (!boms.isEmpty()) attachArtifacts(r.getUuid(), boms);
		computeOnRescan(r.getUuid());
		return r.getUuid();
	}

	private UUID createdRelease(Organization org, Component c) throws RelizaException {
		var branch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		return ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid())
				.org(org.getUuid()).branch(branch.getUuid()).version("3.0." + VERSION.incrementAndGet())
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).build(), WU)
				.getUuid();
	}

	private Component softwareComponent(Organization org) throws RelizaException {
		return componentService.createComponent("tpf132-sw-" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
	}

	private static Map<String, Object> branchVars(UUID branch) {
		return Map.of("b", branch.toString(), "d", LocalDate.now(ZoneOffset.UTC).toString());
	}

	// ---- cases --------------------------------------------------------------------------------------------

	/** (a): the four states, one release each, served from the stored tally. */
	@Test
	public void eachComputedStateIsServed() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID o = org.getUuid();
		UUID noBoms = computedRelease(o, List.of());
		UUID allScannable = computedRelease(o, List.of(scannedBom(o)));
		UUID someNotScannable = computedRelease(o, List.of(scannedBom(o), markedBom(o)));
		UUID allNotScannable = computedRelease(o, List.of(markedBom(o)));

		Map<String, Map<String, Object>> m = metricsAs(admin(org), org, noBoms, allScannable, someNotScannable,
				allNotScannable);

		assertServed(bomScanOf(m.get(noBoms.toString())), "NO_BOMS", 0, 0, List.of());
		assertServed(bomScanOf(m.get(allScannable.toString())), "ALL_SCANNABLE", 1, 0, List.of());
		assertServed(bomScanOf(m.get(someNotScannable.toString())), "SOME_NOT_SCANNABLE", 1, 1,
				List.of("NO_PURL_OR_CPE"));
		Map<String, Object> marked = m.get(allNotScannable.toString());
		assertServed(bomScanOf(marked), "ALL_NOT_SCANNABLE", 0, 1, List.of("NO_PURL_OR_CPE"));
		assertNotNull(marked.get("firstScanned"), "the all-marked release is anchored (TPF-126 row 9)");
	}

	/** (b): a row computed before TPF-129 serves null until the next compute backfills the tally. */
	@Test
	public void aLegacyRowServesNullUntilTheNextCompute() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID bom = saveUnscannedBomArtifact(org.getUuid());
		markBomScanned(bom, 0);
		Object bomFirstScanned = artifactRepository.findById(bom).orElseThrow().getMetrics().get("firstScanned");
		Release r = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		Map<String, Object> metrics = new HashMap<>();
		metrics.put("firstScanned", bomFirstScanned);
		metrics.put("lastScanned", ((Number) bomFirstScanned).doubleValue() - 60);
		r.setMetrics(metrics);
		releaseRepository.save(r);
		attachArtifact(r.getUuid(), bom);
		UserData admin = admin(org);

		Map<String, Object> before = metricsAs(admin, org, r.getUuid()).get(r.getUuid().toString());
		assertNotNull(before.get("firstScanned"), "the legacy row is scanned");
		assertNull(bomScanOf(before), "and carries no tally: null, never an invented NO_BOMS");

		computeOnRescan(r.getUuid());

		assertServed(bomScanOf(metricsAs(admin, org, r.getUuid()).get(r.getUuid().toString())), "ALL_SCANNABLE",
				1, 0, List.of());
	}

	/** (c): a document release answers NOT_APPLICABLE metrics, which carry no tally. */
	@Test
	public void aDocumentReleaseServesNull() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component docComponent = componentService.createBoardDocumentComponent(CreateComponentDto.builder()
				.name("tpf132-gql-doc-" + UUID.randomUUID()).organization(org.getUuid())
				.type(ComponentType.COMPONENT).kind(ComponentKind.BOARD_DOCUMENT)
				.versionSchema("Micro").featureBranchVersioning("Branch.Micro").build(), WU);
		UUID doc = createdRelease(org, docComponent);

		Map<String, Object> dm = metricsAs(admin(org), org, doc).get(doc.toString());
		assertEquals("NOT_APPLICABLE", dm.get("dtrackFetchStatus"));
		assertNull(bomScanOf(dm));
	}

	/** (d) and (e), F-2: the branch root serves the stored tally of its latest assembled release under BRANCH READ. */
	@Test
	public void theBranchRootServesTheStoredTallyOnlyToABranchReader() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component c = softwareComponent(org);
		UUID branch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow().getUuid();
		UUID release = createdRelease(org, c);
		attachArtifacts(release, List.of(scannedBom(org.getUuid()), markedBom(org.getUuid())));
		computeOnRescan(release);
		UserData admin = admin(org);

		ExecutionResult r = as(admin, FOR_BRANCH, branchVars(branch));
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		@SuppressWarnings("unchecked")
		Map<String, Object> forBranch = (Map<String, Object>) ((Map<String, Object>) r.getData())
				.get("findingsPerDayForBranch");
		Map<String, Object> onBranch = bomScanOf(forBranch);
		assertServed(onBranch, "SOME_NOT_SCANNABLE", 1, 1, List.of("NO_PURL_OR_CPE"));
		assertEquals(bomScanOf(metricsAs(admin, org, release).get(release.toString())), onBranch,
				"the branch root serves what Release.metrics serves for that release");

		Organization other = testInitializer.obtainOrganization();
		ExecutionResult refused = as(reader(other), FOR_BRANCH, branchVars(branch));
		List<GraphQLError> errors = refused.getErrors();
		assertEquals(1, errors.size(), errors.toString());
		assertEquals("Not authorized", errors.get(0).getMessage());
		Map<?, ?> data = refused.getData();
		assertNull(null == data ? null : data.get("findingsPerDayForBranch"));
	}
}
