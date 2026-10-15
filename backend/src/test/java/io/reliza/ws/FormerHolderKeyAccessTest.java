/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

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
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.ApiKeyService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * A former member still named holder of a Free Form key, as leaving an org left them before this change,
 * keeps no say over the key: minting its secret is refused (task t20261013-190257-31171).
 */
@SpringBootTest(classes = {App.class})
public class FormerHolderKeyAccessTest {

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private LicenseStatus licenseStatus;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String MINT = "mutation($k: ID!) { addApiKeySecret(apiKeyUuid: $k) { id } }";
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
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
		SecurityContextHolder.clearContext();
	}

	private UserData member(Organization org) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("H " + tag, "h-" + tag + "@former-holder.io", true, List.of(org.getUuid()),
				"h-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE, null, null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private ExecutionResult asPerson(UserData user, String query, Map<String, Object> vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("former-holder-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(query, vars);
	}

	@Test
	public void aFormerMemberStillNamedHolderCannotMint() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		UserData holder = member(org);
		ApiKey ak = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "held", WU);
		apiKeyService.setApiKeyHolder(ak.getUuid(), holder.getUuid(), WU);
		Map<String, Object> vars = Map.of("k", ak.getUuid().toString());

		ExecutionResult minted = asPerson(holder, MINT, vars);
		assertTrue(minted.getErrors().isEmpty(), "a member holding the key mints: " + minted.getErrors());

		userService.removeUserFromOrg(org.getUuid(), holder.getUuid(), WU);
		apiKeyService.setApiKeyHolder(ak.getUuid(), holder.getUuid(), WU);
		UserData former = userService.getUserData(holder.getUuid()).orElseThrow();
		assertFalse(former.getOrganizations().contains(org.getUuid()));

		ExecutionResult refused = asPerson(former, MINT, vars);
		assertEquals(1, refused.getErrors().size(), "a former member still named holder mints nothing");
	}
}
