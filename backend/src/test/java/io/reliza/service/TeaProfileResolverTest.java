/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.model.ComponentData;
import io.reliza.model.ScopedResolutionStatus;
import io.reliza.model.ScopedSettingsSource;
import io.reliza.model.SettingsScope;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.service.ScopedSettingsResolver.ScopedResolution;
import io.reliza.service.TeaProfileResolver.TeaProfileSource;
import io.reliza.service.TeaProfileResolver.TeaResolutionStatus;
import io.reliza.service.TeaProfileResolver.TeaResolvedProfile;
import io.reliza.service.oss.OssPerspectiveService;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;

/**
 * Task TEA-2, design 4.1: which TEA profile a component resolves to. The rows live in an in-memory
 * list the mocked {@link TeaProfileService} answers from, so each case states its rows. Task TEA-27:
 * the resolver is a facade over {@link ScopedSettingsResolver}, here the real engine over the real TEA
 * store of the mocked service; every TEA-2 and TEA-9 case passes through it unchanged.
 */
class TeaProfileResolverTest {

	private TeaProfileResolver resolver;
	private ScopedSettingsResolver engine;
	private TeaProfileService profiles;
	private OssPerspectiveService perspectives;
	private SupportInjectionService support;
	private GetComponentService components;

	private final UUID org = UUID.randomUUID();
	private final List<TeaProfileData> rows = new ArrayList<>();
	private final TeaPerspectiveRef alpha = new TeaPerspectiveRef(UUID.randomUUID(), "Alpha", PerspectiveType.PERSPECTIVE);
	private final TeaPerspectiveRef beta = new TeaPerspectiveRef(UUID.randomUUID(), "beta", PerspectiveType.PERSPECTIVE);
	private ComponentData cd;
	private SupportInjectionSetting injection = SupportInjectionSetting.ENABLED;

	/** In-memory appender on the resolver's own logger, as SchedulerIsolationTest captures. */
	private static class CapturingAppender extends AbstractAppender {
		private final List<LogEvent> events = Collections.synchronizedList(new LinkedList<>());
		CapturingAppender() { super("tea-resolver-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}

	private CapturingAppender logs;
	private Level previousLevel;

	@BeforeEach
	void setUp() {
		resolver = new TeaProfileResolver();
		engine = new ScopedSettingsResolver();
		profiles = mock(TeaProfileService.class);
		perspectives = mock(OssPerspectiveService.class);
		support = mock(SupportInjectionService.class);
		components = mock(GetComponentService.class);
		ReflectionTestUtils.setField(engine, "ossPerspectiveService", perspectives);
		ReflectionTestUtils.setField(engine, "getComponentService", components);
		ReflectionTestUtils.setField(resolver, "teaProfileService", profiles);
		ReflectionTestUtils.setField(resolver, "scopedSettingsResolver", engine);
		ReflectionTestUtils.setField(resolver, "supportInjectionService", support);
		lenient().when(profiles.store()).thenAnswer(inv -> new TeaProfileService.TeaProfileStore(profiles));

		cd = new ComponentData();
		cd.setUuid(UUID.randomUUID());
		cd.setOrg(org);
		cd.setName("widget");
		cd.setPerspectives(Set.of(alpha.uuid(), beta.uuid()));
		candidates(alpha, beta);
		lenient().when(components.getComponentData(cd.getUuid())).thenReturn(Optional.of(cd));

		lenient().when(profiles.getProfile(eq(org), any(), any())).thenAnswer(inv -> {
			TeaProfileScope scope = inv.getArgument(1);
			UUID object = inv.getArgument(2);
			return rows.stream().filter(r -> r.getScope() == scope
					&& (scope == TeaProfileScope.ORGANIZATION ? r.getObject() == null : object != null && object.equals(r.getObject())))
					.findFirst().map(TeaProfileData::copy);
		});
		lenient().when(profiles.listPerspectiveProfiles(eq(org), anyCollection())).thenAnswer(inv -> {
			Collection<UUID> wanted = inv.getArgument(1);
			return rows.stream().filter(r -> r.getScope() == TeaProfileScope.PERSPECTIVE && wanted.contains(r.getObject()))
					.map(TeaProfileData::copy).toList();
		});
		lenient().when(support.isInjectionEnabled(org)).thenAnswer(inv -> injection == SupportInjectionSetting.ENABLED);

		logs = new CapturingAppender();
		logs.start();
		org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) LogManager
				.getLogger(ScopedSettingsResolver.class);
		previousLevel = logger.getLevel();
		// Without a Spring context no log4j2 configuration is loaded and the default level is ERROR.
		logger.setLevel(Level.WARN);
		logger.addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) LogManager
				.getLogger(ScopedSettingsResolver.class);
		logger.removeAppender(logs);
		logger.setLevel(previousLevel);
		logs.stop();
	}

	private void candidates(TeaPerspectiveRef... refs) {
		lenient().when(perspectives.teaPerspectivesOfComponent(any())).thenReturn(List.of(refs));
	}

	private TeaProfileData row(TeaProfileScope scope, UUID object) {
		TeaProfileData d = TeaProfileData.builtInDefault(org);
		d.setUuid(UUID.randomUUID());
		d.setScope(scope);
		d.setObject(object);
		d.setPublishing(TeaPublishing.ENABLED);
		rows.add(d);
		return d;
	}

	private TeaProfileData orgRow() {
		return row(TeaProfileScope.ORGANIZATION, null);
	}

	private TeaProfileData perspectiveRow(TeaPerspectiveRef p) {
		return row(TeaProfileScope.PERSPECTIVE, p.uuid());
	}

	private TeaProfileData componentRow(TeaComponentProfileMode mode, UUID followed) {
		TeaProfileData d = row(TeaProfileScope.COMPONENT, cd.getUuid());
		d.setMode(mode);
		d.setFollowedPerspective(followed);
		return d;
	}

	private long warnings() {
		return logs.events.stream().filter(e -> e.getLevel() == Level.WARN).count();
	}

	@Test
	void noRowsResolveToTheBuiltInDefaults() {
		TeaResolvedProfile r = resolver.resolveForComponent(cd);
		assertEquals(TeaResolutionStatus.RESOLVED, r.status());
		assertEquals(TeaProfileSource.DEFAULT, r.source());
		assertNull(r.sourceObject());
		assertEquals(TeaPublishing.DISABLED, r.profile().getPublishing());
		assertEquals(TeaProfileData.builtInDefault(org), r.profile());
		assertTrue(r.conflictingPerspectives().isEmpty());
	}

	@Test
	void theOrganizationRowAlone() {
		TeaProfileData o = orgRow();
		TeaResolvedProfile r = resolver.resolveForComponent(cd);
		assertEquals(TeaProfileSource.ORGANIZATION, r.source());
		assertEquals(o.getUuid(), r.profile().getUuid());
	}

	@Test
	void aComponentOverrideWinsOverPerspectiveAndOrganization() {
		orgRow();
		perspectiveRow(alpha);
		TeaProfileData c = componentRow(TeaComponentProfileMode.OVERRIDE, null);
		TeaResolvedProfile r = resolver.resolveForComponent(cd);
		assertEquals(TeaProfileSource.COMPONENT, r.source());
		assertEquals(cd.getUuid(), r.sourceObject());
		assertEquals(c.getUuid(), r.profile().getUuid());
	}

	@Test
	void oneCandidatePerspectiveRowAmongTwoPerspectives() {
		orgRow();
		TeaProfileData p = perspectiveRow(beta);
		TeaResolvedProfile r = resolver.resolveForComponent(cd);
		assertEquals(TeaProfileSource.PERSPECTIVE, r.source());
		assertEquals(beta.uuid(), r.sourceObject());
		assertEquals(p.getUuid(), r.profile().getUuid());
	}

	@Test
	void twoCandidateRowsAndNoComponentRowAreAConflictInNameOrder() {
		orgRow();
		perspectiveRow(beta);
		perspectiveRow(alpha);
		TeaResolvedProfile r = resolver.resolveForComponent(cd);
		assertEquals(TeaResolutionStatus.CONFLICT, r.status());
		assertNull(r.profile());
		assertEquals(List.of(alpha, beta), r.conflictingPerspectives());
		assertEquals("Alpha", r.conflictingPerspectives().get(0).name());
	}

	@Test
	void aFollowRowPicksOneOfTheConflictingPerspectives() {
		perspectiveRow(alpha);
		TeaProfileData b = perspectiveRow(beta);
		componentRow(TeaComponentProfileMode.FOLLOW_PERSPECTIVE, beta.uuid());
		TeaResolvedProfile r = resolver.resolveForComponent(cd);
		assertEquals(TeaProfileSource.FOLLOWED_PERSPECTIVE, r.source());
		assertEquals(beta.uuid(), r.sourceObject());
		assertEquals(b.getUuid(), r.profile().getUuid());
	}

	@Test
	void aFollowOfAPerspectiveTheComponentLeftIsIgnoredWithOneWarning() {
		TeaPerspectiveRef gone = new TeaPerspectiveRef(UUID.randomUUID(), "Gone", PerspectiveType.PERSPECTIVE);
		perspectiveRow(gone);
		TeaProfileData a = perspectiveRow(alpha);
		componentRow(TeaComponentProfileMode.FOLLOW_PERSPECTIVE, gone.uuid());

		candidates(alpha);
		TeaResolvedProfile one = resolver.resolveForComponent(cd);
		assertEquals(TeaProfileSource.PERSPECTIVE, one.source());
		assertEquals(a.getUuid(), one.profile().getUuid());
		assertEquals(1, warnings());

		perspectiveRow(beta);
		candidates(alpha, beta);
		TeaResolvedProfile two = resolver.resolveForComponent(cd);
		assertEquals(TeaResolutionStatus.CONFLICT, two.status());
		assertEquals(List.of(alpha, beta), two.conflictingPerspectives());
		assertEquals(2, warnings(), "one warning per resolution");
	}

	@Test
	void aFollowOfACandidateWithoutARowFallsThroughToTheOrganization() {
		TeaProfileData o = orgRow();
		componentRow(TeaComponentProfileMode.FOLLOW_PERSPECTIVE, alpha.uuid());
		TeaResolvedProfile r = resolver.resolveForComponent(cd);
		assertEquals(TeaProfileSource.ORGANIZATION, r.source());
		assertEquals(o.getUuid(), r.profile().getUuid());
	}

	@Test
	void theRecordedProfileSettlesAConflictWhileItIsStillACandidate() {
		TeaProfileData o = orgRow();
		perspectiveRow(alpha);
		TeaProfileData b = perspectiveRow(beta);

		TeaResolvedProfile recorded = resolver.resolveForComponent(cd, b.getUuid());
		assertEquals(TeaResolutionStatus.RESOLVED, recorded.status());
		assertEquals(TeaProfileSource.PERSPECTIVE, recorded.source());
		assertEquals(b.getUuid(), recorded.profile().getUuid());

		assertEquals(TeaResolutionStatus.CONFLICT, resolver.resolveForComponent(cd, o.getUuid()).status(),
				"the org row is not a candidate");
		assertEquals(TeaResolutionStatus.CONFLICT, resolver.resolveForComponent(cd, UUID.randomUUID()).status(),
				"a deleted row is not a candidate");
	}

	@Test
	void theCeHookHasNoPerspectives() {
		candidates();
		TeaProfileData o = orgRow();
		componentRow(TeaComponentProfileMode.FOLLOW_PERSPECTIVE, alpha.uuid());
		TeaResolvedProfile follow = resolver.resolveForComponent(cd);
		assertEquals(TeaProfileSource.ORGANIZATION, follow.source());
		assertEquals(o.getUuid(), follow.profile().getUuid());

		rows.removeIf(r -> r.getScope() == TeaProfileScope.COMPONENT);
		TeaProfileData c = componentRow(TeaComponentProfileMode.OVERRIDE, null);
		TeaResolvedProfile override = resolver.resolveForComponent(cd);
		assertEquals(TeaProfileSource.COMPONENT, override.source());
		assertEquals(c.getUuid(), override.profile().getUuid());
	}

	// ---- TEA-9, design 4.1: the what-if resolution a save or a removal is assessed on ----

	/** A row that is not stored: what a save would write. */
	private TeaProfileData prospective(TeaComponentProfileMode mode, UUID followed) {
		TeaProfileData d = TeaProfileData.builtInDefault(org);
		d.setScope(TeaProfileScope.COMPONENT);
		d.setObject(cd.getUuid());
		d.setMode(mode);
		d.setFollowedPerspective(followed);
		return d;
	}

	@Test
	void theWhatIfOfAnOverrideIsThatRowWithoutReadingTheStoredOne() {
		orgRow();
		componentRow(TeaComponentProfileMode.FOLLOW_PERSPECTIVE, alpha.uuid());
		TeaProfileData d = prospective(TeaComponentProfileMode.OVERRIDE, null);
		d.setVisibility(TeaVisibility.PUBLIC);
		TeaResolvedProfile r = resolver.resolveForComponentWithRow(cd, d);
		assertEquals(TeaProfileSource.COMPONENT, r.source());
		assertEquals(cd.getUuid(), r.sourceObject());
		assertEquals(TeaVisibility.PUBLIC, r.profile().getVisibility());
		verify(profiles, never()).getProfile(org, TeaProfileScope.COMPONENT, cd.getUuid());
	}

	@Test
	void theWhatIfOfAFollowResolvesLikeAStoredFollow() {
		TeaProfileData b = perspectiveRow(beta);
		perspectiveRow(alpha);
		TeaResolvedProfile followed = resolver.resolveForComponentWithRow(cd,
				prospective(TeaComponentProfileMode.FOLLOW_PERSPECTIVE, beta.uuid()));
		assertEquals(TeaProfileSource.FOLLOWED_PERSPECTIVE, followed.source());
		assertEquals(b.getUuid(), followed.profile().getUuid());

		rows.removeIf(r -> r.getObject() != null && r.getObject().equals(alpha.uuid()));
		rows.removeIf(r -> r.getObject() != null && r.getObject().equals(beta.uuid()));
		TeaProfileData a = perspectiveRow(alpha);
		TeaResolvedProfile fallsThrough = resolver.resolveForComponentWithRow(cd,
				prospective(TeaComponentProfileMode.FOLLOW_PERSPECTIVE, beta.uuid()));
		assertEquals(TeaProfileSource.PERSPECTIVE, fallsThrough.source(), "beta has no row: the perspective step");
		assertEquals(a.getUuid(), fallsThrough.profile().getUuid());
		assertEquals(1, warnings());
		verify(profiles, never()).getProfile(org, TeaProfileScope.COMPONENT, cd.getUuid());
	}

	@Test
	void theWhatIfWithoutARowIsTheParent() {
		componentRow(TeaComponentProfileMode.OVERRIDE, null);
		assertEquals(resolver.parentOfScope(org, TeaProfileScope.COMPONENT, cd.getUuid()),
				resolver.resolveForComponentWithRow(cd, null), "no candidate row: the defaults");
		orgRow();
		perspectiveRow(alpha);
		TeaResolvedProfile one = resolver.resolveForComponentWithRow(cd, null);
		assertEquals(TeaProfileSource.PERSPECTIVE, one.source());
		assertEquals(resolver.parentOfScope(org, TeaProfileScope.COMPONENT, cd.getUuid()), one);
		perspectiveRow(beta);
		TeaResolvedProfile two = resolver.resolveForComponentWithRow(cd, null);
		assertEquals(TeaResolutionStatus.CONFLICT, two.status());
		assertEquals(resolver.parentOfScope(org, TeaProfileScope.COMPONENT, cd.getUuid()), two);
	}

	/** The refs teaPerspectiveRefs answers for these uuids: the known ones, in the order given (round 2). */
	private void refsAnswer(TeaPerspectiveRef... known) {
		lenient().when(perspectives.teaPerspectiveRefs(eq(org), anyCollection())).thenAnswer(inv -> {
			Collection<UUID> wanted = inv.getArgument(1);
			return wanted.stream().flatMap(u -> java.util.Arrays.stream(known).filter(k -> k.uuid().equals(u))).toList();
		});
	}

	@Test
	void theWhatIfOfAMembershipResolvesThroughTheGivenPerspectives() {
		// Design 4.1 case 34.
		TeaPerspectiveRef archived = new TeaPerspectiveRef(UUID.randomUUID(), "Archived", PerspectiveType.PERSPECTIVE);
		refsAnswer(alpha, beta);
		TeaProfileData a = perspectiveRow(alpha);
		perspectiveRow(archived);
		candidates();
		assertEquals(TeaProfileSource.DEFAULT, resolver.resolveForComponent(cd).source(), "stored in {}");
		TeaResolvedProfile joined = resolver.resolveForComponentWithPerspectives(cd, List.of(alpha.uuid()));
		assertEquals(TeaProfileSource.PERSPECTIVE, joined.source());
		assertEquals(a.getUuid(), joined.profile().getUuid());
		// An archived, a foreign and an unknown uuid are no candidates (teaPerspectiveRefs drops them).
		TeaResolvedProfile skipped = resolver.resolveForComponentWithPerspectives(cd,
				List.of(archived.uuid(), UUID.randomUUID(), UUID.randomUUID()));
		assertEquals(TeaProfileSource.DEFAULT, skipped.source());

		TeaProfileData o = orgRow();
		candidates(alpha);
		assertEquals(TeaProfileSource.PERSPECTIVE, resolver.resolveForComponent(cd).source(), "stored in {Alpha}");
		TeaResolvedProfile left = resolver.resolveForComponentWithPerspectives(cd, List.of());
		assertEquals(TeaProfileSource.ORGANIZATION, left.source());
		assertEquals(o.getUuid(), left.profile().getUuid());

		componentRow(TeaComponentProfileMode.FOLLOW_PERSPECTIVE, alpha.uuid());
		long before = warnings();
		TeaResolvedProfile fallsThrough = resolver.resolveForComponentWithPerspectives(cd, List.of(beta.uuid()));
		assertEquals(TeaProfileSource.ORGANIZATION, fallsThrough.source(), "a FOLLOW of a perspective left falls through");
		assertEquals(before + 1, warnings());
	}

	@Test
	void theWhatIfWithoutAPerspectiveRowReadsItAsMissing() {
		// Design 4.1 case 35: {P, Q} with rows on both is a CONFLICT; without Q's row, P's.
		TeaProfileData a = perspectiveRow(alpha);
		perspectiveRow(beta);
		assertEquals(TeaResolutionStatus.CONFLICT, resolver.resolveForComponent(cd).status());
		TeaResolvedProfile noRow = resolver.resolveForComponentWithoutPerspectiveRow(cd, beta.uuid());
		assertEquals(TeaProfileSource.PERSPECTIVE, noRow.source());
		assertEquals(a.getUuid(), noRow.profile().getUuid());
		assertEquals(TeaResolutionStatus.CONFLICT, resolver.resolveForComponent(cd).status(), "unchanged");

		TeaProfileData follow = componentRow(TeaComponentProfileMode.FOLLOW_PERSPECTIVE, beta.uuid());
		assertEquals(TeaProfileSource.FOLLOWED_PERSPECTIVE, resolver.resolveForComponent(cd).source());
		TeaResolvedProfile fallsThrough = resolver.resolveForComponentWithoutPerspectiveRow(cd, beta.uuid());
		assertEquals(TeaProfileSource.PERSPECTIVE, fallsThrough.source());
		assertEquals(alpha.uuid(), fallsThrough.sourceObject());
		assertEquals(TeaProfileSource.FOLLOWED_PERSPECTIVE, resolver.resolveForComponent(cd).source(), "unchanged");

		rows.remove(follow);
		TeaProfileData o = orgRow();
		candidates(beta);
		assertEquals(TeaProfileSource.PERSPECTIVE, resolver.resolveForComponent(cd).source());
		TeaResolvedProfile onlyQ = resolver.resolveForComponentWithoutPerspectiveRow(cd, beta.uuid());
		assertEquals(TeaProfileSource.ORGANIZATION, onlyQ.source());
		assertEquals(o.getUuid(), onlyQ.profile().getUuid());
	}

	@Test
	void theOrganizationsSupportInjectionIsAnsweredForTheEditor() {
		// The supportMetadata normalization left with the recipe (TEA-27): only the editor's switch stays.
		injection = SupportInjectionSetting.DISABLED;
		assertEquals(SupportInjectionSetting.DISABLED, resolver.supportInjectionOf(org));
		injection = SupportInjectionSetting.ENABLED;
		assertEquals(SupportInjectionSetting.ENABLED, resolver.supportInjectionOf(org));
	}

	@Test
	void parentAndEffectiveOfEachScope() {
		componentRow(TeaComponentProfileMode.OVERRIDE, null);
		perspectiveRow(alpha);
		perspectiveRow(beta);
		TeaResolvedProfile parent = resolver.parentOfScope(org, TeaProfileScope.COMPONENT, cd.getUuid());
		assertEquals(TeaResolutionStatus.CONFLICT, parent.status(), "the component's own row is left out");
		assertEquals(TeaProfileSource.COMPONENT,
				resolver.effectiveOfScope(org, TeaProfileScope.COMPONENT, cd.getUuid()).source());

		assertEquals(TeaProfileSource.DEFAULT,
				resolver.parentOfScope(org, TeaProfileScope.PERSPECTIVE, alpha.uuid()).source());
		TeaProfileData o = orgRow();
		TeaResolvedProfile pParent = resolver.parentOfScope(org, TeaProfileScope.PERSPECTIVE, alpha.uuid());
		assertEquals(TeaProfileSource.ORGANIZATION, pParent.source());
		assertEquals(o.getUuid(), pParent.profile().getUuid());

		TeaResolvedProfile oParent = resolver.parentOfScope(org, TeaProfileScope.ORGANIZATION, null);
		assertEquals(TeaProfileSource.DEFAULT, oParent.source());
		assertNull(oParent.profile().getUuid());

		TeaResolvedProfile pEffective = resolver.effectiveOfScope(org, TeaProfileScope.PERSPECTIVE, alpha.uuid());
		assertEquals(TeaProfileSource.PERSPECTIVE, pEffective.source());
		assertEquals(alpha.uuid(), pEffective.sourceObject());
		assertEquals(TeaProfileSource.ORGANIZATION,
				resolver.effectiveOfScope(org, TeaProfileScope.PERSPECTIVE, UUID.randomUUID()).source(),
				"a perspective without a row resolves to the organization");
		assertEquals(TeaProfileSource.ORGANIZATION,
				resolver.effectiveOfScope(org, TeaProfileScope.ORGANIZATION, null).source());
	}

	// ---- TEA-27, design 4.2: the facade over the engine ----

	@Test
	void theFacadeMapsScopeSourceAndStatusByName() {
		TeaProfileData row = TeaProfileData.builtInDefault(org);
		for (ScopedSettingsSource source : ScopedSettingsSource.values()) {
			TeaResolvedProfile r = TeaProfileResolver.of(new ScopedResolution<>(ScopedResolutionStatus.RESOLVED, source,
					alpha.uuid(), row, List.of()));
			assertEquals(source.name(), r.source().name());
			assertEquals(TeaResolutionStatus.RESOLVED, r.status());
			assertEquals(alpha.uuid(), r.sourceObject());
			assertEquals(row, r.profile());
		}
		TeaResolvedProfile conflict = TeaProfileResolver.of(new ScopedResolution<TeaProfileData>(
				ScopedResolutionStatus.CONFLICT, ScopedSettingsSource.PERSPECTIVE, null, null, List.of(alpha, beta)));
		assertEquals(TeaResolutionStatus.CONFLICT, conflict.status());
		assertEquals(TeaProfileSource.PERSPECTIVE, conflict.source());
		assertEquals(List.of(alpha, beta), conflict.conflictingPerspectives());

		// Every TEA scope reaches the engine as the shared scope of the same name.
		ScopedSettingsResolver spied = spy(engine);
		ReflectionTestUtils.setField(resolver, "scopedSettingsResolver", spied);
		for (TeaProfileScope scope : TeaProfileScope.values()) {
			UUID object = TeaProfileScope.ORGANIZATION == scope ? null
					: TeaProfileScope.PERSPECTIVE == scope ? alpha.uuid() : cd.getUuid();
			resolver.effectiveOfScope(org, scope, object);
			resolver.parentOfScope(org, scope, object);
			verify(spied).effectiveOfScope(any(), eq(org), eq(SettingsScope.valueOf(scope.name())), eq(object));
			verify(spied).parentOfScope(any(), eq(org), eq(SettingsScope.valueOf(scope.name())), eq(object));
		}
	}

	@Test
	void theFacadePassesNoPreferredPerspective() {
		// Threat model T1: no TEA visibility or publishing answer can be picked by a preference.
		ScopedSettingsResolver spied = spy(engine);
		ReflectionTestUtils.setField(resolver, "scopedSettingsResolver", spied);
		TeaProfileData a = perspectiveRow(alpha);
		perspectiveRow(beta);
		assertEquals(TeaResolutionStatus.CONFLICT, resolver.resolveForComponent(cd).status());
		assertEquals(TeaResolutionStatus.CONFLICT, resolver.resolveForComponent(cd, UUID.randomUUID()).status());
		assertEquals(a.getUuid(), resolver.resolveForComponent(cd, a.getUuid()).profile().getUuid(),
				"the recorded row is still the TEA fallback");
		resolver.resolveForComponentWithRow(cd, null);
		resolver.resolveForComponentWithPerspectives(cd, List.of(alpha.uuid()));
		resolver.resolveForComponentWithoutPerspectiveRow(cd, beta.uuid());
		resolver.effectiveOfScope(org, TeaProfileScope.COMPONENT, cd.getUuid());
		resolver.parentOfScope(org, TeaProfileScope.COMPONENT, cd.getUuid());
		verify(spied, atLeastOnce()).resolveForComponent(any(), any(), any(), isNull());
		verify(spied, never()).resolveForComponent(any(), any(), any(), notNull());
	}
}
