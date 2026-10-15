/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.Utils;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.OrganizationService;
import tools.jackson.databind.JsonNode;

/**
 * Shared fixture of the programmatic merged release SBOM tests (task SCORE-24): the export and the
 * score as the CLI calls them, over HTTP on {@code /api/programmatic/graphql}, through the real
 * security chain, token endpoint and CLI login of {@link ProgrammaticArtifactDownloadTestBase}
 * (whose Spring context this shares). Rebom is the base's mock, here also merging, rendering CSV
 * and EXCEL and scoring.
 *
 * <p>The releases: {@code covered} and {@code uncovered} are components of organization A with one
 * release each, {@code foreign} a component of organization B with one; each release carries one
 * stored BOM. Keys are Free Form keys of A unless named foreign.
 */
abstract class ProgrammaticReleaseSbomTestBase extends ProgrammaticArtifactDownloadTestBase {

	private static final AtomicInteger VERSION = new AtomicInteger();

	protected static final String CSV = "name,version,purl\nscore24-lib,1.0,\n";
	/** What rebom returns for EXCEL: the workbook base64-encoded; a zip, so it starts with PK. */
	protected static final byte[] XLSX = new byte[] {'P', 'K', 3, 4, 20, 0, 6, 0};
	protected static final String REPORT = "{\"reportVersion\":1,\"input\":{\"sha256\":\"x\"},\"profiles\":[]}";

	protected static final String EXPORT = "mutation($r: ID, $c: ID, $v: String, $t: Boolean, $i: Boolean,"
			+ " $s: BomStructureType, $b: ArtifactBelongsToEnum, $m: BomMediaType, $x: [ArtifactCoverageType],"
			+ " $sm: Boolean, $im: Boolean, $fc: Boolean) {"
			+ " releaseSbomExportProgrammatic(release: $r, componentId: $c, version: $v, tldOnly: $t, ignoreDev: $i,"
			+ " structure: $s, belongsTo: $b, mediaType: $m, excludeCoverageTypes: $x, includeSupportMetadata: $sm,"
			+ " includeInternalMetadata: $im, excludeFileComponents: $fc) }";
	protected static final String SCORE = "query($r: ID, $c: ID, $v: String, $t: Boolean, $i: Boolean,"
			+ " $s: BomStructureType, $b: ArtifactBelongsToEnum, $x: [ArtifactCoverageType],"
			+ " $sm: Boolean, $im: Boolean, $fc: Boolean, $p: [String!]!) {"
			+ " releaseSbomScoreProgrammatic(release: $r, componentId: $c, version: $v, tldOnly: $t, ignoreDev: $i,"
			+ " structure: $s, belongsTo: $b, excludeCoverageTypes: $x, includeSupportMetadata: $sm,"
			+ " includeInternalMetadata: $im, excludeFileComponents: $fc, profiles: $p) }";
	/** The user field, through the user GraphQL layer, for the same-document comparison. */
	protected static final String USER_EXPORT = "mutation($r: ID!, $t: Boolean, $i: Boolean, $s: BomStructureType,"
			+ " $b: ArtifactBelongsToEnum, $m: BomMediaType, $x: [ArtifactCoverageType], $sm: Boolean, $im: Boolean,"
			+ " $fc: Boolean) { releaseSbomExport(release: $r, tldOnly: $t, ignoreDev: $i, structure: $s,"
			+ " belongsTo: $b, mediaType: $m, excludeCoverageTypes: $x, includeSupportMetadata: $sm,"
			+ " includeInternalMetadata: $im, excludeFileComponents: $fc) }";

	@Autowired protected DgsQueryExecutor dgsQueryExecutor;
	@Autowired protected OrganizationService organizationService;

	protected Releases r;

	/** One release per component, by uuid and by the component (uuid or name) and version that name it. */
	protected record Rel(Component component, String name, UUID uuid, String version) {}

	protected record Releases(Rel covered, Rel uncovered, Rel foreign, Key componentKey, Key readerKey) {}

	@BeforeEach
	void rebomMergesRendersAndScores() throws Exception {
		when(rebomService.mergeAndStoreBoms(anyList(), any(), any())).thenAnswer(inv -> UUID.randomUUID());
		when(rebomService.findBomByIdCsv(any(), any())).thenReturn(CSV);
		when(rebomService.findBomByIdExcel(any(), any())).thenReturn(Base64.getEncoder().encodeToString(XLSX));
		when(rebomService.scoreBom(any(), any())).thenReturn(REPORT);
		if (null == r) {
			Rel covered = rel(w.org());
			r = new Releases(covered, rel(w.org()), rel(w.foreignOrg()),
					freeform(w.org(), new PermissionDto(w.org().getUuid(), PermissionScope.COMPONENT, covered.component().getUuid(),
							PermissionType.READ_ONLY, Set.of(PermissionFunction.ARTIFACT_DOWNLOAD), null)),
					freeform(w.org(), orgWide(w.org(), PermissionType.READ_ONLY, PermissionFunction.RESOURCE)));
		}
	}

	private Rel rel(Organization org) throws Exception {
		String name = "score24-" + UUID.randomUUID();
		Component c = componentService.createComponent(name, org.getUuid(), ComponentType.COMPONENT, "semver",
				"Branch.Micro", null, WU);
		var branch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		String version = "24.0." + VERSION.incrementAndGet();
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid())
				.org(org.getUuid()).branch(branch.getUuid()).version(version)
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED)
				.artifacts(new ArrayList<>(List.of(bom(org.getUuid())))).build(), WU).getUuid();
		return new Rel(c, name, release, version);
	}

	// ---- requests ------------------------------------------------------------------------------

	/** Variables naming the release by uuid, then the pairs given; a pair with a null value is sent as null. */
	protected static Map<String, Object> byUuid(Rel rel, Object... pairs) {
		Map<String, Object> vars = new LinkedHashMap<>();
		vars.put("r", rel.uuid().toString());
		return with(vars, pairs);
	}

	/** Variables naming the release by its component's uuid and its version. */
	protected static Map<String, Object> byComponent(Rel rel, Object... pairs) {
		Map<String, Object> vars = new LinkedHashMap<>();
		vars.put("c", rel.component().getUuid().toString());
		vars.put("v", rel.version());
		return with(vars, pairs);
	}

	protected static Map<String, Object> with(Map<String, Object> vars, Object... pairs) {
		for (int i = 0; i < pairs.length; i += 2) vars.put((String) pairs[i], pairs[i + 1]);
		return vars;
	}

	/** A GraphQL request on the programmatic endpoint, answered 200: the parsed body. */
	protected JsonNode gql(String authorization, String document, Map<String, Object> vars) throws Exception {
		HttpResponse<String> resp = gqlResponse(authorization, document, vars);
		assertStatus(200, resp);
		return Utils.OM.readTree(resp.body());
	}

	protected HttpResponse<String> gqlResponse(String authorization, String document, Map<String, Object> vars) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("query", document);
		body.put("variables", vars);
		return post(ProgrammaticGraphQlConfig.PATH, Utils.OM.writeValueAsString(body), authorization);
	}

	/** The field's string value; fails with the errors when there are any. */
	protected static String value(JsonNode body, String field) {
		assertTrue(!body.has("errors") || body.get("errors").isEmpty(), () -> "errors: " + body.get("errors"));
		JsonNode v = body.get("data").get(field);
		assertNotNull(v, body::toString);
		return v.isNull() ? null : v.asText();
	}

	/** The only error's message; the field itself must be null. */
	protected static String error(JsonNode body, String field) {
		JsonNode errors = body.get("errors");
		assertNotNull(errors, () -> "no errors: " + body);
		assertEquals(1, errors.size(), errors::toString);
		JsonNode data = body.get("data");
		assertTrue(null == data || data.isNull() || data.get(field).isNull(), body::toString);
		return errors.get(0).get("message").asText();
	}

	/** The user field's answer for the downloader, through the user GraphQL layer. */
	protected String userExport(Map<String, Object> vars) {
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("score-24-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		try {
			ExecutionResult er = dgsQueryExecutor.execute(USER_EXPORT, vars);
			assertTrue(er.getErrors().isEmpty(), er.getErrors().toString());
			Map<String, Object> d = er.getData();
			return (String) d.get("releaseSbomExport");
		} finally {
			SecurityContextHolder.clearContext();
		}
	}

	protected void setSupportInjection(Organization org, SupportInjectionSetting setting) throws Exception {
		OrganizationData.Settings s = new OrganizationData.Settings();
		s.setSupportInjection(setting);
		organizationService.updateSettings(org.getUuid(), s, WU);
	}
}
