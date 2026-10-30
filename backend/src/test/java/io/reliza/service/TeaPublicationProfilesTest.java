/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.exceptions.MergedSbomUnavailableException;
import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.TeaPublishRefusedException;
import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.SbomProfileData;
import io.reliza.model.SbomProfileData.SbomDependencyDepth;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaPublicationData.TeaAggregateArtifact;
import io.reliza.model.TeaPublishedCollection;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.model.TeaPublishedCollectionData.TeaArtifactOrigin;
import io.reliza.model.TeaPublishedCollectionData.TeaPublishedEntry;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.tea.TeaArtifactType;
import io.reliza.model.tea.TeaCollectionUpdateReasonType;
import io.reliza.service.ReleaseService.PublicationMergeRequest;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishResult;

/**
 * Task TEA-28, design 4.2, 4.3 and 4.6 (failure paths): a publish produces one named aggregate per SBOM
 * profile name of the resolved TEA settings, in list order, each its own row, serial and TEA id; a
 * re-publish compares content per name and writes ARTIFACT_UPDATED, ARTIFACT_REMOVED or ARTIFACT_ADDED
 * by the spec's precedence; a removed name added back carries the entry an earlier version froze (F-2).
 * The {@code top} recipe is top-level only, so its document comes from {@code tldOnlyDocuments}.
 */
class TeaPublicationProfilesTest extends TeaPublicationServiceTestBase {

	/** Names the TEA settings publish, registering a recipe for every name but {@code default}. */
	private void names(String... names) {
		profiles(profile, names);
		for (String n : names) {
			if (SbomProfileData.DEFAULT_NAME.equals(n)) continue;
			SbomProfileData r = recipe(component.getUuid(), n);
			if ("top".equals(n)) r.setDependencyDepth(SbomDependencyDepth.TOP_LEVEL_ONLY);
		}
	}

	private TeaPublishedCollectionData latest() {
		return service.getLatestCollection(head().getUuid()).orElseThrow();
	}

	private List<TeaPublishedCollectionData> versions() {
		return service.listCollections(head().getUuid());
	}

	private static List<TeaPublishedEntry> synthetic(TeaPublishedCollectionData c) {
		return c.getEntries().stream().filter(e -> TeaArtifactOrigin.SYNTHETIC == e.origin()).toList();
	}

	private static TeaPublishedEntry entryNamed(TeaPublishedCollectionData c, String name) {
		return synthetic(c).stream().filter(e -> name.equals(e.sbomProfileName())).findFirst().orElseThrow();
	}

	private UUID row(String name) {
		return TeaAggregates.aggregateOf(head(), name);
	}

	private List<UUID> boundSynthetic() {
		return ReleaseData.dataFromRecord(releaseRows.get(release.getUuid())).getSyntheticArtifacts();
	}

	private static String tag(ArtifactDto dto, String key) {
		return dto.getTags().stream().filter(t -> key.equals(t.key())).map(TagRecord::value).findFirst().orElse(null);
	}

	private String serialOf(int upload) {
		return Utils.OM.readTree(writtenBytes.get(upload)).get("serialNumber").asString();
	}

	// ---------------------------------------------------------------- 4.2, the shape

	@ParameterizedTest
	@ValueSource(ints = { 1, 2, 3 })
	void aPublishWritesOneNamedAggregatePerProfileInListOrder(int count) throws Exception {
		List<String> list = List.of("default", "top", "third").subList(0, count);
		names(list.toArray(String[]::new));
		profile.setRawArtifacts(TeaRawArtifacts.ALL);

		TeaPublishResult r = publish(TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		assertEquals(1, versions().size());
		TeaPublishedCollectionData v1 = latest();
		assertEquals(TeaCollectionUpdateReasonType.INITIAL_RELEASE, v1.getUpdateReasonType());
		List<TeaPublishedEntry> aggregates = synthetic(v1);
		assertEquals(list, aggregates.stream().map(TeaPublishedEntry::name).toList(), "named after the profiles, in order");
		assertEquals(count, v1.getEntries().indexOf(v1.getEntries().stream()
				.filter(e -> TeaArtifactOrigin.REAL == e.origin()).findFirst().orElseThrow()), "the ride-alongs after them");
		assertEquals(count, aggregates.stream().map(TeaPublishedEntry::teaUuid).distinct().count());
		for (TeaPublishedEntry e : aggregates) {
			assertEquals(TeaArtifactType.BOM, e.teaType());
			assertEquals(1, e.revision());
			assertEquals(e.name(), e.sbomProfileName());
			assertEquals(e.name(), e.frozen().name());
			SbomProfileData recipe = recipeOf(component.getUuid(), e.name()).profile();
			assertEquals(recipe.getUuid(), e.sbomProfileUuid());
			assertEquals(Integer.valueOf(recipe.getRevision()), e.sbomProfileRevision());
		}
		assertNull(entryNamed(v1, "default").sbomProfileUuid(), "the built-in default has no row");
		assertEquals(Integer.valueOf(0), entryNamed(v1, "default").sbomProfileRevision());

		assertEquals(count, writtenDtos.size());
		for (int i = 0; i < count; i++) {
			ArtifactDto dto = writtenDtos.get(i);
			String name = list.get(i);
			assertEquals("pump-1.2.3-" + name + "-tea-aggregate.cdx.json", dto.getDisplayIdentifier());
			assertEquals(name, tag(dto, CommonVariables.TEA_PROFILE_NAME_TAG_KEY));
			for (String key : List.of(CommonVariables.TEA_AGGREGATE_TAG_KEY, CommonVariables.TEA_PROFILE_NAME_TAG_KEY,
					CommonVariables.TEA_PUBLICATION_TAG_KEY, CommonVariables.TEA_CONTENT_DIGEST_TAG_KEY)) {
				assertTrue(dto.getTags().stream().anyMatch(t -> key.equals(t.key()) && Removable.NO == t.removable()),
						key + " as a system tag");
			}
			assertEquals(head().getUuid().toString(), tag(dto, CommonVariables.TEA_PUBLICATION_TAG_KEY));
		}
		assertEquals(count, java.util.stream.IntStream.range(0, count).mapToObj(this::serialOf).distinct().count(),
				"each document its own serial");

		List<TeaAggregateArtifact> listed = head().getAggregateArtifacts();
		assertEquals(list, listed.stream().map(TeaAggregateArtifact::profileName).toList());
		assertEquals(aggregates.stream().map(TeaPublishedEntry::artifact).toList(),
				listed.stream().map(TeaAggregateArtifact::artifact).toList());
		assertEquals(listed.stream().map(TeaAggregateArtifact::artifact).toList(), boundSynthetic());
		long binds = ReleaseData.dataFromRecord(releaseRows.get(release.getUuid())).getUpdateEvents().stream()
				.filter(ev -> ReleaseUpdateScope.SYNTHETIC_ARTIFACT == ev.rus() && ReleaseUpdateAction.ADDED == ev.rua())
				.count();
		assertEquals(count, binds, "one bind event per row");
		for (TeaAggregateArtifact a : listed) verify(artifactService, times(1)).ensureTeaUuid(a.artifact(), WU);
		verify(collections, never()).findOldestNaming(any(), anyString());
	}

	@Test
	void oneNameIsTodaysShapeTheCeCase() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		assertEquals(1, writtenDtos.size());
		assertEquals(List.of("default"), synthetic(latest()).stream().map(TeaPublishedEntry::name).toList());
		assertEquals(List.of(row("default")), boundSynthetic());
	}

	@Test
	void aDuplicateNameIsPublishedOnce() throws Exception {
		names("default", "top", "default");
		publish(TeaPublishMode.PUBLISH);
		assertEquals(List.of("default", "top"), synthetic(latest()).stream().map(TeaPublishedEntry::name).toList());
		assertEquals(2, writtenDtos.size());
	}

	@Test
	void theActsLogLineNamesEveryAggregateWithItsRevision() throws Exception {
		CapturingAppender info = new CapturingAppender();
		info.start();
		org.apache.logging.log4j.core.Logger logger =
				(org.apache.logging.log4j.core.Logger) LogManager.getLogger(TeaPublicationService.class);
		Level before = logger.getLevel();
		Configurator.setLevel(TeaPublicationService.class.getName(), Level.INFO);
		logger.addAppender(info);
		try {
			names("default", "top");
			publish(TeaPublishMode.PUBLISH);
			String line = info.events.stream().map(e -> e.getMessage().getFormattedMessage())
					.filter(m -> m.startsWith("TEA publish of release")).reduce((a, b) -> b).orElseThrow();
			assertTrue(line.contains("aggregates [default r1, top r1]"), line);
		} finally {
			logger.removeAppender(info);
			Configurator.setLevel(TeaPublicationService.class.getName(), before);
			info.stop();
		}
	}

	// ---------------------------------------------------------------- 4.3, re-publish

	@Test
	void anUnchangedRepublishOfEveryNameWritesNothing() throws Exception {
		names("default", "top");
		tldOnlyDocuments.put(release.getUuid(), bom("pkg:generic/top@1"));
		publish(TeaPublishMode.PUBLISH);
		clearInvocations(collections);

		TeaPublishResult r = publish(TeaPublishMode.REPUBLISH);

		assertEquals(TeaPublishOutcome.UNCHANGED, r.outcome());
		assertEquals(2, writtenDtos.size(), "no upload");
		assertEquals(1, versions().size());
		verify(collections, never()).findOldestNaming(any(), anyString());
	}

	@Test
	void aChangeOfOneProfilesContentBumpsThatRowOnly() throws Exception {
		names("default", "top");
		tldOnlyDocuments.put(release.getUuid(), bom("pkg:generic/top@1"));
		publish(TeaPublishMode.PUBLISH);
		UUID row1 = row("default");
		UUID row2 = row("top");
		UUID serial2 = artifacts.get(row2).getInternalBom().id();
		String digestBefore = artifacts.get(row2).getTags().stream()
				.filter(t -> CommonVariables.TEA_CONTENT_DIGEST_TAG_KEY.equals(t.key())).findFirst().orElseThrow().value();

		tldOnlyDocuments.put(release.getUuid(), bom("pkg:generic/top@2"));
		TeaPublishResult r = publish(TeaPublishMode.REPUBLISH);

		assertEquals(TeaPublishOutcome.REPUBLISHED, r.outcome());
		assertEquals(3, writtenDtos.size(), "one upload");
		assertEquals(row2, writtenDtos.get(2).getUuid(), "in place");
		assertEquals(serial2, artifacts.get(row2).getInternalBom().id(), "same serial");
		assertEquals("2", artifacts.get(row2).getVersion());
		assertNotEquals(digestBefore, tag(writtenDtos.get(2), CommonVariables.TEA_CONTENT_DIGEST_TAG_KEY));
		assertEquals("1", artifacts.get(row1).getVersion(), "default untouched");
		TeaPublishedCollectionData v2 = latest();
		assertEquals(2, v2.getVersion());
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_UPDATED, v2.getUpdateReasonType());
		assertEquals("aggregate SBOM top regenerated: revision 1 -> 2", v2.getUpdateReasonComment());
		assertEquals(versions().get(0).getEntries().get(0), v2.getEntries().get(0), "default carried verbatim");
	}

	@Test
	void aRemovedNameIsArtifactRemovedAndItsRowStaysBoundAndTagged() throws Exception {
		names("default", "top");
		publish(TeaPublishMode.PUBLISH);
		UUID row2 = row("top");
		UUID topTea = entryNamed(latest(), "top").teaUuid();

		names("default");
		publish(TeaPublishMode.REPUBLISH);

		TeaPublishedCollectionData v2 = latest();
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_REMOVED, v2.getUpdateReasonType());
		assertTrue(v2.getUpdateReasonComment().contains("removed: top (" + topTea + ")"), v2.getUpdateReasonComment());
		assertTrue(boundSynthetic().contains(row2), "the row stays bound");
		assertTrue(artifacts.get(row2).getTags().stream().anyMatch(t -> CommonVariables.TEA_PROFILE_NAME_TAG_KEY
				.equals(t.key()) && "top".equals(t.value())), "and tagged");
		assertEquals(List.of("default"), head().getAggregateArtifacts().stream().map(TeaAggregateArtifact::profileName)
				.toList());
	}

	@Test
	void anAddedNameIsArtifactAdded() throws Exception {
		names("default", "top");
		publish(TeaPublishMode.PUBLISH);
		names("default", "top", "third");
		publish(TeaPublishMode.REPUBLISH);
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_ADDED, latest().getUpdateReasonType());
		assertEquals(List.of("default", "top", "third"), synthetic(latest()).stream().map(TeaPublishedEntry::name).toList());
	}

	@Test
	void aReaddedNameCarriesTheEntryTheLastVersionNamingItsRevisionFroze() throws Exception {
		names("default", "top");
		tldOnlyDocuments.put(release.getUuid(), bom("pkg:generic/top@1"));
		publish(TeaPublishMode.PUBLISH);
		tldOnlyDocuments.put(release.getUuid(), bom("pkg:generic/top@2"));
		publish(TeaPublishMode.REPUBLISH);
		TeaPublishedEntry frozenTop = entryNamed(latest(), "top");
		assertEquals(2, frozenTop.revision());
		UUID row2 = row("top");
		names("default");
		publish(TeaPublishMode.REPUBLISH);
		// a save that is no content change moves the row's updatedDate
		ReflectionTestUtils.setField(artifacts.get(row2), "updatedDate", ZonedDateTime.now().plusHours(1));
		int uploads = writtenDtos.size();
		clearInvocations(collections);

		names("default", "top");
		TeaPublishResult r = publish(TeaPublishMode.REPUBLISH);

		assertEquals(TeaPublishOutcome.REPUBLISHED, r.outcome());
		assertEquals(uploads, writtenDtos.size(), "the row is found by its tag pair and reused, no upload");
		TeaPublishedCollectionData v4 = latest();
		assertEquals(4, v4.getVersion());
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_ADDED, v4.getUpdateReasonType());
		assertEquals(frozenTop, entryNamed(v4, "top"), "byte-identical, createdDate and sbomProfile fields included");
		assertEquals(row2, row("top"));
		UUID pub = head().getUuid();
		verify(collections, times(1)).findOldestNaming(eq(pub), argThat(n -> n.contains(frozenTop.teaUuid().toString())
				&& n.contains("\"revision\":2")));
		verify(collections, times(1)).findOldestNaming(any(), anyString());
	}

	@Test
	void aReaddedNameWithChangedContentIsAFreshRevisionNamedAfterTheProfile() throws Exception {
		names("default", "top");
		tldOnlyDocuments.put(release.getUuid(), bom("pkg:generic/top@1"));
		publish(TeaPublishMode.PUBLISH);
		UUID topTea = entryNamed(latest(), "top").teaUuid();
		names("default");
		publish(TeaPublishMode.REPUBLISH);
		tldOnlyDocuments.put(release.getUuid(), bom("pkg:generic/top@3"));
		clearInvocations(collections);

		names("default", "top");
		publish(TeaPublishMode.REPUBLISH);

		TeaPublishedEntry top = entryNamed(latest(), "top");
		assertEquals(topTea, top.teaUuid(), "the same TEA id");
		assertEquals(2, top.revision());
		assertEquals("top", top.name());
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_ADDED, latest().getUpdateReasonType());
		// asked once for (top, 2), which no earlier version froze: the fresh entry stands
		verify(collections, times(1)).findOldestNaming(any(), anyString());
		assertNotEquals(versions().get(0).getEntries().get(1).frozen(), top.frozen());
	}

	@Test
	void aRideAlongDroppedAndBroughtBackCarriesItsFirstFrozenEntry() throws Exception {
		profile.setRawArtifacts(TeaRawArtifacts.ALL);
		publish(TeaPublishMode.PUBLISH);
		TeaPublishedEntry first = latest().getEntries().stream().filter(e -> inventoryBom.equals(e.artifact()))
				.findFirst().orElseThrow();
		profile.setRawArtifacts(TeaRawArtifacts.NONE);
		publish(TeaPublishMode.REPUBLISH);
		ReflectionTestUtils.setField(artifacts.get(inventoryBom), "updatedDate", ZonedDateTime.now().plusHours(1));

		profile.setRawArtifacts(TeaRawArtifacts.ALL);
		publish(TeaPublishMode.REPUBLISH);

		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_ADDED, latest().getUpdateReasonType());
		assertEquals(first, latest().getEntries().stream().filter(e -> inventoryBom.equals(e.artifact())).findFirst()
				.orElseThrow());
	}

	@Test
	void removeTopAndAddThirdIsArtifactRemoved() throws Exception {
		names("default", "top");
		publish(TeaPublishMode.PUBLISH);
		names("default", "third");
		publish(TeaPublishMode.REPUBLISH);
		TeaPublishedCollectionData v2 = latest();
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_REMOVED, v2.getUpdateReasonType());
		assertTrue(v2.getUpdateReasonComment().contains("added: third (") && v2.getUpdateReasonComment()
				.contains("removed: top ("), v2.getUpdateReasonComment());
	}

	@Test
	void addThirdAndChangeDefaultIsArtifactAdded() throws Exception {
		names("default", "top");
		tldOnlyDocuments.put(release.getUuid(), bom("pkg:generic/top@1"));
		publish(TeaPublishMode.PUBLISH);
		names("default", "top", "third");
		documents.put(release.getUuid(), bom("pkg:generic/a@2"));
		publish(TeaPublishMode.REPUBLISH);
		TeaPublishedCollectionData v2 = latest();
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_ADDED, v2.getUpdateReasonType());
		assertTrue(v2.getUpdateReasonComment().startsWith("aggregate SBOM default regenerated: revision 1 -> 2; added: "
				+ "third ("), v2.getUpdateReasonComment());
	}

	@Test
	void aRevisedVulnerabilitiesRideAlongBesideARemovedAggregateIsVexUpdated() throws Exception {
		UUID vdr = artifact(ArtifactType.VDR, "vdr.json", "4", StoredIn.REARM, true, "dd44");
		setInventory(release, List.of(inventoryBom, vdr));
		profile.setRawArtifacts(TeaRawArtifacts.ALL);
		names("default", "top");
		publish(TeaPublishMode.PUBLISH);
		names("default");
		artifacts.get(vdr).setVersion("5");
		publish(TeaPublishMode.REPUBLISH);
		TeaPublishedCollectionData v2 = latest();
		assertEquals(TeaCollectionUpdateReasonType.VEX_UPDATED, v2.getUpdateReasonType());
		assertTrue(v2.getUpdateReasonComment().contains("removed: top (") && v2.getUpdateReasonComment()
				.contains("revised: vdr.json (") && v2.getUpdateReasonComment().contains("revision 4 -> 5"),
				v2.getUpdateReasonComment());
	}

	@Test
	void aChangeOfOrderAloneWritesNoVersion() throws Exception {
		names("default", "top");
		tldOnlyDocuments.put(release.getUuid(), bom("pkg:generic/top@1"));
		publish(TeaPublishMode.PUBLISH);
		names("top", "default");
		TeaPublishResult r = publish(TeaPublishMode.REPUBLISH);
		assertEquals(TeaPublishOutcome.UNCHANGED, r.outcome());
		assertEquals(1, versions().size());
		assertEquals(2, writtenDtos.size());
	}

	@Test
	void aLegacyHeadWithoutTheListFindsItsRowByTheTagPair() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		UUID row1 = row("default");
		// the pre-TEA-28 head shape: no list (the row carries the teaProfileName tag the engine already wrote)
		var headRow = heads.get(head().getUuid());
		Map<String, Object> record = new java.util.HashMap<>(headRow.getRecordData());
		record.remove("aggregateArtifacts");
		record.put("aggregateArtifact", row1.toString());
		headRow.setRecordData(record);
		assertTrue(head().getAggregateArtifacts().isEmpty());

		TeaPublishResult r = publish(TeaPublishMode.REPUBLISH);

		assertEquals(TeaPublishOutcome.UNCHANGED, r.outcome());
		assertEquals(1, writtenDtos.size(), "no upload: the row was found by its tag pair");
		assertEquals(List.of(new TeaAggregateArtifact("default", row1)), head().getAggregateArtifacts(),
				"the head records the list again");
	}

	// ---------------------------------------------------------------- 4.6, failure paths

	/** The n-th upload from now (0-based) throws; the others store as the base does; -1 never fails. */
	private void uploadFails(int failing) throws Exception {
		List<Integer> calls = new ArrayList<>();
		doAnswer(inv -> {
			calls.add(calls.size());
			if (calls.size() - 1 == failing) throw new RelizaException("registry down");
			return storeAggregate(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
		}).when(synthetic).upload(any(UUID.class), any(ArtifactDto.class), any(Resource.class), any(RebomOptions.class),
				any());
	}

	@Test
	void aFirstPublishWhoseSecondUploadFailsArchivesTheFirstRowAndWritesNothing() throws Exception {
		names("default", "top");
		uploadFails(1);

		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishRefusal.GENERATION_FAILED, e.reason());
		assertTrue(e.getMessage().contains("storing the TEA aggregate top failed"), e.getMessage());
		assertEquals(1, writtenDtos.size());
		UUID first = artifacts.keySet().stream().filter(id -> !id.equals(inventoryBom)).findFirst().orElseThrow();
		verify(artifactService, times(1)).archiveArtifact(first, WU);
		verify(publications, never()).save(any());
		verify(collections, never()).save(any());
		assertTrue(service.getPublicationOfRelease(release.getUuid()).isEmpty());
	}

	/** The aggregate rows this act uploaded, in upload order (the inventory BOM is not one). */
	private List<UUID> uploadedAggregates() {
		return artifacts.keySet().stream().filter(id -> !id.equals(inventoryBom)).toList();
	}

	/** Design 3.3 step 6: every row the failed act created is archived, each once, the first and the last alike. */
	@Test
	void aFirstPublishWhoseThirdUploadFailsArchivesBothEarlierRowsEachOnce() throws Exception {
		names("default", "top", "third");
		uploadFails(2);

		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishRefusal.GENERATION_FAILED, e.reason());
		assertTrue(e.getMessage().contains("storing the TEA aggregate third failed"), e.getMessage());
		List<UUID> rows = uploadedAggregates();
		assertEquals(2, rows.size(), "default and top were uploaded");
		for (UUID row : rows) verify(artifactService, times(1)).archiveArtifact(row, WU);
		verify(artifactService, times(2)).archiveArtifact(any(), any());
		verify(publications, never()).save(any());
		verify(collections, never()).save(any());
	}

	/** Design 3.3 step 6 and 4.6: a commit that fails after every upload archives every unbound row. */
	@Test
	void aFirstPublishOfTwoNamesWhoseCommitFailsArchivesBothRows() throws Exception {
		names("default", "top");
		tldOnlyDocuments.put(release.getUuid(), bom("pkg:generic/top@1"));
		doThrow(new IllegalStateException("db down")).when(collections).save(any(TeaPublishedCollection.class));

		assertEquals(TeaPublishRefusal.GENERATION_FAILED, refused(TeaPublishMode.PUBLISH).reason());

		List<UUID> rows = uploadedAggregates();
		assertEquals(2, rows.size(), "both names were uploaded before the commit");
		for (UUID row : rows) verify(artifactService, times(1)).archiveArtifact(row, WU);
		verify(artifactService, times(2)).archiveArtifact(any(), any());
		assertTrue(service.getPublicationOfRelease(release.getUuid()).isEmpty());
	}

	@Test
	void aRepublishWhoseSecondUploadFailsLeavesTheFirstBumpedAndTheNextActRecordsIt() throws Exception {
		names("default", "top");
		tldOnlyDocuments.put(release.getUuid(), bom("pkg:generic/top@1"));
		publish(TeaPublishMode.PUBLISH);
		UUID row1 = row("default");
		int headRevision = heads.get(head().getUuid()).getRevision();
		documents.put(release.getUuid(), bom("pkg:generic/a@2"));
		tldOnlyDocuments.put(release.getUuid(), bom("pkg:generic/top@2"));
		uploadFails(1);

		assertEquals(TeaPublishRefusal.GENERATION_FAILED, refused(TeaPublishMode.REPUBLISH).reason());

		assertEquals("2", artifacts.get(row1).getVersion(), "the first row went up in place");
		assertTrue(boundSynthetic().contains(row1));
		verify(artifactService, never()).archiveArtifact(any(), any());
		assertEquals(1, versions().size(), "nothing committed");
		assertEquals(headRevision, heads.get(head().getUuid()).getRevision());

		uploadFails(-1);
		int uploads = writtenDtos.size();
		TeaPublishResult r = publish(TeaPublishMode.REPUBLISH);
		assertEquals(TeaPublishOutcome.REPUBLISHED, r.outcome());
		assertEquals(uploads + 1, writtenDtos.size(), "default reused at its bumped revision, top uploaded");
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_UPDATED, latest().getUpdateReasonType());
		assertEquals(2, entryNamed(latest(), "default").revision());
	}

	@Test
	void aMergeFailureOfTheSecondNameUploadsNothingForTheFirst() throws Exception {
		names("default", "top");
		MergedSbomUnavailableException cause = MergedSbomUnavailableException.noSbomArtifacts(release, component,
				new RebomOptions(null, false, false, null), List.of(), List.of());
		doThrow(cause).when(releaseService).generateMergedBomForPublication(any(ReleaseData.class),
				argThat((PublicationMergeRequest m) -> null != m && m.tldOnly()), any());

		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishRefusal.NO_SBOM_ARTIFACTS, e.reason());
		assertNothingWritten();
		verify(artifactService, never()).archiveArtifact(any(), any());
	}
}
