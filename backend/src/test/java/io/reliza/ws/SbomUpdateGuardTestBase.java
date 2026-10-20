/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.service.ArtifactAttachmentGuard;
import io.reliza.service.ArtifactService;
import io.reliza.service.ArtifactServiceSpies;
import io.reliza.service.SbomUpdateGuard;
import io.reliza.service.SbomWriteAuthorityService;
import io.reliza.service.SharedReleaseService;

/**
 * Shared fixture of the SBOM rule B tests (SCORE-52, carried from SCORE-47): a real database, the
 * GraphQL layer, rebom mocked by an in-memory {@link RebomStore}, and the artifact service and the
 * attachment guard spied so that a test can see what reached the registry, the write and rebom.
 * One Spring context for every class over it.
 *
 * <p>The world: component A holds a BOM artifact X carrying serialNumber S at version 1 on its
 * release (an authorizing row: written without a copy mark), and rebom stores S's version 1 as
 * {@code cdx(urn:uuid:S, 1)}; component B has a release with its own BOM artifact Y.
 */
abstract class SbomUpdateGuardTestBase extends SbomScoreGraphqlTestBase {

	protected static final String ADD_ARTIFACT_MANUAL = "mutation($i: CreateArtifactInput) {"
			+ " addArtifactManual(artifactInput: $i) { uuid } }";
	protected static final String REBOM_REACHED = "rebom reached (test stop)";

	@MockitoSpyBean protected ArtifactAttachmentGuard guard;
	@MockitoSpyBean protected ArtifactService uploads;
	@Autowired protected SbomUpdateGuard updateGuard;
	@Autowired protected SbomWriteAuthorityService authorities;
	@Autowired protected SharedReleaseService sharedReleaseService;

	/** The in-memory rebom every upload of a test reaches, unless the test stops uploads itself. */
	protected RebomStore rebom;

	protected record World(Organization org, Component a, Component b, UUID releaseA, UUID releaseB, UUID serial,
			UUID x, UUID y) {}

	@BeforeEach
	void registryAndRebomStubbed() throws RelizaException {
		ArtifactServiceSpies.registryAccepts(uploads);
		rebom = new RebomStore();
		rebom.install(rebomService);
	}

	protected World world() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component a = component(org);
		Component b = component(org);
		UUID serial = UUID.randomUUID();
		UUID x = bomArtifact(org.getUuid(), serial, "1");
		rebom.storeWithoutArtifact(serial, 1, cdx("urn:uuid:" + serial, 1));
		UUID y = artifact(org.getUuid(), ArtifactType.BOM, BomFormat.CYCLONEDX, true);
		return new World(org, a, b, release(org, a, List.of(x)), release(org, b, List.of(y)), serial, x, y);
	}

	protected Component component(Organization org) throws RelizaException {
		return componentService.createComponent("score52-" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
	}

	/** A stored CycloneDX BOM artifact row carrying {@code serial} at {@code version}, listed nowhere yet. */
	protected UUID bomArtifact(UUID org, UUID serial, String version) throws RelizaException {
		return artifactService.createArtifact(ArtifactDto.builder().org(org).type(ArtifactType.BOM)
				.bomFormat(BomFormat.CYCLONEDX).displayIdentifier("score52-" + UUID.randomUUID())
				.internalBom(new InternalBom(serial, ArtifactBelongsTo.RELEASE)).version(version).build(), WU).getUuid();
	}

	/** An artifact row of {@code org} whose nested artifacts are {@code children}, listed nowhere yet. */
	protected UUID containerArtifact(UUID org, UUID... children) throws RelizaException {
		return artifactService.createArtifact(ArtifactDto.builder().org(org).type(ArtifactType.BOM)
				.bomFormat(BomFormat.CYCLONEDX).displayIdentifier("score52-container-" + UUID.randomUUID())
				.internalBom(new InternalBom(UUID.randomUUID(), ArtifactBelongsTo.RELEASE))
				.artifacts(new ArrayList<>(List.of(children))).build(), WU).getUuid();
	}

	protected UserData writer(Organization org, PermissionScope scope, UUID object) throws RelizaException {
		return member(org, scope, object, PermissionType.READ_WRITE);
	}

	/** A user with COMPONENT READ_WRITE on each of {@code components} and nothing else. */
	protected UserData writerOn(Organization org, UUID... components) throws RelizaException {
		UserData u = writer(org, PermissionScope.COMPONENT, components[0]);
		for (int i = 1; i < components.length; i++) {
			userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.COMPONENT, components[i],
					PermissionType.READ_WRITE, List.of(PermissionFunction.RESOURCE), null, WU);
		}
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	/** A user with organization-wide READ_WRITE. */
	protected UserData orgWriter(Organization org) throws RelizaException {
		return writer(org, PermissionScope.ORGANIZATION, org.getUuid());
	}

	protected UserData member(Organization org, PermissionScope scope, UUID object, PermissionType type)
			throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("RuleB " + tag, "score52-" + tag + "@score.io", true, List.of(org.getUuid()),
				"score52-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), scope, object, type,
				List.of(PermissionFunction.RESOURCE), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	protected static String cdx(String serialNumber, int version) {
		return "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"serialNumber\":\"" + serialNumber + "\","
				+ "\"version\":" + version + ",\"components\":[]}";
	}

	protected static MockMultipartFile file(String doc) {
		return new MockMultipartFile("file", "bom.json", "application/json", doc.getBytes(StandardCharsets.UTF_8));
	}

	/** addArtifactManual through the GraphQL layer, as {@code user}, attaching {@code doc} to {@code release}. */
	protected ExecutionResult manualUpload(UserData user, UUID release, String doc) {
		Map<String, Object> artifact = new LinkedHashMap<>();
		artifact.put("type", ArtifactType.BOM.name());
		artifact.put("storedIn", "REARM");
		artifact.put("displayIdentifier", "score52-sbom-" + UUID.randomUUID());
		artifact.put("file", file(doc));
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("release", release.toString());
		input.put("belongsTo", ArtifactBelongsTo.RELEASE.name());
		input.put("artifact", artifact);
		Map<String, Object> vars = new LinkedHashMap<>();
		vars.put("i", input);
		return as(user, ADD_ARTIFACT_MANUAL, vars);
	}

	protected static final String ADD_ARTIFACT_MANUAL_IN_PLACE = "mutation($i: CreateArtifactInput, $a: ID) {"
			+ " addArtifactManual(artifactInput: $i, artifactUuid: $a) { uuid } }";

	/**
	 * addArtifactManual with {@code artifactUuid}: a new version of {@code artifact} on {@code release},
	 * in place, as {@code user}; {@code format} is the document's bomFormat.
	 */
	protected ExecutionResult manualUpdate(UserData user, UUID release, UUID artifact, BomFormat format, String doc) {
		Map<String, Object> a = new LinkedHashMap<>();
		a.put("type", ArtifactType.BOM.name());
		a.put("storedIn", "REARM");
		a.put("bomFormat", format.name());
		a.put("displayIdentifier", "score52-sbom-" + UUID.randomUUID());
		a.put("file", file(doc));
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("release", release.toString());
		input.put("belongsTo", ArtifactBelongsTo.RELEASE.name());
		input.put("artifact", a);
		Map<String, Object> vars = new LinkedHashMap<>();
		vars.put("i", input);
		vars.put("a", artifact.toString());
		return as(user, ADD_ARTIFACT_MANUAL_IN_PLACE, vars);
	}

	/** addArtifactManual of a {@code format} document as a new artifact of {@code release}. */
	protected ExecutionResult manualUpload(UserData user, UUID release, BomFormat format, String doc) {
		Map<String, Object> a = new LinkedHashMap<>();
		a.put("type", ArtifactType.BOM.name());
		a.put("storedIn", "REARM");
		a.put("bomFormat", format.name());
		a.put("displayIdentifier", "score52-sbom-" + UUID.randomUUID());
		a.put("file", file(doc));
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("release", release.toString());
		input.put("belongsTo", ArtifactBelongsTo.RELEASE.name());
		input.put("artifact", a);
		return as(user, ADD_ARTIFACT_MANUAL, Map.of("i", input));
	}

	/** rebom stops every upload that reaches it, so a test sees the options it was sent. */
	protected void rebomStopsUploads() throws RelizaException {
		when(rebomService.uploadSbom(any(), any(), any(), any(), any())).thenThrow(new RelizaException(REBOM_REACHED));
	}

	protected List<UUID> artifactsOf(UUID release) {
		return sharedReleaseService.getReleaseData(release).map(ReleaseData::getArtifacts).orElseThrow();
	}

	/** Appends {@code artifact} to the artifacts of {@code release} directly in the row, past every guard. */
	protected void listDirectly(UUID release, UUID artifact) {
		jdbcTemplate.update("UPDATE rearm.releases SET record_data = jsonb_set(record_data, '{artifacts}',"
				+ " coalesce(record_data->'artifacts', '[]'::jsonb) || to_jsonb(?::text)) WHERE uuid = ?",
				artifact.toString(), release);
	}

	/** Removes {@code artifact} from the artifacts of {@code release} directly in the row (an unlisting). */
	protected void unlistDirectly(UUID release, UUID artifact) {
		jdbcTemplate.update("UPDATE rearm.releases SET record_data = jsonb_set(record_data, '{artifacts}',"
				+ " coalesce(record_data->'artifacts', '[]'::jsonb) - ?) WHERE uuid = ?", artifact.toString(), release);
	}

	/** {@code ReleaseService.replaceArtifact}: {@code old} replaced on {@code release} by a fresh unrelated artifact. */
	protected UUID replaceOnRelease(UUID release, UUID old) throws RelizaException {
		UUID org = sharedReleaseService.getReleaseData(release).orElseThrow().getOrg();
		UUID replacement = artifact(org, ArtifactType.BOM, BomFormat.CYCLONEDX, true);
		assertEquals(Boolean.TRUE, releaseService.replaceArtifact(old, replacement, release, WU));
		return replacement;
	}

	/** The one artifact {@code release} lists now that it did not list in {@code before}. */
	protected UUID addedRow(UUID release, List<UUID> before) {
		List<UUID> added = artifactsOf(release).stream().filter(a -> !before.contains(a)).toList();
		assertEquals(1, added.size(), added.toString());
		return added.get(0);
	}

	/** Whether the artifact row carries the copy mark of rule B. */
	protected boolean isCopy(UUID artifact) {
		return artifactService.getArtifactData(artifact).orElseThrow().isCopyRow();
	}

	/** The stored version text of an artifact row. */
	protected String versionOf(UUID artifact) {
		return artifactService.getArtifactData(artifact).orElseThrow().getVersion();
	}

	protected long artifactRowsWithSerial(UUID org, UUID serial) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.artifacts WHERE record_data->>'org' = ?"
				+ " AND record_data->'internalBom'->>'id' = ?", Long.class, org.toString(), serial.toString());
	}
}
