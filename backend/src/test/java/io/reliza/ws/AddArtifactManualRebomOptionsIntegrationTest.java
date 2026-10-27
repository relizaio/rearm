/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;

import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeliverableData.SoftwareDeliverableMetadata;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.dto.DeliverableDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.SceDto;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.DigestAlgorithm;
import io.reliza.service.ArtifactService;
import io.reliza.service.DeliverableService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.SourceCodeEntryService;
import io.reliza.service.VcsRepositoryService;
import io.reliza.service.VariantService;

/**
 * SCORE-30 (T-18, ADR-12, ADR-13): what addArtifactManual tells rebom about an SBOM it uploads.
 * The reference is the attachment's own (the deliverable's SHA-256 digest record, the source code
 * entry's commit, none for a release), never the artifact's legacy {@code digests}, which are the
 * uploaded file's; the supplier is the deliverable's publisher, else the organization for its own
 * software. Called through the real resolver as a signed-in writer, the database real, the upload
 * itself stopped at {@link ArtifactService#uploadArtifact} with its options captured.
 */
class AddArtifactManualRebomOptionsIntegrationTest extends SbomScoreGraphqlTestBase {

	private static final String DIGEST = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
	/** The legacy digest an old client sends for the SBOM file it uploads. */
	private static final String FILE_DIGEST = "sha256:60303ae22b998861bce3b28f33eec1be758a213c86c93c076dbe9f558c11c752";
	private static final String COMMIT = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3";
	private static final String PUBLISHER = "Example Devices Inc.";
	private static final String SBOM = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,\"components\":[]}";

	@MockitoSpyBean private ArtifactService uploads;
	@Autowired private ReleaseDatafetcher releaseDatafetcher;
	@Autowired private DeliverableService deliverableService;
	@Autowired private SourceCodeEntryService sourceCodeEntryService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private VariantService variantService;
	@Autowired private GetOrganizationService getOrganizationService;

	private Organization org;
	private Branch branch;
	private UUID release;
	private UserData writer;
	private UUID stored;

	@BeforeEach
	void releaseAndWriter() throws Exception {
		org = testInitializer.obtainOrganization();
		Component c = componentService.createComponent("score30-" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		branch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		release = release(org, c, List.of());
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Score " + tag, "score30-" + tag + "@score.io", true, List.of(org.getUuid()),
				"score30-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(),
				PermissionType.READ_WRITE, List.of(PermissionFunction.RESOURCE), null, WU);
		writer = userService.getUserData(u.getUuid()).orElseThrow();
		// a real artifact row to attach, so the resolver's attach step runs as it does after an upload
		stored = artifact(org.getUuid(), ArtifactType.BOM, BomFormat.CYCLONEDX, true);
		doReturn(stored).when(uploads).uploadArtifact(any(), any(), any(), any(), any());
	}

	private UUID deliverable(String publisher, boolean withSha256) throws RelizaException {
		DigestRecord record = withSha256 ? new DigestRecord(DigestAlgorithm.SHA_256, DIGEST)
				: new DigestRecord(DigestAlgorithm.SHA_1, COMMIT);
		return deliverableService.createDeliverable(DeliverableDto.builder()
				.displayIdentifier("score30-" + UUID.randomUUID()).branch(branch.getUuid()).type(CdxType.CONTAINER)
				.publisher(publisher)
				.softwareMetadata(SoftwareDeliverableMetadata.builder()
						.digestRecords(new LinkedHashSet<>(List.of(record))).build())
				.build(), WU).getUuid();
	}

	private UUID sce() throws RelizaException {
		UUID vcs = vcsRepositoryService.provisionVcsRepository(org.getUuid(),
				"github.com/example/score30-" + UUID.randomUUID(), VcsType.GIT, WU);
		return sourceCodeEntryService.createSourceCodeEntry(SceDto.builder().branch(branch.getUuid()).vcs(vcs)
				.commit(COMMIT).organizationUuid(org.getUuid()).build(), WU).getUuid();
	}

	/** TPF-44: an upload may attach only to the named release's own deliverable or commit. */
	private UUID onRelease(UUID deliverable) throws RelizaException {
		variantService.addOutboundDeliverables(List.of(deliverable),
				variantService.getBaseVariantForRelease(release).getUuid(), WU);
		return deliverable;
	}

	private UUID commitOfRelease(UUID sce) throws RelizaException {
		ossReleaseService.updateRelease(ReleaseDto.builder().uuid(release).commits(List.of(sce)).build(),
				io.reliza.model.ReleaseData.UpdateReleaseStrength.FULL, WU); // the fixture release is ASSEMBLED
		return sce;
	}

	/** Calls addArtifactManual as the writer with an SBOM file and its legacy digest; the options sent. */
	private RebomOptions upload(ArtifactBelongsTo belongsTo, String key, UUID attachment) throws Exception {
		doReturn(Optional.of(writer)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("score-30-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);

		Map<String, Object> artifact = new LinkedHashMap<>();
		artifact.put("type", ArtifactType.BOM.name());
		artifact.put("bomFormat", BomFormat.CYCLONEDX.name());
		artifact.put("displayIdentifier", "score30-sbom-" + UUID.randomUUID());
		artifact.put("digests", Set.of(FILE_DIGEST));
		artifact.put("file", new MockMultipartFile("file", "bom.json", "application/json",
				SBOM.getBytes(StandardCharsets.UTF_8)));
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("release", release.toString());
		input.put("belongsTo", belongsTo.name());
		if (null != key) input.put(key, attachment.toString());
		input.put("artifact", artifact);
		DgsDataFetchingEnvironment dfe = mock(DgsDataFetchingEnvironment.class);
		when(dfe.getArgument("artifactInput")).thenReturn(input);

		releaseDatafetcher.addArtifactManual(dfe, null);

		ArgumentCaptor<RebomOptions> sent = ArgumentCaptor.forClass(RebomOptions.class);
		verify(uploads).uploadArtifact(any(), any(), sent.capture(), any(), any());
		return sent.getValue();
	}

	private String orgName() {
		return getOrganizationService.getOrganizationData(org.getUuid()).orElseThrow().getName();
	}

	@Test
	void aReleaseAttachedUploadSendsNoReferenceAndTheOrganization() throws Exception {
		RebomOptions sent = upload(ArtifactBelongsTo.RELEASE, null, null);

		assertEquals(ArtifactBelongsTo.RELEASE, sent.belongsTo());
		assertNull(sent.hash(), "the SBOM file's own digest is not the release's");
		assertEquals(orgName(), sent.supplierName());
	}

	@Test
	void aDeliverableUploadSendsTheDeliverablesDigestAndItsPublisher() throws Exception {
		RebomOptions sent = upload(ArtifactBelongsTo.DELIVERABLE, "deliverable", onRelease(deliverable(PUBLISHER, true)));

		assertEquals(ArtifactBelongsTo.DELIVERABLE, sent.belongsTo());
		assertEquals("sha256:" + DIGEST, sent.hash());
		assertEquals(PUBLISHER, sent.supplierName());
	}

	@Test
	void aDeliverableWithoutAPublisherOnABranchSendsTheOrganization() throws Exception {
		RebomOptions sent = upload(ArtifactBelongsTo.DELIVERABLE, "deliverable", onRelease(deliverable(null, true)));

		assertEquals("sha256:" + DIGEST, sent.hash());
		assertEquals(orgName(), sent.supplierName());
	}

	@Test
	void aDeliverableWithoutASha256RecordSendsNoReferenceAndDoesNotThrow() throws Exception {
		RebomOptions sent = upload(ArtifactBelongsTo.DELIVERABLE, "deliverable", onRelease(deliverable(null, false)));

		assertNull(sent.hash());
		assertEquals(orgName(), sent.supplierName());
	}

	@Test
	void aSourceCodeEntryUploadSendsItsCommitAndTheOrganization() throws Exception {
		RebomOptions sent = upload(ArtifactBelongsTo.SCE, "sce", commitOfRelease(sce()));

		assertEquals(ArtifactBelongsTo.SCE, sent.belongsTo());
		assertEquals(COMMIT, sent.hash());
		assertEquals(orgName(), sent.supplierName());
	}
}
