/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Organization;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.ApiKeyService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.OrganizationService;
import io.reliza.service.UserService;
import io.reliza.service.saas.SaasOrganizationService;

/**
 * The shared rig of the API key declarative tests (task RD3-11): a fresh organization, a FREEFORM caller with an
 * organization-wide level, the programmatic executor with the filter's principal, and the API_KEYS file builders.
 * Split out of ApiKeyDeclarativeIntegrationTest when TPF-15 added its grant-object cases.
 */
@SpringBootTest(classes = {App.class})
@SuppressWarnings("unchecked")
abstract class ApiKeyDeclarativeTestBase {

	static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	static final String APPLY = "mutation($s: ApiKeysSpecInput!, $d: Boolean, $src: DeclarativeSourceInput) {"
			+ " applyApiKeysProgrammatic(spec: $s, dryRun: $d, source: $src) { kind created updated unchanged archived errors"
			+ " changes { name action fields message } } }";
	static final String EXPORT = "query($k: [String!]) { exportApiKeysProgrammatic(keys: $k) { kind authoritative"
			+ " keys { name type notes status secretExpiresDays sessionMaxMinutes"
			+ " permissions { type functions approvals objects { scope object type functions approvals } }"
			+ " provenance { specHash source { repo path commit } } } } }";
	static final String LIST = "query($k: [String!]) { apiKeysProgrammatic(keys: $k) { uuid keyId name type"
			+ " status notes secretExpiresDays sessionMaxMinutes permissions { type functions }"
			+ " secretSlots { slot active createdDate expiresDate lastUsedDate } declarative { specHash source { repo path } } } }";
	static final String MINT = "mutation($k: String!, $s: Int!, $r: Boolean) {"
			+ " mintApiKeySecretProgrammatic(key: $k, slot: $s, rotate: $r) { keyId name slot minted secret expiresDate } }";
	static final String ARCHIVE = "mutation($n: String!) { archiveApiKeyProgrammatic(name: $n) { name status secretSlots { slot } } }";

	@MockitoSpyBean UserService userService;
	@Autowired DgsQueryExecutor dgsQueryExecutor;
	@Autowired SaasOrganizationService saasOrganizationService;
	@Autowired OrganizationService organizationService;
	@Autowired ApiKeyService apiKeyService;
	@Autowired LicenseStatus licenseStatus;

	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void operational() {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
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

	// ---------- helpers ----------

	/** A fresh organization, so declared names and the prune setting touch no other test. */
	Organization org() throws RelizaException {
		return saasOrganizationService.createOrganization("keys-" + UUID.randomUUID(), WU);
	}

	/** A FREEFORM key with this organization-wide level and functions, and a secret, like a CI caller's. */
	ApiKey caller(Organization org, PermissionType type, PermissionFunction... functions) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "caller", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), type, Set.of(functions), null)), WU);
		return apiKeyService.getApiKey(key.getUuid()).orElseThrow();
	}

	ApiKey admin(Organization org) throws RelizaException {
		return caller(org, PermissionType.ADMIN, PermissionFunction.CONFIGURATION_WRITE);
	}

	ExecutionResult as(ApiKey key, String query, Map<String, Object> vars) {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.12"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		return dgsQueryExecutor.execute(query, vars, Map.of(), headers, null, new ServletWebRequest(request));
	}

	static <T> T data(ExecutionResult r, String field) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		return (T) ((Map<String, Object>) r.getData()).get(field);
	}

	static Map<String, Object> file(Object... keys) {
		return file(false, keys);
	}

	static Map<String, Object> authoritativeFile(Object... keys) {
		return file(true, keys);
	}

	static Map<String, Object> file(boolean authoritative, Object[] keys) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("kind", "API_KEYS");
		m.put("version", 1);
		m.put("authoritative", authoritative);
		m.put("keys", List.of(keys));
		return m;
	}

	static Map<String, Object> key(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	Map<String, Object> apply(ApiKey caller, Map<String, Object> spec, boolean dryRun) {
		Map<String, Object> vars = new HashMap<>();
		vars.put("s", spec);
		vars.put("d", dryRun);
		vars.put("src", Map.of("repo", "github.com/acme/keys", "path", "keys/ci.yaml", "commit", "abc123"));
		return data(as(caller, APPLY, vars), "applyApiKeysProgrammatic");
	}

	static Map<String, Object> change(Map<String, Object> result, String name) {
		return ((List<Map<String, Object>>) result.get("changes")).stream().filter(c -> name.equals(c.get("name")))
				.findFirst().orElseThrow(() -> new AssertionError("no change for " + name + ": " + result));
	}

	List<Map<String, Object>> list(ApiKey caller, List<String> names) {
		Map<String, Object> vars = new HashMap<>();
		vars.put("k", names);
		return data(as(caller, LIST, vars), "apiKeysProgrammatic");
	}

	Map<String, Object> declared(ApiKey caller, String name) {
		List<Map<String, Object>> found = list(caller, List.of(name));
		assertEquals(1, found.size(), found.toString());
		return found.get(0);
	}

	Map<String, Object> mint(ApiKey caller, String key, int slot, boolean rotate) {
		return data(as(caller, MINT, Map.of("k", key, "s", slot, "r", rotate)), "mintApiKeySecretProgrammatic");
	}

	ApiKey keyNamed(Organization org, String name) {
		return apiKeyService.findByDeclaredName(org.getUuid(), name).orElseThrow();
	}
}
