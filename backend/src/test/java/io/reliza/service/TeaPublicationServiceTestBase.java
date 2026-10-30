/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.MergedSbomUnavailableException;
import io.reliza.exceptions.MergedSbomUnavailableException.ChildBomOutcome;
import io.reliza.exceptions.MergedSbomUnavailableException.ChildBomStatus;
import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.TeaPublishRefusedException;
import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.OrganizationData;
import io.reliza.model.ParentRelease;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.SbomProfileData;
import io.reliza.model.SbomProfileData.SbomInternalMetadata;
import io.reliza.model.ScopedResolutionStatus;
import io.reliza.model.ScopedSettingsSource;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaTei;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaPublication;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.TeaPublishedCollection;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.model.TeaPublishedCollectionData.TeaArtifactOrigin;
import io.reliza.model.TeaPublishedCollectionData.TeaPublishedEntry;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.Link;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.tea.TeaArtifactType;
import io.reliza.model.DigestAlgorithm;
import io.reliza.model.tea.TeaCollectionBelongsToType;
import io.reliza.model.tea.TeaCollectionUpdateReasonType;
import io.reliza.repositories.ComponentRepository;
import io.reliza.repositories.TeaPublicationRepository;
import io.reliza.repositories.TeaPublicationRepository.TeaPublicationHeadView;
import io.reliza.repositories.TeaPublishedCollectionRepository;
import io.reliza.service.ReleaseService.PublicationMergeRequest;
import io.reliza.service.SbomProfileResolver.SbomProfileResolution;
import io.reliza.service.TeaProfileResolver.TeaProfileSource;
import io.reliza.service.TeaProfileResolver.TeaResolutionStatus;
import io.reliza.service.TeaProfileResolver.TeaResolvedProfile;
import io.reliza.service.TeaProfileService.TeaOrgDiscovery;
import io.reliza.service.TeaPublicationService.TeaChildOutcome;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.TeaPublicationService.TeaPublicationExposure;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishPreview;
import io.reliza.service.TeaPublicationService.TeaPublishRequest;
import io.reliza.service.TeaPublicationService.TeaPublishResult;
import io.reliza.service.TeaPublicationService.TeaRideAlongStatus;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.service.oss.OssReleaseService.AcollectionMode;
import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * TEA-5, section 4.1: the fixture every TeaPublicationService unit class shares. The engine runs
 * with every collaborator mocked, both repositories kept in memory and the EntityManager mocked
 * (its refresh is a no-op unless a case stubs it). The baseline is an ASSEMBLED COMPONENT release
 * with one inventory SBOM whose component resolves RESOLVED from ORGANIZATION with publishing
 * ENABLED, rawArtifacts NONE, minimumLifecycle ASSEMBLED; each case changes one thing.
 */
abstract class TeaPublicationServiceTestBase {

	static final UUID ORG = UUID.randomUUID();
	static final UUID ORG_TEA = UUID.randomUUID();
	static final WhoUpdated WU = WhoUpdated.getAutoWhoUpdated();
	static final String API_BASE = "https://rearm.example.com/tea/" + ORG_TEA;

	TeaPublicationService service;
	SharedReleaseService shared;
	GetComponentService getComponentService;
	TeaProfileResolver resolver;
	/** Task TEA-27: answers each component's recipe from {@link #recipes}, else the built-in default. */
	SbomProfileResolver sbomResolver;
	TeaProfileService teaProfileService;
	OrganizationService organizationService;
	ComponentService componentService;
	ReleaseService releaseService;
	SyntheticArtifactService synthetic;
	ArtifactService artifactService;
	TeaPublicationRepository publications;
	TeaPublishedCollectionRepository collections;
	OssReleaseService oss;
	AuditService audit;
	ReleaseChangeHook hook;
	EntityManager entityManager;
	TeaPublishLock teaPublishLock;
	ComponentRepository componentRepository;
	/** Every lease the mocked lock handed out, in order; each a spy over a lease that holds nothing. */
	final List<TeaPublishLock.Lease> leases = new ArrayList<>();

	/** In-memory state the mocks read and write. */
	final Map<UUID, Release> releaseRows = new HashMap<>();
	final Map<UUID, ComponentData> components = new HashMap<>();
	final Map<UUID, ArtifactData> artifacts = new HashMap<>();
	final Map<UUID, TeaPublication> heads = new HashMap<>();
	final Map<String, TeaPublishedCollection> collectionRows = new HashMap<>();
	final Map<UUID, UUID> teaIds = new HashMap<>();
	/** What upload() received, in order. */
	final List<ArtifactDto> writtenDtos = new ArrayList<>();
	final List<byte[]> writtenBytes = new ArrayList<>();
	final List<RebomOptions> writtenOptions = new ArrayList<>();
	/** "acquire:<release>" and "close:<release>" of the leases, in order. */
	final List<String> lockOrder = new ArrayList<>();

	/** What the merge answers next, per release. */
	final Map<UUID, JsonNode> documents = new HashMap<>();
	final Map<UUID, TeaResolvedProfile> resolutions = new HashMap<>();
	/**
	 * What the merge answers next for a request whose recipe says top-level only, per release (task TEA-28):
	 * absent, {@link #documents} answers; so two profiles of one release can produce different digests.
	 */
	final Map<UUID, JsonNode> tldOnlyDocuments = new HashMap<>();
	/** A component and an SBOM profile name: what the mocked SBOM profile resolver answers by (task TEA-28). */
	record RecipeKey(UUID component, String name) {}

	static RecipeKey key(UUID component) {
		return new RecipeKey(component, SbomProfileData.DEFAULT_NAME);
	}

	static RecipeKey key(UUID component, String name) {
		return new RecipeKey(component, name);
	}

	/**
	 * The SBOM profile each (component, name) resolves to (tasks TEA-27, TEA-28); absent: the built-in
	 * default for {@code default}, NOT_FOUND for any other name.
	 */
	final Map<RecipeKey, SbomProfileData> recipes = new HashMap<>();
	/** A whole resolution per (component, name), for the NOT_FOUND and CONFLICT cases; wins over {@link #recipes}. */
	final Map<RecipeKey, SbomProfileResolution> recipeResolutions = new HashMap<>();

	TeaProfileData profile;
	ComponentData component;
	ReleaseData release;
	UUID inventoryBom;

	@BeforeEach
	void setUp() throws Exception {
		service = new TeaPublicationService();
		shared = mock(SharedReleaseService.class);
		getComponentService = mock(GetComponentService.class);
		GetOrganizationService getOrganizationService = mock(GetOrganizationService.class);
		resolver = mock(TeaProfileResolver.class);
		sbomResolver = mock(SbomProfileResolver.class);
		teaProfileService = mock(TeaProfileService.class);
		organizationService = mock(OrganizationService.class);
		componentService = mock(ComponentService.class);
		releaseService = mock(ReleaseService.class);
		synthetic = mock(SyntheticArtifactService.class);
		artifactService = mock(ArtifactService.class);
		publications = mock(TeaPublicationRepository.class);
		collections = mock(TeaPublishedCollectionRepository.class);
		oss = mock(OssReleaseService.class);
		audit = mock(AuditService.class);
		hook = mock(ReleaseChangeHook.class);
		entityManager = mock(EntityManager.class);
		teaPublishLock = mock(TeaPublishLock.class);
		componentRepository = mock(ComponentRepository.class);
		ReflectionTestUtils.setField(service, "sharedReleaseService", shared);
		ReflectionTestUtils.setField(service, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(service, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(service, "teaProfileResolver", resolver);
		ReflectionTestUtils.setField(service, "sbomProfileResolver", sbomResolver);
		ReflectionTestUtils.setField(service, "teaProfileService", teaProfileService);
		ReflectionTestUtils.setField(service, "organizationService", organizationService);
		ReflectionTestUtils.setField(service, "componentService", componentService);
		ReflectionTestUtils.setField(service, "releaseService", releaseService);
		ReflectionTestUtils.setField(service, "syntheticArtifactService", synthetic);
		ReflectionTestUtils.setField(service, "artifactService", artifactService);
		ReflectionTestUtils.setField(service, "publicationRepository", publications);
		ReflectionTestUtils.setField(service, "collectionRepository", collections);
		ReflectionTestUtils.setField(service, "ossReleaseService", oss);
		ReflectionTestUtils.setField(service, "auditService", audit);
		ReflectionTestUtils.setField(service, "releaseChangeHook", hook);
		ReflectionTestUtils.setField(service, "entityManager", entityManager);
		ReflectionTestUtils.setField(service, "teaPublishLock", teaPublishLock);
		ReflectionTestUtils.setField(service, "componentRepository", componentRepository);
		// the component row readFresh refreshes: built from the in-memory data on every read
		when(componentRepository.findById(any(UUID.class)))
				.thenAnswer(inv -> Optional.ofNullable(components.get(inv.<UUID>getArgument(0))).map(TeaPublicationServiceTestBase::componentRow));
		when(teaPublishLock.acquire(any(UUID.class), any(Duration.class))).thenAnswer(inv -> lease(inv.getArgument(0)));
		ReflectionTestUtils.setField(service, "self", service);

		OrganizationData od = new OrganizationData();
		ReflectionTestUtils.setField(od, "name", "Acme");
		when(getOrganizationService.getOrganizationData(any())).thenReturn(Optional.of(od));
		when(teaProfileService.orgDiscovery(any())).thenReturn(new TeaOrgDiscovery(ORG, ORG_TEA, API_BASE, "{}"));

		when(shared.getRelease(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(releaseRows.get(inv.<UUID>getArgument(0))));
		when(shared.getReleaseData(any(UUID.class))).thenAnswer(inv -> Optional
				.ofNullable(releaseRows.get(inv.<UUID>getArgument(0))).map(ReleaseData::dataFromRecord));
		when(oss.saveRelease(any(Release.class), any(ReleaseData.class), any(), anyBoolean(), any(AcollectionMode.class)))
				.thenAnswer(inv -> {
					Release r = inv.getArgument(0);
					ReleaseData saved = inv.getArgument(1);
					r.setRecordData(Utils.dataToRecord(saved));
					// the update events live in their own column, as OssReleaseService.saveRelease writes them
					r.setUpdateEvents(saved.getUpdateEvents().stream()
							.map(e -> (Map<String, Object>) Utils.OM.convertValue(e, java.util.LinkedHashMap.class)).toList());
					return r;
				});
		when(getComponentService.getComponentData(any(UUID.class)))
				.thenAnswer(inv -> Optional.ofNullable(components.get(inv.<UUID>getArgument(0))));
		when(resolver.resolveForComponent(any(ComponentData.class)))
				.thenAnswer(inv -> resolutions.get(inv.<ComponentData>getArgument(0).getUuid()));
		when(resolver.resolveForComponent(any(ComponentData.class), any()))
				.thenAnswer(inv -> resolutions.get(inv.<ComponentData>getArgument(0).getUuid()));
		when(sbomResolver.resolveForComponent(any(ComponentData.class), any(), any(), any()))
				.thenAnswer(inv -> recipeOf(inv.<ComponentData>getArgument(0).getUuid(), inv.<String>getArgument(1)));

		when(artifactService.getArtifactData(any(UUID.class)))
				.thenAnswer(inv -> Optional.ofNullable(artifacts.get(inv.<UUID>getArgument(0))));
		when(artifactService.getArtifactDataList(any())).thenAnswer(inv -> {
			List<ArtifactData> out = new ArrayList<>();
			for (Object id : (Iterable<?>) inv.getArgument(0)) if (artifacts.containsKey(id)) out.add(artifacts.get(id));
			return out;
		});
		when(artifactService.getArtifactSignature(any(ArtifactData.class))).thenAnswer(inv -> {
			ArtifactData ad = inv.getArgument(0);
			return ad.getArtifacts().stream().map(artifacts::get).filter(a -> null != a && ArtifactType.SIGNATURE == a.getType())
					.findFirst();
		});
		when(artifactService.ensureTeaUuid(any(UUID.class), any())).thenAnswer(inv -> {
			UUID id = inv.getArgument(0);
			UUID tea = teaIds.computeIfAbsent(id, k -> UUID.randomUUID());
			if (artifacts.containsKey(id)) artifacts.get(id).setTeaUuid(tea);
			return tea;
		});
		when(synthetic.upload(any(UUID.class), any(ArtifactDto.class), any(Resource.class), any(RebomOptions.class), any()))
				.thenAnswer(inv -> storeAggregate(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
						inv.getArgument(3)));
		// the real stageBind's effect on the caller's ReleaseData: bind once, one event, no save
		when(synthetic.stageBind(any(ReleaseData.class), any(UUID.class), any())).thenAnswer(inv -> {
			ReleaseData rd = inv.getArgument(0);
			UUID artifact = inv.getArgument(1);
			if (!rd.addSyntheticArtifact(artifact)) return false;
			rd.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.SYNTHETIC_ARTIFACT, ReleaseUpdateAction.ADDED,
					null, null, artifact, ZonedDateTime.now(), inv.getArgument(2)));
			return true;
		});
		// the real rule (task TEA-28, F-5): every pair carried as a Removable.NO tag, a YES tag never matches
		when(synthetic.findByTag(any(ReleaseData.class), anyString(), anyString())).thenAnswer(inv -> boundCarrying(
				inv.getArgument(0), Map.of(inv.<String>getArgument(1), inv.<String>getArgument(2))));
		when(synthetic.findByTags(any(ReleaseData.class), any())).thenAnswer(inv -> boundCarrying(inv.getArgument(0),
				inv.getArgument(1)));
		when(releaseService.generateMergedBomForPublication(any(ReleaseData.class), any(PublicationMergeRequest.class), any()))
				.thenAnswer(inv -> {
					UUID r = inv.<ReleaseData>getArgument(0).getUuid();
					JsonNode tld = inv.<PublicationMergeRequest>getArgument(1).tldOnly() ? tldOnlyDocuments.get(r) : null;
					return (null != tld ? tld : documents.get(r)).deepCopy();
				});

		wireRepositories();

		profile = profile(TeaVisibility.PRIVATE);
		component = component("pump", ComponentType.COMPONENT);
		inventoryBom = artifact(ArtifactType.BOM, "pump.cdx.json", "1", StoredIn.REARM, true, "aa11");
		release = release(component, ReleaseLifecycle.ASSEMBLED, List.of(inventoryBom));
		resolutions.put(component.getUuid(), resolvedFromOrg(profile));
		documents.put(release.getUuid(), bom("pkg:generic/a@1"));
	}

	void wireRepositories() {
		when(publications.findByRelease(any(UUID.class))).thenAnswer(inv -> heads.values().stream()
				.filter(h -> inv.getArgument(0).equals(h.getRelease())).findFirst().map(this::copyOf));
		when(publications.findById(any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(heads.get(inv.<UUID>getArgument(0)))
				.map(this::copyOf));
		when(publications.save(any(TeaPublication.class))).thenAnswer(inv -> {
			TeaPublication p = copyOf(inv.getArgument(0));
			heads.put(p.getUuid(), p);
			return copyOf(p);
		});
		when(publications.listHeadsOfOrg(any(UUID.class), any(TeaPublicationState.class))).thenAnswer(inv -> heads.values().stream()
				.filter(h -> inv.getArgument(0).equals(h.getOrg()) && inv.getArgument(1).equals(h.getState()))
				.map(h -> (TeaPublicationHeadView) new HeadView(h.getUuid(), h.getComponent(), h.getCascadeOf()))
				.collect(Collectors.toList()));
		when(collections.save(any(TeaPublishedCollection.class))).thenAnswer(inv -> {
			TeaPublishedCollection c = inv.getArgument(0);
			String key = c.getUuid() + ":" + c.getVersion();
			if (collectionRows.containsKey(key)) throw new IllegalStateException("collection row " + key + " already exists");
			collectionRows.put(key, c);
			return c;
		});
		when(collections.findVersion(any(UUID.class), anyInt())).thenAnswer(inv -> Optional.ofNullable(
				collectionRows.get(inv.getArgument(0) + ":" + inv.getArgument(1))));
		when(collections.maxVersion(any(UUID.class))).thenAnswer(inv -> collectionRows.values().stream()
				.filter(c -> inv.getArgument(0).equals(c.getUuid())).mapToInt(TeaPublishedCollection::getVersion).max()
				.orElse(0));
		when(collections.listByPublication(any(UUID.class))).thenAnswer(inv -> collectionRows.values().stream()
				.filter(c -> inv.getArgument(0).equals(c.getUuid()))
				.sorted((a, b) -> Integer.compare(a.getVersion(), b.getVersion())).collect(Collectors.toList()));
		// task TEA-28, D16: the versions stored under the uuid for another organization or release
		when(collections.countForeign(any(UUID.class), any(UUID.class), any(UUID.class))).thenAnswer(inv -> collectionRows
				.values().stream().filter(c -> inv.getArgument(0).equals(c.getUuid()) && (!inv.getArgument(1)
						.equals(c.getOrg()) || !inv.getArgument(2).equals(c.getRelease()))).count());
		// task TEA-28, D13: the oldest version of the publication whose entries contain the needle's pairs
		when(collections.findOldestNaming(any(UUID.class), anyString())).thenAnswer(inv -> {
			JsonNode needle = Utils.OM.readTree(inv.<String>getArgument(1));
			return collectionRows.values().stream().filter(c -> inv.getArgument(0).equals(c.getUuid()))
					.filter(c -> containsEveryPair(c, needle))
					.min((a, b) -> Integer.compare(a.getVersion(), b.getVersion()));
		});
	}

	/** Whether the version's entries contain an entry for every object of the needle (teaUuid and revision). */
	static boolean containsEveryPair(TeaPublishedCollection c, JsonNode needle) {
		List<TeaPublishedEntry> entries = TeaPublishedCollectionData.dataFromRecord(c).getEntries();
		for (JsonNode n : needle) {
			boolean found = entries.stream().anyMatch(e -> e.teaUuid().toString().equals(n.get("teaUuid").asText())
					&& (!n.has("revision") || e.revision() == n.get("revision").asInt()));
			if (!found) return false;
		}
		return true;
	}

	/** The first artifact bound to {@code rd}'s synthetic list carrying every pair as a Removable.NO tag. */
	Optional<ArtifactData> boundCarrying(ReleaseData rd, Map<String, String> pairs) {
		return rd.getSyntheticArtifacts().stream().map(artifacts::get).filter(a -> null != a && null != a.getTags()
				&& pairs.entrySet().stream().allMatch(p -> a.getTags().stream().anyMatch(t -> Removable.NO == t.removable()
						&& p.getKey().equals(t.key()) && p.getValue().equals(t.value()))))
				.findFirst();
	}

	record HeadView(UUID uuid, UUID component, UUID cascadeOf) implements TeaPublicationHeadView {
		@Override public UUID getUuid() { return uuid; }
		@Override public UUID getComponent() { return component; }
		@Override public UUID getCascadeOf() { return cascadeOf; }
	}

	TeaPublication copyOf(TeaPublication p) {
		TeaPublication c = new TeaPublication();
		c.setUuid(p.getUuid());
		c.setOrg(p.getOrg());
		c.setRelease(p.getRelease());
		c.setComponent(p.getComponent());
		c.setState(p.getState());
		c.setLatestVersion(p.getLatestVersion());
		c.setProfile(p.getProfile());
		c.setProfileRevision(p.getProfileRevision());
		c.setCascadeOf(p.getCascadeOf());
		c.setRevision(p.getRevision());
		c.setCreatedDate(p.getCreatedDate());
		c.setLastUpdatedDate(p.getLastUpdatedDate());
		c.setRecordData(new HashMap<>(p.getRecordData()));
		return c;
	}

	/** A spy over a lease that holds nothing, recording its acquire and close in {@link #lockOrder}. */
	TeaPublishLock.Lease lease(UUID release) {
		TeaPublishLock.Lease l = spy(new TeaPublishLock.Lease(null, release));
		doAnswer(inv -> {
			lockOrder.add("close:" + release);
			return null;
		}).when(l).close();
		lockOrder.add("acquire:" + release);
		leases.add(l);
		return l;
	}

	/** The mocked upload: what rebom and the registry would leave on the artifact row; binds nothing. */
	UUID storeAggregate(UUID releaseUuid, ArtifactDto dto, Resource file, RebomOptions options) throws Exception {
		byte[] bytes = file.getContentAsByteArray();
		writtenDtos.add(dto);
		writtenBytes.add(bytes);
		writtenOptions.add(options);
		JsonNode doc = Utils.OM.readTree(bytes);
		UUID uuid = null == dto.getUuid() ? UUID.randomUUID() : dto.getUuid();
		ArtifactData ad = artifacts.containsKey(uuid) ? artifacts.get(uuid) : new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", uuid);
		ad.setOrg(ORG);
		ad.setType(ArtifactType.BOM);
		ad.setStoredIn(StoredIn.REARM);
		ad.setDisplayIdentifier(dto.getDisplayIdentifier());
		ad.setVersion(doc.get("version").asText());
		ad.setInternalBom(new InternalBom(UUID.fromString(doc.get("serialNumber").asText().replace("urn:uuid:", "")),
				ArtifactBelongsTo.SYNTHETIC));
		ad.setDigestRecords(new LinkedHashSet<>(Set.of(new DigestRecord(DigestAlgorithm.SHA_256,
				Utils.bytesToHexSha256(bytes), DigestScope.AS_UPLOADED))));
		ad.setTags(new ArrayList<>(dto.getTags()));
		ReflectionTestUtils.setField(ad, "updatedDate", ZonedDateTime.now());
		artifacts.put(uuid, ad);
		return uuid;
	}

	// ---------------------------------------------------------------- fixtures

	static TeaProfileData profile(TeaVisibility visibility) {
		TeaProfileData p = TeaProfileData.builtInDefault(ORG);
		p.setUuid(UUID.randomUUID());
		p.setPublishing(TeaPublishing.ENABLED);
		p.setVisibility(visibility);
		p.setRevision(3);
		return p;
	}

	/** What the mocked SBOM profile resolver answers for a component's {@code default} (task TEA-27). */
	SbomProfileResolution recipeOf(UUID component) {
		return recipeOf(component, SbomProfileData.DEFAULT_NAME);
	}

	/**
	 * What the mocked SBOM profile resolver answers for (component, name) (task TEA-28): a registered
	 * resolution, else a registered recipe RESOLVED from the COMPONENT, else the built-in DEFAULT for
	 * {@code default} and NOT_FOUND for any other name.
	 */
	SbomProfileResolution recipeOf(UUID component, String name) {
		SbomProfileResolution r = recipeResolutions.get(key(component, name));
		if (null != r) return r;
		SbomProfileData d = recipes.get(key(component, name));
		if (null == d) {
			if (!SbomProfileData.DEFAULT_NAME.equals(name)) {
				return new SbomProfileResolution(ScopedResolutionStatus.NOT_FOUND, null, null, null, List.of());
			}
			d = SbomProfileData.builtInDefault(ORG);
			return new SbomProfileResolution(ScopedResolutionStatus.RESOLVED, ScopedSettingsSource.DEFAULT, null, d,
					List.of());
		}
		return new SbomProfileResolution(ScopedResolutionStatus.RESOLVED, ScopedSettingsSource.COMPONENT, component, d,
				List.of());
	}

	/** The built-in recipe of component {@code component}'s {@code default}, to change in a case. */
	SbomProfileData recipe(UUID component) {
		return recipes.computeIfAbsent(key(component), k -> SbomProfileData.builtInDefault(ORG));
	}

	/**
	 * A recipe registered for (component, name) (task TEA-28): a saved row of that name with its own uuid
	 * and revision 1, otherwise the built-in shape, to change in a case.
	 */
	SbomProfileData recipe(UUID component, String name) {
		return recipes.computeIfAbsent(key(component, name), k -> {
			SbomProfileData d = SbomProfileData.builtInDefault(ORG);
			d.setName(name);
			d.setUuid(UUID.randomUUID());
			d.setRevision(1);
			return d;
		});
	}

	SbomProfileData recipe(UUID component, String name, SbomInternalMetadata internalMetadata) {
		SbomProfileData d = recipe(component, name);
		d.setInternalMetadata(internalMetadata);
		return d;
	}

	/** {@code p} naming {@code names}, in that order (task TEA-28). */
	static TeaProfileData profiles(TeaProfileData p, String... names) {
		p.setProfiles(List.of(names));
		return p;
	}

	static TeaResolvedProfile resolvedFromOrg(TeaProfileData p) {
		return new TeaResolvedProfile(TeaResolutionStatus.RESOLVED, TeaProfileSource.ORGANIZATION, null, p, List.of());
	}

	/** The component row the repository answers for {@code cd}, as the database would hold it. */
	static Component componentRow(ComponentData cd) {
		Component c = new Component();
		c.setUuid(cd.getUuid());
		c.setRecordData(Utils.dataToRecord(cd));
		return c;
	}

	ComponentData component(String name, ComponentType type) {
		ComponentData cd = new ComponentData();
		cd.setUuid(UUID.randomUUID());
		cd.setOrg(ORG);
		cd.setName(name);
		cd.setType(type);
		components.put(cd.getUuid(), cd);
		return cd;
	}

	ReleaseData release(ComponentData cd, ReleaseLifecycle lc, List<UUID> inventory) {
		return releaseIn(ORG, cd, lc, inventory);
	}

	ReleaseData releaseIn(UUID org, ComponentData cd, ReleaseLifecycle lc, List<UUID> inventory) {
		ReleaseData rd = new ReleaseData();
		UUID uuid = UUID.randomUUID();
		ReflectionTestUtils.setField(rd, "uuid", uuid);
		ReflectionTestUtils.setField(rd, "org", org);
		ReflectionTestUtils.setField(rd, "component", cd.getUuid());
		rd.setVersion("1.2.3");
		rd.setLifecycle(lc);
		rd.setStatus(ReleaseStatus.ACTIVE);
		rd.setArtifacts(new LinkedList<>(inventory));
		Release r = new Release();
		r.setUuid(uuid);
		r.setRecordData(Utils.dataToRecord(rd));
		releaseRows.put(uuid, r);
		return ReleaseData.dataFromRecord(r);
	}

	void setLifecycle(ReleaseData rd, ReleaseLifecycle lc) {
		Release r = releaseRows.get(rd.getUuid());
		ReleaseData d = ReleaseData.dataFromRecord(r);
		d.setLifecycle(lc);
		r.setRecordData(Utils.dataToRecord(d));
	}

	void setStatus(ReleaseData rd, ReleaseStatus status) {
		Release r = releaseRows.get(rd.getUuid());
		ReleaseData d = ReleaseData.dataFromRecord(r);
		d.setStatus(status);
		r.setRecordData(Utils.dataToRecord(d));
	}

	/** Names {@code children} as the parent releases of {@code product}, in that order. */
	void setParents(ReleaseData product, List<UUID> children) {
		Release r = releaseRows.get(product.getUuid());
		ReleaseData d = ReleaseData.dataFromRecord(r);
		d.setParentReleases(new LinkedList<>(children.stream().map(ParentRelease::minimalParentReleaseFactory).toList()));
		r.setRecordData(Utils.dataToRecord(d));
	}

	/** An ASSEMBLED product release of a new PRODUCT component resolving to {@code p}, over {@code children}. */
	ReleaseData productOver(String name, TeaResolvedProfile p, List<UUID> children) {
		ComponentData cd = component(name, ComponentType.PRODUCT);
		resolutions.put(cd.getUuid(), p);
		ReleaseData rd = release(cd, ReleaseLifecycle.ASSEMBLED, List.of());
		setParents(rd, children);
		documents.put(rd.getUuid(), bom("pkg:generic/" + name + "@1"));
		return rd;
	}

	TeaPublishResult publishRelease(UUID release, TeaPublishMode mode) throws RelizaException {
		return service.publish(new TeaPublishRequest(release, mode, null, WU));
	}

	void setInventory(ReleaseData rd, List<UUID> inventory) {
		Release r = releaseRows.get(rd.getUuid());
		ReleaseData d = ReleaseData.dataFromRecord(r);
		d.setArtifacts(new LinkedList<>(inventory));
		r.setRecordData(Utils.dataToRecord(d));
	}

	UUID artifact(ArtifactType type, String name, String version, StoredIn storedIn, boolean internalBom,
			String asUploaded) {
		ArtifactData ad = new ArtifactData();
		UUID uuid = UUID.randomUUID();
		ReflectionTestUtils.setField(ad, "uuid", uuid);
		ad.setOrg(ORG);
		ad.setType(type);
		ad.setDisplayIdentifier(name);
		ad.setVersion(version);
		ad.setStoredIn(storedIn);
		if (internalBom) ad.setInternalBom(new InternalBom(UUID.randomUUID(), ArtifactBelongsTo.RELEASE));
		if (null != asUploaded) {
			ad.getDigestRecords().add(new DigestRecord(DigestAlgorithm.SHA_256, asUploaded, DigestScope.AS_UPLOADED));
		}
		ReflectionTestUtils.setField(ad, "updatedDate", ZonedDateTime.now());
		artifacts.put(uuid, ad);
		return uuid;
	}

	static JsonNode bom(String... purls) {
		ObjectNode doc = Utils.OM.createObjectNode();
		doc.put("bomFormat", "CycloneDX");
		doc.put("specVersion", "1.6");
		doc.put("serialNumber", "urn:uuid:" + UUID.randomUUID());
		doc.put("version", 1);
		doc.putObject("metadata").put("timestamp", "2026-01-01T00:00:00Z");
		var comps = doc.putArray("components");
		for (String p : purls) comps.addObject().put("purl", p).put("name", p);
		return doc;
	}

	TeaPublishResult publish(TeaPublishMode mode) throws RelizaException {
		return service.publish(new TeaPublishRequest(release.getUuid(), mode, null, WU));
	}

	TeaPublicationData head() {
		return service.getPublicationOfRelease(release.getUuid()).orElseThrow();
	}

	List<ReleaseUpdateEvent> teaEvents(UUID releaseUuid) {
		return ReleaseData.dataFromRecord(releaseRows.get(releaseUuid)).getUpdateEvents().stream()
				.filter(e -> ReleaseUpdateScope.TEA_PUBLICATION == e.rus()).toList();
	}

	void assertNothingWritten() throws Exception {
		verify(synthetic, never()).upload(any(), any(), any(), any(), any());
		verify(publications, never()).save(any());
		verify(collections, never()).save(any());
		verify(oss, never()).saveRelease(any(Release.class), any(ReleaseData.class), any(), anyBoolean(), any(AcollectionMode.class));
		verify(hook, never()).onReleaseTeaPublished(any(), any(), anyInt(), any(), anyInt());
	}

	TeaPublishRefusedException refused(TeaPublishMode mode) {
		return assertThrows(TeaPublishRefusedException.class, () -> publish(mode));
	}

	/** A head over a fresh ASSEMBLED release of {@code cd}, as if published earlier. */
	UUID seedHead(ComponentData cd, UUID cascadeOf, TeaPublicationState state) {
		return seedHead(cd, release(cd, ReleaseLifecycle.ASSEMBLED, List.of()).getUuid(), cascadeOf, state);
	}

	UUID seedHead(ComponentData cd, UUID releaseUuid, UUID cascadeOf, TeaPublicationState state) {
		TeaPublication p = new TeaPublication();
		p.setUuid(UUID.randomUUID());
		p.setOrg(ORG);
		p.setRelease(releaseUuid);
		p.setComponent(cd.getUuid());
		p.setState(state);
		p.setLatestVersion(1);
		p.setProfile(profile.getUuid());
		p.setCascadeOf(cascadeOf);
		TeaPublicationData d = new TeaPublicationData();
		d.setUuid(p.getUuid());
		d.setOrg(ORG);
		d.setRelease(p.getRelease());
		d.setComponent(cd.getUuid());
		d.setComponentType(cd.getType());
		d.setState(state);
		d.setLatestVersion(1);
		d.setProfile(profile.getUuid());
		d.setCascadeOf(cascadeOf);
		p.setRecordData(d.toRecordData());
		heads.put(p.getUuid(), p);
		return p.getUuid();
	}

	TeaPublicationData data(UUID publication) {
		return TeaPublicationData.dataFromRecord(heads.get(publication));
	}

	// ---------------------------------------------------------------- log capture (opt-in, task TEA-19)

	/** In-memory appender on the service's own logger (the TeaPublicationCommitTest idiom). */
	static class CapturingAppender extends AbstractAppender {
		final List<LogEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
		CapturingAppender() { super("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}

	private CapturingAppender capturedLogs;
	private Level levelBeforeCapture;

	/**
	 * Captures the service's WARN lines until the case ends. A unit test has no log4j2 test configuration, so the
	 * level is set to WARN explicitly; the previous level is restored after the case.
	 */
	void captureServiceWarnings() {
		capturedLogs = new CapturingAppender();
		capturedLogs.start();
		org.apache.logging.log4j.core.Logger logger =
				(org.apache.logging.log4j.core.Logger) LogManager.getLogger(TeaPublicationService.class);
		levelBeforeCapture = logger.getLevel();
		Configurator.setLevel(TeaPublicationService.class.getName(), Level.WARN);
		logger.addAppender(capturedLogs);
	}

	/** The formatted messages of the WARN lines captured since {@link #captureServiceWarnings()}. */
	List<String> capturedWarnings() {
		synchronized (capturedLogs.events) {
			return capturedLogs.events.stream().filter(e -> Level.WARN == e.getLevel())
					.map(e -> e.getMessage().getFormattedMessage()).toList();
		}
	}

	@AfterEach
	void releaseLogCapture() {
		if (null == capturedLogs) return;
		((org.apache.logging.log4j.core.Logger) LogManager.getLogger(TeaPublicationService.class))
				.removeAppender(capturedLogs);
		Configurator.setLevel(TeaPublicationService.class.getName(), levelBeforeCapture);
		capturedLogs.stop();
		capturedLogs = null;
	}
}
