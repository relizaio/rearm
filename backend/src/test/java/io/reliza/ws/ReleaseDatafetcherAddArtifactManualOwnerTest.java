/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentIdentityCredential.IdentityType;
import io.reliza.model.AgentSessionData;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.Link;
import io.reliza.service.AgentIdentityService;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ArtifactService;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.ReleaseService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.oss.TestInitializer;

/**
 * TPF-1: addArtifactManual replaces an artifact in place (artifactUuid set) only when the caller
 * holds RESOURCE WRITE on the artifact's own owners, the components of the releases holding it.
 * Before this the check covered the named release only, so a writer of component A could upload a
 * new version of any artifact of component B in the same organization. A missing, foreign-org,
 * not-owned or release-less artifact (an agent session artifact above all) is refused with one
 * "Artifact not found" message, before anything is written; a caller without WRITE on the named
 * release still gets Forbidden. Called through the real resolver as a signed-in user.
 */
@SpringBootTest(classes = {App.class})
public class ReleaseDatafetcherAddArtifactManualOwnerTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String NOT_FOUND = "Artifact not found: ";

	@MockitoSpyBean private UserService userService;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ReleaseDatafetcher releaseDatafetcher;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private ReleaseService releaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ArtifactService artifactService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private AgentIdentityService agentIdentityService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private LicenseStatus licenseStatus;

	private boolean wasSealed;
	private boolean wasLicensed;

	/** The test database is sealed and unlicensed; the authorization under test refuses both first. */
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

	/** A component with one release on its base branch holding one externally stored artifact. */
	private record Owned(UUID org, UUID component, UUID release, UUID artifact) {}

	/**
	 * Organization one with components A and B, organization two with component X, a component
	 * writer on A, an organization-wide RESOURCE writer (no AGENT) and a reader of A.
	 */
	private record Rig(Owned a, Owned b, Owned x, UserData writerA, UserData orgWriter, UserData readerA) {}

	private Rig rig() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		UUID otherOrg = testInitializer.obtainOrganization().getUuid();
		Owned a = owned(org);
		Owned b = owned(org);
		Owned x = owned(otherOrg);
		UserData writerA = user(org, PermissionScope.COMPONENT, a.component(), PermissionType.READ_WRITE);
		UserData orgWriter = user(org, PermissionScope.ORGANIZATION, org, PermissionType.READ_WRITE);
		UserData readerA = user(org, PermissionScope.COMPONENT, a.component(), PermissionType.READ_ONLY);
		return new Rig(a, b, x, writerA, orgWriter, readerA);
	}

	private Owned owned(UUID org) throws Exception {
		Component component = componentService.createComponent("tpf1_" + UUID.randomUUID(), org,
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		Branch branch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(component.getUuid()).org(org)
				.branch(branch.getUuid()).version("1.0.0").status(ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.DRAFT).build(), WU).getUuid();
		UUID artifact = artifact(org, ArtifactType.OTHER);
		releaseService.addArtifact(artifact, release, WU);
		return new Owned(org, component.getUuid(), release, artifact);
	}

	private UUID artifact(UUID org, ArtifactType type) throws RelizaException {
		Link link = new Link();
		link.setUri("https://example.com/tpf1/" + UUID.randomUUID());
		ArtifactDto dto = ArtifactDto.builder()
				.org(org)
				.type(type)
				.displayIdentifier("original-" + UUID.randomUUID())
				.storedIn(StoredIn.EXTERNALLY)
				.downloadLinks(List.of(link))
				.build();
		return artifactService.createArtifact(dto, WU).getUuid();
	}

	private UserData user(UUID org, PermissionScope scope, UUID object, PermissionType type) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("TPF-1 " + tag, "tpf1-" + tag + "@owner.io", true, List.of(org),
				"tpf1-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org, scope, object, type,
				List.of(PermissionFunction.RESOURCE), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	/**
	 * An agent session of {@code org} with one artifact attached to the session record and to no
	 * release: what AgentSessionService.uploadAndAttachArtifacts leaves once its upload is done,
	 * minus the policy hook. The artifact row carries no session link, so the row the check sees
	 * is exactly a real session artifact's.
	 */
	private record Session(UUID session, UUID artifact) {}

	private Session sessionWithArtifact(UUID org) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org, ApiTypeEnum.FREEFORM, org,
				UUID.randomUUID().toString(), "tpf-1 session artifact", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org,
				PermissionScope.ORGANIZATION, org, PermissionType.READ_WRITE, Set.of(PermissionFunction.AGENT),
				null)), WU);
		UUID identity = agentIdentityService.findOrRegisterByCredential(org, IdentityType.REARM_API_KEY,
				key.getUuid().toString(), WU).getUuid();
		AgentData agent = agentService.findOrRegisterRootAgent(org, identity, "tpf1-" + UUID.randomUUID(),
				null, null, null, WU);
		AgentSessionData sd = agentSessionService.initialize(org, agent.getUuid(), key.getUuid(),
				"tpf1-" + UUID.randomUUID(), "tpf-1 session", null, null, WU);
		UUID artifact = artifact(org, ArtifactType.AGENTIC_REPORT);
		sd.addArtifact(artifact);
		agentSessionService.saveData(sd, WU);
		return new Session(sd.getUuid(), artifact);
	}

	/** Calls addArtifactManual as {@code user}, replacing {@code artifactUuid} through {@code release}. */
	private ReleaseData replaceAs(UserData user, UUID release, UUID artifactUuid) throws RelizaException {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tpf1-owner-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);

		Map<String, Object> link = new LinkedHashMap<>();
		link.put("uri", "https://example.com/tpf1/replaced-" + UUID.randomUUID());
		Map<String, Object> artifact = new LinkedHashMap<>();
		artifact.put("type", ArtifactType.OTHER.name());
		artifact.put("displayIdentifier", "replaced-" + UUID.randomUUID());
		artifact.put("storedIn", StoredIn.EXTERNALLY.name());
		artifact.put("downloadLinks", List.of(link));
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("release", release.toString());
		input.put("artifact", artifact);
		DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);
		when(dfe.getArgument("artifactInput")).thenReturn(input);
		return releaseDatafetcher.addArtifactManual(dfe, artifactUuid);
	}

	private void refusedAsNotFound(UserData user, UUID release, UUID artifactUuid) {
		RelizaException e = assertThrows(RelizaException.class, () -> replaceAs(user, release, artifactUuid));
		assertEquals(NOT_FOUND + artifactUuid, e.getMessage());
	}

	/** What an in-place replacement would change: the identifier, the version and the links. */
	private record Snapshot(String displayIdentifier, String version, List<String> links) {}

	private Snapshot snapshot(UUID artifactUuid) {
		ArtifactData ad = artifactService.getArtifactData(artifactUuid).orElseThrow();
		return new Snapshot(ad.getDisplayIdentifier(), ad.getVersion(),
				ad.getDownloadLinks().stream().map(Link::getUri).toList());
	}

	private List<UUID> releaseArtifacts(UUID release) {
		return sharedReleaseService.getReleaseData(release).orElseThrow().getArtifacts();
	}

	@Test
	public void aWriterOfOneComponentIsRefusedAnArtifactOfAnotherComponentOfTheSameOrg() throws Exception {
		Rig r = rig();
		Snapshot before = snapshot(r.b().artifact());
		List<UUID> releaseBBefore = releaseArtifacts(r.b().release());

		refusedAsNotFound(r.writerA(), r.a().release(), r.b().artifact());

		assertEquals(before, snapshot(r.b().artifact()));
		assertEquals(releaseBBefore, releaseArtifacts(r.b().release()));
	}

	@Test
	public void anArtifactOfAnotherOrgIsRefusedWithTheSameMessage() throws Exception {
		Rig r = rig();
		Snapshot before = snapshot(r.x().artifact());

		refusedAsNotFound(r.writerA(), r.a().release(), r.x().artifact());

		assertEquals(before, snapshot(r.x().artifact()));
	}

	@Test
	public void anArtifactAttachedToTheNamedReleaseIsReplacedInPlace() throws Exception {
		Rig r = rig();
		Snapshot before = snapshot(r.a().artifact());

		ReleaseData returned = replaceAs(r.writerA(), r.a().release(), r.a().artifact());

		assertEquals(r.a().release(), returned.getUuid());
		Snapshot after = snapshot(r.a().artifact());
		assertTrue(after.displayIdentifier().startsWith("replaced-"), after.toString());
		assertNotEquals(before.displayIdentifier(), after.displayIdentifier());
		assertEquals(List.of(r.a().artifact()), releaseArtifacts(r.a().release()));
	}

	@Test
	public void anArtifactHeldByNoReleaseIsRefusedForEveryWriter() throws Exception {
		Rig r = rig();
		UUID orphan = artifact(r.a().org(), ArtifactType.OTHER);
		Snapshot before = snapshot(orphan);

		refusedAsNotFound(r.writerA(), r.a().release(), orphan);
		refusedAsNotFound(r.orgWriter(), r.a().release(), orphan);

		assertEquals(before, snapshot(orphan));
	}

	@Test
	public void anUnknownUuidReadsNotFoundNotNoSuchElement() throws Exception {
		Rig r = rig();
		refusedAsNotFound(r.writerA(), r.a().release(), UUID.randomUUID());
	}

	@Test
	public void noWriteOnTheNamedReleaseStillAnswersForbidden() throws Exception {
		Rig r = rig();
		Snapshot before = snapshot(r.a().artifact());

		assertThrows(AccessDeniedException.class, () -> replaceAs(r.readerA(), r.a().release(), r.a().artifact()));

		assertEquals(before, snapshot(r.a().artifact()));
	}

	@Test
	public void anAgentSessionArtifactIsRefusedForAnOrgWideResourceWriter() throws Exception {
		Rig r = rig();
		Session s = sessionWithArtifact(r.a().org());
		Snapshot before = snapshot(s.artifact());

		refusedAsNotFound(r.orgWriter(), r.a().release(), s.artifact());
		refusedAsNotFound(r.writerA(), r.a().release(), s.artifact());

		assertEquals(before, snapshot(s.artifact()));
		assertEquals(List.of(s.artifact()),
				agentSessionService.getSessionData(s.session()).orElseThrow().getArtifacts());
	}
}
