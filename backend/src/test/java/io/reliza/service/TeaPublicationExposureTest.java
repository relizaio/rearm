/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaPublication;
import io.reliza.model.TeaPublicationData;
import io.reliza.service.TeaProfileResolver.TeaProfileSource;
import io.reliza.service.TeaProfileResolver.TeaResolutionStatus;
import io.reliza.service.TeaProfileResolver.TeaResolvedProfile;
import io.reliza.service.TeaPublicationService.TeaConcealReason;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.TeaPublicationService.TeaPublicationExposure;
import io.reliza.service.TeaPublicationService.TeaPublicationState;

/**
 * TEA-5, cases 14 and 15 as amended by round 2's 66 and 67: exposure at request time (3.9), one
 * reason per row, the owner cap of a cascade child, and the count seam.
 */
class TeaPublicationExposureTest extends TeaPublicationServiceTestBase {

	private TeaPublicationExposure exposure(UUID publication) {
		return service.exposureOf(data(publication));
	}

	private void assertConcealed(UUID publication, TeaConcealReason reason) {
		TeaPublicationExposure e = exposure(publication);
		assertEquals(TeaExposureState.CONCEALED, e.state());
		assertEquals(reason, e.concealedBecause());
	}

	private static TeaProfileData disabled() {
		TeaProfileData d = profile(TeaVisibility.PUBLIC);
		d.setPublishing(TeaPublishing.DISABLED);
		return d;
	}

	private void hideHead(UUID publication) {
		TeaPublication p = heads.get(publication);
		TeaPublicationData d = TeaPublicationData.dataFromRecord(p);
		d.setState(TeaPublicationState.HIDDEN);
		p.setState(TeaPublicationState.HIDDEN);
		p.setRecordData(d.toRecordData());
	}

	private ReleaseData releaseOf(UUID publication) {
		return ReleaseData.dataFromRecord(releaseRows.get(heads.get(publication).getRelease()));
	}

	// ---------------------------------------------------------------- 66, the rows of 3.9

	@Test
	void aDirectPublicationFollowsItsOwnResolutionPassingTheRecordedRow() {
		resolutions.put(component.getUuid(), resolvedFromOrg(profile(TeaVisibility.PUBLIC)));
		UUID direct = seedHead(component, null, TeaPublicationState.PUBLISHED);

		TeaPublicationExposure e = exposure(direct);

		assertEquals(TeaExposureState.PUBLIC, e.state());
		assertNull(e.concealedBecause());
		assertEquals(component.getUuid(), e.governingComponent().getUuid());
		assertNull(e.cappedBy());
		verify(resolver).resolveForComponent(component, profile.getUuid());
		resolutions.put(component.getUuid(), resolvedFromOrg(profile(TeaVisibility.PRIVATE)));
		assertEquals(TeaExposureState.PRIVATE, exposure(direct).state());
	}

	@Test
	void hiddenMissingAndArchivedAreConcealedEachWithItsReason() {
		UUID hidden = seedHead(component, null, TeaPublicationState.HIDDEN);
		assertConcealed(hidden, TeaConcealReason.HIDDEN);

		UUID gone = seedHead(component, UUID.randomUUID(), null, TeaPublicationState.PUBLISHED);
		assertConcealed(gone, TeaConcealReason.RELEASE_MISSING);

		UUID archived = seedHead(component, null, TeaPublicationState.PUBLISHED);
		setStatus(releaseOf(archived), ReleaseStatus.ARCHIVED);
		assertConcealed(archived, TeaConcealReason.RELEASE_ARCHIVED);

		ComponentData foreign = component("foreign", ComponentType.COMPONENT);
		foreign.setOrg(UUID.randomUUID());
		assertConcealed(seedHead(foreign, null, TeaPublicationState.PUBLISHED), TeaConcealReason.COMPONENT_MISSING);
		ComponentData vanished = component("vanished", ComponentType.COMPONENT);
		UUID orphanComponent = seedHead(vanished, null, TeaPublicationState.PUBLISHED);
		components.remove(vanished.getUuid());
		assertConcealed(orphanComponent, TeaConcealReason.COMPONENT_MISSING);
	}

	@ParameterizedTest
	@EnumSource(value = ReleaseLifecycle.class, names = { "CANCELLED", "REJECTED", "PENDING", "DRAFT" })
	void aReleaseBelowAssembledIsConcealedWithoutARowWritten(ReleaseLifecycle lc) {
		UUID direct = seedHead(component, null, TeaPublicationState.PUBLISHED);
		setLifecycle(releaseOf(direct), lc);

		assertConcealed(direct, TeaConcealReason.LIFECYCLE_BELOW_ASSEMBLED);
		assertEquals(TeaPublicationState.PUBLISHED, data(direct).getState());
		verify(publications, never()).save(any());
	}

	@ParameterizedTest
	@EnumSource(value = ReleaseLifecycle.class, names = { "ASSEMBLED", "READY_TO_SHIP", "END_OF_LIFE" })
	void assembledAndAboveAreServed(ReleaseLifecycle lc) {
		UUID direct = seedHead(component, null, TeaPublicationState.PUBLISHED);
		setLifecycle(releaseOf(direct), lc);
		assertEquals(TeaExposureState.PRIVATE, exposure(direct).state());
	}

	@Test
	void aConflictOrDisabledOwnResolutionIsConcealed() {
		UUID direct = seedHead(component, null, TeaPublicationState.PUBLISHED);
		resolutions.put(component.getUuid(), new TeaResolvedProfile(TeaResolutionStatus.CONFLICT,
				TeaProfileSource.PERSPECTIVE, null, null, List.of()));
		assertConcealed(direct, TeaConcealReason.PROFILE_CONFLICT);
		resolutions.put(component.getUuid(), resolvedFromOrg(disabled()));
		assertConcealed(direct, TeaConcealReason.PUBLISHING_DISABLED);
	}

	@Test
	void aCascadeChildWhoseOwnerIsMissingForeignHiddenOrCancelledIsConcealed() {
		ComponentData product = component("p", ComponentType.PRODUCT);
		resolutions.put(product.getUuid(), resolvedFromOrg(profile(TeaVisibility.PUBLIC)));

		UUID orphan = seedHead(component, UUID.randomUUID(), TeaPublicationState.PUBLISHED);
		assertConcealed(orphan, TeaConcealReason.PRODUCT_MISSING);

		UUID foreignOwner = seedHead(product, null, TeaPublicationState.PUBLISHED);
		TeaPublication fo = heads.get(foreignOwner);
		TeaPublicationData fod = TeaPublicationData.dataFromRecord(fo);
		fod.setOrg(UUID.randomUUID());
		fo.setOrg(fod.getOrg());
		fo.setRecordData(fod.toRecordData());
		assertConcealed(seedHead(component, foreignOwner, TeaPublicationState.PUBLISHED), TeaConcealReason.PRODUCT_MISSING);

		UUID hiddenOwner = seedHead(product, null, TeaPublicationState.PUBLISHED);
		UUID underHidden = seedHead(component, hiddenOwner, TeaPublicationState.PUBLISHED);
		hideHead(hiddenOwner);
		TeaPublicationExposure e = exposure(underHidden);
		assertEquals(TeaConcealReason.PRODUCT_CONCEALED, e.concealedBecause());
		assertEquals(hiddenOwner, e.cappedBy().getUuid());

		UUID cancelledOwner = seedHead(product, null, TeaPublicationState.PUBLISHED);
		setLifecycle(releaseOf(cancelledOwner), ReleaseLifecycle.CANCELLED);
		assertConcealed(seedHead(component, cancelledOwner, TeaPublicationState.PUBLISHED),
				TeaConcealReason.PRODUCT_CONCEALED);
	}

	@Test
	void theOwnerCapIsTheNarrowerOfTheChildAndItsOwner() {
		ComponentData product = component("p", ComponentType.PRODUCT);
		UUID owner = seedHead(product, null, TeaPublicationState.PUBLISHED);
		UUID child = seedHead(component, owner, TeaPublicationState.PUBLISHED);

		// own PUBLIC under owner PUBLIC: PUBLIC
		resolutions.put(product.getUuid(), resolvedFromOrg(profile(TeaVisibility.PUBLIC)));
		TeaResolvedProfile ownPublic = resolvedFromOrg(profile(TeaVisibility.PUBLIC));
		resolutions.put(component.getUuid(), ownPublic);
		TeaPublicationExposure e = exposure(child);
		assertEquals(TeaExposureState.PUBLIC, e.state());
		assertEquals(component.getUuid(), e.governingComponent().getUuid());
		assertEquals(owner, e.cappedBy().getUuid());
		assertSame(ownPublic, e.resolved());

		// own PUBLIC under owner PRIVATE: PRIVATE, and the resolution handed on says PRIVATE
		resolutions.put(product.getUuid(), resolvedFromOrg(profile(TeaVisibility.PRIVATE)));
		e = exposure(child);
		assertEquals(TeaExposureState.PRIVATE, e.state());
		assertEquals(TeaVisibility.PRIVATE, e.resolved().profile().getVisibility());
		assertEquals(TeaVisibility.PUBLIC, ownPublic.profile().getVisibility(), "the own profile is never touched");
		assertEquals(owner, e.cappedBy().getUuid());
		assertEquals(component.getUuid(), e.governingComponent().getUuid());

		// own PRIVATE under owner PUBLIC: PRIVATE with the own profile untouched
		resolutions.put(product.getUuid(), resolvedFromOrg(profile(TeaVisibility.PUBLIC)));
		TeaResolvedProfile ownPrivate = resolvedFromOrg(profile(TeaVisibility.PRIVATE));
		resolutions.put(component.getUuid(), ownPrivate);
		e = exposure(child);
		assertEquals(TeaExposureState.PRIVATE, e.state());
		assertSame(ownPrivate, e.resolved());
		assertEquals(component.getUuid(), e.governingComponent().getUuid());

		// own DISABLED under owner PUBLIC: concealed by the child's own profile
		resolutions.put(component.getUuid(), resolvedFromOrg(disabled()));
		e = exposure(child);
		assertEquals(TeaConcealReason.PUBLISHING_DISABLED, e.concealedBecause());
		assertEquals(component.getUuid(), e.governingComponent().getUuid());

		// a cascade child passes no recorded row to the resolver
		verify(resolver, never()).resolveForComponent(eq(component), eq(profile.getUuid()));
		verify(resolver, org.mockito.Mockito.atLeastOnce()).resolveForComponent(eq(component), isNull());
	}

	// ---------------------------------------------------------------- 14 and 67, the count seam

	@Test
	void theCountSeamResolvesEachComponentOnceAndCountsEachHeadOnce() throws Exception {
		UUID perspective = UUID.randomUUID();
		ComponentData a = component("a", ComponentType.COMPONENT);
		ComponentData b = component("b", ComponentType.COMPONENT);
		ComponentData product = component("p", ComponentType.PRODUCT);
		ComponentData c = component("c", ComponentType.COMPONENT);
		ComponentData d = component("d", ComponentType.COMPONENT);
		resolutions.put(a.getUuid(), new TeaResolvedProfile(TeaResolutionStatus.RESOLVED, TeaProfileSource.COMPONENT,
				a.getUuid(), profile, List.of()));
		resolutions.put(b.getUuid(), new TeaResolvedProfile(TeaResolutionStatus.RESOLVED, TeaProfileSource.PERSPECTIVE,
				perspective, profile, List.of()));
		resolutions.put(product.getUuid(), resolvedFromOrg(profile));
		resolutions.put(c.getUuid(), resolvedFromOrg(profile));
		resolutions.put(d.getUuid(), new TeaResolvedProfile(TeaResolutionStatus.RESOLVED, TeaProfileSource.COMPONENT,
				d.getUuid(), profile, List.of()));
		UUID productPub = seedHead(product, null, TeaPublicationState.PUBLISHED);
		seedHead(a, null, TeaPublicationState.PUBLISHED);
		seedHead(b, null, TeaPublicationState.PUBLISHED);
		seedHead(c, productPub, TeaPublicationState.PUBLISHED);
		seedHead(d, null, TeaPublicationState.HIDDEN);

		assertEquals(1, service.countPublishedResolvingTo(ORG, TeaProfileScope.COMPONENT, a.getUuid()));
		assertEquals(1, service.countPublishedResolvingTo(ORG, TeaProfileScope.PERSPECTIVE, perspective));
		assertEquals(2, service.countPublishedResolvingTo(ORG, TeaProfileScope.ORGANIZATION, null),
				"the product and C, which resolves to the organization itself and through its owner: once");
		assertEquals(0, service.countPublishedResolvingTo(ORG, TeaProfileScope.COMPONENT, d.getUuid()));
		verify(resolver, never()).resolveForComponent(any(ComponentData.class), any());
	}

	@Test
	void aCascadeChildCountsForItsOwnScopeAndForItsOwnersScope() {
		UUID perspective = UUID.randomUUID();
		ComponentData x = component("x", ComponentType.PRODUCT);
		resolutions.put(x.getUuid(), new TeaResolvedProfile(TeaResolutionStatus.RESOLVED, TeaProfileSource.COMPONENT,
				x.getUuid(), profile, List.of()));
		resolutions.put(component.getUuid(), new TeaResolvedProfile(TeaResolutionStatus.RESOLVED,
				TeaProfileSource.PERSPECTIVE, perspective, profile, List.of()));
		UUID owner = seedHead(x, null, TeaPublicationState.PUBLISHED);
		seedHead(component, owner, TeaPublicationState.PUBLISHED);

		assertEquals(1, service.countPublishedResolvingTo(ORG, TeaProfileScope.PERSPECTIVE, perspective));
		assertEquals(2, service.countPublishedResolvingTo(ORG, TeaProfileScope.COMPONENT, x.getUuid()),
				"the product itself and the child through it");
		verify(resolver, org.mockito.Mockito.times(2)).resolveForComponent(component);
	}
}
