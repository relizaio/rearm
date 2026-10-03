/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AcollectionData;
import io.reliza.model.AcollectionData.VersionedArtifact;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.Branch;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseArtifactIndex;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.ReleaseArtifactIndexRepository;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * TEA-4 against the test database: a generated BOM bound to a release through
 * ReleaseData.syntheticArtifacts persists, is audited, is visible on the GraphQL Release and to the
 * artifact-holder lookup the download endpoints authorize through, and stays out of the release
 * collection, the sbom_components reconcile and the inventory attach flow. Artifacts are seeded
 * without rebom, the way InPlaceReUploadCarryForwardTest seeds them.
 */
@SpringBootTest(classes = {App.class})
public class SyntheticArtifactIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String RELEASE_QUERY = "query($r: ID!) { release(releaseUuid: $r) {"
			+ " uuid artifacts syntheticArtifacts artifactDetails { uuid }"
			+ " syntheticArtifactDetails { uuid internalBom { id belongsTo } tags { key value } }"
			+ " updateEvents { rus rua objectId } } }";

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private ReleaseService releaseService;
	@Autowired private ArtifactService artifactService;
	@Autowired private SharedArtifactService sharedArtifactService;
	@Autowired private SyntheticArtifactService syntheticArtifactService;
	@Autowired private AcollectionService acollectionService;
	@Autowired private SbomComponentService sbomComponentService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ReleaseArtifactIndexRepository releaseArtifactIndexRepository;
	@Autowired private ArtifactSbomComponentRepository artifactSbomComponentRepository;
	@Autowired private ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
	@Autowired private LicenseStatus licenseStatus;

	private boolean wasSealed;
	private boolean wasLicensed;

	private Organization org;
	private UUID releaseUuid;
	private UUID inventory;
	private UUID synthetic;

	@BeforeEach
	void fixtures() throws Exception {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);

		org = testInitializer.obtainOrganization();
		String slug = "tea-4-synthetic-" + UUID.randomUUID().toString().substring(0, 8);
		UUID componentUuid = componentService.createComponent(CreateComponentDto.builder()
				.organization(org.getUuid()).name(slug).type(ComponentType.COMPONENT)
				.versionSchema("semver").featureBranchVersioning("Branch.Micro").build(), WU).getUuid();
		Branch branch = branchService.findBranchByName(componentUuid, "main", true, WU).get();
		releaseUuid = ossReleaseService.createRelease(ReleaseDto.builder()
				.component(componentUuid).branch(branch.getUuid()).org(org.getUuid())
				.status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseData.ReleaseLifecycle.DRAFT)
				.version("1.0.0").build(), WU).getUuid();

		inventory = seedBom("inventory.cdx.json", ArtifactBelongsTo.RELEASE, List.of());
		releaseService.addArtifact(inventory, releaseUuid, WU);
		synthetic = seedBom("generated.cdx.json", ArtifactBelongsTo.SYNTHETIC, List.of(
				new TagRecord(CommonVariables.SYNTHETIC_ARTIFACT_TAG_KEY, "true", Removable.NO),
				new TagRecord(CommonVariables.SYNTHETIC_OF_RELEASE_TAG_KEY, releaseUuid.toString(), Removable.NO)));
	}

	@AfterEach
	void restore() {
		SecurityContextHolder.clearContext();
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	/** A ReARM-stored BOM row with an internal BOM pointer and no rebom side. */
	private UUID seedBom(String name, ArtifactBelongsTo belongsTo, List<TagRecord> tags) throws Exception {
		Artifact a = artifactService.persistArtifact(ArtifactDto.builder().uuid(UUID.randomUUID()).org(org.getUuid())
				.type(ArtifactType.BOM).displayIdentifier(name).storedIn(StoredIn.REARM).build(), WU);
		ArtifactData ad = ArtifactData.dataFromRecord(sharedArtifactService.getArtifact(a.getUuid()).orElseThrow());
		ad.setInternalBom(new InternalBom(UUID.randomUUID(), belongsTo));
		ad.setTags(new ArrayList<>(tags));
		sharedArtifactService.saveArtifact(sharedArtifactService.getArtifact(a.getUuid()).orElseThrow(), ad, WU);
		return a.getUuid();
	}

	private ReleaseData stored() {
		return sharedReleaseService.getReleaseData(releaseUuid).orElseThrow();
	}

	private List<ReleaseUpdateEvent> syntheticEvents() {
		return stored().getUpdateEvents().stream().filter(e -> e.rus() == ReleaseUpdateScope.SYNTHETIC_ARTIFACT).toList();
	}

	private static Set<UUID> artifactSet(AcollectionData acd) {
		return acd.getArtifacts().stream().map(VersionedArtifact::artifactUuid).collect(Collectors.toSet());
	}

	@Test
	void bindPersistsTheListBesideTheInventoryWithOneAddedEvent() throws Exception {
		syntheticArtifactService.bind(releaseUuid, synthetic, WU);

		ReleaseData rd = stored();
		assertEquals(List.of(synthetic), rd.getSyntheticArtifacts());
		assertEquals(List.of(inventory), rd.getArtifacts());
		List<ReleaseUpdateEvent> events = syntheticEvents();
		assertEquals(1, events.size(), events.toString());
		assertEquals(ReleaseUpdateAction.ADDED, events.get(0).rua());
		assertEquals(synthetic, events.get(0).objectId());
	}

	@Test
	void theCollectionSnapshotIsTheSameBeforeAndAfterTheBind() throws Exception {
		AcollectionData before = acollectionService.resolveReleaseCollection(releaseUuid, WU);

		syntheticArtifactService.bind(releaseUuid, synthetic, WU);
		AcollectionData after = acollectionService.resolveReleaseCollection(releaseUuid, WU);

		assertEquals(Set.of(inventory), artifactSet(before));
		assertEquals(before.getVersion(), after.getVersion(), "binding a generated artifact is no collection change");
		assertEquals(Set.of(inventory), artifactSet(after));
	}

	@Test
	void theReconcileWritesNothingForTheGeneratedBom() throws Exception {
		syntheticArtifactService.bind(releaseUuid, synthetic, WU);

		sbomComponentService.reconcileReleaseSbomComponents(releaseUuid);

		List<ReleaseArtifactIndex> index = releaseArtifactIndexRepository.findByOrgAndReleaseUuid(org.getUuid(), releaseUuid);
		assertFalse(index.stream().anyMatch(i -> synthetic.equals(i.getCanonicalArtifactUuid())), index.toString());
		assertTrue(artifactCanonicalMapRepository.findByArtifactUuid(synthetic).isEmpty(), "never mapped");
		assertFalse(artifactSbomComponentRepository.existsByCanonicalArtifactUuid(synthetic));
	}

	@Test
	void theHolderLookupSeesTheSyntheticHolder() throws Exception {
		syntheticArtifactService.bind(releaseUuid, synthetic, WU);

		assertTrue(sharedReleaseService.gatherReleasesForArtifact(synthetic, org.getUuid()).stream()
				.anyMatch(rd -> releaseUuid.equals(rd.getUuid())));
		assertEquals(List.of(releaseUuid), sharedReleaseService.findReleasesBySyntheticArtifact(synthetic, org.getUuid())
				.stream().map(r -> r.getUuid()).toList());
		assertTrue(sharedReleaseService.findReleasesByReleaseArtifact(synthetic, org.getUuid()).isEmpty());
	}

	@Test
	@SuppressWarnings("unchecked")
	void theReleaseServesBothListsApartOverGraphql() throws Exception {
		syntheticArtifactService.bind(releaseUuid, synthetic, WU);
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Admin " + tag, "tea4-" + tag + "@synthetic.io", true, List.of(org.getUuid()),
				"tea4-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.ADMIN, List.of(), null, WU);
		UserData admin = userService.getUserData(u.getUuid()).orElseThrow();
		doReturn(Optional.of(admin)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tea-4-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		Map<String, Object> vars = new LinkedHashMap<>();
		vars.put("r", releaseUuid.toString());

		ExecutionResult result = dgsQueryExecutor.execute(RELEASE_QUERY, vars);

		assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
		Map<String, Object> release = (Map<String, Object>) ((Map<String, Object>) result.getData()).get("release");
		assertEquals(List.of(inventory.toString()), release.get("artifacts"));
		assertEquals(List.of(synthetic.toString()), release.get("syntheticArtifacts"));
		assertEquals(List.of(Map.of("uuid", inventory.toString())), release.get("artifactDetails"));
		List<Map<String, Object>> generated = (List<Map<String, Object>>) release.get("syntheticArtifactDetails");
		assertEquals(1, generated.size());
		assertEquals(synthetic.toString(), generated.get(0).get("uuid"));
		assertEquals("SYNTHETIC", ((Map<String, Object>) generated.get(0).get("internalBom")).get("belongsTo"));
		assertTrue(((List<Map<String, Object>>) generated.get(0).get("tags"))
				.contains(Map.of("key", CommonVariables.SYNTHETIC_ARTIFACT_TAG_KEY, "value", "true")));
		assertTrue(((List<Map<String, Object>>) release.get("updateEvents")).stream()
				.anyMatch(e -> "SYNTHETIC_ARTIFACT".equals(e.get("rus")) && "ADDED".equals(e.get("rua"))
						&& synthetic.toString().equals(e.get("objectId"))));
	}

	@Test
	void theInventoryAttachRefusesABoundGeneratedArtifact() throws Exception {
		syntheticArtifactService.bind(releaseUuid, synthetic, WU);

		assertThrows(RelizaException.class, () -> releaseService.addArtifact(synthetic, releaseUuid, WU));

		assertEquals(List.of(inventory), stored().getArtifacts());
	}

	@Test
	void removeUnbindsArchivesAndIsThenANoOp() throws Exception {
		syntheticArtifactService.bind(releaseUuid, synthetic, WU);

		assertTrue(syntheticArtifactService.remove(releaseUuid, synthetic, WU));

		ReleaseData rd = stored();
		assertEquals(List.of(), rd.getSyntheticArtifacts());
		assertEquals(List.of(inventory), rd.getArtifacts());
		assertTrue(syntheticEvents().stream().anyMatch(e -> e.rua() == ReleaseUpdateAction.REMOVED
				&& synthetic.equals(e.objectId())));
		assertEquals(StatusEnum.ARCHIVED, artifactService.getArtifactData(synthetic).orElseThrow().getStatus());
		assertTrue(StatusEnum.ARCHIVED != artifactService.getArtifactData(inventory).orElseThrow().getStatus(),
				"the inventory artifact is untouched");
		assertFalse(syntheticArtifactService.remove(releaseUuid, synthetic, WU));
	}
}
