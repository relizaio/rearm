/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.SbomUpdateNotPermittedException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.Component;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ArtifactServiceSpies;

/**
 * The programmatic side of the SBOM rule B tests (SCORE-52, carried from SCORE-47 T-18): resolver
 * environments for API keys, and uploads that go the real way only for the world's serialNumber S
 * ({@link #onlySerialIsRefused}). An upload of S goes the real way (rule B, then the in-memory
 * rebom); any other
 * upload is accepted as an artifact row written in the caller's transaction, recorded in
 * {@link #accepted}, so a rollback of the request takes it with it.
 */
abstract class SbomUpdateGuardProgrammaticTestBase extends SbomUpdateGuardTestBase {

	@Autowired protected ReleaseDatafetcher releaseDatafetcher;
	@Autowired protected ApiKeyService apiKeyService;
	@Autowired protected ApplicationContext applicationContext;

	/** The artifact rows the accepted uploads wrote, in upload order. */
	protected final List<UUID> accepted = new ArrayList<>();

	/** Only an upload of {@code serial} reaches the real path; the rest are accepted as rows. */
	protected void onlySerialIsRefused(World w) throws RelizaException {
		accepted.clear();
		doAnswer(inv -> {
			Resource file = inv.getArgument(1);
			String doc = new String(file.getContentAsByteArray(), StandardCharsets.UTF_8);
			if (doc.contains(w.serial().toString())) {
				return inv.callRealMethod();
			}
			UUID row = artifact(((ArtifactDto) inv.getArgument(0)).getOrg(), ArtifactType.BOM, BomFormat.CYCLONEDX, true);
			accepted.add(row);
			return row;
		}).when(uploads).uploadArtifact(any(), any(), any(), any(), any());
	}

	@BeforeEach
	void clearAccepted() {
		accepted.clear();
	}

	protected ApiKey componentKey(World w, Component c) throws RelizaException {
		return apiKeyService.getApiKey(apiKeyService.createObjectApiKey(c.getUuid(), ApiTypeEnum.COMPONENT,
				w.org().getUuid(), null, "score52 component key", WU).getUuid()).orElseThrow();
	}

	/** A resolver environment carrying the principal the programmatic filter leaves for {@code key}. */
	protected DgsDataFetchingEnvironment env(ApiKey key, String argument, Object input) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.47"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		Map<String, Object> arguments = new HashMap<>();
		arguments.put(argument, input);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(arguments).build(), applicationContext);
	}

	protected static String commit() {
		byte[] b = new byte[20];
		ThreadLocalRandom.current().nextBytes(b);
		return HexFormat.of().formatHex(b);
	}

	protected static Map<String, Object> sce(String uri, String commit, String... docs) {
		Map<String, Object> m = map("uri", uri, "type", "git", "commit", commit);
		if (docs.length > 0) m.put("artifacts", entries(docs));
		return m;
	}

	protected static String uri() {
		return "github.com/example/score52-" + UUID.randomUUID();
	}

	protected static List<Map<String, Object>> deliverables(String... docs) {
		return new ArrayList<>(List.of(map("displayIdentifier", "score52-odel-" + UUID.randomUUID(),
				"type", CdxType.CONTAINER.name(), "artifacts", entries(docs))));
	}

	/** The artifacts list of the source code entry of {@code commit}, or null when there is no entry. */
	protected List<Object> sceArtifacts(String commit) {
		List<String> rows = jdbcTemplate.queryForList("SELECT coalesce(record_data->'artifacts', '[]'::jsonb)::text"
				+ " FROM rearm.source_code_entries WHERE record_data->>'commit' = ?", String.class, commit);
		assertTrue(rows.size() <= 1, rows.toString());
		return rows.isEmpty() ? null : Utils.OM.readValue(rows.get(0), List.class);
	}

	protected static Map<String, Object> artifactInput(UUID release, Object... parts) {
		Map<String, Object> input = map(parts);
		input.put(CommonVariables.RELEASE_FIELD, release.toString());
		return input;
	}

	@FunctionalInterface
	protected interface ThrowingCall {
		void run() throws Exception;
	}

	/**
	 * {@code call} is refused by rule B before any byte leaves: nothing retained or written by the
	 * upload, nothing sent to rebom. Spies are cleared first, so earlier uploads of the test do not count.
	 */
	protected SbomUpdateNotPermittedException assertRefusedUpload(ThrowingCall call) throws Exception {
		clearInvocations(uploads, rebomService);
		SbomUpdateNotPermittedException e = assertThrows(SbomUpdateNotPermittedException.class, call::run);
		ArtifactServiceSpies.nothingRetainedOrWritten(uploads);
		verify(rebomService, never()).uploadSbom(any(), any(), any(), any(), any());
		return e;
	}

	protected ApiKey orgRwKey(World w) {
		return apiKeyService.getApiKey(apiKeyService.createObjectApiKey(w.org().getUuid(), ApiTypeEnum.ORGANIZATION_RW,
				w.org().getUuid(), null, "score52 org rw key", WU).getUuid()).orElseThrow();
	}

	/** A Free Form key with {@code orgType} on the organization (null for none) and COMPONENT READ_WRITE on each of {@code components}. */
	protected ApiKey freeformKey(World w, PermissionType orgType, UUID... components) throws RelizaException {
		ApiKey k = apiKeyService.createObjectApiKey(w.org().getUuid(), ApiTypeEnum.FREEFORM, w.org().getUuid(),
				UUID.randomUUID().toString(), "score52 free form key", WU);
		List<PermissionDto> perms = new ArrayList<>();
		for (UUID c : components) {
			perms.add(new PermissionDto(null, PermissionScope.COMPONENT, c, PermissionType.READ_WRITE,
					Set.of(PermissionFunction.RESOURCE), null));
		}
		apiKeyService.setPermissionsOnApiKey(k.getUuid(), orgType, perms, WU);
		return apiKeyService.getApiKey(k.getUuid()).orElseThrow();
	}

	/** addArtifactProgrammatic: {@code docs} as release artifacts of {@code release}, with {@code key}. */
	protected ReleaseData progUpload(ApiKey key, UUID release, String... docs) throws Exception {
		return releaseDatafetcher.addArtifactProg(env(key, "artifactInput",
				artifactInput(release, "releaseArtifacts", entries(docs))));
	}

	/** addArtifactProgrammatic: one release artifact entry as given. */
	protected ReleaseData progUploadEntry(ApiKey key, UUID release, Map<String, Object> entry) throws Exception {
		return releaseDatafetcher.addArtifactProg(env(key, "artifactInput",
				artifactInput(release, "releaseArtifacts", new ArrayList<>(List.of(entry)))));
	}

	/** A release artifact entry carrying {@code children} as nested uploads. */
	protected static Map<String, Object> container(String doc, Map<String, Object>... children) {
		Map<String, Object> outer = entry(doc);
		outer.put("artifacts", new ArrayList<>(List.of(children)));
		return outer;
	}

	/** A release input of {@code c}'s base branch at {@code version}, DRAFT. */
	protected Map<String, Object> releaseInputOf(Component c, String version) {
		return map(CommonVariables.COMPONENT_FIELD, c.getUuid().toString(),
				CommonVariables.BRANCH_FIELD, branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow()
						.getUuid().toString(),
				CommonVariables.VERSION_FIELD, version, CommonVariables.LIFECYCLE_FIELD, ReleaseLifecycle.DRAFT.name());
	}

	/** addReleasesProgrammatic with one release input. */
	protected void progReleases(ApiKey key, Map<String, Object> releaseInput) throws Exception {
		releaseDatafetcher.addReleasesProgrammatic(env(key, "releases", new ArrayList<>(List.of(releaseInput))));
	}

	/** The uuid of the release of {@code c} at {@code version}. */
	protected UUID releaseOf(Component c, String version) {
		return jdbcTemplate.queryForObject("SELECT uuid FROM rearm.releases WHERE record_data->>'component' = ?"
				+ " AND record_data->>'version' = ?", UUID.class, c.getUuid().toString(), version);
	}

	/** The artifact rows of {@code org} carrying {@code serial}, oldest first. */
	protected List<UUID> rowsOf(UUID org, UUID serial) {
		return jdbcTemplate.queryForList("SELECT uuid FROM rearm.artifacts WHERE record_data->>'org' = ?"
				+ " AND record_data->'internalBom'->>'id' = ? ORDER BY created_date, uuid", UUID.class, org.toString(),
				serial.toString());
	}

	protected static Map<String, Object> entry(String doc) {
		Map<String, Object> a = new LinkedHashMap<>();
		a.put("type", ArtifactType.BOM.name());
		a.put("bomFormat", BomFormat.CYCLONEDX.name());
		a.put("displayIdentifier", "score52-" + UUID.randomUUID());
		a.put("file", file(doc));
		return a;
	}

	protected static List<Map<String, Object>> entries(String... docs) {
		List<Map<String, Object>> list = new ArrayList<>();
		for (String doc : docs) list.add(entry(doc));
		return list;
	}

	/** A document under a serialNumber of its own, which the world's holders never see. */
	protected static String fresh() {
		return cdx("urn:uuid:" + UUID.randomUUID(), 1);
	}

	/** The world's serialNumber at version 2: a supersession of A's lineage. */
	protected static String superseding(World w) {
		return cdx("urn:uuid:" + w.serial(), 2);
	}

	protected List<ReleaseUpdateEvent> guardEvents(UUID release) {
		ReleaseData rd = sharedReleaseService.getReleaseData(release).orElseThrow();
		return null == rd.getUpdateEvents() ? List.of()
				: rd.getUpdateEvents().stream().filter(e -> e.rus() == ReleaseUpdateScope.GUARD).toList();
	}

	protected long releaseRows(Component c, String version) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.releases WHERE record_data->>'component' = ?"
				+ " AND record_data->>'version' = ?", Long.class, c.getUuid().toString(), version);
	}

	protected long artifactRows(List<UUID> uuids) {
		long n = 0;
		for (UUID u : uuids) {
			n += jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.artifacts WHERE uuid = ?", Long.class, u);
		}
		return n;
	}

	protected static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}
}
