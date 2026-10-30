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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
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
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.SbomProfileData;
import io.reliza.model.SbomProfileData.SbomDependencyDepth;
import io.reliza.model.SbomProfileData.SbomInternalMetadata;
import io.reliza.model.SbomProfileData.SbomOptionalDependencies;
import io.reliza.model.SbomProfileData.SbomSourceKind;
import io.reliza.model.SbomProfileData.SbomSupportMetadata;
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
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.model.DigestAlgorithm;
import io.reliza.model.tea.TeaCollectionBelongsToType;
import io.reliza.model.tea.TeaCollectionUpdateReasonType;
import io.reliza.repositories.TeaPublicationRepository;
import io.reliza.repositories.TeaPublicationRepository.TeaPublicationHeadView;
import io.reliza.repositories.TeaPublishedCollectionRepository;
import io.reliza.service.ReleaseService.PublicationMergeRequest;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * TEA-5, section 4.1: the publication engine's publish, re-publish, hide, ride-along and preview
 * cases on the shared fixture of {@link TeaPublicationServiceTestBase}. The cascade, the commit
 * under concurrency and the exposure have their own classes.
 */
class TeaPublicationServiceTest extends TeaPublicationServiceTestBase {

	// ---------------------------------------------------------------- 1

	@Test
	void firstPublishWritesTheHeadVersionOneAndTheAggregate() throws Exception {
		TeaPublishResult r = publish(TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		TeaPublicationData h = head();
		assertEquals(TeaPublicationState.PUBLISHED, h.getState());
		assertEquals(1, h.getLatestVersion());
		assertEquals(profile.getUuid(), h.getProfile());
		assertEquals(3, h.getProfileRevision());
		assertEquals(TeaProfileSource.ORGANIZATION, h.getProfileSource());
		assertNull(h.getCascadeOf());
		assertNotNull(h.getFirstPublishedDate());
		assertEquals(ComponentType.COMPONENT, h.getComponentType());
		UUID aggregate = writtenDtos.get(0).getUuid() == null ? TeaAggregates.defaultAggregate(h) : null;
		assertEquals(TeaAggregates.defaultAggregate(h), aggregate);

		assertEquals(1, collectionRows.size());
		TeaPublishedCollectionData c = r.collection();
		assertEquals(1, c.getVersion());
		assertEquals(TeaCollectionUpdateReasonType.INITIAL_RELEASE, c.getUpdateReasonType());
		assertEquals(TeaCollectionBelongsToType.COMPONENT_RELEASE, c.getBelongsTo());
		assertEquals(1, c.getEntries().size());
		TeaPublishedEntry e = c.getEntries().get(0);
		assertEquals(TeaArtifactOrigin.SYNTHETIC, e.origin());
		assertEquals(1, e.revision());
		ArtifactData stored = artifacts.get(TeaAggregates.defaultAggregate(h));
		assertEquals(stored.getTeaUuid(), e.teaUuid());
		assertEquals(Utils.bytesToHexSha256(writtenBytes.get(0)), e.checksums().get(0).algValue());

		JsonNode doc = Utils.OM.readTree(c.getDocument());
		assertEquals(h.getUuid().toString(), doc.get("uuid").asText());
		assertEquals(1, doc.get("version").asInt());
		assertTrue(doc.get("createdDate").asText().endsWith("Z"));
		assertEquals("COMPONENT_RELEASE", doc.get("belongsTo").asText());
		assertEquals("INITIAL_RELEASE", doc.get("updateReason").get("type").asText());
		JsonNode format = doc.get("artifacts").get(0).get("formats").get(0);
		assertFalse(format.has("url"), format.toString());
		assertEquals("SHA-256", format.get("checksums").get(0).get("algType").asText());
		assertEquals(Utils.bytesToHexSha256(c.getDocument().getBytes(StandardCharsets.UTF_8)), c.getDocumentSha256());

		ArtifactDto dto = writtenDtos.get(0);
		assertEquals(ArtifactType.BOM, dto.getType());
		assertEquals(StoredIn.REARM, dto.getStoredIn());
		assertNull(dto.getUuid());
		Map<String, String> tags = dto.getTags().stream().collect(Collectors.toMap(TagRecord::key, TagRecord::value));
		assertEquals("true", tags.get(CommonVariables.TEA_AGGREGATE_TAG_KEY));
		assertEquals(h.getUuid().toString(), tags.get(CommonVariables.TEA_PUBLICATION_TAG_KEY));
		assertNotNull(tags.get(CommonVariables.TEA_CONTENT_DIGEST_TAG_KEY));
		assertTrue(dto.getTags().stream().allMatch(t -> Removable.NO == t.removable()));
		assertEquals(ArtifactBelongsTo.SYNTHETIC, writtenOptions.get(0).belongsTo());

		JsonNode bytes = Utils.OM.readTree(writtenBytes.get(0));
		assertEquals("urn:uuid:" + stored.getInternalBom().id(), bytes.get("serialNumber").asText());
		assertEquals(1, bytes.get("version").asInt());
		assertTrue(bytes.get("metadata").get("timestamp").asText().endsWith("Z"));

		verify(organizationService).ensureTeaUuid(ORG, WU);
		verify(componentService).ensureTeaUuid(component.getUuid(), WU);
		verify(artifactService).ensureTeaUuid(TeaAggregates.defaultAggregate(h), WU);
		List<ReleaseUpdateEvent> events = teaEvents(release.getUuid());
		assertEquals(1, events.size());
		assertEquals(ReleaseUpdateAction.ADDED, events.get(0).rua());
		assertEquals("v1", events.get(0).newValue());
		assertEquals(h.getUuid(), events.get(0).objectId());
		verify(oss).saveRelease(any(Release.class), any(ReleaseData.class), eq(WU), eq(false), eq(AcollectionMode.SKIP));
		verify(hook, times(1)).onReleaseTeaPublished(any(), any(), eq(1), eq(TeaExposureState.PRIVATE), eq(0));
	}

	// ---------------------------------------------------------------- 2

	@Test
	void aMissingReleaseIsRefused() throws Exception {
		TeaPublishRefusedException e = assertThrows(TeaPublishRefusedException.class,
				() -> service.publish(new TeaPublishRequest(UUID.randomUUID(), TeaPublishMode.PUBLISH, null, WU)));
		assertEquals(TeaPublishRefusal.RELEASE_NOT_FOUND, e.reason());
		assertTrue(e.getMessage().contains("not found"));
		assertNothingWritten();
	}

	@Test
	void anArchivedReleaseIsRefused() throws Exception {
		Release r = releaseRows.get(release.getUuid());
		ReleaseData d = ReleaseData.dataFromRecord(r);
		d.setStatus(ReleaseStatus.ARCHIVED);
		r.setRecordData(Utils.dataToRecord(d));
		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);
		assertEquals(TeaPublishRefusal.RELEASE_ARCHIVED, e.reason());
		assertTrue(e.getMessage().contains("archived"));
		assertNothingWritten();
	}

	@Test
	void anArchivedComponentIsRefused() throws Exception {
		component.setStatus(StatusEnum.ARCHIVED);
		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);
		assertEquals(TeaPublishRefusal.COMPONENT_ARCHIVED, e.reason());
		assertTrue(e.getMessage().contains("pump"));
		assertNothingWritten();
	}

	// ---------------------------------------------------------------- 3

	@Test
	void publishRefusesAPublishedHeadAndRepublishRefusesNone() throws Exception {
		TeaPublishRefusedException none = refused(TeaPublishMode.REPUBLISH);
		assertEquals(TeaPublishRefusal.NOT_PUBLISHED, none.reason());
		assertNothingWritten();

		publish(TeaPublishMode.PUBLISH);
		TeaPublishRefusedException again = refused(TeaPublishMode.PUBLISH);
		assertEquals(TeaPublishRefusal.ALREADY_PUBLISHED, again.reason());
		assertTrue(again.getMessage().contains("already published"));
	}

	@Test
	void publishOrRepublishWorksInBothStates() throws Exception {
		assertEquals(TeaPublishOutcome.PUBLISHED, publish(TeaPublishMode.PUBLISH_OR_REPUBLISH).outcome());
		assertEquals(TeaPublishOutcome.UNCHANGED, publish(TeaPublishMode.PUBLISH_OR_REPUBLISH).outcome());
	}

	// ---------------------------------------------------------------- 4

	@Test
	void aConflictIsRefusedNamingBothPerspectives() throws Exception {
		resolutions.put(component.getUuid(), new TeaResolvedProfile(TeaResolutionStatus.CONFLICT,
				TeaProfileSource.PERSPECTIVE, null, null, List.of(new TeaPerspectiveRef(UUID.randomUUID(), "Alpha", null),
						new TeaPerspectiveRef(UUID.randomUUID(), "Beta", null))));
		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);
		assertEquals(TeaPublishRefusal.PROFILE_CONFLICT, e.reason());
		assertTrue(e.getMessage().contains("Alpha") && e.getMessage().contains("Beta"), e.getMessage());
		verify(resolver).resolveForComponent(any(ComponentData.class));
		verify(resolver, never()).resolveForComponent(any(ComponentData.class), any());
		assertNothingWritten();
	}

	@Test
	void disabledPublishingIsRefusedNamingTheSource() throws Exception {
		profile.setPublishing(TeaPublishing.DISABLED);
		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);
		assertEquals(TeaPublishRefusal.PUBLISHING_DISABLED, e.reason());
		assertTrue(e.getMessage().contains("DISABLED") && e.getMessage().contains("organization"), e.getMessage());
		assertNothingWritten();
	}

	// ---------------------------------------------------------------- 5

	@ParameterizedTest
	@EnumSource(value = ReleaseLifecycle.class, names = { "CANCELLED", "REJECTED", "PENDING", "DRAFT" })
	void lowLifecyclesAreAlwaysRefused(ReleaseLifecycle lc) throws Exception {
		profile.setMinimumLifecycle(ReleaseLifecycle.ASSEMBLED);
		setLifecycle(release, lc);
		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);
		assertEquals(TeaPublishRefusal.LIFECYCLE_TOO_LOW, e.reason());
		assertTrue(e.getMessage().contains(lc.name()), e.getMessage());
		assertNothingWritten();
	}

	@Test
	void assembledUnderAReadyToShipMinimumIsRefusedNamingBoth() throws Exception {
		profile.setMinimumLifecycle(ReleaseLifecycle.READY_TO_SHIP);
		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);
		assertEquals(TeaPublishRefusal.LIFECYCLE_TOO_LOW, e.reason());
		assertTrue(e.getMessage().contains("ASSEMBLED") && e.getMessage().contains("READY_TO_SHIP"), e.getMessage());
	}

	@Test
	void readyToShipAndEndOfLifeMeetTheirMinimums() throws Exception {
		profile.setMinimumLifecycle(ReleaseLifecycle.READY_TO_SHIP);
		setLifecycle(release, ReleaseLifecycle.READY_TO_SHIP);
		assertEquals(TeaPublishOutcome.PUBLISHED, publish(TeaPublishMode.PUBLISH).outcome());

		ReleaseData eol = release(component, ReleaseLifecycle.END_OF_LIFE, List.of(inventoryBom));
		documents.put(eol.getUuid(), bom("pkg:generic/a@1"));
		profile.setMinimumLifecycle(ReleaseLifecycle.GENERAL_AVAILABILITY);
		assertEquals(TeaPublishOutcome.PUBLISHED, service.publish(new TeaPublishRequest(eol.getUuid(),
				TeaPublishMode.PUBLISH, null, WU)).outcome());
	}

	// ---------------------------------------------------------------- 6

	@Test
	void noSbomArtifactsKeepsTheMergeMessage() throws Exception {
		MergedSbomUnavailableException cause = MergedSbomUnavailableException.noSbomArtifacts(release, component,
				new RebomOptions(null, false, false, null), List.of(), List.of());
		when(releaseService.generateMergedBomForPublication(any(), any(), any())).thenThrow(cause);
		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);
		assertEquals(TeaPublishRefusal.NO_SBOM_ARTIFACTS, e.reason());
		assertEquals(cause.getMessage(), e.getMessage());
		assertNothingWritten();
	}

	@Test
	void childMergeFailedCarriesTheChildren() throws Exception {
		List<ChildBomOutcome> children = List.of(new ChildBomOutcome(UUID.randomUUID(), "c", "1", ChildBomStatus.FAILED,
				null, "boom"));
		MergedSbomUnavailableException cause = MergedSbomUnavailableException.childMergeFailed(release, component, children);
		when(releaseService.generateMergedBomForPublication(any(), any(), any())).thenThrow(cause);
		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);
		assertEquals(TeaPublishRefusal.CHILD_MERGE_FAILED, e.reason());
		assertEquals(children, e.children());
		assertNothingWritten();
	}

	@Test
	void anyOtherGenerationFailureIsGenerationFailed() throws Exception {
		when(releaseService.generateMergedBomForPublication(any(), any(), any())).thenThrow(new RelizaException("rebom down"));
		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);
		assertEquals(TeaPublishRefusal.GENERATION_FAILED, e.reason());
		assertTrue(e.getMessage().contains("rebom down"));
		assertNothingWritten();
	}

	@Test
	void aWriterFailureIsGenerationFailedWithNoCommit() throws Exception {
		when(synthetic.upload(any(), any(), any(), any(), any())).thenThrow(new RelizaException("registry down"));
		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);
		assertEquals(TeaPublishRefusal.GENERATION_FAILED, e.reason());
		verify(publications, never()).save(any());
		verify(collections, never()).save(any());
	}

	@Test
	void theMergeRequestCarriesTheProfileKnobsExplicitly() throws Exception {
		// Task TEA-27: the knobs are the resolved SBOM profile's, at the component's scope.
		SbomProfileData recipe = recipe(component.getUuid());
		recipe.setSources(EnumSet.of(SbomSourceKind.RELEASE, SbomSourceKind.SOURCE_CODE));
		recipe.setDependencyDepth(SbomDependencyDepth.TOP_LEVEL_ONLY);
		recipe.setOptionalDependencies(SbomOptionalDependencies.EXCLUDE);
		recipe.setStructure(RebomService.BomStructureType.HIERARCHICAL);
		recipe.setExcludedCoverage(EnumSet.of(ArtifactCoverageType.TEST));
		recipe.setSupportMetadata(SbomSupportMetadata.INCLUDE);
		recipe.setInternalMetadata(SbomInternalMetadata.EXCLUDE);
		ArgumentCaptor<PublicationMergeRequest> mr = ArgumentCaptor.forClass(PublicationMergeRequest.class);

		publish(TeaPublishMode.PUBLISH);

		verify(releaseService).generateMergedBomForPublication(any(), mr.capture(), any());
		assertEquals(EnumSet.of(ArtifactBelongsTo.RELEASE, ArtifactBelongsTo.SCE), mr.getValue().sources());
		assertTrue(mr.getValue().tldOnly());
		assertTrue(mr.getValue().ignoreDev());
		assertEquals(RebomService.BomStructureType.HIERARCHICAL, mr.getValue().structure());
		assertEquals(List.of(ArtifactCoverageType.TEST), mr.getValue().excludeCoverageTypes());
		assertTrue(mr.getValue().exportMetadata().supportMetadataRequested());
		assertTrue(mr.getValue().exportMetadata().internalMetadataDeclined());
		assertFalse(mr.getValue().exportMetadata().supportMetadataDeclined());
	}

	// ---------------------------------------------------------------- 7 and 8

	@Test
	void republishWithTheSameDocumentWritesNothing() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		UUID aggregate = TeaAggregates.defaultAggregate(head());

		TeaPublishResult r = publish(TeaPublishMode.REPUBLISH);

		assertEquals(TeaPublishOutcome.UNCHANGED, r.outcome());
		verify(synthetic, times(1)).upload(any(), any(), any(), any(), any());
		assertEquals(1, collectionRows.size());
		assertEquals(1, teaEvents(release.getUuid()).size());
		verify(hook, times(1)).onReleaseTeaPublished(any(), any(), anyInt(), any(), anyInt());
		assertEquals(1, head().getLatestVersion());
		assertEquals("1", artifacts.get(aggregate).getVersion());
	}

	@Test
	void republishWithAChangedDocumentBumpsTheAggregateInPlace() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		UUID aggregate = TeaAggregates.defaultAggregate(head());
		UUID serial = artifacts.get(aggregate).getInternalBom().id();
		documents.put(release.getUuid(), bom("pkg:generic/a@1", "pkg:generic/b@2"));

		TeaPublishResult r = publish(TeaPublishMode.REPUBLISH);

		assertEquals(TeaPublishOutcome.REPUBLISHED, r.outcome());
		assertEquals(aggregate, writtenDtos.get(1).getUuid());
		JsonNode bytes = Utils.OM.readTree(writtenBytes.get(1));
		assertEquals("urn:uuid:" + serial, bytes.get("serialNumber").asText());
		assertEquals(2, bytes.get("version").asInt());
		assertEquals(2, r.collection().getVersion());
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_UPDATED, r.collection().getUpdateReasonType());
		assertTrue(r.collection().getUpdateReasonComment().contains("revision 1 -> 2"), r.collection().getUpdateReasonComment());
		List<ReleaseUpdateEvent> events = teaEvents(release.getUuid());
		assertEquals(ReleaseUpdateAction.CHANGED, events.get(1).rua());
		assertEquals("v1", events.get(1).oldValue());
		assertEquals("v2", events.get(1).newValue());
	}

	/** Live run 1 on psclaude: a re-merge carries a fresh rebom producer BOM-Link; that is no change. */
	@Test
	void theRebomProducerLinkNeitherShipsNorMakesARevision() throws Exception {
		ObjectNode linked = (ObjectNode) bom("pkg:generic/a@1");
		linked.putArray("externalReferences").addObject().put("type", "bom")
				.put("url", "urn:cdx:" + UUID.randomUUID() + "/1").put("comment", TeaCollectionAssembler.REBOM_PRODUCER_LINK_COMMENT);
		documents.put(release.getUuid(), linked);
		publish(TeaPublishMode.PUBLISH);
		assertFalse(Utils.OM.readTree(writtenBytes.get(0)).has("externalReferences"));

		ObjectNode relinked = (ObjectNode) bom("pkg:generic/a@1");
		relinked.putArray("externalReferences").addObject().put("type", "bom")
				.put("url", "urn:cdx:" + UUID.randomUUID() + "/1").put("comment", TeaCollectionAssembler.REBOM_PRODUCER_LINK_COMMENT);
		documents.put(release.getUuid(), relinked);
		assertEquals(TeaPublishOutcome.UNCHANGED, publish(TeaPublishMode.REPUBLISH).outcome());
		assertEquals(1, writtenBytes.size());
	}

	// ---------------------------------------------------------------- 9

	@Test
	void anotherSerialFromRebomIsGenerationFailed() throws Exception {
		when(synthetic.upload(any(), any(), any(), any(), any())).thenAnswer(inv -> {
			UUID id = storeAggregate(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
			artifacts.get(id).setInternalBom(new InternalBom(UUID.randomUUID(), ArtifactBelongsTo.SYNTHETIC));
			return id;
		});
		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);
		assertEquals(TeaPublishRefusal.GENERATION_FAILED, e.reason());
		assertTrue(e.getMessage().contains("another serial or version"));
		verify(collections, never()).save(any());
		verify(publications, never()).save(any());
	}

	@Test
	void anotherVersionFromRebomIsGenerationFailed() throws Exception {
		when(synthetic.upload(any(), any(), any(), any(), any())).thenAnswer(inv -> {
			UUID id = storeAggregate(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
			artifacts.get(id).setVersion("7");
			return id;
		});
		assertEquals(TeaPublishRefusal.GENERATION_FAILED, refused(TeaPublishMode.PUBLISH).reason());
		verify(collections, never()).save(any());
	}

	@Test
	void noRetainedDigestIsGenerationFailed() throws Exception {
		when(synthetic.upload(any(), any(), any(), any(), any())).thenAnswer(inv -> {
			UUID id = storeAggregate(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3));
			artifacts.get(id).setDigestRecords(new LinkedHashSet<>());
			return id;
		});
		assertEquals(TeaPublishRefusal.GENERATION_FAILED, refused(TeaPublishMode.PUBLISH).reason());
		verify(collections, never()).save(any());
	}

	// ---------------------------------------------------------------- 10

	@Test
	void hideThenRepublishContinuesTheNumbering() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		documents.put(release.getUuid(), bom("pkg:generic/a@1", "pkg:generic/b@2"));
		publish(TeaPublishMode.REPUBLISH);
		int rows = collectionRows.size();

		TeaPublicationData hidden = service.hide(release.getUuid(), "pulled", WU);

		assertEquals(TeaPublicationState.HIDDEN, hidden.getState());
		assertNotNull(hidden.getLastHiddenDate());
		assertEquals(rows, collectionRows.size());
		List<ReleaseUpdateEvent> events = teaEvents(release.getUuid());
		assertEquals(ReleaseUpdateAction.REMOVED, events.get(events.size() - 1).rua());
		assertEquals("HIDDEN", events.get(events.size() - 1).newValue());
		verify(hook, times(1)).onReleaseTeaHidden(any(), any());

		service.hide(release.getUuid(), null, WU);
		assertEquals(events.size(), teaEvents(release.getUuid()).size());
		verify(hook, times(1)).onReleaseTeaHidden(any(), any());

		TeaPublishResult again = publish(TeaPublishMode.REPUBLISH);
		assertEquals(TeaPublicationState.PUBLISHED, again.publication().getState());
		assertEquals(2, again.publication().getLatestVersion());
		ReleaseUpdateEvent last = teaEvents(release.getUuid()).get(teaEvents(release.getUuid()).size() - 1);
		assertEquals(ReleaseUpdateAction.CHANGED, last.rua());
		assertEquals("HIDDEN", last.oldValue());
		assertEquals("PUBLISHED v2", last.newValue());
		verify(hook, times(3)).onReleaseTeaPublished(any(), any(), anyInt(), any(), anyInt());

		documents.put(release.getUuid(), bom("pkg:generic/a@1", "pkg:generic/b@2", "pkg:generic/c@3"));
		assertEquals(3, publish(TeaPublishMode.REPUBLISH).collection().getVersion());
	}

	@Test
	void hideWithoutAPublicationIsRefused() {
		TeaPublishRefusedException e = assertThrows(TeaPublishRefusedException.class,
				() -> service.hide(release.getUuid(), null, WU));
		assertEquals(TeaPublishRefusal.NOT_PUBLISHED, e.reason());
	}

	@Test
	void everyModePublishesAHiddenHead() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		for (TeaPublishMode mode : TeaPublishMode.values()) {
			service.hide(release.getUuid(), null, WU);
			assertEquals(TeaPublicationState.PUBLISHED, publish(mode).publication().getState(), mode.name());
		}
	}

	// ---------------------------------------------------------------- 11

	private UUID pdf, signature, external, externalNoChecksum, notInteger, vdr, bomNoRaw;

	private void rideAlongInventory() {
		bomNoRaw = artifact(ArtifactType.BOM, "old.cdx.json", "1", StoredIn.REARM, true, null);
		pdf = artifact(ArtifactType.USER_DOCUMENT, "manual.pdf", "2", StoredIn.REARM, false, null);
		artifacts.get(pdf).getDigestRecords().add(new DigestRecord(DigestAlgorithm.SHA_256, "BB22", DigestScope.ORIGINAL_FILE));
		artifacts.get(pdf).getTags().add(new TagRecord(CommonVariables.MEDIA_TYPE_FIELD, "application/pdf", Removable.NO));
		signature = artifact(ArtifactType.SIGNATURE, "pump.sig", "1", StoredIn.REARM, false, null);
		artifacts.get(inventoryBom).setArtifacts(new ArrayList<>(List.of(signature)));
		external = artifact(ArtifactType.RELEASE_NOTES, "notes.html", "1", StoredIn.EXTERNALLY, false, null);
		Link link = new Link();
		link.setUri("https://example.com/notes.html");
		artifacts.get(external).setDownloadLinks(new ArrayList<>(List.of(link)));
		artifacts.get(external).getDigestRecords().add(new DigestRecord(DigestAlgorithm.SHA_256, "cc33", DigestScope.ORIGINAL_FILE));
		externalNoChecksum = artifact(ArtifactType.RELEASE_NOTES, "more.html", "1", StoredIn.EXTERNALLY, false, null);
		artifacts.get(externalNoChecksum).setDownloadLinks(new ArrayList<>(List.of(link)));
		notInteger = artifact(ArtifactType.USER_DOCUMENT, "draft.txt", "abc", StoredIn.REARM, false, null);
		vdr = artifact(ArtifactType.VDR, "vdr.json", "4", StoredIn.REARM, true, "dd44");
		setInventory(release, List.of(inventoryBom, bomNoRaw, pdf, signature, external, externalNoChecksum, notInteger, vdr));
	}

	private Map<UUID, TeaRideAlongStatus> statuses(TeaPublishResult r) {
		Map<UUID, TeaRideAlongStatus> m = new HashMap<>();
		r.artifacts().forEach(o -> m.put(o.artifact(), o.status()));
		return m;
	}

	/** Task TEA-6, design 4.2 case 4: TEA 1.0.0 cannot express MD5, so an MD5-only artifact has no checksum to publish. */
	@Test
	void anArtifactWhoseOnlyDigestIsMd5HasNoChecksumWhileSha256StillPublishes() throws Exception {
		UUID md5Only = artifact(ArtifactType.RELEASE_NOTES, "md5.html", "1", StoredIn.EXTERNALLY, false, null);
		UUID sha = artifact(ArtifactType.RELEASE_NOTES, "sha.html", "1", StoredIn.EXTERNALLY, false, null);
		Link link = new Link();
		link.setUri("https://example.com/notes.html");
		artifacts.get(md5Only).setDownloadLinks(new ArrayList<>(List.of(link)));
		artifacts.get(md5Only).getDigestRecords().add(new DigestRecord(DigestAlgorithm.MD5,
				"9e107d9d372bb6826bd81d3542a419d6", DigestScope.ORIGINAL_FILE));
		artifacts.get(sha).setDownloadLinks(new ArrayList<>(List.of(link)));
		artifacts.get(sha).getDigestRecords().add(new DigestRecord(DigestAlgorithm.SHA_256, "AB12", DigestScope.ORIGINAL_FILE));
		setInventory(release, List.of(inventoryBom, md5Only, sha));
		profile.setRawArtifacts(TeaRawArtifacts.ALL);

		TeaPublishResult r = publish(TeaPublishMode.PUBLISH);

		Map<UUID, TeaRideAlongStatus> s = statuses(r);
		assertEquals(TeaRideAlongStatus.SKIPPED_NO_CHECKSUM, s.get(md5Only));
		assertEquals(TeaRideAlongStatus.PUBLISHED, s.get(sha));
		TeaPublishedEntry entry = r.collection().getEntries().stream().filter(e -> sha.equals(e.artifact())).findFirst().orElseThrow();
		assertEquals(List.of(TeaChecksumType.SHA_256), entry.checksums().stream().map(c -> c.algType()).toList());
	}

	@Test
	void rideAlongsUnderAllPublishWhatCanBeServedAndSkipTheRest() throws Exception {
		rideAlongInventory();
		profile.setRawArtifacts(TeaRawArtifacts.ALL);

		TeaPublishResult r = publish(TeaPublishMode.PUBLISH);

		Map<UUID, TeaRideAlongStatus> s = statuses(r);
		assertEquals(TeaRideAlongStatus.PUBLISHED, s.get(inventoryBom));
		assertEquals(TeaRideAlongStatus.SKIPPED_NO_RAW_BYTES, s.get(bomNoRaw));
		assertEquals(TeaRideAlongStatus.PUBLISHED, s.get(pdf));
		assertEquals(TeaRideAlongStatus.SKIPPED_SIGNATURE_TYPE, s.get(signature));
		assertEquals(TeaRideAlongStatus.PUBLISHED, s.get(external));
		assertEquals(TeaRideAlongStatus.SKIPPED_NO_CHECKSUM, s.get(externalNoChecksum));
		assertEquals(TeaRideAlongStatus.SKIPPED_VERSION_NOT_INTEGER, s.get(notInteger));
		assertEquals(TeaRideAlongStatus.PUBLISHED, s.get(vdr));

		List<TeaPublishedEntry> entries = r.collection().getEntries();
		assertEquals(List.of(TeaAggregates.defaultAggregate(head()), inventoryBom, pdf, external, vdr),
				entries.stream().map(TeaPublishedEntry::artifact).toList());
		TeaPublishedEntry bomEntry = entries.get(1);
		assertEquals(TeaArtifactType.BOM, bomEntry.teaType());
		assertEquals("aa11", bomEntry.checksums().get(0).algValue());
		assertEquals(signature, bomEntry.signatureArtifact());
		assertEquals(1, bomEntry.signatureRevision());
		assertEquals("application/pdf", entries.get(2).mediaType());
		assertEquals("bb22", entries.get(2).checksums().get(0).algValue());
		assertEquals("https://example.com/notes.html", entries.get(3).url());
		assertEquals(TeaArtifactType.VULNERABILITIES, entries.get(4).teaType());
		assertEquals(4, entries.get(4).revision());
		JsonNode doc = Utils.OM.readTree(r.collection().getDocument());
		assertEquals("https://example.com/notes.html", doc.get("artifacts").get(3).get("formats").get(0).get("url").asText());
	}

	// ---------------------------------------------------------------- task TEA-19: the external url rule

	/** An external RELEASE_NOTES row with these links in order and, when {@code sha256} is set, a declared digest. */
	private UUID externalRow(String name, String sha256, String... links) {
		UUID id = artifact(ArtifactType.RELEASE_NOTES, name, "1", StoredIn.EXTERNALLY, false, null);
		List<Link> l = new ArrayList<>();
		for (String uri : links) {
			Link link = new Link();
			link.setUri(uri);
			l.add(link);
		}
		artifacts.get(id).setDownloadLinks(l);
		if (null != sha256) {
			artifacts.get(id).getDigestRecords().add(new DigestRecord(DigestAlgorithm.SHA_256, sha256, DigestScope.ORIGINAL_FILE));
		}
		return id;
	}

	private void setLinks(UUID artifact, String uri) {
		Link link = new Link();
		link.setUri(uri);
		artifacts.get(artifact).setDownloadLinks(new ArrayList<>(List.of(link)));
	}

	@Test
	void aRideAlongWhoseLinkIsNotAnHttpUrlIsSkippedAndNothingOfItIsFrozen() throws Exception {
		captureServiceWarnings();
		String tooLong = "https://files.example/" + "a".repeat(4075);
		Map<String, String> endings = new java.util.LinkedHashMap<>();
		endings.put("/notes/1.0.html", ": is relative; an absolute http or https url is required");
		endings.put("javascript:alert(1)", ": has a scheme other than http or https");
		endings.put("https://user@files.example/x", ": carries user information before the host, which is never published");
		endings.put(tooLong, ": is 4097 octets, over the 4096 this server publishes");
		endings.put("https://files.example/na\u00efve.md",
				": has a character outside printable US-ASCII at index 24 (U+00EF); percent-encode it");
		Map<UUID, String> bad = new java.util.LinkedHashMap<>();
		int n = 0;
		for (String url : endings.keySet()) bad.put(externalRow("bad-" + (n++) + ".html", "cc3" + n, url), url);
		UUID good = externalRow("notes.html", "cc33", "https://example.com/notes.html");
		List<UUID> inventory = new ArrayList<>(List.of(inventoryBom, good));
		inventory.addAll(bad.keySet());
		setInventory(release, inventory);
		profile.setRawArtifacts(TeaRawArtifacts.ALL);

		TeaPublishResult r = publish(TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		assertEquals(TeaRideAlongStatus.PUBLISHED, statuses(r).get(good));
		for (Map.Entry<UUID, String> e : bad.entrySet()) {
			var o = r.artifacts().stream().filter(a -> e.getKey().equals(a.artifact())).toList();
			assertEquals(1, o.size(), e.getValue());
			assertEquals(TeaRideAlongStatus.SKIPPED_LINK_NOT_HTTP_URL, o.get(0).status());
			assertTrue(o.get(0).detail().startsWith("download link "), o.get(0).detail());
			assertTrue(o.get(0).detail().endsWith(endings.get(e.getValue())), o.get(0).detail());
		}
		List<UUID> entries = r.collection().getEntries().stream().map(TeaPublishedEntry::artifact).toList();
		assertTrue(entries.contains(good));
		bad.keySet().forEach(id -> assertFalse(entries.contains(id), id.toString()));
		String document = r.collection().getDocument();
		bad.values().forEach(url -> assertFalse(document.contains(url), url));
		assertTrue(document.contains("https://example.com/notes.html"));
		List<String> skips = capturedWarnings().stream().filter(w -> w.contains("SKIPPED_LINK_NOT_HTTP_URL")).toList();
		assertEquals(5, skips.size(), skips.toString());
		for (UUID id : bad.keySet()) {
			assertEquals(1, skips.stream().filter(w -> w.contains(id.toString())
					&& w.contains("not published: SKIPPED_LINK_NOT_HTTP_URL")).count(), id.toString());
		}
		for (String w : skips) bad.values().forEach(url -> assertFalse(w.contains(url), w));
	}

	@Test
	void aRideAlongWithABadLinkAndNoChecksumReportsTheLink() throws Exception {
		UUID row = externalRow("rel.html", null, "/rel/notes.html");
		setInventory(release, List.of(inventoryBom, row));
		profile.setRawArtifacts(TeaRawArtifacts.ALL);

		assertEquals(TeaRideAlongStatus.SKIPPED_LINK_NOT_HTTP_URL, statuses(publish(TeaPublishMode.PUBLISH)).get(row));
	}

	@Test
	void theSecondLinkIsNeverTried() throws Exception {
		UUID scriptFirst = externalRow("script-first.html", "ab12", "javascript:alert(1)", "https://files.example/x.md");
		UUID blankFirst = externalRow("blank-first.html", "ab13", "", "https://files.example/x.md");
		setInventory(release, List.of(inventoryBom, scriptFirst, blankFirst));
		profile.setRawArtifacts(TeaRawArtifacts.ALL);

		TeaPublishResult r = publish(TeaPublishMode.PUBLISH);

		assertEquals(TeaRideAlongStatus.SKIPPED_LINK_NOT_HTTP_URL, statuses(r).get(scriptFirst));
		assertEquals(TeaRideAlongStatus.PUBLISHED, statuses(r).get(blankFirst));
		assertEquals("https://files.example/x.md", r.collection().getEntries().stream()
				.filter(e -> blankFirst.equals(e.artifact())).findFirst().orElseThrow().url());
		assertFalse(r.collection().getEntries().stream().anyMatch(e -> scriptFirst.equals(e.artifact())));
	}

	@Test
	void aRepublishDropsARowWhoseLinkNoLongerConforms() throws Exception {
		UUID row = externalRow("drifted.html", "ab14", "https://files.example/x.md");
		setInventory(release, List.of(inventoryBom, row));
		profile.setRawArtifacts(TeaRawArtifacts.ALL);
		TeaPublishResult first = publish(TeaPublishMode.PUBLISH);
		UUID publication = first.publication().getUuid();
		assertEquals(TeaRideAlongStatus.PUBLISHED, statuses(first).get(row));

		setLinks(row, "/x.md");
		TeaPublishResult second = publish(TeaPublishMode.REPUBLISH);

		assertEquals(TeaPublishOutcome.REPUBLISHED, second.outcome());
		assertEquals(2, second.collection().getVersion());
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_REMOVED, second.collection().getUpdateReasonType());
		String comment = second.collection().getUpdateReasonComment();
		assertTrue(comment.contains("removed: ") && comment.contains("drifted.html"), comment);
		assertEquals(TeaRideAlongStatus.SKIPPED_LINK_NOT_HTTP_URL, statuses(second).get(row));
		assertTrue(collectionRows.get(publication + ":1").getDocument().contains("https://files.example/x.md"));
		assertFalse(collectionRows.get(publication + ":2").getDocument().contains("https://files.example/x.md"));

		assertEquals(TeaPublishOutcome.UNCHANGED, publish(TeaPublishMode.REPUBLISH).outcome());

		setLinks(row, "https://files.example/x.md");
		TeaPublishResult third = publish(TeaPublishMode.REPUBLISH);
		assertEquals(3, third.collection().getVersion());
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_ADDED, third.collection().getUpdateReasonType());
	}

	@Test
	void nonBomDropsTheBomsAndKeepsTheVdr() throws Exception {
		rideAlongInventory();
		profile.setRawArtifacts(TeaRawArtifacts.NON_BOM);
		TeaPublishResult r = publish(TeaPublishMode.PUBLISH);
		List<UUID> published = r.collection().getEntries().stream().map(TeaPublishedEntry::artifact).toList();
		assertFalse(published.contains(inventoryBom));
		assertFalse(statuses(r).containsKey(bomNoRaw));
		assertTrue(published.contains(vdr));
	}

	@Test
	void noneKeepsOnlyTheAggregate() throws Exception {
		rideAlongInventory();
		TeaPublishResult r = publish(TeaPublishMode.PUBLISH);
		assertEquals(1, r.collection().getEntries().size());
		assertTrue(r.artifacts().isEmpty());
	}

	// ---------------------------------------------------------------- 12

	@Test
	void addingARideAlongIsArtifactAdded() throws Exception {
		profile.setRawArtifacts(TeaRawArtifacts.ALL);
		publish(TeaPublishMode.PUBLISH);
		UUID pdfNow = artifact(ArtifactType.USER_DOCUMENT, "manual.pdf", "1", StoredIn.REARM, false, null);
		artifacts.get(pdfNow).getDigestRecords().add(new DigestRecord(DigestAlgorithm.SHA_256, "ee55", DigestScope.ORIGINAL_FILE));
		setInventory(release, List.of(inventoryBom, pdfNow));
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_ADDED,
				publish(TeaPublishMode.REPUBLISH).collection().getUpdateReasonType());
	}

	@Test
	void droppingARideAlongIsArtifactRemoved() throws Exception {
		profile.setRawArtifacts(TeaRawArtifacts.ALL);
		publish(TeaPublishMode.PUBLISH);
		setInventory(release, List.of());
		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_REMOVED,
				publish(TeaPublishMode.REPUBLISH).collection().getUpdateReasonType());
	}

	@Test
	void bumpingAVdrRevisionIsVexUpdated() throws Exception {
		profile.setRawArtifacts(TeaRawArtifacts.ALL);
		UUID theVdr = artifact(ArtifactType.VDR, "vdr.json", "1", StoredIn.REARM, true, "dd44");
		setInventory(release, List.of(inventoryBom, theVdr));
		publish(TeaPublishMode.PUBLISH);
		artifacts.get(theVdr).setVersion("2");
		assertEquals(TeaCollectionUpdateReasonType.VEX_UPDATED,
				publish(TeaPublishMode.REPUBLISH).collection().getUpdateReasonType());
	}

	@Test
	void addedRemovedAndAggregateChangedIsArtifactRemovedNamingAllThree() throws Exception {
		profile.setRawArtifacts(TeaRawArtifacts.ALL);
		UUID gone = artifact(ArtifactType.USER_DOCUMENT, "gone.pdf", "1", StoredIn.REARM, false, null);
		artifacts.get(gone).getDigestRecords().add(new DigestRecord(DigestAlgorithm.SHA_256, "ff66", DigestScope.ORIGINAL_FILE));
		setInventory(release, List.of(gone));
		publish(TeaPublishMode.PUBLISH);
		UUID fresh = artifact(ArtifactType.USER_DOCUMENT, "fresh.pdf", "1", StoredIn.REARM, false, null);
		artifacts.get(fresh).getDigestRecords().add(new DigestRecord(DigestAlgorithm.SHA_256, "aa77", DigestScope.ORIGINAL_FILE));
		setInventory(release, List.of(fresh));
		documents.put(release.getUuid(), bom("pkg:generic/z@9"));

		TeaPublishedCollectionData c = publish(TeaPublishMode.REPUBLISH).collection();

		assertEquals(TeaCollectionUpdateReasonType.ARTIFACT_REMOVED, c.getUpdateReasonType());
		String comment = c.getUpdateReasonComment();
		assertTrue(comment.contains("revision 1 -> 2") && comment.contains("added: fresh.pdf")
				&& comment.contains("removed: gone.pdf"), comment);
	}

	@Test
	void aReplacedBomRowIsTheOldRemovedAndTheNewAdded() throws Exception {
		profile.setRawArtifacts(TeaRawArtifacts.ALL);
		publish(TeaPublishMode.PUBLISH);
		UUID oldTea = teaIds.get(inventoryBom);
		UUID replacement = artifact(ArtifactType.BOM, "pump.cdx.json", "1", StoredIn.REARM, true, "aa88");
		setInventory(release, List.of(replacement));

		TeaPublishedCollectionData c = publish(TeaPublishMode.REPUBLISH).collection();

		String comment = c.getUpdateReasonComment();
		assertTrue(comment.contains("removed: pump.cdx.json (" + oldTea + ")"), comment);
		assertTrue(comment.contains("added: pump.cdx.json (" + teaIds.get(replacement) + ")"), comment);
		assertNotEquals(oldTea, teaIds.get(replacement));
	}

	// ---------------------------------------------------------------- 13 and 22

	@Test
	void theActTakesTheLeaseBeforeItReadsTheHead() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		InOrder order = inOrder(teaPublishLock, publications);
		order.verify(teaPublishLock).acquire(eq(release.getUuid()), any());
		order.verify(publications, Mockito.calls(1)).findByRelease(release.getUuid());
	}

	@Test
	void aHeadReReadAtVersionThreeMakesTheNewRowVersionFour() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		TeaPublication h = heads.values().iterator().next();
		// another publisher committed versions 2 and 3 meanwhile
		TeaPublishedCollection v1 = collectionRows.get(h.getUuid() + ":1");
		for (int v = 2; v <= 3; v++) {
			TeaPublishedCollection c = new TeaPublishedCollection();
			c.setUuid(h.getUuid());
			c.setVersion(v);
			c.setRecordData(v1.getRecordData());
			c.setDocument(v1.getDocument());
			c.setDocumentSha256(v1.getDocumentSha256());
			collectionRows.put(h.getUuid() + ":" + v, c);
		}
		h.setLatestVersion(3);
		TeaPublicationData hd = TeaPublicationData.dataFromRecord(h);
		hd.setLatestVersion(3);
		h.setRecordData(hd.toRecordData());
		documents.put(release.getUuid(), bom("pkg:generic/q@1"));

		assertEquals(4, publish(TeaPublishMode.REPUBLISH).collection().getVersion());
	}

	@Test
	void theServiceNeverSavesAnExistingCollectionRow() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		publish(TeaPublishMode.REPUBLISH);
		documents.put(release.getUuid(), bom("pkg:generic/b@1"));
		publish(TeaPublishMode.REPUBLISH);
		service.hide(release.getUuid(), null, WU);
		publish(TeaPublishMode.REPUBLISH);
		// the in-memory repository throws on a second save of one (uuid, version); two saves, two keys
		ArgumentCaptor<TeaPublishedCollection> saved = ArgumentCaptor.forClass(TeaPublishedCollection.class);
		verify(collections, times(2)).save(saved.capture());
		assertEquals(List.of(1, 2), saved.getAllValues().stream().map(TeaPublishedCollection::getVersion).toList());
	}

	// Cases 14 and 15 (the count seam and the exposure) are in TeaPublicationExposureTest.

	// ---------------------------------------------------------------- 16

	@Test
	void theTeiFollowsTheProfileOnEveryPublish() throws Exception {
		profile.setTei(TeaTei.UUID);
		profile.setTeiDomain("products.example.com");
		publish(TeaPublishMode.PUBLISH);
		assertEquals("tei://products.example.com/uuid/" + head().getUuid(), head().getTei());

		profile.setTeiDomain("other.example.com");
		publish(TeaPublishMode.REPUBLISH);
		assertEquals("tei://other.example.com/uuid/" + head().getUuid(), head().getTei());

		profile.setTei(TeaTei.DISABLED);
		profile.setTeiDomain(null);
		publish(TeaPublishMode.REPUBLISH);
		assertNull(head().getTei());
	}

	// ---------------------------------------------------------------- 17

	@Test
	void thePreviewRunsTheGatesWithoutWriting() throws Exception {
		TeaPublishPreview before = service.preview(release.getUuid());
		assertTrue(before.canPublish());
		assertEquals(TeaCollectionBelongsToType.COMPONENT_RELEASE, before.kind());
		assertEquals(TeaVisibility.PRIVATE, before.effectiveVisibility());
		assertNull(before.publicUrl());
		assertEquals(API_BASE, before.apiBase());
		assertNothingWritten();

		publish(TeaPublishMode.PUBLISH);
		TeaPublishPreview after = service.preview(release.getUuid());
		assertEquals(TeaPublishRefusal.ALREADY_PUBLISHED, after.refusal());
		assertEquals(API_BASE + "/v1.0.0/componentRelease/" + head().getUuid(), after.publicUrl());
		assertEquals(1, after.collections().size());

		ReleaseData draft = release(component, ReleaseLifecycle.DRAFT, List.of(inventoryBom));
		TeaPublishPreview d = service.preview(draft.getUuid());
		assertFalse(d.canPublish());
		assertEquals(TeaPublishRefusal.LIFECYCLE_TOO_LOW, d.refusal());
		assertNotNull(d.refusalMessage());
	}

	// the effective publishing switch is served on its own: an earlier refusal (ALREADY_PUBLISHED) hides
	// PUBLISHING_DISABLED, so the release page reads the switch from here
	@Test
	void thePreviewServesThePublishingSwitchWhateverTheRefusal() throws Exception {
		assertEquals(TeaPublishing.ENABLED, service.preview(release.getUuid()).publishing());
		publish(TeaPublishMode.PUBLISH);
		profile.setPublishing(TeaPublishing.DISABLED);
		TeaPublishPreview off = service.preview(release.getUuid());
		assertEquals(TeaPublishRefusal.ALREADY_PUBLISHED, off.refusal());
		assertEquals(TeaPublishing.DISABLED, off.publishing());
		assertNull(service.preview(UUID.randomUUID()).publishing(), "an unknown release resolves no profile");
	}

	// ---------------------------------------------------------------- the cascade (3.7)

	@Test
	void aProductCascadesToItsComponentReleasesUnderItsProfile() throws Exception {
		ComponentData productCd = component("platform", ComponentType.PRODUCT);
		ComponentData other = component("valve", ComponentType.COMPONENT);
		ReleaseData childA = release;
		ReleaseData childB = release(other, ReleaseLifecycle.ASSEMBLED, List.of(inventoryBom));
		documents.put(childB.getUuid(), bom("pkg:generic/v@1"));
		ReleaseData product = release(productCd, ReleaseLifecycle.ASSEMBLED, List.of());
		Release pr = releaseRows.get(product.getUuid());
		ReleaseData prd = ReleaseData.dataFromRecord(pr);
		prd.setParentReleases(new LinkedList<>(List.of(ParentRelease.minimalParentReleaseFactory(childA.getUuid()),
				ParentRelease.minimalParentReleaseFactory(childB.getUuid()))));
		pr.setRecordData(Utils.dataToRecord(prd));
		documents.put(product.getUuid(), bom("pkg:generic/a@1", "pkg:generic/v@1"));
		resolutions.put(productCd.getUuid(), resolvedFromOrg(profile));
		resolutions.put(other.getUuid(), resolvedFromOrg(profile));

		TeaPublishResult r = service.publish(new TeaPublishRequest(product.getUuid(), TeaPublishMode.PUBLISH, null, WU));

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		assertEquals(TeaCollectionBelongsToType.PRODUCT_RELEASE, r.collection().getBelongsTo());
		assertEquals(List.of(TeaChildOutcome.PUBLISHED, TeaChildOutcome.PUBLISHED),
				r.children().stream().map(c -> c.outcome()).toList());
		UUID productPub = r.publication().getUuid();
		assertEquals(productPub, service.getPublicationOfRelease(childA.getUuid()).orElseThrow().getCascadeOf());
		assertEquals(productPub, service.getPublicationOfRelease(childB.getUuid()).orElseThrow().getCascadeOf());
		// the product once for the knobs, each child once for the gate (3.7 step e)
		verify(resolver, times(1)).resolveForComponent(productCd);
		verify(resolver, times(1)).resolveForComponent(component);
		verify(resolver, times(1)).resolveForComponent(other);
		verify(hook, times(1)).onReleaseTeaPublished(any(), any(), anyInt(), any(), eq(2));
		assertEquals(1, teaEvents(childA.getUuid()).size());

		setLifecycle(childA, ReleaseLifecycle.DRAFT);
		TeaPublishResult again = service.publish(new TeaPublishRequest(product.getUuid(), TeaPublishMode.REPUBLISH,
				null, WU));
		assertEquals(TeaChildOutcome.REFUSED, again.children().get(0).outcome());
		assertEquals(TeaPublishRefusal.LIFECYCLE_TOO_LOW, again.children().get(0).refusal());
		assertEquals(TeaChildOutcome.UNCHANGED, again.children().get(1).outcome());
	}

	@Test
	void aDirectlyPublishedOrHiddenChildIsSkippedAndProductOnlyPublishesNoChild() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		ComponentData productCd = component("platform", ComponentType.PRODUCT);
		ComponentData other = component("valve", ComponentType.COMPONENT);
		resolutions.put(other.getUuid(), resolvedFromOrg(profile));
		ReleaseData hiddenChild = release(other, ReleaseLifecycle.ASSEMBLED, List.of(inventoryBom));
		documents.put(hiddenChild.getUuid(), bom("pkg:generic/v@1"));
		service.publish(new TeaPublishRequest(hiddenChild.getUuid(), TeaPublishMode.PUBLISH, null, WU));
		service.hide(hiddenChild.getUuid(), null, WU);
		ReleaseData product = release(productCd, ReleaseLifecycle.ASSEMBLED, List.of());
		Release pr = releaseRows.get(product.getUuid());
		ReleaseData prd = ReleaseData.dataFromRecord(pr);
		prd.setParentReleases(new LinkedList<>(List.of(ParentRelease.minimalParentReleaseFactory(release.getUuid()),
				ParentRelease.minimalParentReleaseFactory(hiddenChild.getUuid()))));
		pr.setRecordData(Utils.dataToRecord(prd));
		documents.put(product.getUuid(), bom("pkg:generic/a@1"));
		resolutions.put(productCd.getUuid(), resolvedFromOrg(profile));

		TeaPublishResult r = service.publish(new TeaPublishRequest(product.getUuid(), TeaPublishMode.PUBLISH, null, WU));
		assertEquals(List.of(TeaChildOutcome.SKIPPED_DIRECT, TeaChildOutcome.SKIPPED_HIDDEN),
				r.children().stream().map(c -> c.outcome()).toList());

		TeaProfileData productOnly = profile(TeaVisibility.PRIVATE);
		productOnly.setProductComponents(TeaProductComponents.PRODUCT_ONLY);
		ReleaseData lone = release(productCd, ReleaseLifecycle.ASSEMBLED, List.of());
		Release lr = releaseRows.get(lone.getUuid());
		ReleaseData lrd = ReleaseData.dataFromRecord(lr);
		ReleaseData fresh = release(other, ReleaseLifecycle.ASSEMBLED, List.of(inventoryBom));
		lrd.setParentReleases(new LinkedList<>(List.of(ParentRelease.minimalParentReleaseFactory(fresh.getUuid()))));
		lr.setRecordData(Utils.dataToRecord(lrd));
		documents.put(lone.getUuid(), bom("pkg:generic/a@1"));
		resolutions.put(productCd.getUuid(), resolvedFromOrg(productOnly));
		TeaPublishResult solo = service.publish(new TeaPublishRequest(lone.getUuid(), TeaPublishMode.PUBLISH, null, WU));
		assertTrue(solo.children().isEmpty());
		assertTrue(service.getPublicationOfRelease(fresh.getUuid()).isEmpty());
		verify(componentService, times(3)).ensureTeaUuid(other.getUuid(), WU);
	}

	@Test
	void aFailingProductWritesNothingAnywhere() throws Exception {
		ComponentData productCd = component("platform", ComponentType.PRODUCT);
		ReleaseData product = release(productCd, ReleaseLifecycle.ASSEMBLED, List.of());
		resolutions.put(productCd.getUuid(), resolvedFromOrg(profile));
		when(releaseService.generateMergedBomForPublication(any(), any(), any())).thenThrow(
				MergedSbomUnavailableException.childMergeFailed(product, productCd, List.of(new ChildBomOutcome(
						release.getUuid(), "pump", "1.2.3", ChildBomStatus.FAILED, null, "x"))));
		assertThrows(TeaPublishRefusedException.class, () -> service.publish(new TeaPublishRequest(product.getUuid(),
				TeaPublishMode.PUBLISH, null, WU)));
		assertNothingWritten();
	}

	@Test
	void aRideAlongWithAMediaTypeTagMissingApplicationGetsIt() {
		ArtifactData ad = artifacts.get(inventoryBom);
		ad.setBomFormat(ArtifactData.BomFormat.CYCLONEDX);
		ad.getTags().add(new TagRecord(CommonVariables.MEDIA_TYPE_FIELD, "application/json", Removable.NO));
		assertEquals("application/vnd.cyclonedx+json", TeaPublicationService.mediaTypeOf(ad));
		ArtifactData none = artifacts.get(artifact(ArtifactType.OTHER, "x", "1", StoredIn.REARM, false, null));
		assertEquals("application/octet-stream", TeaPublicationService.mediaTypeOf(none));
	}

	@Test
	void theAggregateDtoIsDoneWithinTheWriterAndNeverDirect() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		verify(artifactService, never()).uploadArtifact(any(), any(), any(), any(), any());
		verify(releaseService, never()).addArtifact(any(), any(), any());
	}

}
