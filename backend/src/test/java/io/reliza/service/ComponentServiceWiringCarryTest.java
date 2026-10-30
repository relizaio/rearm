/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.GlobalInputEventRef;
import io.reliza.model.ComponentData.ReleaseInputEvent;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ComponentDto;
import io.reliza.repositories.ComponentRepository;
import io.reliza.service.ComponentService.WiringWrite;
import io.reliza.service.oss.OssPerspectiveService;
import jakarta.persistence.EntityManager;

/**
 * TEA-7, round 5, unit (an), T-13: every whole-record save of a component carries the stored TEA
 * id, and every save but the funnel's carries the five keys only the funnel writes (name,
 * approval policy, actions, rules, refs) from the row it locks, so a stale copy restores nothing;
 * the funnel reads the row locked before it decides anything and writes in WRITE mode. The
 * repository answers {@code rowJson} for the locked read; the managed instance starts stale and a
 * refresh loads the row, as the database would. No Spring.
 */
class ComponentServiceWiringCarryTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private final ComponentRepository repository = mock(ComponentRepository.class);
	private final GetComponentService getComponentService = mock(GetComponentService.class);
	private final EntityManager entityManager = mock(EntityManager.class);
	private final OssPerspectiveService ossPerspectiveService = mock(OssPerspectiveService.class);
	private ComponentService service;
	private Component component;
	private Map<String, Object> row;
	private final UUID teaUuid = UUID.randomUUID();
	private final UUID user = UUID.randomUUID();
	private final UUID otherUser = UUID.randomUUID();

	private static ReleaseInputEvent rule(String name) {
		ReleaseInputEvent r = new ReleaseInputEvent();
		r.setUuid(UUID.randomUUID());
		r.setName(name);
		r.setCelExpression("true");
		r.setEnabled(true);
		r.setOutputEvents(Set.of());
		return r;
	}

	private static GlobalInputEventRef ref(UUID uuid) {
		GlobalInputEventRef ref = new GlobalInputEventRef();
		ref.setUuid(uuid);
		ref.setDisabled(true);
		return ref;
	}

	private ComponentData data(String name, String ruleName, UUID policy, Set<UUID> users) {
		ComponentData cd = new ComponentData();
		cd.setUuid(component.getUuid());
		cd.setName(name);
		cd.setOrg(UUID.randomUUID());
		cd.setType(ComponentType.COMPONENT);
		cd.setApprovalPolicy(policy);
		cd.setOutputTriggers(new ArrayList<>(List.of(ReleaseOutputEvent.builder().uuid(UUID.randomUUID()).name("a-" + name)
				.type(EventType.EMAIL_NOTIFICATION).users(new LinkedHashSet<>(users)).build())));
		cd.setReleaseInputTriggers(new ArrayList<>(List.of(rule(ruleName))));
		cd.setGlobalInputEventRefs(new ArrayList<>(List.of(ref(UUID.randomUUID()))));
		return cd;
	}

	@BeforeEach
	void wire() {
		service = new ComponentService(repository, "https://rearm.example.com");
		ReflectionTestUtils.setField(service, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(service, "auditService", mock(AuditService.class));
		ReflectionTestUtils.setField(service, "entityManager", entityManager);
		ReflectionTestUtils.setField(service, "getOrganizationService", mock(GetOrganizationService.class));
		ReflectionTestUtils.setField(service, "ossPerspectiveService", ossPerspectiveService);
		component = new Component();
		component.setRecordData(Utils.dataToRecord(data("stale", "r-stale", UUID.randomUUID(), Set.of(user))));
		Map<String, Object> fresh = new LinkedHashMap<>(Utils.dataToRecord(data("fresh", "r-fresh", UUID.randomUUID(),
				Set.of(user, otherUser))));
		fresh.put("teaUuid", teaUuid.toString());
		row = fresh;
		when(getComponentService.getComponent(component.getUuid())).thenReturn(Optional.of(component));
		when(repository.findByIdWriteLocked(component.getUuid())).thenReturn(Optional.of(component));
		when(repository.lockRowForUpdate(component.getUuid())).thenAnswer(inv -> Optional.of(Utils.OM.writeValueAsString(row)));
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		doAnswer(inv -> {
			component.setRecordData(new LinkedHashMap<>(row));
			return null;
		}).when(entityManager).refresh(component);
		when(ossPerspectiveService.assignablePerspectiveUuids(any(), anyCollection())).thenReturn(List.of());
	}

	private ComponentData saved() {
		ArgumentCaptor<Component> saved = ArgumentCaptor.forClass(Component.class);
		verify(repository).save(saved.capture());
		return ComponentData.dataFromRecord(saved.getValue());
	}

	private ComponentData rowData() {
		Component c = new Component();
		c.setRecordData(new LinkedHashMap<>(row));
		return ComponentData.dataFromRecord(c);
	}

	@Test
	void carryWritesTheStoredFiveKeysAndTheTeaIdAndLeavesTheRest() {
		Map<String, Object> caller = new LinkedHashMap<>(component.getRecordData());
		caller.put("visibilitySetting", "PUBLIC");
		caller.put("teaUuid", UUID.randomUUID().toString());
		Map<String, Object> rowWithoutRefs = new LinkedHashMap<>(row);
		rowWithoutRefs.remove("globalInputEventRefs");
		Map<String, Object> out = ComponentService.withStoredKeys(caller,
				Optional.of(Utils.OM.writeValueAsString(rowWithoutRefs)), WiringWrite.CARRY);
		for (String key : List.of("name", "approvalPolicy", "outputTriggers", "releaseInputTriggers", "teaUuid")) {
			assertEquals(Utils.OM.convertValue(rowWithoutRefs.get(key), Object.class), out.get(key), key);
		}
		assertFalse(out.containsKey("globalInputEventRefs"), "absent stays absent");
		assertEquals("PUBLIC", out.get("visibilitySetting"), "every other key is the caller's");
	}

	@Test
	void writeWritesTheCallersFiveKeysAndCarriesTheTeaIdOnly() {
		Map<String, Object> caller = new LinkedHashMap<>(component.getRecordData());
		Map<String, Object> out = ComponentService.withStoredKeys(caller, Optional.of(Utils.OM.writeValueAsString(row)),
				WiringWrite.WRITE);
		for (String key : ComponentService.WIRING_KEYS) {
			assertEquals(caller.get(key), out.get(key), key);
		}
		assertEquals(teaUuid.toString(), out.get("teaUuid"));
	}

	@Test
	void theFunnelReadsTheRowLockedFirstAndWritesTheCallersLists() throws Exception {
		ReleaseInputEvent mine = rule("r-mine");
		service.updateComponent(ComponentDto.builder().uuid(component.getUuid()).releaseInputTriggers(new ArrayList<>(
				List.of(mine))).build(), WU);
		InOrder order = inOrder(repository, getComponentService, entityManager);
		// the funnel's locked read (TPF-38's bounded lock, then the refresh), then the carry's native read
		order.verify(repository).findByIdWriteLocked(component.getUuid());
		order.verify(entityManager).refresh(component);
		order.verify(getComponentService).getComponent(component.getUuid());
		order.verify(repository).lockRowForUpdate(component.getUuid());
		ComponentData saved = saved();
		assertEquals(List.of("r-mine"), saved.getReleaseInputTriggers().stream().map(ReleaseInputEvent::getName).toList(),
				"WRITE: the caller's rules");
		ComponentData fresh = rowData();
		assertEquals(fresh.getName(), saved.getName(), "decided on the locked row, not the stale copy");
		assertEquals(fresh.getOutputTriggers(), saved.getOutputTriggers());
		assertEquals(fresh.getApprovalPolicy(), saved.getApprovalPolicy());
		assertEquals(teaUuid, saved.getTeaUuid());
	}

	@Test
	void aWholeRecordSaveFromAStaleCopyRestoresNothing() throws Exception {
		service.setPerspectives(component.getUuid(), List.of(), WU);
		ComponentData saved = saved();
		ComponentData fresh = rowData();
		assertEquals("fresh", saved.getName());
		assertEquals(fresh.getApprovalPolicy(), saved.getApprovalPolicy());
		assertEquals(fresh.getOutputTriggers(), saved.getOutputTriggers());
		assertEquals(fresh.getReleaseInputTriggers(), saved.getReleaseInputTriggers());
		assertEquals(fresh.getGlobalInputEventRefs(), saved.getGlobalInputEventRefs());
		assertEquals(teaUuid, saved.getTeaUuid());
	}

	@Test
	void aRefsListCarryingOneUuidTwiceIsRefused() {
		UUID twice = UUID.randomUUID();
		RelizaException e = assertThrows(RelizaException.class, () -> service.updateComponent(ComponentDto.builder()
				.uuid(component.getUuid()).globalInputEventRefs(new ArrayList<>(List.of(ref(twice), ref(twice)))).build(), WU));
		assertEquals("Global rule ref uuid " + twice + " appears more than once on this component", e.getMessage());
	}

	/** The funnel's own action check (TEA-7 round 8, F-2): the mutation answers main's text first, so this pins it. */
	@Test
	void anActionsListCarryingOneUuidTwiceIsRefused() {
		UUID twice = UUID.randomUUID();
		ReleaseOutputEvent first = ReleaseOutputEvent.builder().uuid(twice).name("a-1").type(EventType.EMAIL_NOTIFICATION)
				.users(new LinkedHashSet<>(Set.of(user))).build();
		ReleaseOutputEvent second = ReleaseOutputEvent.builder().uuid(twice).name("a-2").type(EventType.EMAIL_NOTIFICATION)
				.users(new LinkedHashSet<>(Set.of(user))).build();
		RelizaException e = assertThrows(RelizaException.class, () -> service.updateComponent(ComponentDto.builder()
				.uuid(component.getUuid()).outputTriggers(new ArrayList<>(List.of(first, second))).build(), WU));
		assertEquals("Output event uuid " + twice + " appears more than once on this component", e.getMessage());
		verify(repository, never()).save(any());
	}

	@Test
	void removingAUserFromTheActionsWritesTheLockedRowWithOnlyTheRecipientsChanged() throws Exception {
		ReflectionTestUtils.invokeMethod(service, "removeUserFromActions", component.getUuid(), user, WU);
		ComponentData saved = saved();
		ComponentData fresh = rowData();
		assertEquals(Set.of(otherUser), saved.getOutputTriggers().get(0).getUsers());
		assertEquals(fresh.getOutputTriggers().get(0).getUuid(), saved.getOutputTriggers().get(0).getUuid());
		assertEquals(fresh.getReleaseInputTriggers(), saved.getReleaseInputTriggers());
		assertEquals("fresh", saved.getName());
	}
}
