/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeliverableData.BelongsToOrganization;
import io.reliza.model.Organization;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.DeliverableDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.SceDto;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ArtifactService;
import io.reliza.service.DeliverableService;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetDeliverableService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.GetSourceCodeEntryService;
import io.reliza.service.ReleaseService.DeliverableArtifacts;
import io.reliza.service.ReleaseService.SceArtifacts;
import io.reliza.service.SbomWriteAuthority;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SourceCodeEntryService;
import io.reliza.service.VariantService;
import io.reliza.service.VcsRepositoryService;

/**
 * SCORE-30 (run 2 item T-6, ADR-12): the supplier each upload entry point sends rebom with an SBOM
 * it uploads, down to {@link ArtifactService#uploadArtifact}, so every case also passes through the
 * list upload's per-artifact copy of the options. The programmatic resolvers (addArtifactProgrammatic,
 * addReleaseProgrammatic) are called as a component key; the service sites they and the other paths
 * share are called directly for the cases the resolvers do not reach (EXTERNAL components, no
 * publisher, nested artifacts). The database is real, the upload stops at {@code uploadArtifact},
 * which records a stored artifact row and returns it.
 *
 * <p>SCORE-39 (T-9, ADR-3): a supplier name recorded on the component comes after the publisher and
 * before the organization rule at every entry point, also for an EXTERNAL component.
 */
class UploadSupplierNameWiringIntegrationTest extends SbomScoreGraphqlTestBase {

	private static final AtomicInteger VERSION = new AtomicInteger(600);
	private static final String COMMIT = "a94a8fe5ccb19ba61c4c0873d391e987982fbbd3";
	private static final String PUBLISHER = "Example Devices Inc.";
	private static final String SBOM = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,\"components\":[]}";

	@MockitoSpyBean private ArtifactService uploads;
	@Autowired private ReleaseDatafetcher releaseDatafetcher;
	@Autowired private DeliverableService deliverableService;
	@Autowired private GetDeliverableService getDeliverableService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private GetSourceCodeEntryService getSourceCodeEntryService;
	@Autowired private SourceCodeEntryService sourceCodeEntryService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private VariantService variantService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private ApplicationContext applicationContext;

	private Organization org;

	@BeforeEach
	void uploadStopsAtTheArtifactRow() throws Exception {
		org = testInitializer.obtainOrganization();
		doAnswer(inv -> artifact(((ArtifactDto) inv.getArgument(0)).getOrg(), ArtifactType.BOM, BomFormat.CYCLONEDX, true))
				.when(uploads).uploadArtifact(any(), any(), any(), any(), any());
	}

	// ---- fixture -------------------------------------------------------------------------------

	private Component internalComponent() throws RelizaException {
		return componentService.createComponent("score30-t6-" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
	}

	private Component externalComponent() throws RelizaException {
		return componentService.createComponent(CreateComponentDto.builder().name("score30-t6-ext-" + UUID.randomUUID())
				.organization(org.getUuid()).type(ComponentType.COMPONENT).versionSchema("semver")
				.featureBranchVersioning("Branch.Micro").isInternal(BelongsToOrganization.EXTERNAL).build(), WU);
	}

	private Branch branch(Component c) {
		return branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
	}

	private ComponentData cd(Component c) {
		return getComponentService.getComponentData(c.getUuid()).orElseThrow();
	}

	private OrganizationData od() {
		return getOrganizationService.getOrganizationData(org.getUuid()).orElseThrow();
	}

	private UUID sce(Component c) throws RelizaException {
		UUID vcs = vcsRepositoryService.provisionVcsRepository(org.getUuid(),
				"github.com/example/score30-t6-" + UUID.randomUUID(), VcsType.GIT, WU);
		return sourceCodeEntryService.createSourceCodeEntry(SceDto.builder().branch(branch(c).getUuid()).vcs(vcs)
				.commit(COMMIT).organizationUuid(org.getUuid()).build(), WU).getUuid();
	}

	/** A release of {@code c} built from {@code sce}, its base variant shipping a deliverable with {@code publisher}. */
	private record Shipped(UUID release, UUID deliverable, UUID sce) {}

	private Shipped shipped(Component c, String publisher) throws RelizaException {
		UUID sce = sce(c);
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid()).org(org.getUuid())
				.branch(branch(c).getUuid()).version("30.6." + VERSION.incrementAndGet())
				.sourceCodeEntry(sce).status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.DRAFT).build(), WU)
				.getUuid();
		UUID deliverable = deliverableService.createDeliverable(DeliverableDto.builder()
				.displayIdentifier("score30-t6-" + UUID.randomUUID()).branch(branch(c).getUuid())
				.type(CdxType.CONTAINER).publisher(publisher).build(), WU).getUuid();
		variantService.addOutboundDeliverables(List.of(deliverable),
				variantService.getBaseVariantForRelease(release).getUuid(), WU);
		return new Shipped(release, deliverable, sce);
	}

	private static Map<String, Object> bom() {
		Map<String, Object> a = new LinkedHashMap<>();
		a.put("type", ArtifactType.BOM.name());
		a.put("bomFormat", BomFormat.CYCLONEDX.name());
		a.put("displayIdentifier", "score30-t6-sbom-" + UUID.randomUUID());
		a.put("file", new MockMultipartFile("file", "bom.json", "application/json", SBOM.getBytes(StandardCharsets.UTF_8)));
		return a;
	}

	private static List<Map<String, Object>> boms() {
		return new ArrayList<>(List.of(bom()));
	}

	/** Every options record that reached uploadArtifact, in call order. */
	private List<RebomOptions> sent() throws RelizaException {
		ArgumentCaptor<RebomOptions> c = ArgumentCaptor.forClass(RebomOptions.class);
		verify(uploads, atLeastOnce()).uploadArtifact(any(), any(), c.capture(), any(), any());
		return c.getAllValues();
	}

	/** The one upload of an attachment kind. */
	private RebomOptions sentFor(ArtifactBelongsTo belongsTo) throws RelizaException {
		List<RebomOptions> of = sent().stream().filter(o -> o.belongsTo() == belongsTo).toList();
		assertEquals(1, of.size(), "uploads attached to " + belongsTo);
		return of.get(0);
	}

	private String orgName() {
		return od().getName();
	}

	// ---- programmatic resolvers, as a component key ------------------------------------------

	private DgsDataFetchingEnvironment asComponentKey(Component c, String argument, Map<String, Object> input)
			throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(c.getUuid(), ApiTypeEnum.COMPONENT, org.getUuid(), null,
				"score30 t6 component key", WU);
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.30"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		Map<String, Object> arguments = new HashMap<>();
		arguments.put(argument, input);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(arguments).build(), applicationContext);
	}

	private static List<Map<String, Object>> targeted(String field, UUID target) {
		Map<String, Object> entry = new HashMap<>();
		entry.put(field, target.toString());
		entry.put("artifacts", boms());
		return new ArrayList<>(List.of(entry));
	}

	/** addArtifactProgrammatic (rearm addartifact): release, deliverable and SCE uploads in one call. */
	@Test
	void addArtifactProgrammaticNamesTheOrgAndThePublisher() throws Exception {
		Component c = internalComponent();
		Shipped s = shipped(c, PUBLISHER);
		Map<String, Object> input = new HashMap<>();
		input.put(CommonVariables.RELEASE_FIELD, s.release().toString());
		input.put("releaseArtifacts", boms());
		input.put("deliverableArtifacts", targeted("deliverable", s.deliverable()));
		input.put("sceArtifacts", targeted("sce", s.sce()));

		releaseDatafetcher.addArtifactProg(asComponentKey(c, "artifactInput", input));

		assertEquals(3, sent().size());
		assertEquals(orgName(), sentFor(ArtifactBelongsTo.RELEASE).supplierName(), "release artifact");
		assertEquals(PUBLISHER, sentFor(ArtifactBelongsTo.DELIVERABLE).supplierName(), "deliverable artifact");
		assertEquals(orgName(), sentFor(ArtifactBelongsTo.SCE).supplierName(), "source code entry artifact");
	}

	/** addReleaseProgrammatic (rearm addrelease): --artifacts, --odel* with artifacts and --scearts. */
	@Test
	void addReleaseProgrammaticNamesTheOrgAndThePublisher() throws Exception {
		Component c = internalComponent();
		Map<String, Object> deliverable = new HashMap<>();
		deliverable.put("displayIdentifier", "score30-t6-odel-" + UUID.randomUUID());
		deliverable.put("type", CdxType.CONTAINER.name());
		deliverable.put("publisher", PUBLISHER);
		deliverable.put("artifacts", boms());
		Map<String, Object> sce = new HashMap<>();
		sce.put("uri", "github.com/example/score30-t6-" + UUID.randomUUID());
		sce.put("type", "git");
		sce.put("commit", COMMIT);
		sce.put("artifacts", boms());
		Map<String, Object> input = new HashMap<>();
		input.put(CommonVariables.COMPONENT_FIELD, c.getUuid().toString());
		input.put(CommonVariables.BRANCH_FIELD, branch(c).getUuid().toString());
		input.put(CommonVariables.VERSION_FIELD, "30.6.1");
		input.put(CommonVariables.LIFECYCLE_FIELD, ReleaseLifecycle.DRAFT.name());
		input.put("artifacts", boms());
		input.put("outboundDeliverables", new ArrayList<>(List.of(deliverable)));
		input.put(CommonVariables.SOURCE_CODE_ENTRY_FIELD, sce);

		releaseDatafetcher.addReleaseProgrammatic(asComponentKey(c, "release", input));

		assertEquals(3, sent().size());
		assertEquals(orgName(), sentFor(ArtifactBelongsTo.RELEASE).supplierName(), "release artifact");
		assertEquals(PUBLISHER, sentFor(ArtifactBelongsTo.DELIVERABLE).supplierName(), "new deliverable artifact");
		RebomOptions sceUpload = sentFor(ArtifactBelongsTo.SCE);
		assertEquals(orgName(), sceUpload.supplierName(), "source code entry artifact");
		assertEquals(COMMIT, sceUpload.hash(), "source code entry reference");
	}

	// ---- the shared service sites ------------------------------------------------------------

	private ReleaseData releaseOf(Component c) throws RelizaException {
		return sharedReleaseService.getReleaseData(release(org, c, List.of())).orElseThrow();
	}

	@Test
	void releaseArtifactsOfAnInternalComponentNameTheOrg() throws Exception {
		Component c = internalComponent();
		ReleaseData rd = releaseOf(c);
		releaseService.processReleaseArtifacts(boms(), rd, cd(c), od(), rd.getVersion(), SbomWriteAuthority.NO_PRINCIPAL, WU);
		assertEquals(orgName(), sentFor(ArtifactBelongsTo.RELEASE).supplierName());
	}

	@Test
	void releaseArtifactsOfAnExternalComponentNameNone() throws Exception {
		Component c = externalComponent();
		ReleaseData rd = releaseOf(c);
		releaseService.processReleaseArtifacts(boms(), rd, cd(c), od(), rd.getVersion(), SbomWriteAuthority.NO_PRINCIPAL, WU);
		assertNull(sentFor(ArtifactBelongsTo.RELEASE).supplierName());
	}

	/** An artifact with artifacts of its own: the nested upload is made with the same supplier. */
	@Test
	void nestedArtifactsKeepTheSupplier() throws Exception {
		Component c = internalComponent();
		ReleaseData rd = releaseOf(c);
		Map<String, Object> outer = bom();
		outer.put("artifacts", boms());
		releaseService.processReleaseArtifacts(new ArrayList<>(List.of(outer)), rd, cd(c), od(), rd.getVersion(), SbomWriteAuthority.NO_PRINCIPAL, WU);
		List<RebomOptions> sent = sent();
		assertEquals(2, sent.size());
		sent.forEach(o -> assertEquals(orgName(), o.supplierName(), "nested and outer upload"));
	}

	private RebomOptions deliverableArtifacts(Component c, String publisher) throws Exception {
		Shipped s = shipped(c, publisher);
		ReleaseData rd = sharedReleaseService.getReleaseData(s.release()).orElseThrow();
		releaseService.processDeliverableArtifacts(List.of(new DeliverableArtifacts(
				getDeliverableService.getDeliverableData(s.deliverable()).orElseThrow(), boms())),
				rd, cd(c), od(), rd.getVersion(), SbomWriteAuthority.NO_PRINCIPAL, WU);
		return sentFor(ArtifactBelongsTo.DELIVERABLE);
	}

	@Test
	void deliverableArtifactsNameThePublisher() throws Exception {
		assertEquals(PUBLISHER, deliverableArtifacts(internalComponent(), PUBLISHER).supplierName());
	}

	@Test
	void deliverableArtifactsWithoutAPublisherNameTheOrg() throws Exception {
		assertEquals(orgName(), deliverableArtifacts(internalComponent(), null).supplierName());
	}

	@Test
	void deliverableArtifactsOfAnExternalComponentNameNone() throws Exception {
		assertNull(deliverableArtifacts(externalComponent(), null).supplierName());
	}

	private RebomOptions sceArtifacts(Component c) throws Exception {
		Shipped s = shipped(c, null);
		ReleaseData rd = sharedReleaseService.getReleaseData(s.release()).orElseThrow();
		SourceCodeEntryData sced = getSourceCodeEntryService.getSourceCodeEntryData(s.sce()).orElseThrow();
		releaseService.processSceArtifacts(List.of(new SceArtifacts(sced, boms())), rd, cd(c), od(), rd.getVersion(), SbomWriteAuthority.NO_PRINCIPAL, WU);
		return sentFor(ArtifactBelongsTo.SCE);
	}

	@Test
	void sceArtifactsOfAnInternalComponentNameTheOrg() throws Exception {
		assertEquals(orgName(), sceArtifacts(internalComponent()).supplierName());
	}

	@Test
	void sceArtifactsOfAnExternalComponentNameNone() throws Exception {
		assertNull(sceArtifacts(externalComponent()).supplierName());
	}

	private RebomOptions sceUpload(Component c) throws Exception {
		releaseService.uploadSceArtifacts(boms(), od(), SceDto.builder().commit(COMMIT).build(), cd(c), "30.6.2", SbomWriteAuthority.NO_PRINCIPAL, WU);
		return sentFor(ArtifactBelongsTo.SCE);
	}

	/** uploadSceArtifacts: addrelease --scearts and --commits, and the commit paths of a new version. */
	@Test
	void sceUploadOfAnInternalComponentNamesTheOrg() throws Exception {
		assertEquals(orgName(), sceUpload(internalComponent()).supplierName());
	}

	@Test
	void sceUploadOfAnExternalComponentNamesNone() throws Exception {
		assertNull(sceUpload(externalComponent()).supplierName());
	}

	/** prepareListofDeliverables: addrelease --odel* / inbound deliverables and addDeliverableProgrammatic. */
	private RebomOptions newDeliverable(Component c, String publisher) throws Exception {
		Map<String, Object> d = new LinkedHashMap<>();
		d.put("displayIdentifier", "score30-t6-" + UUID.randomUUID());
		d.put("type", CdxType.CONTAINER.name());
		if (null != publisher) d.put("publisher", publisher);
		d.put("artifacts", boms());
		deliverableService.prepareListofDeliverables(new ArrayList<>(List.of(d)), branch(c).getUuid(), "30.6.3", SbomWriteAuthority.NO_PRINCIPAL, WU);
		return sentFor(ArtifactBelongsTo.DELIVERABLE);
	}

	@Test
	void newDeliverableNamesThePublisher() throws Exception {
		assertEquals(PUBLISHER, newDeliverable(internalComponent(), PUBLISHER).supplierName());
	}

	@Test
	void newDeliverableWithoutAPublisherNamesTheOrg() throws Exception {
		assertEquals(orgName(), newDeliverable(internalComponent(), null).supplierName());
	}

	@Test
	void newDeliverableOfAnExternalComponentNamesNone() throws Exception {
		assertNull(newDeliverable(externalComponent(), null).supplierName());
	}

	// ---- SCORE-39 T-9: the component's supplier name -------------------------------------------

	private static final String COMPONENT_SUPPLIER = "Acme Corp";

	private Component suppliedComponent(BelongsToOrganization isInternal) throws RelizaException {
		return componentService.createComponent(CreateComponentDto.builder().name("score39-t9-" + UUID.randomUUID())
				.organization(org.getUuid()).type(ComponentType.COMPONENT).versionSchema("semver")
				.featureBranchVersioning("Branch.Micro").isInternal(isInternal).supplierName(COMPONENT_SUPPLIER).build(), WU);
	}

	@Test
	void addArtifactProgrammaticNamesTheComponentSupplierWhereNoPublisherDoes() throws Exception {
		Component c = suppliedComponent(BelongsToOrganization.INTERNAL);
		Shipped s = shipped(c, null);
		Map<String, Object> input = new HashMap<>();
		input.put(CommonVariables.RELEASE_FIELD, s.release().toString());
		input.put("releaseArtifacts", boms());
		input.put("deliverableArtifacts", targeted("deliverable", s.deliverable()));
		input.put("sceArtifacts", targeted("sce", s.sce()));

		releaseDatafetcher.addArtifactProg(asComponentKey(c, "artifactInput", input));

		assertEquals(3, sent().size());
		assertEquals(COMPONENT_SUPPLIER, sentFor(ArtifactBelongsTo.RELEASE).supplierName(), "release artifact");
		assertEquals(COMPONENT_SUPPLIER, sentFor(ArtifactBelongsTo.DELIVERABLE).supplierName(), "deliverable artifact");
		assertEquals(COMPONENT_SUPPLIER, sentFor(ArtifactBelongsTo.SCE).supplierName(), "source code entry artifact");
	}

	@Test
	void addReleaseProgrammaticNamesThePublisherThenTheComponentSupplier() throws Exception {
		Component c = suppliedComponent(BelongsToOrganization.INTERNAL);
		Map<String, Object> deliverable = new HashMap<>();
		deliverable.put("displayIdentifier", "score39-t9-odel-" + UUID.randomUUID());
		deliverable.put("type", CdxType.CONTAINER.name());
		deliverable.put("publisher", PUBLISHER);
		deliverable.put("artifacts", boms());
		Map<String, Object> input = new HashMap<>();
		input.put(CommonVariables.COMPONENT_FIELD, c.getUuid().toString());
		input.put(CommonVariables.BRANCH_FIELD, branch(c).getUuid().toString());
		input.put(CommonVariables.VERSION_FIELD, "39.9.1");
		input.put(CommonVariables.LIFECYCLE_FIELD, ReleaseLifecycle.DRAFT.name());
		input.put("artifacts", boms());
		input.put("outboundDeliverables", new ArrayList<>(List.of(deliverable)));

		releaseDatafetcher.addReleaseProgrammatic(asComponentKey(c, "release", input));

		assertEquals(COMPONENT_SUPPLIER, sentFor(ArtifactBelongsTo.RELEASE).supplierName(), "release artifact");
		assertEquals(PUBLISHER, sentFor(ArtifactBelongsTo.DELIVERABLE).supplierName(), "a publisher still wins");
	}

	@Test
	void releaseArtifactsOfAnExternalComponentNameItsSupplier() throws Exception {
		Component c = suppliedComponent(BelongsToOrganization.EXTERNAL);
		ReleaseData rd = releaseOf(c);
		releaseService.processReleaseArtifacts(boms(), rd, cd(c), od(), rd.getVersion(), SbomWriteAuthority.NO_PRINCIPAL, WU);
		assertEquals(COMPONENT_SUPPLIER, sentFor(ArtifactBelongsTo.RELEASE).supplierName());
	}

	@Test
	void deliverableArtifactsOfAnExternalComponentWithoutAPublisherNameItsSupplier() throws Exception {
		assertEquals(COMPONENT_SUPPLIER,
				deliverableArtifacts(suppliedComponent(BelongsToOrganization.EXTERNAL), null).supplierName());
	}
}
