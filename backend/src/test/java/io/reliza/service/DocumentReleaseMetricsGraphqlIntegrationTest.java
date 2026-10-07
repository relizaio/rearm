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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData.DocumentRef;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Task RD4-11: {@code Release.metrics} answers NOT_APPLICABLE for a release that is not scanned, so a
 * list hides its scan state instead of showing it pending; a software release reads its stored
 * metrics as before. In this package for the board-only BOARD_DOCUMENT component factory.
 */
@SpringBootTest(classes = {App.class})
public class DocumentReleaseMetricsGraphqlIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AtomicInteger VERSION = new AtomicInteger();
	private static final String METRICS = "query($o: ID, $r: [ID]) { releases(orgFilter: $o, releaseFilter: $r) {"
			+ " uuid metrics { dtrackFetchStatus firstScanned lastScanned critical policyViolationsLicenseTotal } } }";

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private ReleaseMetricsComputeService releaseMetricsComputeService;
	@Autowired private SharedReleaseService sharedReleaseService;

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

	private UUID release(Organization org, Component c, DocumentRef doc) throws RelizaException {
		var branch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		return ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid())
				.org(org.getUuid()).branch(branch.getUuid()).version("2.0." + VERSION.incrementAndGet())
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).document(doc).build(), WU)
				.getUuid();
	}

	private UserData admin(Organization org) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Admin " + tag, "rd411-" + tag + "@docs.io", true, List.of(org.getUuid()),
				"rd411-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.ADMIN, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Map<String, Object>> metricsAs(UserData user, Organization org, UUID... releases) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("rd4-11-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		Map<String, Object> vars = new LinkedHashMap<>();
		vars.put("o", org.getUuid().toString());
		vars.put("r", java.util.Arrays.stream(releases).map(UUID::toString).toList());
		ExecutionResult r = dgsQueryExecutor.execute(METRICS, vars);
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		List<Map<String, Object>> rows = (List<Map<String, Object>>) ((Map<String, Object>) r.getData()).get("releases");
		Map<String, Map<String, Object>> out = new LinkedHashMap<>();
		rows.forEach(row -> out.put((String) row.get("uuid"), (Map<String, Object>) row.get("metrics")));
		return out;
	}

	@Test
	public void aDocumentReleaseServesNotApplicableMetrics() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component docComponent = componentService.createBoardDocumentComponent(CreateComponentDto.builder()
				.name("rd4-11-gql-doc-" + UUID.randomUUID()).organization(org.getUuid())
				.type(ComponentType.COMPONENT).kind(ComponentKind.BOARD_DOCUMENT)
				.versionSchema("Micro").featureBranchVersioning("Branch.Micro").build(), WU);
		Component generic = componentService.createComponent("rd4-11-gql-sw-" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);

		// By kind: a BOARD_DOCUMENT component's release, through the batched component loader.
		UUID byKind = release(org, docComponent, null);
		// By document: a legacy GENERIC component carrying a board round (RD4-10).
		UUID byDocument = release(org, generic, new DocumentRef(RearmSpecificationType.ARCHITECTURE,
				"design/rd4-11.md", "d-" + UUID.randomUUID(), "text/markdown", null, null, null, null, 1, null,
				null, null));
		// Software: its stored metrics, as before.
		UUID software = release(org, generic, null);
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(sharedReleaseService.getRelease(software).orElseThrow());

		Map<String, Map<String, Object>> m = metricsAs(admin(org), org, byKind, byDocument, software);
		for (UUID doc : List.of(byKind, byDocument)) {
			Map<String, Object> dm = m.get(doc.toString());
			assertNotNull(dm, "a document still answers metrics, so a reader needs no null check");
			assertEquals("NOT_APPLICABLE", dm.get("dtrackFetchStatus"));
			assertNull(dm.get("firstScanned"));
			assertNull(dm.get("lastScanned"));
			assertEquals(0, dm.get("critical"));
			assertEquals(0, dm.get("policyViolationsLicenseTotal"));
		}
		Map<String, Object> sm = m.get(software.toString());
		assertNull(sm.get("dtrackFetchStatus"), "a software release carries no fetch status of its own");
		assertNotNull(sm.get("firstScanned"), "and reads its stored scan state");
	}
}
