/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.ComponentRepository;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.TeaProfileService.TeaEffectiveVisibility;
import io.reliza.service.TeaProfileService.TeaExposureChange;
import io.reliza.service.TeaProfileService.TeaPublicPath;
import io.reliza.service.oss.OssPerspectiveService;
import jakarta.persistence.EntityManager;

/**
 * Task TEA-14, decision 3 (architecture rows 22 to 24 and 50): the TEA membership gate of TEA-9 runs
 * inside the perspective writers of ComponentService, under the component row lock, on the set as
 * it will be written; the creation form has no gate; a SYSTEM join that widens to PUBLIC is written
 * with one WARN line.
 */
class ComponentServiceMembershipGateTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String WARN_TEXT = "joins the perspectives of its board and resolves to the PUBLIC TEA profile of";

	private final UUID org = UUID.randomUUID();
	private final UUID a = UUID.randomUUID();
	private final UUID b = UUID.randomUUID();
	private final UUID c = UUID.randomUUID();

	private ComponentRepository repository;
	private OssPerspectiveService ossPerspectiveService;
	private TeaProfileService teaProfileService;
	private ComponentService service;
	private Component row;
	private CapturingAppender logs;
	private Level levelBefore;

	/** In-memory appender on ComponentService's own logger; only the level and the message matter. */
	private static class CapturingAppender extends AbstractAppender {
		private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
		CapturingAppender() { super("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}

	@BeforeEach
	void setUp() {
		repository = mock(ComponentRepository.class);
		ossPerspectiveService = mock(OssPerspectiveService.class);
		teaProfileService = mock(TeaProfileService.class);
		GetComponentService getComponentService = mock(GetComponentService.class);
		service = new ComponentService(repository, "https://rearm.example.com");
		ReflectionTestUtils.setField(service, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(service, "auditService", mock(AuditService.class));
		ReflectionTestUtils.setField(service, "entityManager", mock(EntityManager.class));
		// no organization to resolve a sid policy against: the collision check returns early
		ReflectionTestUtils.setField(service, "getOrganizationService", mock(GetOrganizationService.class));
		ReflectionTestUtils.setField(service, "ossPerspectiveService", ossPerspectiveService);
		ReflectionTestUtils.setField(service, "teaProfileService", teaProfileService);

		row = new Component();
		when(repository.findByIdWriteLocked(row.getUuid())).thenReturn(Optional.of(row));
		when(getComponentService.getComponent(row.getUuid())).thenReturn(Optional.of(row));
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(repository.currentTeaUuidForUpdate(any())).thenReturn(Optional.empty());
		// every wanted perspective is assignable unless a test says otherwise
		when(ossPerspectiveService.assignablePerspectiveUuids(eq(org), anyCollection()))
				.thenAnswer(inv -> new ArrayList<>(inv.<Collection<UUID>>getArgument(1)));

		logs = new CapturingAppender();
		logs.start();
		org.apache.logging.log4j.core.Logger target = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(ComponentService.class);
		levelBefore = target.getLevel();
		target.addAppender(logs);
		// attach first, then set the level: without a configuration the logger answers ERROR only
		Configurator.setLevel(ComponentService.class.getName(), Level.WARN);
	}

	@AfterEach
	void tearDown() {
		((org.apache.logging.log4j.core.Logger) LogManager.getLogger(ComponentService.class)).removeAppender(logs);
		Configurator.setLevel(ComponentService.class.getName(), levelBefore);
		logs.stop();
	}

	/** The row as committed, which the locked read answers: {@code stored} is its perspective set. */
	private void stored(UUID... stored) {
		ComponentData cd = new ComponentData();
		cd.setUuid(row.getUuid());
		cd.setName("widget");
		cd.setOrg(org);
		cd.setType(ComponentType.COMPONENT);
		cd.setPerspectives(new LinkedHashSet<>(List.of(stored)));
		row.setRecordData(Utils.dataToRecord(cd));
	}

	private Set<UUID> savedPerspectives() {
		ArgumentCaptor<Component> saved = ArgumentCaptor.forClass(Component.class);
		verify(repository).save(saved.capture());
		return ComponentData.dataFromRecord(saved.getValue()).getPerspectives();
	}

	private static TeaExposureChange change(TeaPublicPath path) {
		return new TeaExposureChange(TeaEffectiveVisibility.PRIVATE,
				TeaPublicPath.NONE == path ? TeaEffectiveVisibility.PRIVATE : TeaEffectiveVisibility.PUBLIC,
				path, null, null, 0, List.of());
	}

	private List<LogEvent> warnings() {
		return logs.events.stream().filter(e -> e.getLevel() == Level.WARN
				&& e.getMessage().getFormattedMessage().contains(WARN_TEXT)).toList();
	}

	// row 22
	@Test
	void theGateJudgesTheStoredSetReadUnderTheLock() throws Exception {
		// the caller's earlier read saw {A}; the row as committed, read under the lock, holds {A, B}
		stored(a, b);
		when(teaProfileService.requireMembershipAllowed(any(), any(), any())).thenReturn(change(TeaPublicPath.NONE));

		service.changePerspectives(row.getUuid(), Set.of(c), Set.of(), TeaCaller.SCOPE_ADMIN, WU);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<UUID>> judged = ArgumentCaptor.forClass(Collection.class);
		InOrder order = inOrder(repository, teaProfileService);
		order.verify(repository).findByIdWriteLocked(row.getUuid());
		order.verify(teaProfileService, times(1)).requireMembershipAllowed(any(ComponentData.class), judged.capture(),
				eq(TeaCaller.SCOPE_ADMIN));
		order.verify(repository).save(any());
		assertEquals(Set.of(a, b, c), Set.copyOf(judged.getValue()), "stored plus the new one, as it will be written");
		assertEquals(Set.of(a, b, c), savedPerspectives());
	}

	// row 22, the refusal
	@Test
	void aRefusalFromTheGateWritesNothing() throws Exception {
		stored(a, b);
		when(teaProfileService.requireMembershipAllowed(any(), any(), eq(TeaCaller.SCOPE_ADMIN))).thenThrow(
				new RelizaException("Only an organization admin can change the perspectives of component widget"));

		RelizaException e = assertThrows(RelizaException.class,
				() -> service.changePerspectives(row.getUuid(), Set.of(c), Set.of(), TeaCaller.SCOPE_ADMIN, WU));

		assertTrue(e.getMessage().startsWith("Only an organization admin"), e.getMessage());
		verify(repository, never()).save(any());
	}

	@Test
	void aDeltaThatChangesNothingAsksNoGate() throws Exception {
		stored(a, b);

		service.changePerspectives(row.getUuid(), Set.of(a), Set.of(c), TeaCaller.SCOPE_ADMIN, WU);

		verifyNoInteractions(teaProfileService);
		verify(repository, never()).save(any());
	}

	// row 23
	@Test
	void setPerspectivesSweepsThenJudgesThenWrites() throws Exception {
		UUID stale = UUID.randomUUID();
		stored();
		when(ossPerspectiveService.assignablePerspectiveUuids(eq(org), anyCollection())).thenReturn(List.of(a));
		when(teaProfileService.requireMembershipAllowed(any(), any(), any())).thenReturn(change(TeaPublicPath.NONE));

		service.setPerspectives(row.getUuid(), List.of(a, stale), TeaCaller.ORG_ADMIN, WU);

		verify(teaProfileService).requireMembershipAllowed(any(ComponentData.class), eq(List.of(a)), eq(TeaCaller.ORG_ADMIN));
		assertEquals(Set.of(a), savedPerspectives());
	}

	// row 24
	@Test
	void theCreationFormHasNoGate() throws Exception {
		stored();

		service.setPerspectives(row.getUuid(), List.of(a), WU);

		verifyNoInteractions(teaProfileService);
		assertEquals(Set.of(a), savedPerspectives());
	}

	// row 50
	@Test
	void aSystemJoinThatWidensIsLoggedAndWritten() throws Exception {
		stored(a);
		when(teaProfileService.requireMembershipAllowed(any(), any(), eq(TeaCaller.SYSTEM)))
				.thenReturn(change(TeaPublicPath.MEMBERSHIP_RESOLVES_PUBLIC));
		when(teaProfileService.sourceLabelOf(any())).thenReturn("perspective Payments");

		service.changePerspectives(row.getUuid(), Set.of(b), Set.of(), TeaCaller.SYSTEM, WU);

		verify(teaProfileService, times(1)).requireMembershipAllowed(any(ComponentData.class), eq(Set.of(a, b)),
				eq(TeaCaller.SYSTEM));
		List<LogEvent> warned = warnings();
		assertEquals(1, warned.size(), logs.events.toString());
		assertTrue(warned.get(0).getMessage().getFormattedMessage().contains(row.getUuid().toString())
				&& warned.get(0).getMessage().getFormattedMessage().contains("perspective Payments"),
				warned.get(0).getMessage().getFormattedMessage());
		assertEquals(Set.of(a, b), savedPerspectives());
	}

	// row 50, no widening
	@Test
	void aSystemJoinThatDoesNotWidenIsWrittenWithoutAWarning() throws Exception {
		stored(a);
		when(teaProfileService.requireMembershipAllowed(any(), any(), eq(TeaCaller.SYSTEM)))
				.thenReturn(change(TeaPublicPath.NONE));

		service.changePerspectives(row.getUuid(), Set.of(b), Set.of(), TeaCaller.SYSTEM, WU);

		assertTrue(warnings().isEmpty(), logs.events.toString());
		assertEquals(Set.of(a, b), savedPerspectives());
	}
}
