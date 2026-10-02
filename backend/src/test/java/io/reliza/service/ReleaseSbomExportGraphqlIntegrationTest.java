/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
import graphql.GraphQLError;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * TEA-1: the merged SBOM export (releaseSbomExport) of a release that carries nothing answers
 * one BAD_REQUEST error that names the release, instead of "No SBOMs found!". No merge is
 * attempted, so no rebom is reached.
 */
@SpringBootTest(classes = {App.class})
public class ReleaseSbomExportGraphqlIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String EXPORT = "mutation($r: ID!) { releaseSbomExport(release: $r, mediaType: JSON,"
			+ " structure: FLAT) }";

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
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

	private UUID releaseOf(Organization org, ComponentType type) throws RelizaException {
		Component c = componentService.createComponent("tea-1-export-" + type + "-" + UUID.randomUUID(),
				org.getUuid(), type, "semver", "Branch.Micro", null, WU);
		var branch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		return ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid())
				.org(org.getUuid()).branch(branch.getUuid()).version("9000.1.0")
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).build(), WU)
				.getUuid();
	}

	private UserData admin(Organization org) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Admin " + tag, "tea1-" + tag + "@export.io", true, List.of(org.getUuid()),
				"tea1-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.ADMIN, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private ExecutionResult exportAs(UserData user, UUID release) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tea-1-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		Map<String, Object> vars = new LinkedHashMap<>();
		vars.put("r", release.toString());
		return dgsQueryExecutor.execute(EXPORT, vars);
	}

	private static Object exportData(ExecutionResult r) {
		Map<String, Object> data = r.getData();
		return null == data ? null : data.get("releaseSbomExport");
	}

	@Test
	public void aProductReleaseWithNothingNamesItselfAsABadRequest() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID release = releaseOf(org, ComponentType.PRODUCT);

		ExecutionResult r = exportAs(admin(org), release);

		assertEquals(1, r.getErrors().size(), r.getErrors().toString());
		GraphQLError err = r.getErrors().get(0);
		assertTrue(err.getMessage().startsWith("No SBOMs found: product release "), err.getMessage());
		assertTrue(err.getMessage().contains("(" + release + ")"), err.getMessage());
		assertTrue(err.getMessage().contains("it has no component releases"), err.getMessage());
		assertEquals("BAD_REQUEST", String.valueOf(err.getErrorType()));
		assertNull(exportData(r));
	}

	@Test
	public void aComponentReleaseWithNothingNamesItselfAsABadRequest() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		UUID release = releaseOf(org, ComponentType.COMPONENT);

		ExecutionResult r = exportAs(admin(org), release);

		assertEquals(1, r.getErrors().size(), r.getErrors().toString());
		GraphQLError err = r.getErrors().get(0);
		assertTrue(err.getMessage().startsWith("No SBOMs found: release "), err.getMessage());
		assertTrue(err.getMessage().contains("(" + release + ")"), err.getMessage());
		assertEquals("BAD_REQUEST", String.valueOf(err.getErrorType()));
		assertNull(exportData(r));
	}
}
