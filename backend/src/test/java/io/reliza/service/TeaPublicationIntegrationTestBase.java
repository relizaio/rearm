/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.RootComponentMergeMode;
import io.reliza.common.Utils.StripBom;
import io.reliza.model.AcollectionData;
import io.reliza.model.AcollectionData.VersionedArtifact;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.Branch;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ParentRelease;
import io.reliza.model.ReleaseArtifactIndex;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaTei;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.SbomProfileData;
import io.reliza.model.SbomProfileData.SbomDependencyDepth;
import io.reliza.model.SbomProfileData.SbomInternalMetadata;
import io.reliza.model.SbomProfileData.SbomOptionalDependencies;
import io.reliza.model.SbomProfileData.SbomSourceKind;
import io.reliza.model.SbomProfileData.SbomSupportMetadata;
import io.reliza.model.SbomProfileData.SbomVulnerabilityDocuments;
import io.reliza.model.SettingsScope;
import io.reliza.model.dto.SbomProfileInputDto;
import io.reliza.model.TeaPublication;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.TeaPublishedCollection;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ArtifactUploadResponseDto;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.OASResponseDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.TeaProfileInputDto;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.Rebom.RebomResponse;
import io.reliza.model.DigestAlgorithm;
import io.reliza.model.Link;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.ReleaseArtifactIndexRepository;
import io.reliza.repositories.TeaPublicationRepository;
import io.reliza.repositories.TeaPublishedCollectionRepository;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.TeaPublicationService.TeaChildOutcome;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishRequest;
import io.reliza.service.TeaPublicationService.TeaPublishResult;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.ProgrammaticAuthenticationFilter;
import io.reliza.ws.ProgrammaticGraphQlConfig;
import io.reliza.ws.oss.TestInitializer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * TEA-5, section 4.8: the fixture every TeaPublicationService database class shares. The engine
 * runs end to end against the test database with rebom mocked (the merge answers a small
 * CycloneDX document rooted at the release, the upload echoes the uploaded serial number) and the
 * OCI push stubbed on a spied ArtifactService. Each test uses a fresh organization whose TEA
 * profile it saves itself. The subclasses share one Spring context. {@link #asRequest} plays the
 * persistence context of a GraphQL request around an act (round 4, T-8).
 */
@SpringBootTest(classes = {App.class})
abstract class TeaPublicationIntegrationTestBase {

	static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@MockitoBean RebomService rebomService;
	@MockitoSpyBean ArtifactService artifactService;
	@MockitoSpyBean UserService userService;
	@Autowired DgsQueryExecutor dgsQueryExecutor;
	@Autowired TestInitializer testInitializer;
	@Autowired ComponentService componentService;
	@Autowired BranchService branchService;
	@Autowired OssReleaseService ossReleaseService;
	@Autowired ReleaseService releaseService;
	@Autowired SharedArtifactService sharedArtifactService;
	@Autowired SharedReleaseService sharedReleaseService;
	@Autowired AcollectionService acollectionService;
	@Autowired SbomComponentService sbomComponentService;
	@Autowired SbomProfileService sbomProfileService;
	@Autowired TeaProfileService teaProfileService;
	@Autowired TeaPublicationService teaPublicationService;
	@Autowired TeaPublicationRepository publicationRepository;
	@Autowired TeaPublishedCollectionRepository collectionRepository;
	@Autowired ArtifactRepository artifactRepository;
	@Autowired ReleaseArtifactIndexRepository releaseArtifactIndexRepository;
	@Autowired ApiKeyService apiKeyService;
	@Autowired LicenseStatus licenseStatus;
	@Autowired EntityManagerFactory emf;
	@Autowired GetComponentService getComponentService;
	@Autowired GetOrganizationService getOrganizationService;

	boolean wasSealed;
	boolean wasLicensed;

	/** The purls the mocked merge answers, per release, and every merge's input ids. */
	final Map<UUID, List<String>> mergedPurls = new ConcurrentHashMap<>();
	final Map<UUID, UUID> mergeOfRelease = new ConcurrentHashMap<>();
	final Map<UUID, JsonNode> uploaded = new ConcurrentHashMap<>();
	final List<List<UUID>> mergeInputs = Collections.synchronizedList(new ArrayList<>());
	/**
	 * Task TEA-28, design 4.12: the purls a merge answers when its options ask for top-level dependencies
	 * only, per release; absent, such a merge answers {@link #mergedPurls} as any other. So two profiles of
	 * one release can produce different documents.
	 */
	final Map<UUID, List<String>> tldOnlyPurls = new ConcurrentHashMap<>();
	final Set<UUID> tldOnlyMergeIds = ConcurrentHashMap.newKeySet();
	/**
	 * Task TEA-28: releases whose merged components carry {@link #INTERNAL_MARKER}, a reliza property an
	 * internalMetadata EXCLUDE strips, so the stored aggregate shows which way its recipe said.
	 */
	final Set<UUID> internalMarked = ConcurrentHashMap.newKeySet();
	static final String INTERNAL_MARKER = "reliza:tea28:internal";
	/** One rebom merge as the mock received it: the inputs, the options and the id it answered (TEA-15). */
	record MergeCall(List<UUID> ids, RebomOptions options, UUID id) {}
	/** Every merge, in order (TEA-15). */
	final List<MergeCall> mergeCalls = Collections.synchronizedList(new ArrayList<>());
	/** When set, the next merge counts {@code mergeEntered} down and blocks until {@code mergeGate} opens. */
	volatile CountDownLatch mergeGate;
	volatile CountDownLatch mergeEntered;
	/** When set with {@code mergeGate}, only the merge of this release blocks; the others pass. */
	volatile UUID mergeGateRelease;
	/** How many matching merges pass before the gated one (a product's merge also merges its children's). */
	final java.util.concurrent.atomic.AtomicInteger mergeGateSkip = new java.util.concurrent.atomic.AtomicInteger();

	Organization org;

	@BeforeEach
	void fixtures() throws Exception {
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
		org = testInitializer.obtainOrganization();
		wireRebom();
		OASResponseDto push = new OASResponseDto();
		ArtifactUploadResponseDto oci = new ArtifactUploadResponseDto();
		oci.setDigest("sha256:" + "ab".repeat(32));
		oci.setMediaType("application/json");
		oci.setSize("100");
		push.setOciResponse(oci);
		push.setOciRepositoryName("downloadable-artifacts-test");
		doReturn(push).when(artifactService).uploadFileToConfiguredOci(any(), anyString(), any());
	}

	@AfterEach
	void restore() {
		SecurityContextHolder.clearContext();
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	/**
	 * The mocked merge {@link #wireRebom} installs, named so a case can wrap it (SCORE-71 T-15) and
	 * restore it with {@code thenAnswer(mergeAnswer)}.
	 */
	final Answer<UUID> mergeAnswer = inv -> {
		RebomOptions o = inv.getArgument(1);
		CountDownLatch gate = mergeGate;
		UUID only = mergeGateRelease;
		boolean matches = null == only || only.equals(releaseByVersion(o.version()));
		if (null != gate && matches && mergeGateSkip.getAndDecrement() > 0) gate = null;
		if (null != gate && matches) {
			mergeGate = null;
			mergeGateRelease = null;
			mergeEntered.countDown();
			assertTrue(gate.await(60, TimeUnit.SECONDS), "the merge gate never opened");
		}
		UUID id = UUID.randomUUID();
		mergeInputs.add(new ArrayList<>(inv.<List<UUID>>getArgument(0)));
		// the merge is named after the release it was asked for: the version is enough here
		mergeOfRelease.put(id, releaseByVersion(o.version()));
		mergeCalls.add(new MergeCall(List.copyOf(inv.<List<UUID>>getArgument(0)), o, id));
		if (Boolean.TRUE.equals(o.tldOnly())) tldOnlyMergeIds.add(id);
		return id;
	};

	void wireRebom() throws Exception {
		when(rebomService.mergeAndStoreBoms(anyList(), any(), any())).thenAnswer(mergeAnswer);
		when(rebomService.findBomByIdJson(any(), any())).thenAnswer(inv -> {
			UUID id = inv.getArgument(0);
			if (uploaded.containsKey(id)) return uploaded.get(id).deepCopy();
			UUID release = mergeOfRelease.get(id);
			JsonNode doc;
			if (null != release && tldOnlyMergeIds.contains(id) && tldOnlyPurls.containsKey(release)) {
				doc = mergedDocument(release, tldOnlyPurls.get(release));
			} else {
				doc = mergedDocument(release, null == release ? List.of() : mergedPurls.getOrDefault(release, List.of()));
			}
			if (null != release && internalMarked.contains(release)) {
				for (JsonNode c : doc.get("components")) {
					((ObjectNode) c).putArray("properties").addObject().put("name", INTERNAL_MARKER).put("value", "1");
				}
			}
			return doc;
		});
		when(rebomService.uploadSbom(any(), any(), any(), any(), any())).thenAnswer(inv -> {
			JsonNode bom = inv.getArgument(0);
			String serial = bom.get("serialNumber").asText();
			uploaded.put(UUID.fromString(serial.replace("urn:uuid:", "")), bom.deepCopy());
			String digest = Utils.bytesToHexSha256(Utils.OM.writeValueAsBytes(bom));
			RebomOptions meta = new RebomOptions(null, null, null, ArtifactBelongsTo.SYNTHETIC, null, false, false,
					null, null, null, null, null, null, null,
					BomStructureType.FLAT, null, StripBom.FALSE, serial, digest, null, null, null, null,
					RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null, null, null);
			OASResponseDto stored = new OASResponseDto();
			ArtifactUploadResponseDto oci = new ArtifactUploadResponseDto();
			oci.setDigest("sha256:" + digest);
			oci.setMediaType("application/vnd.cyclonedx+json");
			oci.setSize("200");
			stored.setOciResponse(oci);
			return new RebomResponse(UUID.randomUUID(), stored, meta, false);
		});
	}

	final Map<String, UUID> releasesByVersion = new ConcurrentHashMap<>();

	/** The merges of {@code release}, in order (TEA-15). */
	List<MergeCall> mergesOf(UUID release) {
		synchronized (mergeCalls) {
			return mergeCalls.stream().filter(m -> release.equals(mergeOfRelease.get(m.id()))).toList();
		}
	}

	/** Task TEA-28: whether the aggregate row's stored document kept {@link #INTERNAL_MARKER} (internal metadata INCLUDE). */
	boolean keptInternalMarker(UUID aggregateRow) {
		UUID serial = artifactService.getArtifactData(aggregateRow).orElseThrow().getInternalBom().id();
		JsonNode doc = uploaded.get(serial);
		assertNotNull(doc, "no upload of " + aggregateRow);
		return doc.toString().contains(INTERNAL_MARKER);
	}

	UUID releaseByVersion(String version) {
		return null == version ? null : releasesByVersion.get(version);
	}

	static JsonNode mergedDocument(UUID release, List<String> purls) {
		ObjectNode doc = Utils.OM.createObjectNode();
		doc.put("bomFormat", "CycloneDX");
		doc.put("specVersion", "1.6");
		doc.put("serialNumber", "urn:uuid:" + UUID.randomUUID());
		doc.put("version", 1);
		ObjectNode md = doc.putObject("metadata");
		md.put("timestamp", "2026-10-03T00:00:00Z");
		md.putObject("component").put("type", "application").put("name", "release-" + release);
		ArrayNode comps = doc.putArray("components");
		for (String p : purls) comps.addObject().put("type", "library").put("name", p).put("purl", p);
		return doc;
	}

	// ---------------------------------------------------------------- fixtures

	void saveOrgProfile(TeaRawArtifacts raw, TeaProductComponents productComponents) throws Exception {
		saveOrgProfile(TeaVisibility.PRIVATE, raw, productComponents);
	}

	void saveOrgProfile(TeaVisibility visibility, TeaRawArtifacts raw, TeaProductComponents productComponents)
			throws Exception {
		saveOrgRecipe();
		teaProfileService.saveProfile(org.getUuid(), TeaProfileScope.ORGANIZATION, null,
				profileInput(TeaPublishing.ENABLED, visibility, raw, productComponents).build(), TeaCaller.ORG_ADMIN, WU);
	}

	/** Task TEA-28: the organization's ENABLED TEA row naming {@code names}, with the org fixture's {@code default}. */
	void saveOrgProfileNaming(TeaVisibility visibility, TeaRawArtifacts raw, String... names) throws Exception {
		saveOrgRecipe();
		teaProfileService.saveProfile(org.getUuid(), TeaProfileScope.ORGANIZATION, null,
				profileInput(TeaPublishing.ENABLED, visibility, raw, TeaProductComponents.PUBLISH_WITH_PRODUCT)
						.profiles(List.of(names)).build(), TeaCaller.ORG_ADMIN, WU);
	}

	/**
	 * Task TEA-28: SBOM profile {@code name} at a scope with the org fixture's shape, {@code depth} and
	 * {@code internalMetadata} (a COMPONENT row is an OVERRIDE); answers the saved row.
	 */
	SbomProfileData saveSbomProfile(SettingsScope scope, UUID object, String name, SbomDependencyDepth depth,
			SbomInternalMetadata internalMetadata) throws Exception {
		return sbomProfileService.saveProfile(org.getUuid(), scope, object, name, recipeInput()
				.mode(SettingsScope.COMPONENT == scope ? io.reliza.model.ScopedSettingsMode.OVERRIDE : null)
				.dependencyDepth(depth).internalMetadata(internalMetadata).build(), WU);
	}

	/**
	 * The org fixture's shape knobs as the organization's SBOM profile {@code default} (task TEA-27):
	 * exactly the recipe the TEA-5 fixture row carried, so every existing case publishes the same
	 * documents as before the recipe moved to the catalog.
	 */
	void saveOrgRecipe() throws Exception {
		sbomProfileService.saveProfile(org.getUuid(), SettingsScope.ORGANIZATION, null, SbomProfileData.DEFAULT_NAME,
				recipeInput().build(), WU);
	}

	static SbomProfileInputDto.SbomProfileInputDtoBuilder recipeInput() {
		return SbomProfileInputDto.builder().dependencyDepth(SbomDependencyDepth.FULL)
				.optionalDependencies(SbomOptionalDependencies.INCLUDE).structure(BomStructureType.FLAT)
				.sources(Set.of(SbomSourceKind.DELIVERABLE, SbomSourceKind.RELEASE, SbomSourceKind.SOURCE_CODE))
				.excludedCoverage(Set.of()).supportMetadata(SbomSupportMetadata.EXCLUDE)
				.internalMetadata(SbomInternalMetadata.EXCLUDE).vulnerabilityDocuments(SbomVulnerabilityDocuments.NONE);
	}

	/** A COMPONENT OVERRIDE row with the given exposure knobs and the org fixture's shape knobs. */
	void saveComponentProfile(UUID component, TeaPublishing publishing, TeaVisibility visibility, TeaRawArtifacts raw)
			throws Exception {
		teaProfileService.saveProfile(org.getUuid(), TeaProfileScope.COMPONENT, component,
				profileInput(publishing, visibility, raw, TeaProductComponents.PUBLISH_WITH_PRODUCT)
						.mode(TeaComponentProfileMode.OVERRIDE).build(), TeaCaller.ORG_ADMIN, WU);
	}

	static TeaProfileInputDto.TeaProfileInputDtoBuilder profileInput(TeaPublishing publishing, TeaVisibility visibility,
			TeaRawArtifacts raw, TeaProductComponents productComponents) {
		return TeaProfileInputDto.builder().publishing(publishing)
				.visibility(visibility).rawArtifacts(raw).productComponents(productComponents)
				.minimumLifecycle(ReleaseLifecycle.ASSEMBLED).tei(TeaTei.DISABLED)
				.profiles(List.of(SbomProfileData.DEFAULT_NAME));
	}

	record World(UUID component, UUID branch, UUID release, UUID inventory) {}

	World componentRelease(String purl) throws Exception {
		return componentReleaseIn(org, purl);
	}

	/** An ASSEMBLED component release of a new component of {@code o}, carrying one SBOM when {@code purl} is set. */
	World componentReleaseIn(Organization o, String purl) throws Exception {
		String slug = "tea-5-" + UUID.randomUUID().toString().substring(0, 8);
		UUID component = componentService.createComponent(CreateComponentDto.builder()
				.organization(o.getUuid()).name(slug).type(ComponentType.COMPONENT)
				.versionSchema("semver").featureBranchVersioning("Branch.Micro").build(), WU).getUuid();
		Branch branch = branchService.findBranchByName(component, "main", true, WU).get();
		String version = "1.0." + Math.abs(UUID.randomUUID().hashCode() % 100000);
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(component)
				.branch(branch.getUuid()).org(o.getUuid()).status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.DRAFT).version(version).build(), WU).getUuid();
		releasesByVersion.put(version, release);
		UUID inventory = null;
		if (null != purl) {
			inventory = seedBom(o, slug + ".cdx.json");
			releaseService.addArtifact(inventory, release, WU);
			mergedPurls.put(release, new ArrayList<>(List.of(purl)));
		}
		ossReleaseService.updateReleaseLifecycle(release, ReleaseLifecycle.ASSEMBLED, WU);
		return new World(component, branch.getUuid(), release, inventory);
	}

	/** A ReARM-stored BOM row with an internal BOM pointer and a retained-upload digest, no rebom side. */
	UUID seedBom(String name) throws Exception {
		return seedBom(org, name);
	}

	UUID seedBom(Organization o, String name) throws Exception {
		Artifact a = artifactService.persistArtifact(ArtifactDto.builder().uuid(UUID.randomUUID()).org(o.getUuid())
				.type(ArtifactType.BOM).displayIdentifier(name).storedIn(StoredIn.REARM).version("1").build(), WU);
		ArtifactData ad = ArtifactData.dataFromRecord(sharedArtifactService.getArtifact(a.getUuid()).orElseThrow());
		ad.setInternalBom(new InternalBom(UUID.randomUUID(), ArtifactBelongsTo.RELEASE));
		ad.setDigestRecords(new LinkedHashSet<>(Set.of(new DigestRecord(DigestAlgorithm.SHA_256,
				Utils.bytesToHexSha256(name.getBytes(StandardCharsets.UTF_8)), DigestScope.AS_UPLOADED))));
		sharedArtifactService.saveArtifact(sharedArtifactService.getArtifact(a.getUuid()).orElseThrow(), ad, WU);
		return a.getUuid();
	}

	/**
	 * Task TEA-19: a RELEASE_NOTES row stored EXTERNALLY at {@code url} with one declared ORIGINAL_FILE SHA-256 and the
	 * download tags (media type text/html); the shape of the TEA-10 base's six-argument seedExternal without its type
	 * and media-type parameters. The url is stored as given: the engine, not this helper, judges it.
	 */
	UUID seedExternal(Organization o, String name, String url, String sha256Hex) throws Exception {
		Artifact a = artifactService.persistArtifact(ArtifactDto.builder().uuid(UUID.randomUUID()).org(o.getUuid())
				.type(ArtifactType.RELEASE_NOTES).displayIdentifier(name).storedIn(StoredIn.EXTERNALLY).version("1")
				.downloadLinks(List.of(new Link(url, Link.ContentEnum.OCTET_STREAM))).build(), WU);
		ArtifactData ad = ArtifactData.dataFromRecord(sharedArtifactService.getArtifact(a.getUuid()).orElseThrow());
		ad.setTags(new ArrayList<>(List.of(new TagRecord(CommonVariables.DOWNLOADABLE_ARTIFACT, "true", Removable.YES),
				new TagRecord(CommonVariables.TAG_FIELD, name, Removable.YES),
				new TagRecord(CommonVariables.MEDIA_TYPE_FIELD, "text/html", Removable.YES),
				new TagRecord(CommonVariables.FILE_NAME_FIELD, name, Removable.YES))));
		ad.setStoredIn(StoredIn.EXTERNALLY);
		ad.setDownloadLinks(new ArrayList<>(List.of(new Link(url, Link.ContentEnum.OCTET_STREAM))));
		ad.setDigestRecords(new LinkedHashSet<>(List.of(new DigestRecord(DigestAlgorithm.SHA_256, sha256Hex,
				DigestScope.ORIGINAL_FILE))));
		sharedArtifactService.saveArtifact(sharedArtifactService.getArtifact(a.getUuid()).orElseThrow(), ad, WU);
		return a.getUuid();
	}

	/**
	 * Task TEA-20: a SIGNATURE row stored EXTERNALLY at {@code url} (version 1, no tags, no digest: the engine's
	 * signature block reads the storage, the links, the version, the uuid and the display identifier only), attached
	 * as the first child of {@code signed} the way the TEA-10 base's attachChild attaches one. The url is stored as
	 * given: the engine judges it.
	 */
	UUID attachExternalSignature(Organization o, UUID signed, String name, String url) throws Exception {
		UUID uuid = artifactService.persistArtifact(ArtifactDto.builder().uuid(UUID.randomUUID()).org(o.getUuid())
				.type(ArtifactType.SIGNATURE).displayIdentifier(name).storedIn(StoredIn.EXTERNALLY).version("1")
				.downloadLinks(List.of(new Link(url, Link.ContentEnum.OCTET_STREAM))).build(), WU).getUuid();
		ArtifactData sig = ArtifactData.dataFromRecord(sharedArtifactService.getArtifact(uuid).orElseThrow());
		sig.setStoredIn(StoredIn.EXTERNALLY);
		sig.setDownloadLinks(new ArrayList<>(List.of(new Link(url, Link.ContentEnum.OCTET_STREAM))));
		sharedArtifactService.saveArtifact(sharedArtifactService.getArtifact(uuid).orElseThrow(), sig, WU);
		ArtifactData parent = ArtifactData.dataFromRecord(sharedArtifactService.getArtifact(signed).orElseThrow());
		List<UUID> children = new ArrayList<>(null == parent.getArtifacts() ? List.of() : parent.getArtifacts());
		children.add(uuid);
		parent.setArtifacts(children);
		sharedArtifactService.saveArtifact(sharedArtifactService.getArtifact(signed).orElseThrow(), parent, WU);
		return uuid;
	}

	/**
	 * Runs {@code act} as a GraphQL request runs it (round 4, T-8): a persistence context bound to
	 * this thread exactly as {@code OpenEntityManagerInViewInterceptor} binds it, then the reads
	 * the fetcher and its authorization check make before the service runs (the release, its
	 * component, its organization: a superset, because a stale managed instance is the mechanism
	 * under test and more of them cannot make a correct act fail), then the act, whose
	 * {@code @Transactional} methods join that context. The helper checks itself, so a case that
	 * uses it can never pass vacuously.
	 */
	<T> T asRequest(UUID release, Callable<T> act) throws Exception {
		EntityManager em = emf.createEntityManager();
		TransactionSynchronizationManager.bindResource(emf, new EntityManagerHolder(em));
		try {
			ReleaseData rd = sharedReleaseService.getReleaseData(release).orElseThrow();
			getComponentService.getComponentData(rd.getComponent()).orElseThrow();
			getOrganizationService.getOrganization(rd.getOrg()).orElseThrow();
			assertTrue(TransactionSynchronizationManager.hasResource(emf), "no persistence context is bound");
			assertTrue(em.contains(sharedReleaseService.getRelease(release).orElseThrow()),
					"the pre-read release is not managed by the request's persistence context");
			return act.call();
		} finally {
			TransactionSynchronizationManager.unbindResource(emf);
			em.close();
		}
	}

	TeaPublishResult publish(UUID release, TeaPublishMode mode) throws Exception {
		return teaPublicationService.publish(new TeaPublishRequest(release, mode, null, WU));
	}

	ReleaseData stored(UUID release) {
		return sharedReleaseService.getReleaseData(release).orElseThrow();
	}

	List<ReleaseUpdateEvent> events(UUID release, ReleaseUpdateScope scope) {
		return stored(release).getUpdateEvents().stream().filter(e -> scope == e.rus()).toList();
	}

	UserData admin() throws Exception {
		return member(PermissionType.ADMIN);
	}

	UserData member(PermissionType orgLevel) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Tea5 " + tag, "tea5-" + tag + "@publication.io", true, List.of(org.getUuid()),
				"tea5-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(), orgLevel,
				List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	ExecutionResult asUser(UserData user, String query, Map<String, Object> vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("tea-5-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(query, vars);
	}

	ExecutionResult asKey(ApiKey key, String query, Map<String, Object> vars) {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.12"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		return dgsQueryExecutor.execute(query, vars, Map.of(), headers, null, new ServletWebRequest(request));
	}

	static Map<String, Object> vars(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	@SuppressWarnings("unchecked")
	static <T> T data(ExecutionResult r, String field) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		return (T) ((Map<String, Object>) r.getData()).get(field);
	}

	static final String PUBLISH = "mutation($r: ID!) { publishReleaseOnTea(release: $r) { outcome "
			+ "publication { uuid state latestVersion exposure publicUrl } collection { version } } }";
	/** Task TEA-19: the publish with its ride-along outcomes. */
	static final String PUBLISH_WITH_ARTIFACTS = "mutation($r: ID!) { publishReleaseOnTea(release: $r) { outcome "
			+ "publication { uuid latestVersion } artifacts { artifact status detail } } }";
	static final String REPUBLISH = "mutation($r: ID!) { republishReleaseOnTea(release: $r) { outcome "
			+ "publication { uuid latestVersion } } }";
	static final String HIDE = "mutation($r: ID!) { hideReleaseOnTea(release: $r) { uuid state } }";
	static final String VIEW = "query($r: ID!) { releaseTeaPublicationView(release: $r) { release kind "
			+ "canPublish refusal refusalMessage effectiveVisibility apiBase publicUrl cascades cascadeChildren "
			+ "publication { uuid state latestVersion exposure } collections { version } } }";
	static final String COLLECTIONS = "query($p: ID!) { teaPublicationCollections(publication: $p) { uuid "
			+ "version belongsTo updateReasonType documentSha256 document entries { artifact teaUuid revision origin "
			+ "type checksums { algType algValue } } } }";
	/** Task TEA-20: the collections with the frozen document and each entry's signature record. */
	static final String COLLECTIONS_WITH_SIGNATURES = "query($p: ID!) { teaPublicationCollections(publication: $p) "
			+ "{ version document entries { artifact signatureArtifact signatureRevision } } }";
	static final String PUBLISH_PROG = "mutation($r: ID!) { publishReleaseOnTeaProgrammatic(release: $r) { "
			+ "outcome publication { uuid } } }";

	UUID product(List<UUID> children) throws Exception {
		String slug = "tea-5-product-" + UUID.randomUUID().toString().substring(0, 8);
		UUID component = componentService.createComponent(CreateComponentDto.builder()
				.organization(org.getUuid()).name(slug).type(ComponentType.PRODUCT)
				.versionSchema("semver").featureBranchVersioning("Branch.Micro").build(), WU).getUuid();
		Branch branch = branchService.getBaseBranchOfComponent(component).orElseThrow();
		String version = "2.0." + Math.abs(UUID.randomUUID().hashCode() % 100000);
		UUID release = ossReleaseService.createRelease(ReleaseDto.builder().component(component)
				.branch(branch.getUuid()).org(org.getUuid()).status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.ASSEMBLED).version(version)
				.parentReleases(children.stream().map(c -> ParentRelease.minimalParentReleaseFactory(c)).toList())
				.build(), WU).getUuid();
		releasesByVersion.put(version, release);
		mergedPurls.put(release, children.stream().flatMap(c -> mergedPurls.getOrDefault(c, List.of()).stream())
				.collect(Collectors.toCollection(ArrayList::new)));
		return release;
	}
}
