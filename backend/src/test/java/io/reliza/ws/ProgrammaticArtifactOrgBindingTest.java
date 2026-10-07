/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.VcsType;
import io.reliza.exceptions.ActionRefusedException;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AnalysisScope;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeliverableData;
import io.reliza.model.IssuerClass;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.User;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.VariantData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.DeliverableDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.SceDto;
import io.reliza.model.tea.Link.ContentEnum;
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.repositories.VexStatementProposalRepository;
import io.reliza.repositories.VulnAnalysisRepository;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ArtifactService;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentLockService;
import io.reliza.service.ComponentService;
import io.reliza.service.DeliverableService;
import io.reliza.service.GetDeliverableService;
import io.reliza.service.GetSourceCodeEntryService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SourceCodeEntryService;
import io.reliza.service.UserService;
import io.reliza.service.VariantService;
import io.reliza.service.VcsRepositoryService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.oss.TestInitializer;

/**
 * addArtifactProgrammatic and addOutboundDeliverablesProgrammatic write only to a release (and
 * variant) of the component the call is authorized for, and attach artifacts only to deliverables
 * and source code entries of that release's organization. Before this a key of one organization
 * could name another organization's release, variant, deliverable or source code entry by uuid,
 * attach its own artifacts and deliverables there (running the VEX import against that release),
 * and read the release back. A missing and a foreign uuid are refused with the same answer, and a
 * refusal writes nothing. Called through the real resolvers with the principal the programmatic
 * filter leaves on the request.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ProgrammaticArtifactOrgBindingTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String RELEASE_NOT_FOUND = CommonVariables.PROGRAMMATIC_RELEASE_NOT_FOUND_MESSAGE;
	private static final String VARIANT_NOT_FOUND = CommonVariables.PROGRAMMATIC_VARIANT_NOT_FOUND_MESSAGE;
	private static final String COMPONENT_NOT_FOUND = CommonVariables.PROGRAMMATIC_COMPONENT_NOT_FOUND_MESSAGE;
	private static final String DELIVERABLE_NOT_FOUND = "Deliverable not found in this organization: ";
	private static final String SCE_NOT_FOUND = "Source code entry not found in this organization: ";

	@Autowired private TestInitializer testInitializer;
	@Autowired private ReleaseDatafetcher releaseDatafetcher;
	@Autowired private DeliverableDataFetcher deliverableDataFetcher;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private SourceCodeEntryService sourceCodeEntryService;
	@Autowired private GetSourceCodeEntryService getSourceCodeEntryService;
	@Autowired private DeliverableService deliverableService;
	@Autowired private GetDeliverableService getDeliverableService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private VariantService variantService;
	@Autowired private ArtifactService artifactService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private VulnAnalysisRepository vulnAnalysisRepository;
	@Autowired private VexStatementProposalRepository vexStatementProposalRepository;
	@Autowired private UserService userService;
	@Autowired private ComponentLockService componentLockService;
	@Autowired private LicenseStatus licenseStatus;
	@Autowired private ApplicationContext applicationContext;
	@Autowired private JdbcTemplate jdbcTemplate;

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

	/**
	 * An org with one component and, on its base branch, a release whose source code entry is
	 * {@code sce} and whose base variant ships {@code deliverable}.
	 */
	private record Tenant(UUID org, Component component, Branch branch, UUID release, UUID variant,
			UUID deliverable, UUID sce, ApiKey componentKey, ApiKey orgKey, ApiKey freeformKey,
			String componentSecret, String orgSecret, String freeformSecret) {}

	private Tenant tenant() throws RelizaException {
		return tenantIn(testInitializer.obtainOrganization().getUuid());
	}

	private Tenant tenantIn(UUID org) throws RelizaException {
		Component component = componentService.createComponent("bind_" + UUID.randomUUID(), org,
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		Branch branch = branchService.getBaseBranchOfComponent(component.getUuid()).orElseThrow();
		UUID vcs = vcsRepositoryService.provisionVcsRepository(org,
				"github.com/example/bind-" + UUID.randomUUID(), VcsType.GIT, WU);
		UUID sce = sourceCodeEntryService.createSourceCodeEntry(SceDto.builder()
				.branch(branch.getUuid()).vcs(vcs).organizationUuid(org)
				.commit(UUID.randomUUID().toString().replace("-", "") + "cccccccc").build(), WU).getUuid();
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(component.getUuid()).org(org)
				.branch(branch.getUuid()).version("1.0.0").sourceCodeEntry(sce)
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.DRAFT).build(), WU).getUuid();
		UUID variant = variantService.getBaseVariantForRelease(release).getUuid();
		UUID deliverable = deliverableService.createDeliverable(DeliverableDto.builder()
				.displayIdentifier("bind-deliverable-" + UUID.randomUUID()).branch(branch.getUuid())
				.type(CdxType.FILE).build(), WU).getUuid();
		variantService.addOutboundDeliverables(List.of(deliverable), variant, WU);
		ApiKey componentKey = apiKeyService.createObjectApiKey(component.getUuid(), ApiTypeEnum.COMPONENT, org,
				null, "binding test component key", WU);
		ApiKey orgKey = apiKeyService.createObjectApiKey(org, ApiTypeEnum.ORGANIZATION_RW, org,
				null, "binding test org key", WU);
		ApiKey freeformKey = apiKeyService.createObjectApiKey(org, ApiTypeEnum.FREEFORM, org,
				UUID.randomUUID().toString(), "binding test freeform key", WU);
		apiKeyService.setPermissionsOnApiKey(freeformKey.getUuid(), null, List.of(new PermissionDto(org,
				PermissionScope.ORGANIZATION, org, PermissionType.READ_WRITE, Set.of(PermissionFunction.RESOURCE),
				null)), WU);
		// secrets for the CLI's Basic-header path (same key rows)
		String componentSecret = apiKeyService.setObjectApiKey(component.getUuid(), ApiTypeEnum.COMPONENT, org,
				null, "binding test component key", WU);
		String orgSecret = apiKeyService.setObjectApiKey(org, ApiTypeEnum.ORGANIZATION_RW, org,
				null, "binding test org key", WU);
		String freeformSecret = apiKeyService.addApiKeySecret(freeformKey.getUuid(), null, WU);
		return new Tenant(org, component, branch, release, variant, deliverable, sce, componentKey, orgKey, freeformKey,
				componentSecret, orgSecret, freeformSecret);
	}

	private DgsDataFetchingEnvironment as(ApiKey key, String argument, Map<String, Object> input) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.10"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		Map<String, Object> arguments = new HashMap<>();
		arguments.put(argument, input);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(arguments).build(), applicationContext);
	}

	/** The CLI's path: a Basic header the resolver verifies itself (no principal left by the filter). */
	private DgsDataFetchingEnvironment basic(String apiKeyId, String secret, String argument, Map<String, Object> input) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder()
				.encodeToString((apiKeyId + ":" + secret).getBytes(StandardCharsets.UTF_8)));
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		Map<String, Object> arguments = new HashMap<>();
		arguments.put(argument, input);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(arguments).build(), applicationContext);
	}

	private DgsDataFetchingEnvironment basicComponent(Tenant t, String argument, Map<String, Object> input) {
		return basic(ApiTypeEnum.COMPONENT.name() + "__" + t.component().getUuid(), t.componentSecret(), argument, input);
	}

	private DgsDataFetchingEnvironment basicOrg(Tenant t, String argument, Map<String, Object> input) {
		return basic(ApiTypeEnum.ORGANIZATION_RW.name() + "__" + t.org(), t.orgSecret(), argument, input);
	}

	private DgsDataFetchingEnvironment basicFreeform(Tenant t, String argument, Map<String, Object> input) {
		return basic(ApiTypeEnum.FREEFORM.name() + "__" + t.org() + "__ord__" + t.freeformKey().getKeyOrder(),
				t.freeformSecret(), argument, input);
	}

	/** An externally stored document: persisted without the artifact store, so the accepted path runs here. */
	private static Map<String, Object> externalArtifact() {
		String name = "bind-doc-" + UUID.randomUUID();
		Map<String, Object> link = new HashMap<>();
		link.put("uri", "https://example.com/" + name);
		link.put("content", ContentEnum.OCTET_STREAM.name());
		Map<String, Object> a = new HashMap<>();
		a.put("type", ArtifactType.USER_DOCUMENT.name());
		a.put("displayIdentifier", name);
		a.put("storedIn", StoredIn.EXTERNALLY.name());
		a.put("downloadLinks", new ArrayList<>(List.of(link)));
		a.put("file", new MockMultipartFile("file", name + ".txt", "text/plain", "doc".getBytes(StandardCharsets.UTF_8)));
		return a;
	}

	/** An org-scoped NOT_AFFECTED VEX statement of the caller's own issuing, the auto-accept shape. */
	private static Map<String, Object> vexArtifact() {
		String vex = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,"
				+ "\"vulnerabilities\":[{\"id\":\"CVE-2024-0001\",\"analysis\":{\"state\":\"not_affected\","
				+ "\"justification\":\"code_not_reachable\"},\"affects\":[{\"ref\":\"pkg:npm/left-pad@1.3.0\"}]}]}";
		Map<String, Object> a = new HashMap<>();
		a.put("type", ArtifactType.VEX.name());
		a.put("displayIdentifier", "bind-vex-" + UUID.randomUUID());
		a.put("vexScope", AnalysisScope.ORG.name());
		a.put("userIssuerClassOverride", IssuerClass.SELF.name());
		a.put("file", new MockMultipartFile("file", "vex.json", "application/json", vex.getBytes(StandardCharsets.UTF_8)));
		return a;
	}

	private static Map<String, Object> artifactInput(UUID release, UUID component) {
		Map<String, Object> input = new HashMap<>();
		if (null != release) input.put(CommonVariables.RELEASE_FIELD, release.toString());
		if (null != component) input.put(CommonVariables.COMPONENT_FIELD, component.toString());
		return input;
	}

	private static List<Map<String, Object>> targeted(String field, UUID target, Map<String, Object> artifact) {
		Map<String, Object> entry = new HashMap<>();
		entry.put(field, target.toString());
		entry.put("artifacts", new ArrayList<>(List.of(artifact)));
		return new ArrayList<>(List.of(entry));
	}

	private static Map<String, Object> deliverablesInput(UUID release, UUID variant) {
		Map<String, Object> deliverable = new HashMap<>();
		deliverable.put("displayIdentifier", "bind-outbound-" + UUID.randomUUID());
		deliverable.put("type", CdxType.FILE.name());
		Map<String, Object> input = new HashMap<>();
		if (null != release) input.put(CommonVariables.RELEASE_FIELD, release.toString());
		if (null != variant) input.put("variant", variant.toString());
		input.put("deliverables", new ArrayList<>(List.of(deliverable)));
		return input;
	}

	/** Everything a refused call could have written into either org. */
	private record Snapshot(List<UUID> releaseArtifacts, List<UUID> deliverableArtifacts, int sceArtifacts,
			Set<UUID> outbound, int releaseRevision, int artifactRowsA, int artifactRowsB, int deliverableRowsA,
			int deliverableRowsB, int analysesB, int proposalsB) {}

	private Snapshot snapshot(Tenant a, Tenant b, UUID release, UUID deliverable, UUID sce, UUID variant) {
		ReleaseData rd = sharedReleaseService.getReleaseData(release).orElseThrow();
		return new Snapshot(List.copyOf(rd.getArtifacts()),
				List.copyOf(getDeliverableService.getDeliverableData(deliverable).orElseThrow().getArtifacts()),
				getSourceCodeEntryService.getSourceCodeEntryData(sce).orElseThrow().getArtifacts().size(),
				Set.copyOf(variantService.getVariantData(variant).orElseThrow().getOutboundDeliverables()),
				sharedReleaseService.getRelease(release).orElseThrow().getRevision(),
				artifactService.listArtifactsByOrg(a.org()).size(), artifactService.listArtifactsByOrg(b.org()).size(),
				getDeliverableService.listDeliverablesByOrg(a.org()).size(),
				getDeliverableService.listDeliverablesByOrg(b.org()).size(),
				vulnAnalysisRepository.findByOrg(b.org().toString()).size(),
				vexStatementProposalRepository.findByOrg(b.org().toString()).size());
	}

	private Snapshot victim(Tenant a, Tenant b) {
		return snapshot(a, b, b.release(), b.deliverable(), b.sce(), b.variant());
	}

	/** {@link #victim} without the caller's own org counts, for tests whose accepted calls write there. */
	private Snapshot victimSide(Tenant a, Tenant b) {
		Snapshot v = victim(a, b);
		return new Snapshot(v.releaseArtifacts(), v.deliverableArtifacts(), v.sceArtifacts(), v.outbound(),
				v.releaseRevision(), 0, v.artifactRowsB(), 0, v.deliverableRowsB(), v.analysesB(), v.proposalsB());
	}

	// ---------------- addArtifactProgrammatic: the release ----------------

	@Test
	public void componentKeyRefusesAnotherOrgsRelease() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		Snapshot before = victim(a, b);
		Map<String, Object> input = artifactInput(b.release(), null);
		input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));

		RelizaException re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", input)));
		assertEquals(RELEASE_NOT_FOUND, re.getMessage());
		assertEquals(before, victim(a, b), "a refusal writes nothing in either org");
	}

	@Test
	public void orgKeyNamingNoComponentRefusesAnotherOrgsReleaseAsNotFound() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		Snapshot before = victim(a, b);
		Map<String, Object> input = artifactInput(b.release(), null);
		input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));

		// the component would be read off the release: refused as not found, not as an org mismatch
		RelizaException re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.orgKey(), "artifactInput", input)));
		assertEquals(RELEASE_NOT_FOUND, re.getMessage());
		assertEquals(before, victim(a, b));
	}

	@Test
	public void freeformKeyRefusesAnotherOrgsRelease() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		Snapshot before = victim(a, b);
		Map<String, Object> input = artifactInput(b.release(), null);
		input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));

		RelizaException re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.freeformKey(), "artifactInput", input)));
		assertEquals(RELEASE_NOT_FOUND, re.getMessage());
		assertEquals(before, victim(a, b));
	}

	@Test
	public void aForeignVexUploadIsRefusedBeforeTheImport() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		Snapshot before = victim(a, b);
		Map<String, Object> input = artifactInput(b.release(), null);
		input.put("releaseArtifacts", new ArrayList<>(List.of(vexArtifact())));
		input.put("deliverableArtifacts", targeted("deliverable", b.deliverable(), vexArtifact()));
		input.put("sceArtifacts", targeted("sce", b.sce(), vexArtifact()));

		RelizaException re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", input)));
		assertEquals(RELEASE_NOT_FOUND, re.getMessage());
		assertEquals(before, victim(a, b), "no artifact, analysis or VEX proposal lands in the other org");
	}

	@Test
	public void aNonexistentReleaseGetsTheSameAnswerAsAForeignOne() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		Map<String, Object> foreign = artifactInput(b.release(), null);
		foreign.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
		Map<String, Object> missing = artifactInput(UUID.randomUUID(), null);
		missing.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));

		for (ApiKey key : List.of(a.componentKey(), a.orgKey(), a.freeformKey())) {
			RelizaException f = assertThrows(RelizaException.class,
					() -> releaseDatafetcher.addArtifactProg(as(key, "artifactInput", foreign)));
			RelizaException m = assertThrows(RelizaException.class,
					() -> releaseDatafetcher.addArtifactProg(as(key, "artifactInput", missing)));
			assertEquals(m.getMessage(), f.getMessage(), "no oracle for " + key.getUuid());
		}
	}

	@Test
	public void anotherComponentsReleaseInTheSameOrgIsRefused() throws RelizaException {
		Tenant a = tenant();
		Tenant sibling = tenantIn(a.org());
		Snapshot before = snapshot(a, a, sibling.release(), sibling.deliverable(), sibling.sce(), sibling.variant());
		Map<String, Object> input = artifactInput(sibling.release(), null);
		input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));

		RelizaException re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", input)));
		assertEquals(RELEASE_NOT_FOUND, re.getMessage());
		// an org key naming one component and another component's release: the same
		Map<String, Object> named = artifactInput(sibling.release(), a.component().getUuid());
		named.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
		re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.orgKey(), "artifactInput", named)));
		assertEquals(RELEASE_NOT_FOUND, re.getMessage());
		assertEquals(before, snapshot(a, a, sibling.release(), sibling.deliverable(), sibling.sce(), sibling.variant()));
	}

	@Test
	public void anotherOrgsComponentIsRefusedBeforeAnyReleaseLookup() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		Snapshot before = victim(a, b);
		// naming another org's component (with an existing and a missing version), a missing
		// component and a malformed one: the same answer, before any release lookup
		List<String> components = List.of(b.component().getUuid().toString(), b.component().getUuid().toString(),
				UUID.randomUUID().toString(), "not-a-uuid");
		List<String> versions = List.of(sharedReleaseService.getReleaseData(b.release()).orElseThrow().getVersion(),
				"9.9.9", "1.0.0", "1.0.0");
		for (ApiKey key : List.of(a.orgKey(), a.freeformKey())) {
			for (int i = 0; i < components.size(); i++) {
				Map<String, Object> input = new HashMap<>();
				input.put(CommonVariables.COMPONENT_FIELD, components.get(i));
				input.put(CommonVariables.VERSION_FIELD, versions.get(i));
				input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
				RelizaException re = assertThrows(RelizaException.class,
						() -> releaseDatafetcher.addArtifactProg(as(key, "artifactInput", input)));
				assertEquals(COMPONENT_NOT_FOUND, re.getMessage(), components.get(i));
			}
		}
		// a component key naming a malformed component: the same
		Map<String, Object> malformed = artifactInput(a.release(), null);
		malformed.put(CommonVariables.COMPONENT_FIELD, "not-a-uuid");
		malformed.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
		RelizaException re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", malformed)));
		assertEquals(COMPONENT_NOT_FOUND, re.getMessage());
		// and on the outbound mutation
		Map<String, Object> outbound = deliverablesInput(null, null);
		outbound.put(CommonVariables.COMPONENT_FIELD, b.component().getUuid().toString());
		outbound.put(CommonVariables.VERSION_FIELD, "1.0.0");
		re = assertThrows(RelizaException.class, () -> deliverableDataFetcher
				.addOutboundDeliverablesProgrammatic(as(a.orgKey(), "deliverables", outbound)));
		assertEquals(COMPONENT_NOT_FOUND, re.getMessage());
		assertEquals(before, victim(a, b));
	}

	// ---------------- addArtifactProgrammatic: deliverable and SCE targets ----------------

	@Test
	public void anotherOrgsDeliverableIsRefusedBeforeAnyPartIsWritten() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		Snapshot ownBefore = snapshot(a, b, a.release(), a.deliverable(), a.sce(), a.variant());
		Snapshot before = victim(a, b);
		Map<String, Object> input = artifactInput(a.release(), null);
		// the release part is valid: it must not be written either
		input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
		input.put("deliverableArtifacts", targeted("deliverable", b.deliverable(), externalArtifact()));

		RelizaException re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", input)));
		assertEquals(DELIVERABLE_NOT_FOUND + b.deliverable(), re.getMessage());
		assertEquals(before, victim(a, b));
		assertEquals(ownBefore, snapshot(a, b, a.release(), a.deliverable(), a.sce(), a.variant()));

		Map<String, Object> missing = artifactInput(a.release(), null);
		UUID nowhere = UUID.randomUUID();
		missing.put("deliverableArtifacts", targeted("deliverable", nowhere, externalArtifact()));
		re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", missing)));
		assertEquals(DELIVERABLE_NOT_FOUND + nowhere, re.getMessage());
	}

	@Test
	public void anotherOrgsSourceCodeEntryIsRefusedBeforeAnyPartIsWritten() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		Snapshot ownBefore = snapshot(a, b, a.release(), a.deliverable(), a.sce(), a.variant());
		Snapshot before = victim(a, b);
		Map<String, Object> input = artifactInput(a.release(), null);
		input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
		input.put("deliverableArtifacts", targeted("deliverable", a.deliverable(), externalArtifact()));
		input.put("sceArtifacts", targeted("sce", b.sce(), externalArtifact()));

		RelizaException re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", input)));
		assertEquals(SCE_NOT_FOUND + b.sce(), re.getMessage());
		assertEquals(before, victim(a, b));
		assertEquals(ownBefore, snapshot(a, b, a.release(), a.deliverable(), a.sce(), a.variant()));

		for (UUID nowhere : List.of(UUID.randomUUID(), SourceCodeEntryData.NULL_SCE_UUID)) {
			Map<String, Object> missing = artifactInput(a.release(), null);
			missing.put("sceArtifacts", targeted("sce", nowhere, externalArtifact()));
			re = assertThrows(RelizaException.class,
					() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", missing)));
			assertEquals(SCE_NOT_FOUND + nowhere, re.getMessage());
		}
	}

	@Test
	public void ownReleaseDeliverableAndSourceCodeEntryAreAccepted() throws RelizaException {
		Tenant a = tenant();
		Map<String, Object> input = artifactInput(a.release(), null);
		input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
		input.put("deliverableArtifacts", targeted("deliverable", a.deliverable(), externalArtifact()));
		input.put("sceArtifacts", targeted("sce", a.sce(), externalArtifact()));

		ReleaseData returned = releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", input));
		assertEquals(a.release(), returned.getUuid());
		assertEquals(1, sharedReleaseService.getReleaseData(a.release()).orElseThrow().getArtifacts().size());
		assertEquals(1, getDeliverableService.getDeliverableData(a.deliverable()).orElseThrow().getArtifacts().size());
		SourceCodeEntryData sced = getSourceCodeEntryService.getSourceCodeEntryData(a.sce()).orElseThrow();
		assertEquals(1, sced.getArtifacts().size());
	}

	@Test
	public void orgAndFreeformKeysAcceptTheirOwnReleaseByUuidAlone() throws RelizaException {
		Tenant a = tenant();
		for (ApiKey key : List.of(a.orgKey(), a.freeformKey())) {
			Map<String, Object> input = artifactInput(a.release(), null);
			input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
			assertEquals(a.release(), releaseDatafetcher.addArtifactProg(as(key, "artifactInput", input)).getUuid());
		}
		assertEquals(2, sharedReleaseService.getReleaseData(a.release()).orElseThrow().getArtifacts().size());
		Map<String, Object> byVersion = artifactInput(null, a.component().getUuid());
		byVersion.put(CommonVariables.VERSION_FIELD, sharedReleaseService.getReleaseData(a.release()).orElseThrow().getVersion());
		byVersion.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
		assertEquals(a.release(), releaseDatafetcher.addArtifactProg(as(a.orgKey(), "artifactInput", byVersion)).getUuid());
	}

	// ---------------- addOutboundDeliverablesProgrammatic ----------------

	@Test
	public void outboundDeliverablesRefuseAnotherOrgsRelease() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		Snapshot before = victim(a, b);
		for (ApiKey key : List.of(a.componentKey(), a.orgKey())) {
			RelizaException re = assertThrows(RelizaException.class, () -> deliverableDataFetcher
					.addOutboundDeliverablesProgrammatic(as(key, "deliverables", deliverablesInput(b.release(), null))));
			assertEquals(RELEASE_NOT_FOUND, re.getMessage());
			RelizaException m = assertThrows(RelizaException.class, () -> deliverableDataFetcher
					.addOutboundDeliverablesProgrammatic(as(key, "deliverables", deliverablesInput(UUID.randomUUID(), null))));
			assertEquals(m.getMessage(), re.getMessage(), "no oracle");
		}
		assertEquals(before, victim(a, b));
	}

	@Test
	public void outboundDeliverablesRefuseAnotherOrgsVariant() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		Snapshot before = victim(a, b);
		Snapshot ownBefore = snapshot(a, b, a.release(), a.deliverable(), a.sce(), a.variant());
		// own release, foreign variant; a foreign variant alone; the latter with an org key naming
		// no component (no component to authorize until the variant resolves)
		List<Map<String, Object>> inputs = List.of(deliverablesInput(a.release(), b.variant()),
				deliverablesInput(null, b.variant()));
		for (ApiKey key : List.of(a.componentKey(), a.orgKey())) {
			for (Map<String, Object> input : inputs) {
				RelizaException re = assertThrows(RelizaException.class, () -> deliverableDataFetcher
						.addOutboundDeliverablesProgrammatic(as(key, "deliverables", new HashMap<>(input))));
				assertEquals(VARIANT_NOT_FOUND, re.getMessage());
			}
			RelizaException m = assertThrows(RelizaException.class, () -> deliverableDataFetcher
					.addOutboundDeliverablesProgrammatic(as(key, "deliverables", deliverablesInput(null, UUID.randomUUID()))));
			assertEquals(VARIANT_NOT_FOUND, m.getMessage());
		}
		assertEquals(before, victim(a, b));
		assertEquals(ownBefore, snapshot(a, b, a.release(), a.deliverable(), a.sce(), a.variant()));
	}

	@Test
	public void outboundDeliverablesRefuseAnotherComponentsVariantInTheSameOrg() throws RelizaException {
		Tenant a = tenant();
		Tenant sibling = tenantIn(a.org());
		Set<UUID> before = Set.copyOf(variantService.getVariantData(sibling.variant()).orElseThrow().getOutboundDeliverables());
		RelizaException re = assertThrows(RelizaException.class, () -> deliverableDataFetcher
				.addOutboundDeliverablesProgrammatic(as(a.componentKey(), "deliverables", deliverablesInput(null, sibling.variant()))));
		assertEquals(VARIANT_NOT_FOUND, re.getMessage());
		assertEquals(before, Set.copyOf(variantService.getVariantData(sibling.variant()).orElseThrow().getOutboundDeliverables()));
	}

	@Test
	public void outboundDeliverablesAcceptOwnReleaseAndVariant() throws RelizaException {
		Tenant a = tenant();
		ReleaseData byRelease = deliverableDataFetcher.addOutboundDeliverablesProgrammatic(
				as(a.componentKey(), "deliverables", deliverablesInput(a.release(), null)));
		assertEquals(a.release(), byRelease.getUuid());
		ReleaseData byVariant = deliverableDataFetcher.addOutboundDeliverablesProgrammatic(
				as(a.componentKey(), "deliverables", deliverablesInput(null, a.variant())));
		assertEquals(a.release(), byVariant.getUuid());
		// an org key naming no component: the component is read off the variant's release
		ReleaseData byOrgKey = deliverableDataFetcher.addOutboundDeliverablesProgrammatic(
				as(a.orgKey(), "deliverables", deliverablesInput(null, a.variant())));
		assertEquals(a.release(), byOrgKey.getUuid());
		VariantData vd = variantService.getVariantData(a.variant()).orElseThrow();
		assertEquals(4, vd.getOutboundDeliverables().size(), "the fixture's deliverable plus three");
		for (UUID d : vd.getOutboundDeliverables()) {
			DeliverableData dd = getDeliverableService.getDeliverableData(d).orElseThrow();
			assertEquals(a.org(), dd.getOrg());
		}
	}

	// ---------------- review round: ordering, uniform answers, Basic header path ----------------

	private UUID releaseOn(Tenant t, String version) throws RelizaException {
		return ossReleaseService.createRelease(ReleaseDto.builder().component(t.component().getUuid()).org(t.org())
				.branch(t.branch().getUuid()).version(version).status(ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.DRAFT).build(), WU).getUuid();
	}

	/** A FREEFORM key that may write only {@code component}. */
	private ApiKey narrowFreeformKey(UUID org, UUID component) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org, ApiTypeEnum.FREEFORM, org, UUID.randomUUID().toString(),
				"binding test narrow key", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org,
				PermissionScope.COMPONENT, component, PermissionType.READ_WRITE, Set.of(PermissionFunction.RESOURCE),
				null)), WU);
		return key;
	}

	@Test
	public void aVexPreconditionFailureIsFoundBeforeAnyPartIsWritten() throws RelizaException {
		Tenant a = tenant();
		Snapshot before = snapshot(a, a, a.release(), a.deliverable(), a.sce(), a.variant());
		Map<String, Object> input = artifactInput(a.release(), null);
		input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
		input.put("deliverableArtifacts", targeted("deliverable", a.deliverable(), vexArtifact()));

		// the release has no SBOM components: the VEX import cannot run against it
		RelizaException re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", input)));
		assertTrue(re.getMessage().startsWith("Cannot import VEX"), re.getMessage());
		assertEquals(before, snapshot(a, a, a.release(), a.deliverable(), a.sce(), a.variant()),
				"the document in releaseArtifacts is not uploaded either");
	}

	@Test
	public void aRefusedSecondDeliverableEntryLeavesTheFirstUnwritten() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		Snapshot before = snapshot(a, b, a.release(), a.deliverable(), a.sce(), a.variant());
		Map<String, Object> input = artifactInput(a.release(), null);
		List<Map<String, Object>> entries = targeted("deliverable", a.deliverable(), externalArtifact());
		entries.addAll(targeted("deliverable", b.deliverable(), externalArtifact()));
		input.put("deliverableArtifacts", entries);

		RelizaException re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", input)));
		assertEquals(DELIVERABLE_NOT_FOUND + b.deliverable(), re.getMessage());
		assertEquals(before, snapshot(a, b, a.release(), a.deliverable(), a.sce(), a.variant()));
	}

	@Test
	public void malformedIdsAreNotFound() throws RelizaException {
		Tenant a = tenant();
		Snapshot before = snapshot(a, a, a.release(), a.deliverable(), a.sce(), a.variant());
		for (ApiKey key : List.of(a.componentKey(), a.orgKey(), a.freeformKey())) {
			Map<String, Object> input = new HashMap<>();
			input.put(CommonVariables.RELEASE_FIELD, "not-a-uuid");
			input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
			RelizaException re = assertThrows(RelizaException.class,
					() -> releaseDatafetcher.addArtifactProg(as(key, "artifactInput", input)));
			assertEquals(RELEASE_NOT_FOUND, re.getMessage());
		}
		Map<String, Object> badDeliverable = artifactInput(a.release(), null);
		Map<String, Object> delEntry = new HashMap<>();
		delEntry.put("deliverable", "not-a-uuid");
		delEntry.put("artifacts", new ArrayList<>(List.of(externalArtifact())));
		badDeliverable.put("deliverableArtifacts", new ArrayList<>(List.of(delEntry)));
		RelizaException re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", badDeliverable)));
		assertEquals(DELIVERABLE_NOT_FOUND + "not-a-uuid", re.getMessage());

		Map<String, Object> badSce = artifactInput(a.release(), null);
		Map<String, Object> sceEntry = new HashMap<>();
		sceEntry.put("sce", "not-a-uuid");
		sceEntry.put("artifacts", new ArrayList<>(List.of(externalArtifact())));
		badSce.put("sceArtifacts", new ArrayList<>(List.of(sceEntry)));
		re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", badSce)));
		assertEquals(SCE_NOT_FOUND + "not-a-uuid", re.getMessage());

		Map<String, Object> badVariant = deliverablesInput(null, null);
		badVariant.put("variant", "not-a-uuid");
		re = assertThrows(RelizaException.class, () -> deliverableDataFetcher
				.addOutboundDeliverablesProgrammatic(as(a.orgKey(), "deliverables", badVariant)));
		assertEquals(VARIANT_NOT_FOUND, re.getMessage());
		Map<String, Object> badRelease = deliverablesInput(null, null);
		badRelease.put(CommonVariables.RELEASE_FIELD, "not-a-uuid");
		re = assertThrows(RelizaException.class, () -> deliverableDataFetcher
				.addOutboundDeliverablesProgrammatic(as(a.orgKey(), "deliverables", badRelease)));
		assertEquals(RELEASE_NOT_FOUND, re.getMessage());
		assertEquals(before, snapshot(a, a, a.release(), a.deliverable(), a.sce(), a.variant()));
	}

	@Test
	public void aNarrowFreeformKeyCannotTellAnInaccessibleReleaseFromAMissingOne() throws RelizaException {
		Tenant a = tenant();
		Tenant sibling = tenantIn(a.org());
		ApiKey narrow = narrowFreeformKey(a.org(), a.component().getUuid());
		Snapshot before = snapshot(a, a, sibling.release(), sibling.deliverable(), sibling.sce(), sibling.variant());

		// no component named: the release's own component is the one the key may not write
		Map<String, Object> inaccessible = artifactInput(sibling.release(), null);
		inaccessible.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
		Map<String, Object> missing = artifactInput(UUID.randomUUID(), null);
		missing.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
		RelizaException i = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(narrow, "artifactInput", inaccessible)));
		RelizaException m = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(narrow, "artifactInput", missing)));
		assertEquals(RELEASE_NOT_FOUND, i.getMessage());
		assertEquals(m.getMessage(), i.getMessage());

		// naming that component: refused on the component before any release lookup, like a
		// missing component, for an existing and a missing release alike
		Map<String, Object> namedExisting = artifactInput(sibling.release(), sibling.component().getUuid());
		namedExisting.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
		Map<String, Object> namedMissing = artifactInput(UUID.randomUUID(), sibling.component().getUuid());
		namedMissing.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
		RelizaException ne = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(narrow, "artifactInput", namedExisting)));
		RelizaException nm = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(narrow, "artifactInput", namedMissing)));
		assertEquals(COMPONENT_NOT_FOUND, ne.getMessage());
		assertEquals(COMPONENT_NOT_FOUND, nm.getMessage());
		assertEquals(before, snapshot(a, a, sibling.release(), sibling.deliverable(), sibling.sce(), sibling.variant()));

		// and its own component's release is accepted
		Map<String, Object> own = artifactInput(a.release(), null);
		own.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
		assertEquals(a.release(), releaseDatafetcher.addArtifactProg(as(narrow, "artifactInput", own)).getUuid());
	}

	@Test
	public void outboundDeliverablesRefuseARequestedReleaseThatDoesNotResolveEvenWithAVariant() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		Snapshot ownBefore = snapshot(a, b, a.release(), a.deliverable(), a.sce(), a.variant());
		for (UUID release : List.of(b.release(), UUID.randomUUID())) {
			RelizaException re = assertThrows(RelizaException.class, () -> deliverableDataFetcher
					.addOutboundDeliverablesProgrammatic(as(a.componentKey(), "deliverables", deliverablesInput(release, a.variant()))));
			assertEquals(RELEASE_NOT_FOUND, re.getMessage());
		}
		// a version that does not resolve on the component: the same
		Map<String, Object> byVersion = deliverablesInput(null, a.variant());
		byVersion.put(CommonVariables.VERSION_FIELD, "9.9.9");
		RelizaException re = assertThrows(RelizaException.class, () -> deliverableDataFetcher
				.addOutboundDeliverablesProgrammatic(as(a.componentKey(), "deliverables", byVersion)));
		assertEquals(RELEASE_NOT_FOUND, re.getMessage());
		assertEquals(ownBefore, snapshot(a, b, a.release(), a.deliverable(), a.sce(), a.variant()));
	}

	@Test
	public void outboundDeliverablesRefuseAReleaseAndVariantThatDoNotMatch() throws RelizaException {
		Tenant a = tenant();
		UUID other = releaseOn(a, "1.0.1");
		UUID otherVariant = variantService.getBaseVariantForRelease(other).getUuid();
		Snapshot before = snapshot(a, a, a.release(), a.deliverable(), a.sce(), a.variant());
		Set<UUID> otherBefore = Set.copyOf(variantService.getVariantData(otherVariant).orElseThrow().getOutboundDeliverables());
		// a variant of another release of the same component reads like a missing variant
		RelizaException re = assertThrows(RelizaException.class, () -> deliverableDataFetcher
				.addOutboundDeliverablesProgrammatic(basicComponent(a, "deliverables", deliverablesInput(a.release(), otherVariant))));
		assertEquals(VARIANT_NOT_FOUND, re.getMessage());
		RelizaException missing = assertThrows(RelizaException.class, () -> deliverableDataFetcher
				.addOutboundDeliverablesProgrammatic(basicComponent(a, "deliverables", deliverablesInput(a.release(), UUID.randomUUID()))));
		assertEquals(missing.getMessage(), re.getMessage());
		assertEquals(before, snapshot(a, a, a.release(), a.deliverable(), a.sce(), a.variant()));
		assertEquals(otherBefore, Set.copyOf(variantService.getVariantData(otherVariant).orElseThrow().getOutboundDeliverables()));
	}

	@Test
	public void viaBasicHeaderEachKeyTypeWritesItsOwnReleaseOnly() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		Snapshot before = victimSide(a, b);
		List<Function<Map<String, Object>, DgsDataFetchingEnvironment>> callers = List.of(
				in -> basicComponent(a, "artifactInput", in),
				in -> basicOrg(a, "artifactInput", in),
				in -> basicFreeform(a, "artifactInput", in));
		for (var caller : callers) {
			Map<String, Object> foreign = artifactInput(b.release(), null);
			foreign.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
			Map<String, Object> missing = artifactInput(UUID.randomUUID(), null);
			missing.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
			RelizaException f = assertThrows(RelizaException.class, () -> releaseDatafetcher.addArtifactProg(caller.apply(foreign)));
			RelizaException m = assertThrows(RelizaException.class, () -> releaseDatafetcher.addArtifactProg(caller.apply(missing)));
			assertEquals(RELEASE_NOT_FOUND, f.getMessage());
			assertEquals(RELEASE_NOT_FOUND, m.getMessage());

			Map<String, Object> own = artifactInput(a.release(), null);
			own.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
			assertEquals(a.release(), releaseDatafetcher.addArtifactProg(caller.apply(own)).getUuid());
		}
		assertEquals(3, sharedReleaseService.getReleaseData(a.release()).orElseThrow().getArtifacts().size());
		assertEquals(before, victimSide(a, b));

		// a component key locating its release by version
		Map<String, Object> byVersion = artifactInput(null, null);
		byVersion.put(CommonVariables.VERSION_FIELD, "1.0.0");
		byVersion.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
		assertEquals(a.release(), releaseDatafetcher.addArtifactProg(basicComponent(a, "artifactInput", byVersion)).getUuid());
	}

	@Test
	public void viaBasicHeaderAWrongSecretIsRefusedBeforeAnyLookup() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		for (UUID release : List.of(a.release(), b.release(), UUID.randomUUID())) {
			Map<String, Object> input = artifactInput(release, null);
			input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
			AccessDeniedException ade = assertThrows(AccessDeniedException.class, () -> releaseDatafetcher
					.addArtifactProg(basic(ApiTypeEnum.ORGANIZATION_RW.name() + "__" + a.org(), "wrong", "artifactInput", input)));
			assertEquals("Key unauthorized", ade.getMessage());
		}
	}

	@Test
	public void viaBasicHeaderOutboundDeliverables() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		Snapshot before = victimSide(a, b);
		assertEquals(a.release(), deliverableDataFetcher.addOutboundDeliverablesProgrammatic(
				basicComponent(a, "deliverables", deliverablesInput(null, a.variant()))).getUuid());
		assertEquals(a.release(), deliverableDataFetcher.addOutboundDeliverablesProgrammatic(
				basicOrg(a, "deliverables", deliverablesInput(a.release(), null))).getUuid());
		RelizaException re = assertThrows(RelizaException.class, () -> deliverableDataFetcher
				.addOutboundDeliverablesProgrammatic(basicOrg(a, "deliverables", deliverablesInput(b.release(), null))));
		assertEquals(RELEASE_NOT_FOUND, re.getMessage());
		re = assertThrows(RelizaException.class, () -> deliverableDataFetcher
				.addOutboundDeliverablesProgrammatic(basicComponent(a, "deliverables", deliverablesInput(null, b.variant()))));
		assertEquals(VARIANT_NOT_FOUND, re.getMessage());

		// FREEFORM keys are not accepted on this mutation: refused before any lookup, own or not
		for (UUID release : List.of(a.release(), b.release())) {
			AccessDeniedException ade = assertThrows(AccessDeniedException.class, () -> deliverableDataFetcher
					.addOutboundDeliverablesProgrammatic(basicFreeform(a, "deliverables", deliverablesInput(release, null))));
			assertEquals("Unsupported object type", ade.getMessage());
		}
		assertEquals(3, variantService.getVariantData(a.variant()).orElseThrow().getOutboundDeliverables().size());
		assertEquals(before, victimSide(a, b));
	}

	// ---------------- re-review: release binding of targets, key-level refusals, per-artifact checks ----------------

	@Test
	public void anotherComponentsDeliverableAndSourceCodeEntryInTheSameOrgAreRefused() throws RelizaException {
		Tenant a = tenant();
		Tenant sibling = tenantIn(a.org());
		Snapshot ownBefore = snapshot(a, a, a.release(), a.deliverable(), a.sce(), a.variant());
		Snapshot siblingBefore = snapshot(a, a, sibling.release(), sibling.deliverable(), sibling.sce(), sibling.variant());

		Map<String, Object> del = artifactInput(a.release(), null);
		del.put("deliverableArtifacts", targeted("deliverable", sibling.deliverable(), externalArtifact()));
		RelizaException re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", del)));
		assertEquals(DELIVERABLE_NOT_FOUND + sibling.deliverable(), re.getMessage());

		Map<String, Object> sce = artifactInput(a.release(), null);
		sce.put("sceArtifacts", targeted("sce", sibling.sce(), externalArtifact()));
		re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", sce)));
		assertEquals(SCE_NOT_FOUND + sibling.sce(), re.getMessage());

		// an own-component deliverable / SCE that is not this release's: the same
		UUID other = releaseOn(a, "1.0.1");
		Map<String, Object> notThisRelease = artifactInput(other, null);
		notThisRelease.put("deliverableArtifacts", targeted("deliverable", a.deliverable(), externalArtifact()));
		re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", notThisRelease)));
		assertEquals(DELIVERABLE_NOT_FOUND + a.deliverable(), re.getMessage());
		Map<String, Object> notThisReleaseSce = artifactInput(other, null);
		notThisReleaseSce.put("sceArtifacts", targeted("sce", a.sce(), externalArtifact()));
		re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", notThisReleaseSce)));
		assertEquals(SCE_NOT_FOUND + a.sce(), re.getMessage());

		assertEquals(ownBefore, snapshot(a, a, a.release(), a.deliverable(), a.sce(), a.variant()));
		assertEquals(siblingBefore, snapshot(a, a, sibling.release(), sibling.deliverable(), sibling.sce(), sibling.variant()));
	}

	@Test
	public void aKeyLevelRefusalIsNotMaskedAsNotFound() throws RelizaException {
		Tenant a = tenant();
		UUID elsewhere = testInitializer.obtainOrganization().getUuid();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		// the owner is a member of another org only: the key is refused at key level
		User owner = userService.createUser("Leaver " + tag, "leaver-" + tag + "@binding.io", true, List.of(elsewhere),
				"leaver-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		ApiKey userKey = apiKeyService.createObjectApiKey(owner.getUuid(), ApiTypeEnum.USER, a.org(),
				UUID.randomUUID().toString(), "binding test user key", WU);
		Snapshot before = snapshot(a, a, a.release(), a.deliverable(), a.sce(), a.variant());
		for (UUID release : List.of(a.release(), UUID.randomUUID())) {
			Map<String, Object> input = artifactInput(release, null);
			input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
			AccessDeniedException ade = assertThrows(AccessDeniedException.class,
					() -> releaseDatafetcher.addArtifactProg(as(userKey, "artifactInput", input)));
			assertEquals("Owner of this user key is not an active member of the organization", ade.getMessage());
		}
		assertEquals(before, snapshot(a, a, a.release(), a.deliverable(), a.sce(), a.variant()));
	}

	@Test
	public void aBadDigestScopeInALaterPartIsRefusedBeforeAnyPartIsWritten() throws RelizaException {
		Tenant a = tenant();
		Snapshot before = snapshot(a, a, a.release(), a.deliverable(), a.sce(), a.variant());
		Map<String, Object> bom = externalArtifact();
		bom.put("type", ArtifactType.BOM.name());
		Map<String, Object> forged = externalArtifact();
		Map<String, Object> record = new HashMap<>();
		record.put("algo", TeaChecksumType.SHA_256.name());
		record.put("digest", "a".repeat(64));
		record.put("scope", DigestScope.AS_UPLOADED.name());
		forged.put("digestRecords", new ArrayList<>(List.of(record)));
		Map<String, Object> input = artifactInput(a.release(), null);
		input.put("releaseArtifacts", new ArrayList<>(List.of(bom)));
		input.put("deliverableArtifacts", targeted("deliverable", a.deliverable(), forged));

		RelizaException re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", input)));
		assertEquals("Digest scope AS_UPLOADED is computed by ReARM and may not be supplied on upload.", re.getMessage());
		assertEquals(before, snapshot(a, a, a.release(), a.deliverable(), a.sce(), a.variant()));

		// an entry without a file: refused up front too
		Map<String, Object> noFile = externalArtifact();
		noFile.remove("file");
		Map<String, Object> second = artifactInput(a.release(), null);
		second.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
		second.put("sceArtifacts", targeted("sce", a.sce(), noFile));
		re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", second)));
		assertTrue(re.getMessage().startsWith("Every artifact in this upload needs a file"), re.getMessage());
		assertEquals(before, snapshot(a, a, a.release(), a.deliverable(), a.sce(), a.variant()));
	}

	@Test
	public void releaseArtifactsOnALockedComponentAreRefusedBeforeAnyPartIsWritten() throws RelizaException {
		Tenant a = tenant();
		componentLockService.lockComponent(a.component().getUuid(), "binding test freeze", null, null, WU);
		Snapshot before = snapshot(a, a, a.release(), a.deliverable(), a.sce(), a.variant());
		Map<String, Object> input = artifactInput(a.release(), null);
		input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
		input.put("deliverableArtifacts", targeted("deliverable", a.deliverable(), externalArtifact()));
		input.put("sceArtifacts", targeted("sce", a.sce(), externalArtifact()));
		assertThrows(ActionRefusedException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", input)));
		assertEquals(before, snapshot(a, a, a.release(), a.deliverable(), a.sce(), a.variant()),
				"the deliverable and SCE parts are not written either");

		// deliverable- and SCE-only inputs are not lock-gated, as before this change (follow-up)
		Map<String, Object> del = artifactInput(a.release(), null);
		del.put("deliverableArtifacts", targeted("deliverable", a.deliverable(), externalArtifact()));
		assertEquals(a.release(), releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", del)).getUuid());
		assertEquals(1, getDeliverableService.getDeliverableData(a.deliverable()).orElseThrow().getArtifacts().size());
	}

	@Test
	public void anotherComponentsDeliverableListedOnTheReleaseIsRefused() throws RelizaException {
		Tenant a = tenant();
		Tenant sibling = tenantIn(a.org());
		// listed on A's release both ways: reused as an outbound deliverable (as prepareListofDeliverables
		// does for a digest already recorded) and as an inbound deliverable of a second release
		variantService.addOutboundDeliverables(List.of(sibling.deliverable()), a.variant(), WU);
		UUID withInbound = ossReleaseService.createRelease(ReleaseDto.builder().component(a.component().getUuid())
				.org(a.org()).branch(a.branch().getUuid()).version("1.0.2").inboundDeliverables(List.of(sibling.deliverable()))
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.DRAFT).build(), WU).getUuid();
		assertTrue(sharedReleaseService.getReleaseData(withInbound).orElseThrow().getInboundDeliverables()
				.contains(sibling.deliverable()), "fixture: inbound deliverable recorded");
		Snapshot siblingBefore = snapshot(a, a, sibling.release(), sibling.deliverable(), sibling.sce(), sibling.variant());

		for (UUID release : List.of(a.release(), withInbound)) {
			Map<String, Object> input = artifactInput(release, null);
			input.put("deliverableArtifacts", targeted("deliverable", sibling.deliverable(), externalArtifact()));
			RelizaException re = assertThrows(RelizaException.class,
					() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", input)));
			assertEquals(DELIVERABLE_NOT_FOUND + sibling.deliverable(), re.getMessage());
		}
		assertEquals(siblingBefore, snapshot(a, a, sibling.release(), sibling.deliverable(), sibling.sce(), sibling.variant()));
	}

	@Test
	public void aRequestedReleaseThatDoesNotResolveIsNotReplacedByTheVersion() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		Snapshot before = snapshot(a, a, a.release(), a.deliverable(), a.sce(), a.variant());
		// the version names A's own release; the release uuid does not resolve in A's org
		for (String release : List.of(b.release().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
			Map<String, Object> input = new HashMap<>();
			input.put(CommonVariables.RELEASE_FIELD, release);
			input.put(CommonVariables.VERSION_FIELD, "1.0.0");
			input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
			RelizaException re = assertThrows(RelizaException.class,
					() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", input)));
			assertEquals(RELEASE_NOT_FOUND, re.getMessage(), release);
			Map<String, Object> outbound = deliverablesInput(null, null);
			outbound.put(CommonVariables.RELEASE_FIELD, release);
			outbound.put(CommonVariables.VERSION_FIELD, "1.0.0");
			re = assertThrows(RelizaException.class, () -> deliverableDataFetcher
					.addOutboundDeliverablesProgrammatic(as(a.componentKey(), "deliverables", outbound)));
			assertEquals(RELEASE_NOT_FOUND, re.getMessage(), release);
		}
		assertEquals(before, snapshot(a, a, a.release(), a.deliverable(), a.sce(), a.variant()));
	}

	// ---------------- fix round: the shared external org, and the write rule for source code entries ----------------

	@Test
	public void aReleaseAndAVariantOfTheExternalOrgAreNotFound() throws RelizaException {
		// The org-scoped release lookup admits the shared external org next to the key's own; the
		// component check is what keeps it from being written.
		Tenant a = tenant();
		Tenant external = tenantIn(CommonVariables.EXTERNAL_PROJ_ORG_UUID);
		Snapshot before = snapshot(a, external, external.release(), external.deliverable(), external.sce(),
				external.variant());
		for (ApiKey key : List.of(a.componentKey(), a.orgKey(), a.freeformKey())) {
			Map<String, Object> input = artifactInput(external.release(), null);
			input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
			RelizaException re = assertThrows(RelizaException.class,
					() -> releaseDatafetcher.addArtifactProg(as(key, "artifactInput", input)));
			assertEquals(RELEASE_NOT_FOUND, re.getMessage(), key.getUuid().toString());
		}
		for (ApiKey key : List.of(a.componentKey(), a.orgKey())) {
			RelizaException re = assertThrows(RelizaException.class, () -> deliverableDataFetcher
					.addOutboundDeliverablesProgrammatic(as(key, "deliverables", deliverablesInput(external.release(), null))));
			assertEquals(RELEASE_NOT_FOUND, re.getMessage(), key.getUuid().toString());
			re = assertThrows(RelizaException.class, () -> deliverableDataFetcher
					.addOutboundDeliverablesProgrammatic(as(key, "deliverables", deliverablesInput(null, external.variant()))));
			assertEquals(VARIANT_NOT_FOUND, re.getMessage(), key.getUuid().toString());
		}
		assertEquals(before, snapshot(a, external, external.release(), external.deliverable(), external.sce(),
				external.variant()));
	}

	@Test
	public void anExternalOrgEntryAmongTheReleasesCommitsIsNotWritable() throws RelizaException {
		// A release may reference an entry of the shared external org (the read rule); attaching
		// artifacts to it is a write, and writes are own-org only.
		Tenant a = tenant();
		Tenant external = tenantIn(CommonVariables.EXTERNAL_PROJ_ORG_UUID);
		ossReleaseService.updateRelease(ReleaseDto.builder().uuid(a.release())
				.commits(List.of(a.sce(), external.sce())).build(), WU);
		assertTrue(sharedReleaseService.getReleaseData(a.release()).orElseThrow().getAllCommits()
				.contains(external.sce()), "fixture: the external entry is one of the release's commits");
		Snapshot ownBefore = snapshot(a, external, a.release(), a.deliverable(), a.sce(), a.variant());
		Snapshot externalBefore = snapshot(a, external, external.release(), external.deliverable(), external.sce(),
				external.variant());
		Map<String, Object> input = artifactInput(a.release(), null);
		input.put("releaseArtifacts", new ArrayList<>(List.of(externalArtifact())));
		input.put("sceArtifacts", targeted("sce", external.sce(), externalArtifact()));

		RelizaException re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", input)));
		assertEquals(SCE_NOT_FOUND + external.sce(), re.getMessage());
		assertEquals(ownBefore, snapshot(a, external, a.release(), a.deliverable(), a.sce(), a.variant()));
		assertEquals(externalBefore, snapshot(a, external, external.release(), external.deliverable(), external.sce(),
				external.variant()));
	}

	@Test
	public void aLegacyEntryWithoutAnOrgIsOwnedByItsBranchsOrg() throws RelizaException {
		Tenant a = tenant();
		Tenant b = tenant();
		// legacy rows: stored without an org, so the owner is the branch's org
		stripOrg(a.sce());
		stripOrg(b.sce());
		assertNull(getSourceCodeEntryService.getSourceCodeEntryData(a.sce()).orElseThrow().getOrg(),
				"fixture: the entry has no org");

		// on A's own branch: A's, so writable by A
		Map<String, Object> own = artifactInput(a.release(), null);
		own.put("sceArtifacts", targeted("sce", a.sce(), externalArtifact()));
		assertEquals(a.release(), releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", own)).getUuid());
		assertEquals(1, getSourceCodeEntryService.getSourceCodeEntryData(a.sce()).orElseThrow().getArtifacts().size());

		// on B's branch, even when A's release lists it (a legacy reference): B's, so not A's to write
		Release r = sharedReleaseService.getRelease(a.release()).orElseThrow();
		ReleaseData rd = ReleaseData.dataFromRecord(r);
		rd.setCommits(List.of(a.sce(), b.sce()));
		ossReleaseService.saveRelease(r, rd, WU);
		assertTrue(sharedReleaseService.getReleaseData(a.release()).orElseThrow().getAllCommits().contains(b.sce()),
				"fixture: the foreign legacy entry is one of the release's commits");
		Snapshot before = victim(a, b);
		Map<String, Object> foreign = artifactInput(a.release(), null);
		foreign.put("sceArtifacts", targeted("sce", b.sce(), externalArtifact()));
		RelizaException re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", foreign)));
		assertEquals(SCE_NOT_FOUND + b.sce(), re.getMessage());
		assertEquals(before, victim(a, b));

		// no org and a branch that no longer exists: no known owner, so nobody's to write
		UUID orphan = sourceCodeEntryService.createSourceCodeEntry(SceDto.builder()
				.branch(a.branch().getUuid()).vcs(getSourceCodeEntryService.getSourceCodeEntryData(a.sce()).orElseThrow().getVcs())
				.organizationUuid(a.org()).commit(UUID.randomUUID().toString().replace("-", "") + "dddddddd").build(), WU)
				.getUuid();
		stripOrg(orphan);
		jdbcTemplate.update("UPDATE rearm.source_code_entries SET record_data = jsonb_set(record_data, '{branch}', to_jsonb(?::text))"
				+ " WHERE uuid = ?", UUID.randomUUID().toString(), orphan);
		r = sharedReleaseService.getRelease(a.release()).orElseThrow();
		rd = ReleaseData.dataFromRecord(r);
		rd.setCommits(List.of(a.sce(), b.sce(), orphan));
		ossReleaseService.saveRelease(r, rd, WU);
		int orphanArtifacts = getSourceCodeEntryService.getSourceCodeEntryData(orphan).orElseThrow().getArtifacts().size();
		Map<String, Object> unowned = artifactInput(a.release(), null);
		unowned.put("sceArtifacts", targeted("sce", orphan, externalArtifact()));
		re = assertThrows(RelizaException.class,
				() -> releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", unowned)));
		assertEquals(SCE_NOT_FOUND + orphan, re.getMessage());
		assertEquals(orphanArtifacts, getSourceCodeEntryService.getSourceCodeEntryData(orphan).orElseThrow().getArtifacts().size());
	}

	@Test
	public void anInboundDeliverableOfTheReleasesOwnComponentIsAccepted() throws RelizaException {
		// addRelease records inbound deliverables on the release's own branch: they are the
		// release's, so artifacts may be attached to them.
		Tenant a = tenant();
		UUID inbound = deliverableService.createDeliverable(DeliverableDto.builder()
				.displayIdentifier("bind-inbound-" + UUID.randomUUID()).branch(a.branch().getUuid())
				.type(CdxType.FILE).build(), WU).getUuid();
		UUID withInbound = ossReleaseService.createRelease(ReleaseDto.builder().component(a.component().getUuid())
				.org(a.org()).branch(a.branch().getUuid()).version("1.0.3").inboundDeliverables(List.of(inbound))
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.DRAFT).build(), WU).getUuid();
		Map<String, Object> input = artifactInput(withInbound, null);
		input.put("deliverableArtifacts", targeted("deliverable", inbound, externalArtifact()));

		assertEquals(withInbound, releaseDatafetcher.addArtifactProg(as(a.componentKey(), "artifactInput", input)).getUuid());
		assertEquals(1, getDeliverableService.getDeliverableData(inbound).orElseThrow().getArtifacts().size());
	}

	/** Removes the org from a stored entry behind the service's back: the legacy shape. */
	private void stripOrg(UUID sce) {
		jdbcTemplate.update("UPDATE rearm.source_code_entries SET record_data = record_data - 'org' WHERE uuid = ?", sce);
	}
}
