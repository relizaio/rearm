/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.ServletWebRequest;

import graphql.ExecutionResult;
import graphql.GraphQLError;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.service.ApiKeyService;

/**
 * SCORE-41 T-17 (T-1, F-2, ADR-8 of rounds 2 and 3): a refused createComponentProgrammatic, run
 * through the programmatic GraphQL execution and the exception handlers rather than the fetcher
 * method, answers one error carrying the service's message, not "Internal server error", and creates
 * no component. The version-schema case pins that this holds for every RelizaException of the
 * service, not only the three declared-field validators.
 */
class DeclaredLicenseProgrammaticErrorIntegrationTest extends SbomScoreGraphqlTestBase {

	private static final String CREATE = "mutation($c: CreateComponentInput!) { createComponentProgrammatic("
			+ "component: $c) { uuid } }";
	private static final String VERSION_SCHEMA_REQUIRED =
			"Version schema is required. Please specify a version schema (e.g., 'semver')";

	@Autowired private ApiKeyService apiKeyService;

	/** Verified-principal form, as ProgrammaticComponentWriteOrderTest: what the filter leaves after it checked a bearer token. */
	private ExecutionResult asKey(ApiKey key, Map<String, Object> input) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.41"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		return dgsQueryExecutor.execute(CREATE, Map.of("c", input), Map.of(), headers, null,
				new ServletWebRequest(request));
	}

	private int components(Organization org) {
		return componentService.listComponentDataByOrganization(org.getUuid(), ComponentType.COMPONENT,
				ComponentType.PRODUCT).size();
	}

	/**
	 * field '-' for the version-schema case, which leaves versionSchema out; otherwise the input is
	 * valid but for the one declared field.
	 */
	@ParameterizedTest
	@CsvSource(delimiter = '|', quoteCharacter = '`', value = {
		"licenseUrl|javascript:alert(1)|licenseUrl invalid: ",
		"licenseExpression|NOASSERTION|licenseExpression invalid: NOASSERTION and NONE are not a declared licence",
		"supplierName|Acme <b>Inc</b>|supplierName invalid: ",
		"-|-|" + VERSION_SCHEMA_REQUIRED,
	})
	void aRefusedCreateAnswersTheServiceMessage(String field, String value, String message) throws Exception {
		Organization org = testInitializer.obtainOrganization();
		ApiKey orgKey = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.ORGANIZATION_RW, org.getUuid(), null,
				"score41 t17 organization key", WU);
		Map<String, Object> input = new HashMap<>();
		input.put("name", "score41-refused-" + UUID.randomUUID());
		input.put("type", "COMPONENT");
		input.put("featureBranchVersioning", "Branch.Micro");
		if (!"-".equals(field)) {
			input.put("versionSchema", "semver");
			input.put(field, value);
		}
		int before = components(org);

		ExecutionResult r = asKey(orgKey, input);

		GraphQLError error = onlyError(r);
		if ("-".equals(field)) {
			assertEquals(message, error.getMessage());
		} else {
			assertTrue(error.getMessage().startsWith(message), error.getMessage());
		}
		assertTrue(null == error.getExtensions() || !error.getExtensions().containsKey("ref"), error.toString());
		assertEquals(before, components(org), "no component was created");
	}
}
