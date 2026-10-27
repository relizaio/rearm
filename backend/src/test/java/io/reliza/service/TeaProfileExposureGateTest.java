/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.model.ComponentData;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.dto.TeaProfileInputDto;
import io.reliza.service.TeaProfileResolver.TeaProfileSource;
import io.reliza.service.TeaProfileResolver.TeaResolvedProfile;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.TeaProfileService.TeaEffectiveVisibility;
import io.reliza.service.TeaProfileService.TeaExposureChange;
import io.reliza.service.TeaProfileService.TeaPublicPath;
import io.reliza.service.TeaProfileService.TeaWidenedComponent;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;

/**
 * Task TEA-9, design 4.2 cases 5 to 10: the exposure assessment of a save or a removal, and the
 * organization-admin gate on every path to PUBLIC (a PUBLIC row, a FOLLOW that resolves PUBLIC, a
 * removal that resolves PUBLIC). Round 2, cases 36 to 40: a membership change, a perspective-row
 * removal over the members and a perspective deletion. The resolver is mocked per case.
 */
class TeaProfileExposureGateTest extends TeaProfileServiceTestBase {

	private static final String ROW_PUBLIC_MESSAGE =
			"Only an organization admin can save a PUBLIC TEA profile; an admin can make it PRIVATE first";

	private TeaProfileInputDto followAlpha() {
		return TeaProfileInputDto.builder().mode(TeaComponentProfileMode.FOLLOW_PERSPECTIVE)
				.followedPerspective(alpha.uuid()).build();
	}

	/**
	 * The component resolves to {@code before} now; with a FOLLOW row to {@code followed}, with an
	 * OVERRIDE row to that row, and without a row to {@code parent}.
	 */
	private void component(TeaResolvedProfile before, TeaResolvedProfile followed, TeaResolvedProfile parent) {
		when(resolver.resolveForComponent(cd)).thenReturn(before);
		when(resolver.resolveForComponentWithRow(eq(cd), any())).thenAnswer(inv -> {
			TeaProfileData row = inv.getArgument(1);
			if (null == row) return parent;
			if (TeaComponentProfileMode.FOLLOW_PERSPECTIVE == row.getMode()) return followed;
			return resolution(TeaProfileSource.COMPONENT, cd.getUuid(), row.getVisibility(), row.getPublishing());
		});
	}

	private TeaResolvedProfile privateRow() {
		return resolution(TeaProfileSource.COMPONENT, cd.getUuid(), TeaVisibility.PRIVATE, TeaPublishing.DISABLED);
	}

	private TeaResolvedProfile publicAlpha() {
		return resolution(TeaProfileSource.FOLLOWED_PERSPECTIVE, alpha.uuid(), TeaVisibility.PUBLIC, TeaPublishing.DISABLED);
	}

	private TeaResolvedProfile publicOrg() {
		return resolution(TeaProfileSource.ORGANIZATION, null, TeaVisibility.PUBLIC, TeaPublishing.DISABLED);
	}

	private void nothingWritten() throws RelizaException {
		verify(repository, never()).save(any());
		verify(repository, never()).delete(any());
		verify(organizationService, never()).ensureTeaUuid(any(), any());
	}

	@Test
	void aFollowThatResolvesPublicNeedsAnOrganizationAdmin() throws RelizaException {
		component(privateRow(), publicAlpha(), defaults());
		TeaExposureChange x = service.assessSave(org, TeaProfileScope.COMPONENT, cd.getUuid(), followAlpha());
		assertEquals(TeaEffectiveVisibility.PRIVATE, x.before());
		assertEquals(TeaEffectiveVisibility.PUBLIC, x.after());
		assertEquals(TeaPublicPath.FOLLOW_RESOLVES_PUBLIC, x.path());
		assertEquals(TeaProfileSource.FOLLOWED_PERSPECTIVE, x.afterSource());
		assertEquals(alpha.uuid(), x.afterSourceObject());
		assertEquals(0, x.publishedReleases());

		String m = refused(TeaProfileScope.COMPONENT, cd.getUuid(), followAlpha(), TeaCaller.SCOPE_ADMIN).getMessage();
		assertEquals("Only an organization admin can make component widget follow perspective Alpha: it resolves to the "
				+ "PUBLIC TEA profile of perspective Alpha", m);
		nothingWritten();

		TeaProfileData saved = save(TeaProfileScope.COMPONENT, cd.getUuid(), followAlpha(), TeaCaller.ORG_ADMIN);
		assertEquals(alpha.uuid(), saved.getFollowedPerspective());
	}

	@Test
	void aFollowIsGatedOnlyWhenItWidens() throws RelizaException {
		component(publicAlpha(), publicAlpha(), defaults());
		assertEquals(TeaPublicPath.NONE,
				service.assessSave(org, TeaProfileScope.COMPONENT, cd.getUuid(), followAlpha()).path());
		save(TeaProfileScope.COMPONENT, cd.getUuid(), followAlpha(), TeaCaller.SCOPE_ADMIN);

		component(conflict(), publicAlpha(), conflict());
		TeaExposureChange fromConflict = service.assessSave(org, TeaProfileScope.COMPONENT, cd.getUuid(), followAlpha());
		assertEquals(TeaEffectiveVisibility.UNRESOLVED, fromConflict.before());
		assertEquals(TeaPublicPath.FOLLOW_RESOLVES_PUBLIC, fromConflict.path());

		component(privateRow(), resolution(TeaProfileSource.FOLLOWED_PERSPECTIVE, alpha.uuid(), TeaVisibility.PRIVATE,
				TeaPublishing.ENABLED), defaults());
		TeaExposureChange toPrivate = service.assessSave(org, TeaProfileScope.COMPONENT, cd.getUuid(), followAlpha());
		assertEquals(TeaEffectiveVisibility.PRIVATE, toPrivate.after());
		assertEquals(TeaPublicPath.NONE, toPrivate.path());
	}

	@Test
	void aPublicRowIsAlwaysGatedAndAPrivateOneNever() throws RelizaException {
		TeaProfileInputDto publicOverride = override().toBuilder().visibility(TeaVisibility.PUBLIC).build();
		for (TeaResolvedProfile before : new TeaResolvedProfile[] { privateRow(), publicAlpha() }) {
			component(before, publicAlpha(), defaults());
			assertEquals(TeaPublicPath.ROW_PUBLIC,
					service.assessSave(org, TeaProfileScope.COMPONENT, cd.getUuid(), publicOverride).path());
			assertEquals(ROW_PUBLIC_MESSAGE,
					refused(TeaProfileScope.COMPONENT, cd.getUuid(), publicOverride, TeaCaller.SCOPE_ADMIN).getMessage());
		}
		nothingWritten();
		assertEquals(TeaVisibility.PUBLIC,
				save(TeaProfileScope.COMPONENT, cd.getUuid(), publicOverride, TeaCaller.ORG_ADMIN).getVisibility());

		component(publicAlpha(), publicAlpha(), defaults());
		assertEquals(TeaPublicPath.NONE, service.assessSave(org, TeaProfileScope.COMPONENT, cd.getUuid(), override()).path());
		assertEquals(TeaVisibility.PRIVATE,
				save(TeaProfileScope.COMPONENT, cd.getUuid(), override(), TeaCaller.SCOPE_ADMIN).getVisibility());
	}

	@Test
	void aComponentRemovalThatResolvesPublicNeedsAnOrganizationAdmin() throws RelizaException {
		storedRow(TeaProfileScope.COMPONENT, cd.getUuid(), TeaComponentProfileMode.OVERRIDE, TeaVisibility.PRIVATE,
				TeaPublishing.DISABLED);
		component(privateRow(), publicAlpha(), publicOrg());
		TeaExposureChange x = service.assessRemoval(org, TeaProfileScope.COMPONENT, cd.getUuid());
		assertEquals(TeaEffectiveVisibility.PRIVATE, x.before());
		assertEquals(TeaEffectiveVisibility.PUBLIC, x.after());
		assertEquals(TeaPublicPath.REMOVAL_RESOLVES_PUBLIC, x.path());
		assertEquals(TeaProfileSource.ORGANIZATION, x.afterSource());
		assertNull(x.afterSourceObject());

		String m = assertThrows(RelizaException.class,
				() -> service.deleteProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(), TeaCaller.SCOPE_ADMIN, WU))
				.getMessage();
		assertEquals("Only an organization admin can remove the TEA profile of component widget: without it, it resolves "
				+ "to the PUBLIC TEA profile of the organization", m);
		assertEquals(1, stored.size(), "the row is kept");
		verify(auditService, never()).createAndSaveAuditRecord(any(), any());

		assertTrue(service.deleteProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(), TeaCaller.ORG_ADMIN, WU));
		assertTrue(stored.isEmpty());

		// No stored row: the component already resolves to its parent, nothing changes.
		component(publicOrg(), publicAlpha(), publicOrg());
		assertEquals(TeaPublicPath.NONE, service.assessRemoval(org, TeaProfileScope.COMPONENT, cd.getUuid()).path());
		assertFalse(service.deleteProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(), TeaCaller.SCOPE_ADMIN, WU));
	}

	@Test
	void thePerspectiveAndOrganizationScopes() throws RelizaException {
		TeaProfileInputDto publicProfile = full().toBuilder().visibility(TeaVisibility.PUBLIC).build();
		TeaExposureChange row = service.assessSave(org, TeaProfileScope.PERSPECTIVE, alpha.uuid(), publicProfile);
		assertEquals(TeaPublicPath.ROW_PUBLIC, row.path());
		assertEquals(TeaProfileSource.PERSPECTIVE, row.afterSource());
		assertEquals(alpha.uuid(), row.afterSourceObject());
		assertEquals(ROW_PUBLIC_MESSAGE,
				refused(TeaProfileScope.PERSPECTIVE, alpha.uuid(), publicProfile, TeaCaller.SCOPE_ADMIN).getMessage());
		assertEquals(TeaPublicPath.NONE, service.assessSave(org, TeaProfileScope.PERSPECTIVE, alpha.uuid(), full()).path());

		storedRow(TeaProfileScope.PERSPECTIVE, alpha.uuid(), null, TeaVisibility.PRIVATE, TeaPublishing.DISABLED);
		when(resolver.effectiveOfScope(org, TeaProfileScope.PERSPECTIVE, alpha.uuid())).thenReturn(
				resolution(TeaProfileSource.PERSPECTIVE, alpha.uuid(), TeaVisibility.PRIVATE, TeaPublishing.DISABLED));
		when(resolver.parentOfScope(org, TeaProfileScope.PERSPECTIVE, alpha.uuid())).thenReturn(publicOrg());
		assertEquals(TeaPublicPath.REMOVAL_RESOLVES_PUBLIC,
				service.assessRemoval(org, TeaProfileScope.PERSPECTIVE, alpha.uuid()).path());
		String m = assertThrows(RelizaException.class,
				() -> service.deleteProfile(org, TeaProfileScope.PERSPECTIVE, alpha.uuid(), TeaCaller.SCOPE_ADMIN, WU))
				.getMessage();
		assertEquals("Only an organization admin can remove the TEA profile of perspective Alpha: without it, it resolves "
				+ "to the PUBLIC TEA profile of the organization", m);
		assertEquals(1, stored.size());
		assertTrue(service.deleteProfile(org, TeaProfileScope.PERSPECTIVE, alpha.uuid(), TeaCaller.ORG_ADMIN, WU));

		TeaExposureChange orgRow = service.assessSave(org, TeaProfileScope.ORGANIZATION, null, publicProfile);
		assertEquals(TeaPublicPath.ROW_PUBLIC, orgRow.path());
		assertEquals(TeaProfileSource.ORGANIZATION, orgRow.afterSource());
		assertEquals(ROW_PUBLIC_MESSAGE,
				refused(TeaProfileScope.ORGANIZATION, null, publicProfile, TeaCaller.SCOPE_ADMIN).getMessage());
		assertTrue(assertThrows(RelizaException.class,
				() -> service.assessRemoval(org, TeaProfileScope.ORGANIZATION, null)).getMessage().contains("cannot be deleted"));
	}

	@Test
	void theAssessmentValidatesLikeTheSave() throws RelizaException {
		TeaProfileInputDto draft = override().toBuilder().minimumLifecycle(ReleaseLifecycle.DRAFT).build();
		String m = assertThrows(RelizaException.class,
				() -> service.assessSave(org, TeaProfileScope.COMPONENT, cd.getUuid(), draft)).getMessage();
		assertTrue(m.contains("minimumLifecycle") && m.contains("too low"), m);
		nothingWritten();
	}

	// ---- round 2: membership changes, perspective-row removal over members, perspective deletion ----

	private final TeaPerspectiveRef gamma = new TeaPerspectiveRef(UUID.randomUUID(), "Gamma", PerspectiveType.PERSPECTIVE);

	private TeaResolvedProfile publicPerspective(TeaPerspectiveRef p) {
		return resolution(TeaProfileSource.PERSPECTIVE, p.uuid(), TeaVisibility.PUBLIC, TeaPublishing.DISABLED);
	}

	private TeaResolvedProfile privatePerspective(TeaPerspectiveRef p) {
		return resolution(TeaProfileSource.PERSPECTIVE, p.uuid(), TeaVisibility.PRIVATE, TeaPublishing.DISABLED);
	}

	/** {@code c} resolves to {@code before} now and, per prospective set, to the mapped resolution. */
	private void membership(ComponentData c, TeaResolvedProfile before, Map<Set<UUID>, TeaResolvedProfile> after) {
		when(resolver.resolveForComponent(c)).thenReturn(before);
		when(resolver.resolveForComponentWithPerspectives(eq(c), any())).thenAnswer(inv -> {
			java.util.Collection<UUID> next = inv.getArgument(1);
			TeaResolvedProfile r = after.get(Set.copyOf(next));
			return null == r ? defaults() : r;
		});
	}

	private ComponentData member(String name, TeaPerspectiveRef... in) {
		ComponentData m = new ComponentData();
		m.setUuid(UUID.randomUUID());
		m.setOrg(org);
		m.setName(name);
		m.setPerspectives(new java.util.LinkedHashSet<>(java.util.Arrays.stream(in).map(TeaPerspectiveRef::uuid).toList()));
		return m;
	}

	private void assertChange(TeaExposureChange x, TeaEffectiveVisibility before, TeaEffectiveVisibility after,
			TeaPublicPath path) {
		assertEquals(before, x.before());
		assertEquals(after, x.after());
		assertEquals(path, x.path());
	}

	@Test
	void aMembershipChangeIsAPathWhenItWidens() throws RelizaException {
		// Design 4.2 case 36 (alpha is P, PUBLIC; beta is Q, PRIVATE; gamma is Q2, PRIVATE).
		membership(cd, defaults(), Map.of(Set.of(alpha.uuid()), publicPerspective(alpha)));
		TeaExposureChange joined = service.assessMembership(cd, List.of(alpha.uuid()));
		assertChange(joined, TeaEffectiveVisibility.PRIVATE, TeaEffectiveVisibility.PUBLIC,
				TeaPublicPath.MEMBERSHIP_RESOLVES_PUBLIC);
		assertEquals(TeaProfileSource.PERSPECTIVE, joined.afterSource());
		assertEquals(alpha.uuid(), joined.afterSourceObject());
		assertTrue(joined.widened().isEmpty());

		membership(cd, conflict(), Map.of(Set.of(alpha.uuid()), publicPerspective(alpha)));
		assertChange(service.assessMembership(cd, List.of(alpha.uuid())), TeaEffectiveVisibility.UNRESOLVED,
				TeaEffectiveVisibility.PUBLIC, TeaPublicPath.MEMBERSHIP_RESOLVES_PUBLIC);

		membership(cd, resolution(TeaProfileSource.FOLLOWED_PERSPECTIVE, beta.uuid(), TeaVisibility.PRIVATE,
				TeaPublishing.DISABLED), Map.of(Set.of(alpha.uuid()), publicPerspective(alpha)));
		assertChange(service.assessMembership(cd, List.of(alpha.uuid())), TeaEffectiveVisibility.PRIVATE,
				TeaEffectiveVisibility.PUBLIC, TeaPublicPath.MEMBERSHIP_RESOLVES_PUBLIC);

		membership(cd, privatePerspective(gamma), Map.of(Set.of(), publicOrg()));
		TeaExposureChange left = service.assessMembership(cd, List.of());
		assertChange(left, TeaEffectiveVisibility.PRIVATE, TeaEffectiveVisibility.PUBLIC,
				TeaPublicPath.MEMBERSHIP_RESOLVES_PUBLIC);
		assertEquals(TeaProfileSource.ORGANIZATION, left.afterSource());

		membership(cd, publicPerspective(alpha), Map.of(Set.of(alpha.uuid(), beta.uuid()), conflict(),
				Set.of(alpha.uuid()), publicPerspective(alpha)));
		assertChange(service.assessMembership(cd, List.of(alpha.uuid(), beta.uuid())), TeaEffectiveVisibility.PUBLIC,
				TeaEffectiveVisibility.UNRESOLVED, TeaPublicPath.NONE);
		assertChange(service.assessMembership(cd, List.of(alpha.uuid())), TeaEffectiveVisibility.PUBLIC,
				TeaEffectiveVisibility.PUBLIC, TeaPublicPath.NONE);

		membership(cd, defaults(), Map.of(Set.of(beta.uuid()), privatePerspective(beta)));
		assertChange(service.assessMembership(cd, List.of(beta.uuid())), TeaEffectiveVisibility.PRIVATE,
				TeaEffectiveVisibility.PRIVATE, TeaPublicPath.NONE);
	}

	@Test
	void aMembershipWideningNeedsAnOrganizationAdmin() throws RelizaException {
		// Design 4.2 case 40, the membership message; the gate writes nothing itself.
		membership(cd, defaults(), Map.of(Set.of(alpha.uuid()), publicPerspective(alpha)));
		String m = assertThrows(RelizaException.class,
				() -> service.requireMembershipAllowed(cd, List.of(alpha.uuid()), TeaCaller.SCOPE_ADMIN)).getMessage();
		assertEquals("Only an organization admin can change the perspectives of component widget: with them, it resolves "
				+ "to the PUBLIC TEA profile of perspective Alpha", m);
		assertEquals(TeaPublicPath.MEMBERSHIP_RESOLVES_PUBLIC,
				service.requireMembershipAllowed(cd, List.of(alpha.uuid()), TeaCaller.ORG_ADMIN).path());
		// TEA-14 (architecture row 49): a board's document join (SYSTEM) is not refused by the admin rule
		assertEquals(TeaPublicPath.MEMBERSHIP_RESOLVES_PUBLIC,
				service.requireMembershipAllowed(cd, List.of(alpha.uuid()), TeaCaller.SYSTEM).path());
		assertEquals(m, assertThrows(RelizaException.class,
				() -> service.requireMembershipAllowed(cd, List.of(alpha.uuid()), TeaCaller.SCOPE_ADMIN)).getMessage(),
				"a scope admin is still refused with the same text");
		membership(cd, publicPerspective(alpha), Map.of(Set.of(alpha.uuid(), beta.uuid()), conflict()));
		assertEquals(TeaPublicPath.NONE,
				service.requireMembershipAllowed(cd, List.of(alpha.uuid(), beta.uuid()), TeaCaller.SCOPE_ADMIN).path());
		nothingWritten();
	}

	/** Members of Q (beta) as the tester found them: C16 no row in {P, Q}, C17 FOLLOW Q in {P, Q}, C18 OVERRIDE. */
	private List<ComponentData> membersOfBeta() {
		ComponentData c16 = member("C16", alpha, beta);
		ComponentData c17 = member("C17", alpha, beta);
		ComponentData c18 = member("C18", alpha, beta);
		when(components.listDirectComponentsOfPerspective(beta.uuid())).thenReturn(List.of(c18, c17, c16));
		when(resolver.resolveForComponent(c16)).thenReturn(conflict());
		when(resolver.resolveForComponent(c17)).thenReturn(resolution(TeaProfileSource.FOLLOWED_PERSPECTIVE, beta.uuid(),
				TeaVisibility.PRIVATE, TeaPublishing.DISABLED));
		when(resolver.resolveForComponent(c18)).thenReturn(privateRow());
		for (ComponentData c : List.of(c16, c17)) {
			when(resolver.resolveForComponentWithoutPerspectiveRow(c, beta.uuid())).thenReturn(publicPerspective(alpha));
			when(resolver.resolveForComponentWithPerspectives(eq(c), any())).thenAnswer(inv -> {
				java.util.Collection<UUID> next = inv.getArgument(1);
				return Set.copyOf(next).equals(Set.of(alpha.uuid())) ? publicPerspective(alpha) : defaults();
			});
		}
		when(resolver.resolveForComponentWithoutPerspectiveRow(c18, beta.uuid())).thenReturn(privateRow());
		when(resolver.resolveForComponentWithPerspectives(eq(c18), any())).thenReturn(privateRow());
		return List.of(c16, c17, c18);
	}

	private void assertWidened(List<TeaWidenedComponent> widened, ComponentData... expected) {
		assertEquals(java.util.Arrays.stream(expected).map(ComponentData::getName).toList(),
				widened.stream().map(TeaWidenedComponent::name).toList());
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i].getUuid(), widened.get(i).component());
			assertEquals(TeaProfileSource.PERSPECTIVE, widened.get(i).afterSource());
			assertEquals(alpha.uuid(), widened.get(i).afterSourceObject());
		}
	}

	@Test
	void aPerspectiveRowRemovalIsAssessedOverItsMembers() throws RelizaException {
		// Design 4.2 case 37: the organization step is PRIVATE, so the perspective step does not widen.
		storedRow(TeaProfileScope.PERSPECTIVE, beta.uuid(), null, TeaVisibility.PRIVATE, TeaPublishing.DISABLED);
		when(resolver.effectiveOfScope(org, TeaProfileScope.PERSPECTIVE, beta.uuid())).thenReturn(privatePerspective(beta));
		List<ComponentData> ms = membersOfBeta();
		TeaExposureChange x = service.assessRemoval(org, TeaProfileScope.PERSPECTIVE, beta.uuid());
		assertChange(x, TeaEffectiveVisibility.PRIVATE, TeaEffectiveVisibility.PRIVATE,
				TeaPublicPath.REMOVAL_RESOLVES_PUBLIC);
		assertWidened(x.widened(), ms.get(0), ms.get(1));

		String m = assertThrows(RelizaException.class,
				() -> service.deleteProfile(org, TeaProfileScope.PERSPECTIVE, beta.uuid(), TeaCaller.SCOPE_ADMIN, WU))
				.getMessage();
		assertEquals("Only an organization admin can remove the TEA profile of perspective Beta: without it, 2 component(s) "
				+ "resolve to a PUBLIC TEA profile, for instance component C16 to the PUBLIC TEA profile of perspective Alpha", m);
		nothingWritten();
		assertTrue(service.deleteProfile(org, TeaProfileScope.PERSPECTIVE, beta.uuid(), TeaCaller.ORG_ADMIN, WU));

		// No widening member and the organization step PRIVATE: nothing to gate.
		when(components.listDirectComponentsOfPerspective(beta.uuid())).thenReturn(List.of(ms.get(2)));
		TeaExposureChange none = service.assessRemoval(org, TeaProfileScope.PERSPECTIVE, beta.uuid());
		assertEquals(TeaPublicPath.NONE, none.path());
		assertTrue(none.widened().isEmpty());
	}

	@Test
	void aPerspectiveDeletionIsAssessedOverItsMembers() throws RelizaException {
		// Design 4.2 case 38: each member resolved without Q as a candidate.
		List<ComponentData> ms = membersOfBeta();
		ComponentData onlyQ = member("C19", beta);
		when(components.listDirectComponentsOfPerspective(beta.uuid()))
				.thenReturn(List.of(ms.get(2), ms.get(1), onlyQ, ms.get(0)));
		when(resolver.resolveForComponent(onlyQ)).thenReturn(privatePerspective(beta));
		TeaExposureChange x = service.assessPerspectiveDeletion(org, beta.uuid());
		assertEquals(TeaPublicPath.REMOVAL_RESOLVES_PUBLIC, x.path());
		assertWidened(x.widened(), ms.get(0), ms.get(1));
		verify(resolver).resolveForComponentWithPerspectives(ms.get(0), List.of(alpha.uuid()));

		String m = assertThrows(RelizaException.class,
				() -> service.requirePerspectiveDeletionAllowed(org, beta.uuid(), TeaCaller.SCOPE_ADMIN)).getMessage();
		assertEquals("Only an organization admin can delete perspective Beta: without it, component C16 resolves to the "
				+ "PUBLIC TEA profile of perspective Alpha", m);
		assertEquals(2, service.requirePerspectiveDeletionAllowed(org, beta.uuid(), TeaCaller.ORG_ADMIN).widened().size());

		when(components.listDirectComponentsOfPerspective(beta.uuid())).thenReturn(List.of(onlyQ));
		assertEquals(TeaPublicPath.NONE,
				service.requirePerspectiveDeletionAllowed(org, beta.uuid(), TeaCaller.SCOPE_ADMIN).path());
		nothingWritten();
	}

	@Test
	void onlyThePerspectiveScopeRemovalAndTheDeletionListWidenedMembers() throws RelizaException {
		// Design 4.2 case 39.
		membersOfBeta();
		TeaProfileInputDto publicProfile = full().toBuilder().visibility(TeaVisibility.PUBLIC).build();
		when(perspectives.teaPerspectiveObject(beta.uuid())).thenReturn(java.util.Optional.of(orgObject(org)));
		assertTrue(service.assessSave(org, TeaProfileScope.PERSPECTIVE, beta.uuid(), full()).widened().isEmpty());
		assertTrue(service.assessSave(org, TeaProfileScope.PERSPECTIVE, beta.uuid(), publicProfile).widened().isEmpty());
		assertTrue(service.assessSave(org, TeaProfileScope.ORGANIZATION, null, publicProfile).widened().isEmpty());
		assertTrue(service.assessSave(org, TeaProfileScope.COMPONENT, cd.getUuid(), override()).widened().isEmpty());
		storedRow(TeaProfileScope.COMPONENT, cd.getUuid(), TeaComponentProfileMode.OVERRIDE, TeaVisibility.PRIVATE,
				TeaPublishing.DISABLED);
		assertTrue(service.assessRemoval(org, TeaProfileScope.COMPONENT, cd.getUuid()).widened().isEmpty());
		assertTrue(service.assessMembership(cd, List.of(beta.uuid())).widened().isEmpty());
	}
}
