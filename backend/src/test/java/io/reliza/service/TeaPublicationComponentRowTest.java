/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaPublication;
import io.reliza.service.TeaPublicationService.TeaChildOutcome;
import io.reliza.service.TeaPublicationService.TeaChildPublishResult;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishResult;
import io.reliza.service.oss.OssReleaseService.AcollectionMode;

/**
 * TEA-5 round 4, the component row an act judges (T-10 points 3, 6 and 7): case 93's unit half
 * and the mint failures of case 94. The act reads the component through its repository and
 * refreshes it after the release, never through a cached read; the publish commit judges the two
 * archive gates again on the rows it re-reads, before any write, and a hide never does; a mint
 * that fails is GENERATION_FAILED naming the component, before anything is generated.
 */
class TeaPublicationComponentRowTest extends TeaPublicationServiceTestBase {

	/** The cached read answers a stale ACTIVE copy; the row, once refreshed, is ARCHIVED. */
	private void componentRowRefreshesToArchived() {
		ComponentData stale = ComponentData.dataFromRecord(componentRow(component));
		when(getComponentService.getComponentData(component.getUuid())).thenReturn(Optional.of(stale));
		doAnswer(inv -> {
			Component c = inv.getArgument(0);
			ComponentData cd = ComponentData.dataFromRecord(c);
			cd.setStatus(StatusEnum.ARCHIVED);
			c.setRecordData(Utils.dataToRecord(cd));
			return null;
		}).when(entityManager).refresh(any(Component.class));
	}

	/** While the merge runs, another writer archives the component (or the release). */
	private void archivedDuringTheMerge(Runnable archive) throws Exception {
		when(releaseService.generateMergedBomForPublication(any(ReleaseData.class), any(ReleaseService.PublicationMergeRequest.class),
				any())).thenAnswer(inv -> {
					archive.run();
					return documents.get(inv.<ReleaseData>getArgument(0).getUuid()).deepCopy();
				});
	}

	private UUID uploadedAggregate() {
		return artifacts.keySet().stream().filter(id -> !id.equals(inventoryBom)).findFirst().orElseThrow();
	}

	private void assertNoCommitWrite() {
		verify(collections, never()).save(any());
		verify(publications, never()).save(any());
		verify(oss, never()).saveRelease(any(Release.class), any(ReleaseData.class), any(), anyBoolean(),
				any(AcollectionMode.class));
		verify(hook, never()).onReleaseTeaPublished(any(), any(), Mockito.anyInt(), any(), Mockito.anyInt());
	}

	// ---------------------------------------------------------------- 93, the gate

	@Test
	void readFreshLoadsTheComponentRowAndRefreshesItAfterTheRelease() throws Exception {
		assertEquals(TeaPublishOutcome.PUBLISHED, publish(TeaPublishMode.PUBLISH).outcome());

		InOrder order = Mockito.inOrder(shared, entityManager, componentRepository);
		order.verify(shared).getRelease(release.getUuid());
		order.verify(entityManager).refresh(any(Release.class));
		order.verify(componentRepository).findById(component.getUuid());
		order.verify(entityManager).refresh(any(Component.class));
		// once after the lease and once in the commit
		verify(componentRepository, times(2)).findById(component.getUuid());
		verify(entityManager, times(2)).refresh(any(Component.class));
	}

	@Test
	void theGateJudgesTheRefreshedComponentNeverACachedRead() throws Exception {
		componentRowRefreshesToArchived();

		assertEquals(TeaPublishRefusal.COMPONENT_ARCHIVED, refused(TeaPublishMode.PUBLISH).reason());

		verify(getComponentService, never()).getComponentData(component.getUuid());
		verify(componentService, never()).ensureTeaUuid(any(), any());
		assertNothingWritten();
	}

	@Test
	void aMissingComponentRowIsReleaseNotFound() throws Exception {
		when(componentRepository.findById(component.getUuid())).thenReturn(Optional.empty());

		var e = refused(TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishRefusal.RELEASE_NOT_FOUND, e.reason());
		assertTrue(e.getMessage().contains("Component " + component.getUuid()), e.getMessage());
		assertNothingWritten();
	}

	// ---------------------------------------------------------------- 93, the commit's re-check

	@Test
	void aComponentArchivedDuringTheMergeIsRefusedByTheCommitBeforeAnyWrite() throws Exception {
		archivedDuringTheMerge(() -> components.get(component.getUuid()).setStatus(StatusEnum.ARCHIVED));

		var e = refused(TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishRefusal.COMPONENT_ARCHIVED, e.reason(), e.getMessage());
		assertNoCommitWrite();
		verify(artifactService, times(1)).archiveArtifact(uploadedAggregate(), WU);
		assertTrue(heads.isEmpty());
	}

	@Test
	void aReleaseArchivedDuringTheMergeIsRefusedByTheCommitBeforeAnyWrite() throws Exception {
		archivedDuringTheMerge(() -> setStatus(release, ReleaseStatus.ARCHIVED));

		var e = refused(TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishRefusal.RELEASE_ARCHIVED, e.reason(), e.getMessage());
		assertNoCommitWrite();
		verify(artifactService, times(1)).archiveArtifact(uploadedAggregate(), WU);
		assertTrue(heads.isEmpty());
	}

	@Test
	void aRepublishWhoseComponentIsArchivedDuringTheMergeWritesNoVersion() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		Mockito.clearInvocations(collections, publications, oss, hook);
		documents.put(release.getUuid(), bom("pkg:generic/b@1"));
		archivedDuringTheMerge(() -> components.get(component.getUuid()).setStatus(StatusEnum.ARCHIVED));

		assertEquals(TeaPublishRefusal.COMPONENT_ARCHIVED, refused(TeaPublishMode.REPUBLISH).reason());

		assertNoCommitWrite();
		assertEquals(1, head().getLatestVersion());
	}

	@Test
	void aHideOfAnArchivedComponentOrReleaseHidesAsToday() throws Exception {
		publish(TeaPublishMode.PUBLISH);
		components.get(component.getUuid()).setStatus(StatusEnum.ARCHIVED);
		setStatus(release, ReleaseStatus.ARCHIVED);
		Mockito.clearInvocations(publications, oss);

		var hidden = service.hide(release.getUuid(), null, WU);

		assertEquals(TeaPublicationState.HIDDEN, hidden.getState());
		verify(publications, times(1)).save(any(TeaPublication.class));
		verify(oss, times(1)).saveRelease(any(Release.class), any(ReleaseData.class), any(), anyBoolean(),
				any(AcollectionMode.class));
		assertEquals(ReleaseUpdateAction.REMOVED, teaEvents(release.getUuid()).get(teaEvents(release.getUuid()).size() - 1)
				.rua());
	}

	// ---------------------------------------------------------------- 94, a mint that fails

	@Test
	void aComponentMintFailureAtStepFiveIsGenerationFailedBeforeAnythingIsGenerated() throws Exception {
		when(componentService.ensureTeaUuid(eq(component.getUuid()), any())).thenThrow(new RelizaException(
				"the TEA id of component " + component.getUuid() + " could not be assigned: duplicate key"));

		var e = refused(TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishRefusal.GENERATION_FAILED, e.reason());
		assertTrue(e.getMessage().contains("Release pump 1.2.3 (" + release.getUuid() + "): the TEA id of component "
				+ component.getUuid() + " could not be assigned: "), e.getMessage());
		verify(releaseService, never()).generateMergedBomForPublication(any(), any(), any());
		assertNothingWritten();
	}

	@Test
	void anOrganizationMintFailureAtStepFiveIsGenerationFailedBeforeAnythingIsGenerated() throws Exception {
		when(organizationService.ensureTeaUuid(eq(ORG), any())).thenThrow(new RelizaException("boom"));

		var e = refused(TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishRefusal.GENERATION_FAILED, e.reason());
		assertTrue(e.getMessage().contains("the TEA id of organization " + ORG + " could not be assigned: boom"),
				e.getMessage());
		verify(componentService, never()).ensureTeaUuid(any(), any());
		verify(releaseService, never()).generateMergedBomForPublication(any(), any(), any());
		assertNothingWritten();
	}

	@Test
	void aChildMintFailureRefusesTheChildAndTheProductStands() throws Exception {
		TeaProfileData p = profile(TeaVisibility.PRIVATE);
		p.setProductComponents(TeaProductComponents.PUBLISH_WITH_PRODUCT);
		ReleaseData product = productOver("bundle", resolvedFromOrg(p), List.of(release.getUuid()));
		when(componentService.ensureTeaUuid(eq(component.getUuid()), any()))
				.thenThrow(new RelizaException("Component not found: " + component.getUuid()));

		TeaPublishResult r = publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		TeaChildPublishResult child = r.children().get(0);
		assertEquals(TeaChildOutcome.REFUSED, child.outcome());
		assertEquals(TeaPublishRefusal.GENERATION_FAILED, child.refusal());
		assertTrue(child.detail().contains("the TEA id of component " + component.getUuid() + " could not be assigned"),
				child.detail());
		assertNull(child.publication());
		verify(releaseService, never()).generateMergedBomForPublication(
				Mockito.argThat(rd -> null != rd && release.getUuid().equals(rd.getUuid())), any(), any());
		assertTrue(service.getPublicationOfRelease(release.getUuid()).isEmpty());
	}

	@Test
	void aChildMintFailureUnderProductOnlyRefusesTheChildToo() throws Exception {
		TeaProfileData p = profile(TeaVisibility.PRIVATE);
		p.setProductComponents(TeaProductComponents.PRODUCT_ONLY);
		ComponentData other = component("valve", ComponentType.COMPONENT);
		ReleaseData second = release(other, release.getLifecycle(), List.of());
		ReleaseData product = productOver("bundle", resolvedFromOrg(p), List.of(release.getUuid(), second.getUuid()));
		when(componentService.ensureTeaUuid(eq(component.getUuid()), any())).thenThrow(new RelizaException("boom"));

		TeaPublishResult r = publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		assertEquals(1, r.children().size(), r.children().toString());
		assertEquals(TeaPublishRefusal.GENERATION_FAILED, r.children().get(0).refusal());
		verify(componentService).ensureTeaUuid(eq(other.getUuid()), any());
	}
}
