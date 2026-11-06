/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ComponentData;
import io.reliza.model.DigestAlgorithm;
import io.reliza.model.OrganizationData;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseSbomVersion;
import io.reliza.model.ReleaseSbomVersionData;
import io.reliza.model.ReleaseSbomWriter;
import io.reliza.model.ReleaseSbomWriter.Kind;
import io.reliza.model.SbomProfileData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.service.ReleaseSbomService.EnsureOutcome;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * FDAW-7 row 2: {@link ReleaseSbomService#ensureVersion} with every collaborator mocked and the two
 * tables in memory ({@link ReleaseSbomStoreFixture}); the mocked upload stores what rebom and the
 * registry would leave on the artifact row, and a case may tamper with it.
 */
class ReleaseSbomServiceTest {

	static final UUID ORG = UUID.randomUUID();
	static final WhoUpdated WU = WhoUpdated.getAutoWhoUpdated();
	static final String PROFILE = "default";

	ReleaseSbomStoreFixture store;
	ReleaseSbomService service;
	SyntheticArtifactService synthetic;
	ArtifactService artifactService;
	ReleaseData rd;
	TeaPublishLock.Lease lease;
	final Map<UUID, ArtifactData> artifacts = new HashMap<>();
	final List<ArtifactDto> dtos = new ArrayList<>();
	final List<JsonNode> uploadedDocuments = new ArrayList<>();
	/** Applied to the stored row after each upload: the read-back cases' tampering. */
	Consumer<ArtifactData> tamper = a -> {};
	final List<LogEvent> logs = Collections.synchronizedList(new ArrayList<>());
	AbstractAppender appender;
	Level levelBefore;

	@BeforeEach
	void setUp() throws Exception {
		store = new ReleaseSbomStoreFixture();
		synthetic = mock(SyntheticArtifactService.class);
		artifactService = mock(ArtifactService.class);
		GetOrganizationService orgs = mock(GetOrganizationService.class);
		GetComponentService components = mock(GetComponentService.class);
		service = store.service(synthetic, artifactService, orgs, components, mock(RebomService.class));

		ComponentData cd = new ComponentData();
		cd.setUuid(UUID.randomUUID());
		cd.setOrg(ORG);
		cd.setName("pump");
		when(components.getComponentData(cd.getUuid())).thenReturn(Optional.of(cd));
		OrganizationData od = new OrganizationData();
		ReflectionTestUtils.setField(od, "name", "Acme");
		when(orgs.getOrganizationData(any())).thenReturn(Optional.of(od));
		rd = spy(new ReleaseData());
		ReflectionTestUtils.setField(rd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "org", ORG);
		ReflectionTestUtils.setField(rd, "component", cd.getUuid());
		rd.setVersion("1.2.3");
		lease = new TeaPublishLock.Lease(null, rd.getUuid());

		when(synthetic.findByTags(any(ReleaseData.class), any())).thenReturn(Optional.empty());
		when(synthetic.upload(any(UUID.class), any(ArtifactDto.class), any(Resource.class), any(RebomOptions.class),
				any())).thenAnswer(inv -> store(inv.getArgument(1), inv.getArgument(2)));
		when(artifactService.getArtifactData(any(UUID.class)))
				.thenAnswer(inv -> Optional.ofNullable(artifacts.get(inv.<UUID>getArgument(0))));
		when(artifactService.mergeSystemTags(any(UUID.class), any(), any())).thenAnswer(inv -> {
			ArtifactData ad = artifacts.get(inv.<UUID>getArgument(0));
			List<TagRecord> merged = inv.getArgument(1);
			Set<String> keys = merged.stream().map(TagRecord::key).collect(Collectors.toSet());
			List<TagRecord> tags = new ArrayList<>(ad.getTags());
			tags.removeIf(t -> keys.contains(t.key()));
			tags.addAll(merged);
			ad.setTags(tags);
			return null;
		});

		appender = new AbstractAppender("fdaw7-" + UUID.randomUUID(), null, null, true, null) {
			@Override
			public void append(LogEvent event) {
				logs.add(event.toImmutable());
			}
		};
		appender.start();
		var logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(ReleaseSbomService.class);
		levelBefore = logger.getLevel();
		// without a Spring context in the JVM yet, the root level would drop the WARN and INFO lines
		Configurator.setLevel(ReleaseSbomService.class.getName(), Level.INFO);
		logger.addAppender(appender);
	}

	@AfterEach
	void tearDown() {
		((org.apache.logging.log4j.core.Logger) LogManager.getLogger(ReleaseSbomService.class)).removeAppender(appender);
		appender.stop();
		Configurator.setLevel(ReleaseSbomService.class.getName(), levelBefore);
	}

	UUID store(ArtifactDto dto, Resource file) throws Exception {
		byte[] bytes = file.getContentAsByteArray();
		JsonNode doc = Utils.OM.readTree(bytes);
		dtos.add(dto);
		uploadedDocuments.add(doc);
		UUID uuid = null == dto.getUuid() ? UUID.randomUUID() : dto.getUuid();
		ArtifactData ad = artifacts.containsKey(uuid) ? artifacts.get(uuid) : new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", uuid);
		ad.setOrg(ORG);
		ad.setType(ArtifactType.BOM);
		ad.setDisplayIdentifier(dto.getDisplayIdentifier());
		ad.setVersion(doc.get("version").asString());
		ad.setInternalBom(new InternalBom(UUID.fromString(doc.get("serialNumber").asString().replace("urn:uuid:", "")),
				ArtifactBelongsTo.SYNTHETIC));
		ad.setDigestRecords(new LinkedHashSet<>(Set.of(new DigestRecord(DigestAlgorithm.SHA_256,
				Utils.bytesToHexSha256(bytes), DigestScope.AS_UPLOADED))));
		ad.setTags(new ArrayList<>(dto.getTags()));
		tamper.accept(ad);
		artifacts.put(uuid, ad);
		return uuid;
	}

	static JsonNode doc(String purl) {
		ObjectNode d = Utils.OM.createObjectNode();
		d.put("bomFormat", "CycloneDX");
		d.put("specVersion", "1.6");
		d.putObject("metadata").putObject("component").put("name", "pump");
		d.putArray("components").addObject().put("purl", purl);
		return d;
	}

	static SbomProfileData recipe(UUID uuid, int revision) {
		SbomProfileData p = SbomProfileData.builtInDefault(ORG);
		p.setUuid(uuid);
		p.setRevision(revision);
		return p;
	}

	EnsureOutcome ensure(JsonNode document, ReleaseSbomWriter writer) throws RelizaException {
		return service.ensureVersion(rd, PROFILE, document, lease, writer, WU);
	}

	static ReleaseSbomWriter tea(UUID publication) {
		return ReleaseSbomWriter.tea(publication, null);
	}

	static ReleaseSbomWriter pkg() {
		return ReleaseSbomWriter.pkg(null);
	}

	static Map<String, String> tagMap(List<TagRecord> tags) {
		Map<String, String> m = new HashMap<>();
		for (TagRecord t : tags) {
			assertEquals(Removable.NO, t.removable(), t.key());
			assertNull(m.put(t.key(), t.value()), "one tag per key: " + t.key());
		}
		return m;
	}

	long logged(Level level, String text) {
		return logs.stream().filter(e -> level == e.getLevel() && e.getMessage().getFormattedMessage().contains(text))
				.count();
	}

	// ---------------------------------------------------------------- (a)

	@Test
	void aLeaseOfAnotherReleaseIsRefusedBeforeAnyRead() {
		TeaPublishLock.Lease other = new TeaPublishLock.Lease(null, UUID.randomUUID());
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> service.ensureVersion(rd, PROFILE, doc("pkg:generic/a@1"), other, pkg(), WU));
		assertTrue(e.getMessage().contains("was called with the lease of release " + other.release()), e.getMessage());
		verifyNoInteractions(store.heads, store.versions, synthetic, artifactService);
		assertThrows(IllegalArgumentException.class,
				() -> service.ensureVersion(rd, PROFILE, doc("pkg:generic/a@1"), null, pkg(), WU));
	}

	// ---------------------------------------------------------------- (b), (b2)

	@Test
	void aFirstTeaWriteMintsAFreshSerialAtVersionOneWithTheFourTeaTags() throws Exception {
		UUID publication = UUID.randomUUID();
		JsonNode d = doc("pkg:generic/a@1");
		EnsureOutcome o = ensure(d, tea(publication));

		assertTrue(o.created());
		assertTrue(o.needsBind());
		assertEquals(4, o.pin().serial().version(), "a v4 serial");
		assertEquals(1, o.pin().version());
		assertEquals(1, dtos.size());
		assertNull(dtos.get(0).getUuid(), "a new artifact row");
		assertEquals("pump-1.2.3-default-tea-aggregate.cdx.json", dtos.get(0).getDisplayIdentifier());
		assertEquals(Map.of(CommonVariables.TEA_AGGREGATE_TAG_KEY, "true", CommonVariables.TEA_PROFILE_NAME_TAG_KEY,
				PROFILE, CommonVariables.TEA_PUBLICATION_TAG_KEY, publication.toString(),
				CommonVariables.TEA_CONTENT_DIGEST_TAG_KEY, TeaCollectionAssembler.contentDigest(d)),
				tagMap(dtos.get(0).getTags()));
		assertEquals("urn:uuid:" + o.pin().serial(), uploadedDocuments.get(0).get("serialNumber").asString());
		assertEquals(1, uploadedDocuments.get(0).get("version").asInt());
		ArtifactData row = artifacts.get(o.pin().artifact());
		assertEquals(TeaPublicationService.digestOf(row, DigestScope.AS_UPLOADED).orElseThrow(), o.pin().sha256());
		assertEquals(o.pin(), service.latestPin(rd.getUuid(), PROFILE).orElseThrow());
		assertEquals(o.pin().serial(), store.headRows.get(o.pin().serial()).getUuid());
		assertEquals(1, store.headRows.get(o.pin().serial()).getLatestVersion());
	}

	@Test
	void aFirstPackageWriteCarriesOnlyTheContentDigestTag() throws Exception {
		JsonNode d = doc("pkg:generic/a@1");
		EnsureOutcome o = ensure(d, pkg());
		assertTrue(o.created());
		assertEquals(Map.of(CommonVariables.TEA_CONTENT_DIGEST_TAG_KEY, TeaCollectionAssembler.contentDigest(d)),
				tagMap(dtos.get(0).getTags()));
	}

	// ---------------------------------------------------------------- (c), (c2)

	@Test
	void equalContentReusesTheLatestVersionAndNeedsBindReadsOnlyTheSyntheticList() throws Exception {
		EnsureOutcome first = ensure(doc("pkg:generic/a@1"), pkg());
		int versionSaves = store.versionRows.size();

		EnsureOutcome again = ensure(doc("pkg:generic/a@1"), pkg());
		assertFalse(again.created());
		assertTrue(again.needsBind(), "not on the synthetic list yet");
		assertEquals(first.pin(), again.pin());
		assertEquals(1, dtos.size(), "no upload");
		assertEquals(versionSaves, store.versionRows.size(), "no version row");

		rd.addSyntheticArtifact(first.pin().artifact());
		EnsureOutcome bound = ensure(doc("pkg:generic/a@1"), pkg());
		assertFalse(bound.needsBind(), "on the synthetic list");
		verify(rd, never()).getArtifacts();
		verify(artifactService, never()).mergeSystemTags(any(), any(), any());
	}

	@Test
	void aTeaReuseStampsTheThreeIdentityTagsOnlyWhenOneIsAbsentOrDiffers() throws Exception {
		EnsureOutcome first = ensure(doc("pkg:generic/a@1"), pkg());
		UUID p1 = UUID.randomUUID();

		ensure(doc("pkg:generic/a@1"), tea(p1));
		ArgumentCaptor<List<TagRecord>> merged = ArgumentCaptor.forClass(List.class);
		verify(artifactService, times(1)).mergeSystemTags(any(), merged.capture(), any());
		assertEquals(Map.of(CommonVariables.TEA_AGGREGATE_TAG_KEY, "true", CommonVariables.TEA_PROFILE_NAME_TAG_KEY,
				PROFILE, CommonVariables.TEA_PUBLICATION_TAG_KEY, p1.toString()), tagMap(merged.getValue()));

		ensure(doc("pkg:generic/a@1"), tea(p1));
		verify(artifactService, times(1)).mergeSystemTags(any(), any(), any());

		ensure(doc("pkg:generic/a@1"), pkg());
		verify(artifactService, times(1)).mergeSystemTags(any(), any(), any());

		UUID p2 = UUID.randomUUID();
		ensure(doc("pkg:generic/a@1"), tea(p2));
		verify(artifactService, times(2)).mergeSystemTags(any(), merged.capture(), any());
		assertEquals(p2.toString(), tagMap(merged.getValue()).get(CommonVariables.TEA_PUBLICATION_TAG_KEY));
		assertEquals(1, dtos.size(), "every call reused version 1");
		assertEquals(first.pin(), service.latestPin(rd.getUuid(), PROFILE).orElseThrow());
	}

	// ---------------------------------------------------------------- (d), (d2), (g)

	@Test
	void changedContentBumpsTheSameRowUnderTheSerialWithAFreshTimestamp() throws Exception {
		EnsureOutcome first = ensure(doc("pkg:generic/a@1"), pkg());
		Instant before = Instant.now();
		EnsureOutcome bumped = ensure(doc("pkg:generic/b@1"), pkg());

		assertTrue(bumped.created());
		assertEquals(first.pin().serial(), bumped.pin().serial());
		assertEquals(2, bumped.pin().version());
		assertEquals(first.pin().artifact(), dtos.get(1).getUuid(), "an in-place revision of the same row");
		assertEquals(first.pin().artifact(), bumped.pin().artifact());
		JsonNode sent = uploadedDocuments.get(1);
		assertEquals("urn:uuid:" + first.pin().serial(), sent.get("serialNumber").asString());
		assertEquals(2, sent.get("version").asInt());
		assertFalse(Instant.parse(sent.get("metadata").get("timestamp").asString()).isBefore(before.minusSeconds(1)));
		assertEquals(2, store.headRows.get(first.pin().serial()).getLatestVersion());
		assertEquals(2, store.versionRows.size());
		assertEquals(first.pin(), service.pinOf(first.pin().serial(), 1).orElseThrow(), "version 1 is kept");
	}

	@Test
	void aPackageBumpCarriesTeasIdentityTagsVerbatimAndATeaBumpWritesItsOwnPublication() throws Exception {
		UUID p1 = UUID.randomUUID();
		ensure(doc("pkg:generic/a@1"), tea(p1));
		Map<String, String> teaTags = tagMap(dtos.get(0).getTags());

		JsonNode changed = doc("pkg:generic/b@1");
		ensure(changed, pkg());
		Map<String, String> expected = new HashMap<>(teaTags);
		expected.put(CommonVariables.TEA_CONTENT_DIGEST_TAG_KEY, TeaCollectionAssembler.contentDigest(changed));
		assertEquals(expected, tagMap(dtos.get(1).getTags()));

		UUID p2 = UUID.randomUUID();
		ensure(doc("pkg:generic/c@1"), tea(p2));
		assertEquals(p2.toString(), tagMap(dtos.get(2).getTags()).get(CommonVariables.TEA_PUBLICATION_TAG_KEY));
	}

	@Test
	void contentThatReturnsAfterAChangeGetsTheNextNumberNotTheEarlierOne() throws Exception {
		ensure(doc("pkg:generic/a@1"), pkg());
		ensure(doc("pkg:generic/b@1"), pkg());
		EnsureOutcome back = ensure(doc("pkg:generic/a@1"), pkg());
		assertTrue(back.created());
		assertEquals(3, back.pin().version());
	}

	// ---------------------------------------------------------------- (e)

	void assertReadBackRefused(Consumer<ArtifactData> t) throws Exception {
		tamper = t;
		RelizaException e = assertThrows(RelizaException.class, () -> ensure(doc("pkg:generic/a@1"), pkg()));
		assertTrue(e.getMessage().contains("rebom stored it under another serial or version (sent "), e.getMessage());
		verify(store.versions, never()).save(any(ReleaseSbomVersion.class));
		assertTrue(store.headRows.isEmpty());
		// the first upload was nobody's pin: the service archives it (departure: the caller does not)
		verify(artifactService).archiveArtifact(any(), any());
	}

	@Test
	void aReadBackUnderAnotherSerialIsRefused() throws Exception {
		assertReadBackRefused(a -> a.setInternalBom(new InternalBom(UUID.randomUUID(), ArtifactBelongsTo.SYNTHETIC)));
	}

	@Test
	void aReadBackAtAnotherVersionIsRefused() throws Exception {
		assertReadBackRefused(a -> a.setVersion("7"));
	}

	@Test
	void aReadBackWithoutTheUploadDigestIsRefused() throws Exception {
		assertReadBackRefused(a -> a.setDigestRecords(new LinkedHashSet<>()));
	}

	@Test
	void aBumpThatFailsTheReadBackArchivesNothing() throws Exception {
		ensure(doc("pkg:generic/a@1"), pkg());
		tamper = a -> a.setVersion("9");
		assertThrows(RelizaException.class, () -> ensure(doc("pkg:generic/b@1"), pkg()));
		verify(artifactService, never()).archiveArtifact(any(), any());
		assertEquals(1, store.versionRows.size());
	}

	// ---------------------------------------------------------------- (f)

	@Test
	void aKeyConflictOnTheVersionRowIsADefectLoggedAtErrorAndNeverRetried() throws Exception {
		doThrow(new DataIntegrityViolationException("duplicate key")).when(store.versions)
				.save(any(ReleaseSbomVersion.class));
		RelizaException e = assertThrows(RelizaException.class, () -> ensure(doc("pkg:generic/a@1"), pkg()));
		String text = "version 1 already exists: a second writer ran without the lease of release " + rd.getUuid();
		assertTrue(e.getMessage().contains(text), e.getMessage());
		assertEquals(1, logged(Level.ERROR, text));
		assertEquals(1, dtos.size(), "no retry under a fresh serial (security F-5)");
		// the first upload was recorded nowhere: nobody's pin, archived like a failed read-back
		verify(artifactService).archiveArtifact(any(), any());
	}

	// ---------------------------------------------------------------- (h)

	@Test
	void theVersionRowRecordsTheWriterAndRecipeAndARecipeChangeWarnsOnce() throws Exception {
		UUID r1 = UUID.randomUUID();
		ensure(doc("pkg:generic/a@1"), ReleaseSbomWriter.pkg(recipe(r1, 2)));
		ReleaseSbomVersionData v1 = store.versionRows.values().iterator().next().toData();
		assertEquals(Kind.PACKAGE, v1.getWriter());
		assertEquals(r1, v1.getRecipe());
		assertEquals(2, v1.getRecipeRevision());

		// the same uuid at another revision: no WARN
		ensure(doc("pkg:generic/b@1"), ReleaseSbomWriter.tea(UUID.randomUUID(), recipe(r1, 3)));
		assertEquals(0, logged(Level.WARN, "is shaped by recipe"));
		// another recipe uuid on a bump: one WARN
		UUID r2 = UUID.randomUUID();
		ensure(doc("pkg:generic/c@1"), ReleaseSbomWriter.pkg(recipe(r2, 1)));
		assertEquals(1, logged(Level.WARN, "is shaped by recipe " + r2 + " r1, the latest, version 2 (TEA), was "
				+ r1 + " r3"));
		// a reuse never logs, whatever its recipe
		ensure(doc("pkg:generic/c@1"), ReleaseSbomWriter.pkg(recipe(UUID.randomUUID(), 1)));
		assertEquals(1, logged(Level.WARN, "is shaped by recipe"));
		// the built-in recipe records no uuid and no revision
		ensure(doc("pkg:generic/d@1"), ReleaseSbomWriter.pkg(SbomProfileData.builtInDefault(ORG)));
		ReleaseSbomVersionData builtIn = store.versions.latest(v1.getUuid()).orElseThrow().toData();
		assertEquals(4, builtIn.getVersion());
		assertNull(builtIn.getRecipe());
		assertNull(builtIn.getRecipeRevision());
		assertEquals(1, logged(Level.WARN, "is shaped by recipe built-in"));
	}

	// ---------------------------------------------------------------- (i)

	@Test
	void theWriterRefusesTeaWithoutAPublicationAndPackageWithOne() {
		IllegalArgumentException tea = assertThrows(IllegalArgumentException.class,
				() -> new ReleaseSbomWriter(Kind.TEA, null, null, null));
		assertEquals("a TEA writer needs the publication uuid", tea.getMessage());
		IllegalArgumentException pkg = assertThrows(IllegalArgumentException.class,
				() -> new ReleaseSbomWriter(Kind.PACKAGE, UUID.randomUUID(), null, null));
		assertEquals("a PACKAGE writer carries no publication uuid", pkg.getMessage());
		assertNull(new ReleaseSbomWriter(Kind.PACKAGE, null, null, 5).recipeRevision(), "no recipe, no revision");
		assertNotEquals(ReleaseSbomWriter.pkg(null), ReleaseSbomWriter.tea(UUID.randomUUID(), null));
	}
}
