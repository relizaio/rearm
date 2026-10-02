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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

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
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaSupportMetadata;
import io.reliza.service.TeaProfileResolver.TeaProfileSource;
import io.reliza.service.TeaProfileResolver.TeaResolutionStatus;
import io.reliza.service.TeaProfileResolver.TeaResolvedProfile;
import io.reliza.service.oss.OssPerspectiveService;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;

/**
 * Task TEA-2, design 4.1: which TEA profile a component resolves to. The rows live in an in-memory
 * list the mocked {@link TeaProfileService} answers from, so each case states its rows.
 */
class TeaProfileResolverTest {

	private TeaProfileResolver resolver;
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
		profiles = mock(TeaProfileService.class);
		perspectives = mock(OssPerspectiveService.class);
		support = mock(SupportInjectionService.class);
		components = mock(GetComponentService.class);
		ReflectionTestUtils.setField(resolver, "teaProfileService", profiles);
		ReflectionTestUtils.setField(resolver, "ossPerspectiveService", perspectives);
		ReflectionTestUtils.setField(resolver, "supportInjectionService", support);
		ReflectionTestUtils.setField(resolver, "getComponentService", components);

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
				.getLogger(TeaProfileResolver.class);
		previousLevel = logger.getLevel();
		// Without a Spring context no log4j2 configuration is loaded and the default level is ERROR.
		logger.setLevel(Level.WARN);
		logger.addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) LogManager
				.getLogger(TeaProfileResolver.class);
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

	@Test
	void supportMetadataReadsExcludeWhileTheOrgInjectionIsOff() {
		TeaProfileData o = orgRow();
		o.setSupportMetadata(TeaSupportMetadata.INCLUDE);

		injection = SupportInjectionSetting.DISABLED;
		assertEquals(TeaSupportMetadata.EXCLUDE, resolver.resolveForComponent(cd).profile().getSupportMetadata());
		assertEquals(TeaSupportMetadata.INCLUDE, rows.get(0).getSupportMetadata(), "the stored row is untouched");

		injection = SupportInjectionSetting.ENABLED;
		assertEquals(TeaSupportMetadata.INCLUDE, resolver.resolveForComponent(cd).profile().getSupportMetadata());
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
}
