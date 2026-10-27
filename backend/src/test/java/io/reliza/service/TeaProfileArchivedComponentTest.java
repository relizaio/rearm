/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.dto.TeaProfileInputDto;
import io.reliza.service.TeaProfileResolver.TeaProfileSource;
import io.reliza.service.TeaProfileResolver.TeaResolvedProfile;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.TeaProfileService.TeaPublicPath;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;

/**
 * Task TEA-9, design 4.2 cases 11 to 13: an archived component or product keeps its TEA profile
 * row so an admin can hide what it published, and accepts nothing that widens exposure: no FOLLOW,
 * no ENABLED, no PUBLIC, no removal while its parent would expose it. Round 2 (cases 41, 42): no
 * membership change that gains ENABLED or PUBLIC, whoever makes it. Each case runs for a component
 * and for a product.
 */
class TeaProfileArchivedComponentTest extends TeaProfileServiceTestBase {

	private void archive(ComponentType type) {
		cd.setType(type);
		cd.setStatus(StatusEnum.ARCHIVED);
	}

	private TeaProfileInputDto hide() {
		return override();
	}

	private String archivedRefusal(TeaProfileInputDto in, TeaCaller caller) {
		String m = refused(TeaProfileScope.COMPONENT, cd.getUuid(), in, caller).getMessage();
		assertTrue(m.startsWith("Component widget is archived: "), m);
		return m;
	}

	@ParameterizedTest
	@EnumSource(value = ComponentType.class, names = { "COMPONENT", "PRODUCT" })
	void onlyTheHideRowIsAccepted(ComponentType type) throws RelizaException {
		archive(type);
		TeaProfileInputDto follow = TeaProfileInputDto.builder().mode(TeaComponentProfileMode.FOLLOW_PERSPECTIVE)
				.followedPerspective(alpha.uuid()).build();
		assertTrue(archivedRefusal(follow, TeaCaller.ORG_ADMIN).contains("cannot follow a perspective"));
		assertTrue(archivedRefusal(override().toBuilder().publishing(TeaPublishing.ENABLED).build(), TeaCaller.ORG_ADMIN)
				.contains("publishing ENABLED is refused"));
		assertTrue(archivedRefusal(override().toBuilder().visibility(TeaVisibility.PUBLIC).build(), TeaCaller.ORG_ADMIN)
				.contains("visibility PUBLIC is refused"));
		assertTrue(archivedRefusal(override().toBuilder().publishing(TeaPublishing.ENABLED)
				.visibility(TeaVisibility.PUBLIC).build(), TeaCaller.ORG_ADMIN)
				.contains("publishing ENABLED and visibility PUBLIC are refused"));
		// The dry-run the editor sends first answers the same refusal, before any confirmation.
		assertThrows(RelizaException.class, () -> service.assessSave(org, TeaProfileScope.COMPONENT, cd.getUuid(),
				override().toBuilder().visibility(TeaVisibility.PUBLIC).build()));
		verify(repository, never()).save(any());
		verify(organizationService, never()).ensureTeaUuid(any(), any());

		TeaProfileData created = save(TeaProfileScope.COMPONENT, cd.getUuid(), hide(), TeaCaller.SCOPE_ADMIN);
		assertEquals(0, created.getRevision());
		assertEquals(1, stored.size());
		TeaProfileData updated = save(TeaProfileScope.COMPONENT, cd.getUuid(),
				hide().toBuilder().rawArtifacts(TeaProfileData.TeaRawArtifacts.NON_BOM).build(),
				TeaCaller.SCOPE_ADMIN);
		assertEquals(created.getUuid(), updated.getUuid());
		assertEquals(1, updated.getRevision());
		verify(auditService, times(1)).createAndSaveAuditRecord(eq(TableName.TEA_PROFILE), any());
		verify(organizationService, never()).ensureTeaUuid(any(), any());
	}

	@ParameterizedTest
	@EnumSource(value = ComponentType.class, names = { "COMPONENT", "PRODUCT" })
	void theArchiveIsNamedBeforeTheAdminRule(ComponentType type) {
		archive(type);
		String m = archivedRefusal(override().toBuilder().visibility(TeaVisibility.PUBLIC).build(), TeaCaller.SCOPE_ADMIN);
		assertTrue(!m.contains("organization admin"), m);
	}

	private String refusedRemoval(TeaResolvedProfile parent) {
		when(resolver.resolveForComponentWithRow(cd, null)).thenReturn(parent);
		String m = assertThrows(RelizaException.class,
				() -> service.deleteProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(), TeaCaller.ORG_ADMIN, WU))
				.getMessage();
		assertTrue(m.startsWith("Component widget is archived: without its TEA profile it would resolve to the "), m);
		assertThrows(RelizaException.class, () -> service.assessRemoval(org, TeaProfileScope.COMPONENT, cd.getUuid()));
		assertEquals(1, stored.size(), "the hide row stays");
		return m;
	}

	@ParameterizedTest
	@EnumSource(value = ComponentType.class, names = { "COMPONENT", "PRODUCT" })
	void theHideRowStaysWhileTheParentWouldExpose(ComponentType type) throws RelizaException {
		archive(type);
		storedRow(TeaProfileScope.COMPONENT, cd.getUuid(), TeaComponentProfileMode.OVERRIDE, TeaVisibility.PRIVATE,
				TeaPublishing.DISABLED);
		assertTrue(refusedRemoval(resolution(TeaProfileSource.ORGANIZATION, null, TeaVisibility.PRIVATE,
				TeaPublishing.ENABLED)).contains("ENABLED profile of the organization"));
		when(perspectives.teaPerspectiveRef(alpha.uuid())).thenReturn(java.util.Optional.of(alpha));
		assertTrue(refusedRemoval(resolution(TeaProfileSource.PERSPECTIVE, alpha.uuid(), TeaVisibility.PUBLIC,
				TeaPublishing.DISABLED)).contains("PUBLIC profile of perspective Alpha"));
		verify(repository, never()).delete(any());

		when(resolver.resolveForComponentWithRow(cd, null)).thenReturn(conflict());
		assertTrue(service.deleteProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(), TeaCaller.SCOPE_ADMIN, WU));

		storedRow(TeaProfileScope.COMPONENT, cd.getUuid(), TeaComponentProfileMode.OVERRIDE, TeaVisibility.PRIVATE,
				TeaPublishing.DISABLED);
		when(resolver.resolveForComponentWithRow(cd, null)).thenReturn(defaults());
		assertTrue(service.deleteProfile(org, TeaProfileScope.COMPONENT, cd.getUuid(), TeaCaller.SCOPE_ADMIN, WU));
		assertTrue(stored.isEmpty());
	}

	/**
	 * Task TEA-27, F-4: the archived member of two perspectives that both define the SBOM profile
	 * default still saves its hide row, because a DISABLED row is never resolve-checked; the ENABLED
	 * row keeps the archived refusal text; and the hide row stays the one remedy while the parent exposes.
	 */
	@ParameterizedTest
	@EnumSource(value = ComponentType.class, names = { "COMPONENT", "PRODUCT" })
	void theHideRowSavesForAnArchivedMemberOfTwoPerspectivesDefiningDefault(ComponentType type) throws RelizaException {
		archive(type);
		names.put("default", nameConflict());
		assertEquals(java.util.List.of("default"), hide().getProfiles());
		TeaProfileData saved = save(TeaProfileScope.COMPONENT, cd.getUuid(), hide(), TeaCaller.SCOPE_ADMIN);
		assertEquals(TeaPublishing.DISABLED, saved.getPublishing());
		assertEquals(TeaVisibility.PRIVATE, saved.getVisibility());
		verify(sbomResolver, never()).resolveForComponent(any(), anyString());
		assertTrue(archivedRefusal(hide().toBuilder().publishing(TeaPublishing.ENABLED).build(), TeaCaller.ORG_ADMIN)
				.contains("publishing ENABLED is refused"), "the archived refusal keeps its text");
		assertTrue(refusedRemoval(resolution(TeaProfileSource.ORGANIZATION, null, TeaVisibility.PRIVATE,
				TeaPublishing.ENABLED)).contains("ENABLED profile of the organization"));
		assertEquals(1, stored.size());
	}

	// ---- round 2: membership changes on an archived component or product (design 4.3) ----

	private final TeaPerspectiveRef pe = new TeaPerspectiveRef(UUID.randomUUID(), "Pe", PerspectiveType.PERSPECTIVE);
	private final TeaPerspectiveRef pe2 = new TeaPerspectiveRef(UUID.randomUUID(), "Pe2", PerspectiveType.PERSPECTIVE);
	private final TeaPerspectiveRef pp = new TeaPerspectiveRef(UUID.randomUUID(), "Pp", PerspectiveType.PERSPECTIVE);
	private final TeaPerspectiveRef pn = new TeaPerspectiveRef(UUID.randomUUID(), "Pn", PerspectiveType.PERSPECTIVE);
	private final TeaPerspectiveRef qp = new TeaPerspectiveRef(UUID.randomUUID(), "Qp", PerspectiveType.PERSPECTIVE);

	private TeaResolvedProfile through(TeaPerspectiveRef p, TeaVisibility v, TeaPublishing pub) {
		when(perspectives.teaPerspectiveRef(p.uuid())).thenReturn(java.util.Optional.of(p));
		return resolution(TeaProfileSource.PERSPECTIVE, p.uuid(), v, pub);
	}

	/** The component resolves to {@code before} now and, per prospective set, to the mapped resolution. */
	private void membership(TeaResolvedProfile before, Map<Set<UUID>, TeaResolvedProfile> after) {
		when(resolver.resolveForComponent(cd)).thenReturn(before);
		when(resolver.resolveForComponentWithPerspectives(eq(cd), any())).thenAnswer(inv -> {
			Collection<UUID> next = inv.getArgument(1);
			TeaResolvedProfile r = after.get(Set.copyOf(next));
			return null == r ? defaults() : r;
		});
		List<TeaPerspectiveRef> all = List.of(pe, pe2, pp, pn, qp);
		when(perspectives.teaPerspectiveRefs(eq(org), any())).thenAnswer(inv -> {
			Collection<UUID> next = inv.getArgument(1);
			return next.stream().flatMap(u -> all.stream().filter(r -> r.uuid().equals(u))).toList();
		});
	}

	private String archivedMembershipRefusal(List<UUID> next, TeaCaller caller) {
		String m = assertThrows(RelizaException.class, () -> service.requireMembershipAllowed(cd, next, caller))
				.getMessage();
		assertTrue(m.startsWith("Component widget is archived: with perspectives "), m);
		assertTrue(m.endsWith("; an archived component cannot widen its TEA exposure"), m);
		assertThrows(RelizaException.class, () -> service.assessMembership(cd, next), "the dry-run refuses too");
		return m;
	}

	@ParameterizedTest
	@EnumSource(value = ComponentType.class, names = { "COMPONENT", "PRODUCT" })
	void noMembershipChangeGainsEnabledOrPublic(ComponentType type) throws RelizaException {
		// Design 4.3 case 41, for an org admin and a scope admin alike.
		archive(type);
		TeaResolvedProfile enabledPublic = through(pe, TeaVisibility.PUBLIC, TeaPublishing.ENABLED);
		membership(defaults(), Map.of(Set.of(pe.uuid()), enabledPublic,
				Set.of(pp.uuid()), through(pp, TeaVisibility.PUBLIC, TeaPublishing.DISABLED),
				Set.of(pn.uuid()), through(pn, TeaVisibility.PRIVATE, TeaPublishing.ENABLED)));
		for (TeaCaller caller : TeaCaller.values()) {
			assertTrue(archivedMembershipRefusal(List.of(pe.uuid()), caller)
					.contains("with perspectives Pe it would resolve to the ENABLED PUBLIC TEA profile of perspective Pe"));
			assertTrue(archivedMembershipRefusal(List.of(pp.uuid()), caller)
					.contains("it would resolve to the PUBLIC TEA profile of perspective Pp"));
			assertTrue(archivedMembershipRefusal(List.of(pn.uuid()), caller)
					.contains("it would resolve to the ENABLED TEA profile of perspective Pn"));
		}

		// A FOLLOW Qp row stored before archiving, in {Pe, Qp}: dropping Qp lets it fall through to Pe.
		membership(resolution(TeaProfileSource.FOLLOWED_PERSPECTIVE, qp.uuid(), TeaVisibility.PRIVATE,
				TeaPublishing.DISABLED), Map.of(Set.of(pe.uuid()), enabledPublic));
		for (TeaCaller caller : TeaCaller.values()) archivedMembershipRefusal(List.of(pe.uuid()), caller);

		// Nothing gained: accepted, by both.
		membership(enabledPublic, Map.of(Set.of(pe2.uuid()), through(pe2, TeaVisibility.PUBLIC, TeaPublishing.ENABLED),
				Set.of(pe.uuid(), qp.uuid()), conflict(),
				Set.of(), resolution(TeaProfileSource.ORGANIZATION, null, TeaVisibility.PRIVATE, TeaPublishing.DISABLED)));
		for (TeaCaller caller : TeaCaller.values()) {
			assertEquals(TeaPublicPath.NONE, service.requireMembershipAllowed(cd, List.of(pe2.uuid()), caller).path());
			assertEquals(TeaPublicPath.NONE,
					service.requireMembershipAllowed(cd, List.of(pe.uuid(), qp.uuid()), caller).path());
			assertEquals(TeaPublicPath.NONE, service.requireMembershipAllowed(cd, List.of(), caller).path());
		}
		verify(repository, never()).save(any());
		verify(organizationService, never()).ensureTeaUuid(any(), any());
	}

	@ParameterizedTest
	@EnumSource(value = ComponentType.class, names = { "COMPONENT", "PRODUCT" })
	void theArchivedMembershipRefusalComesBeforeTheAdminRule(ComponentType type) {
		// Design 4.3 case 42.
		archive(type);
		membership(defaults(), Map.of(Set.of(pp.uuid()), through(pp, TeaVisibility.PUBLIC, TeaPublishing.DISABLED)));
		String m = archivedMembershipRefusal(List.of(pp.uuid()), TeaCaller.SCOPE_ADMIN);
		assertTrue(!m.contains("organization admin"), m);
	}

	@ParameterizedTest
	@EnumSource(value = ComponentType.class, names = { "COMPONENT", "PRODUCT" })
	void anArchivedMemberCannotWidenThroughAPerspectiveDeletion(ComponentType type) {
		// Design 3.1: the archived rule runs per member inside assessPerspectiveDeletion, for everyone.
		archive(type);
		cd.setPerspectives(new java.util.LinkedHashSet<>(List.of(pe.uuid(), qp.uuid())));
		when(components.listDirectComponentsOfPerspective(qp.uuid())).thenReturn(List.of(cd));
		membership(conflict(), Map.of(Set.of(pe.uuid()), through(pe, TeaVisibility.PUBLIC, TeaPublishing.ENABLED)));
		String m = assertThrows(RelizaException.class,
				() -> service.requirePerspectiveDeletionAllowed(org, qp.uuid(), TeaCaller.ORG_ADMIN)).getMessage();
		assertTrue(m.startsWith("Component widget is archived: with perspectives Pe it would resolve to the ENABLED PUBLIC"),
				m);
	}
}
