/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.SourceCodeEntryData.SCEArtifact;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.DeliverableDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.SceDto;
import io.reliza.model.Link;
import io.reliza.service.ArtifactService;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.DeliverableService;
import io.reliza.service.GetDeliverableService;
import io.reliza.service.GetSourceCodeEntryService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.ReleaseService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SourceCodeEntryService;
import io.reliza.service.UserService;
import io.reliza.service.VariantService;
import io.reliza.service.VcsRepositoryService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.service.saas.PerspectiveService;
import io.reliza.ws.oss.TestInitializer;

/**
 * The rig of {@link ReleaseDatafetcherAddArtifactManualTargetBindingTest} (TPF-44): sides with a
 * release, its variant's outbound deliverable and its source code entry; signed-in principals with
 * component, organization and perspective grants; and a call of addArtifactManual through the bean,
 * with the upload step stubbed, so that a refusal can be shown to have uploaded and written nothing.
 * The two spy beans are those of ReleaseArtifactAppendConcurrencyTest, so the Spring context is shared.
 */
@SpringBootTest(classes = {App.class})
abstract class ReleaseDatafetcherAddArtifactManualTargetBindingTestBase {

	protected static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	/** Literals, not the new constants: a change of the text fails the test. */
	protected static final String DELIVERABLE_NOT_FOUND = "Deliverable not found in this organization: ";
	protected static final String SCE_NOT_FOUND = "Source code entry not found in this organization: ";
	protected static final String COMPONENT_NOT_FOUND = "Component not found in this organization";

	@MockitoSpyBean protected UserService userService;
	@MockitoSpyBean protected ArtifactService artifactService;
	@Autowired protected TestInitializer testInitializer;
	@Autowired protected ReleaseDatafetcher releaseDatafetcher;
	@Autowired protected ComponentService componentService;
	@Autowired protected BranchService branchService;
	@Autowired protected VcsRepositoryService vcsRepositoryService;
	@Autowired protected SourceCodeEntryService sourceCodeEntryService;
	@Autowired protected GetSourceCodeEntryService getSourceCodeEntryService;
	@Autowired protected DeliverableService deliverableService;
	@Autowired protected GetDeliverableService getDeliverableService;
	@Autowired protected OssReleaseService ossReleaseService;
	@Autowired protected ReleaseService releaseService;
	@Autowired protected SharedReleaseService sharedReleaseService;
	@Autowired protected VariantService variantService;
	@Autowired protected PerspectiveService perspectiveService;
	@Autowired protected LicenseStatus licenseStatus;

	private boolean wasSealed;
	private boolean wasLicensed;

	/** The test database is sealed and unlicensed; the resolver's authorization refuses both first. */
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

	/**
	 * A component with its base branch, a VCS repository, a source code entry on that branch, a DRAFT
	 * release whose entry it is, the release's base variant and a deliverable built on the branch and
	 * outbound on that variant (ProgrammaticArtifactOrgBindingTest.tenantIn without the keys).
	 */
	protected record Side(UUID org, UUID component, UUID branch, UUID vcs, UUID release, UUID variant,
			UUID deliverable, UUID sce) {}

	protected Side side(UUID org) throws RelizaException {
		Component component = componentService.createComponent("tpf44_" + UUID.randomUUID(), org,
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		Branch branch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		UUID vcs = vcsRepositoryService.provisionVcsRepository(org,
				"github.com/example/tpf44-" + UUID.randomUUID(), VcsType.GIT, WU);
		UUID sce = sourceCodeEntryService.createSourceCodeEntry(SceDto.builder()
				.branch(branch.getUuid()).vcs(vcs).organizationUuid(org).commit(randomCommit()).build(), WU).getUuid();
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(component.getUuid()).org(org)
				.branch(branch.getUuid()).version("1.0.0").sourceCodeEntry(sce)
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.DRAFT).build(), WU).getUuid();
		UUID variant = variantService.getBaseVariantForRelease(release).getUuid();
		UUID deliverable = deliverableService.createDeliverable(DeliverableDto.builder()
				.displayIdentifier("tpf44-deliverable-" + UUID.randomUUID()).branch(branch.getUuid())
				.type(CdxType.FILE).build(), WU).getUuid();
		variantService.addOutboundDeliverables(List.of(deliverable), variant, WU);
		return new Side(org, component.getUuid(), branch.getUuid(), vcs, release, variant, deliverable, sce);
	}

	protected static String randomCommit() {
		return UUID.randomUUID().toString().replace("-", "") + "44444444";
	}

	/** A further DRAFT release on s's branch, with no entry and the given inbound deliverables. */
	protected UUID releaseOn(Side s, String version, List<UUID> inbound) throws RelizaException {
		return ossReleaseService.createRelease(ReleaseDto.builder().component(s.component()).org(s.org())
				.branch(s.branch()).version(version).inboundDeliverables(inbound).status(ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.DRAFT).build(), WU).getUuid();
	}

	protected UUID artifact(UUID org, ArtifactType type) throws RelizaException {
		Link link = new Link();
		link.setUri("https://example.com/tpf44/" + UUID.randomUUID());
		ArtifactDto dto = ArtifactDto.builder()
				.org(org)
				.type(type)
				.displayIdentifier("original-" + UUID.randomUUID())
				.storedIn(StoredIn.EXTERNALLY)
				.downloadLinks(List.of(link))
				.build();
		return artifactService.createArtifact(dto, WU).getUuid();
	}

	/** A RESOURCE user of org holding one grant. */
	protected UserData user(UUID org, PermissionScope scope, UUID object, PermissionType type) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("TPF-44 " + tag, "tpf44-" + tag + "@binding.io", true, List.of(org),
				"tpf44-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org, scope, object, type,
				List.of(PermissionFunction.RESOURCE), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	protected UserData writerOf(Side s) throws RelizaException {
		return user(s.org(), PermissionScope.COMPONENT, s.component(), PermissionType.READ_WRITE);
	}

	protected UserData orgWriter(UUID org) throws RelizaException {
		return user(org, PermissionScope.ORGANIZATION, org, PermissionType.READ_WRITE);
	}

	/** A writer of a perspective holding s's component alone: it can write s's release and nothing else. */
	protected UserData perspectiveWriterOf(Side s) throws RelizaException {
		UUID perspective = perspectiveService.createPerspective(s.org(),
				"tpf44-" + UUID.randomUUID().toString().substring(0, 8), WU).getUuid();
		componentService.setPerspectives(s.component(), List.of(perspective), WU);
		return user(s.org(), PermissionScope.PERSPECTIVE, perspective, PermissionType.READ_WRITE);
	}

	/**
	 * Calls addArtifactManual through the bean as {@code user}, the input naming {@code release},
	 * {@code targets} (belongsTo, deliverable, sce, component) and {@code artifact}.
	 */
	protected ReleaseData addAs(UserData user, UUID release, UUID artifactUuid, Map<String, String> targets,
			Map<String, Object> artifact) throws RelizaException {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tpf44-binding-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);

		Map<String, Object> input = new LinkedHashMap<>();
		input.put("release", release.toString());
		input.putAll(targets);
		input.put("artifact", artifact);
		DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);
		when(dfe.getArgument("artifactInput")).thenReturn(input);
		return releaseDatafetcher.addArtifactManual(dfe, artifactUuid);
	}

	protected static Map<String, String> targets(String... kv) {
		Map<String, String> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
		return m;
	}

	/** An externally stored artifact: the createArtifact path, no store needed. */
	protected static Map<String, Object> externalArtifact() {
		Map<String, Object> link = new LinkedHashMap<>();
		link.put("uri", "https://example.com/tpf44/new-" + UUID.randomUUID());
		Map<String, Object> artifact = new LinkedHashMap<>();
		artifact.put("type", ArtifactType.OTHER.name());
		artifact.put("displayIdentifier", "new-" + UUID.randomUUID());
		artifact.put("storedIn", StoredIn.EXTERNALLY.name());
		artifact.put("downloadLinks", List.of(link));
		return artifact;
	}

	/** An artifact with a file: the uploadArtifact path, which {@link #stubUpload} answers. */
	protected static Map<String, Object> fileArtifact() {
		Map<String, Object> artifact = new LinkedHashMap<>();
		artifact.put("type", ArtifactType.OTHER.name());
		artifact.put("displayIdentifier", "upload-" + UUID.randomUUID());
		artifact.put("file", new MockMultipartFile("file", "tpf44.txt", "text/plain",
				"tpf-44 upload".getBytes(StandardCharsets.UTF_8)));
		return artifact;
	}

	/**
	 * A premade artifact of org that the stubbed upload returns, so a regression attaches it visibly
	 * instead of failing on the missing artifact store.
	 */
	protected UUID stubUpload(UUID org) throws RelizaException {
		UUID premade = artifact(org, ArtifactType.OTHER);
		doReturn(premade).when(artifactService).uploadArtifact(any(), any(), any(), any(), any());
		return premade;
	}

	/** What an upload naming a side could change there. */
	protected record Written(List<UUID> releaseArtifacts, int releaseRevision, List<UUID> deliverableArtifacts,
			List<SCEArtifact> sceArtifacts, int orgArtifactRows) {}

	protected Written written(Side s) {
		return new Written(sharedReleaseService.getReleaseData(s.release()).orElseThrow().getArtifacts(),
				sharedReleaseService.getRelease(s.release()).orElseThrow().getRevision(),
				getDeliverableService.getDeliverableData(s.deliverable()).orElseThrow().getArtifacts(),
				getSourceCodeEntryService.getSourceCodeEntryData(s.sce()).orElseThrow().getArtifacts(),
				artifactService.listArtifactsByOrg(s.org()).size());
	}

	protected List<Written> written(List<Side> sides) {
		return sides.stream().map(this::written).toList();
	}

	protected List<UUID> releaseArtifacts(UUID release) {
		return sharedReleaseService.getReleaseData(release).orElseThrow().getArtifacts();
	}

	protected List<UUID> deliverableArtifacts(UUID deliverable) {
		return getDeliverableService.getDeliverableData(deliverable).orElseThrow().getArtifacts();
	}

	protected List<SCEArtifact> sceArtifacts(UUID sce) {
		return getSourceCodeEntryService.getSourceCodeEntryData(sce).orElseThrow().getArtifacts();
	}

	/** What an in-place replacement would change: the identifier, the version and the links. */
	protected record Snapshot(String displayIdentifier, String version, List<String> links) {}

	protected Snapshot snapshot(UUID artifactUuid) {
		ArtifactData ad = artifactService.getArtifactData(artifactUuid).orElseThrow();
		return new Snapshot(ad.getDisplayIdentifier(), ad.getVersion(),
				ad.getDownloadLinks().stream().map(Link::getUri).toList());
	}

	/**
	 * Runs {@code call}, expects {@code expected} (with {@code message} when not null), and checks
	 * that nothing was uploaded or created and that no side changed.
	 */
	protected <T extends Throwable> void refusedWithNothingWritten(Class<T> expected, String message,
			List<Side> sides, Executable call) throws RelizaException {
		List<Written> before = written(sides);
		Mockito.clearInvocations(artifactService);
		T e = assertThrows(expected, call);
		if (null != message) assertEquals(message, e.getMessage());
		verify(artifactService, never()).uploadArtifact(any(), any(), any(), any(), any());
		verify(artifactService, never()).createArtifact(any(), any());
		assertEquals(before, written(sides));
	}
}
