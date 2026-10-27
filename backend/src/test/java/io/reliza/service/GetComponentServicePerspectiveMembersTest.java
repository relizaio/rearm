/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.repositories.ComponentRepository;
import io.reliza.service.oss.OssPerspectiveService;

/**
 * Task TEA-8, design round 2 section 3.7 (D10): the members of a perspective are the components of
 * the perspective's own organization among the rows that hold its uuid. A foreign row is a stale
 * membership, ignored by every by-perspective reader; a perspective without a row has no members; a
 * product is still read through the product.
 */
public class GetComponentServicePerspectiveMembersTest {

	private final UUID orgA = UUID.randomUUID();
	private final UUID orgB = UUID.randomUUID();
	private final UUID perspective = UUID.randomUUID();

	private ComponentRepository repository;
	private OssPerspectiveService perspectives;
	private Component cA;
	private Component cB;

	private static Component row(UUID uuid, UUID org, ComponentType type, Set<UUID> perspectiveUuids) {
		ComponentData cd = new ComponentData();
		cd.setUuid(uuid);
		cd.setOrg(org);
		cd.setName("tea8-" + uuid);
		cd.setType(type);
		cd.setPerspectives(perspectiveUuids);
		Component c = new Component();
		c.setUuid(uuid);
		c.setSchemaVersion(0);
		c.setRecordData(Utils.dataToRecord(cd));
		return c;
	}

	@BeforeEach
	void setUp() {
		repository = mock(ComponentRepository.class);
		perspectives = mock(OssPerspectiveService.class);
		cA = row(UUID.randomUUID(), orgA, ComponentType.COMPONENT, Set.of(perspective));
		cB = row(UUID.randomUUID(), orgB, ComponentType.COMPONENT, Set.of(perspective));
		Map<UUID, Component> byUuid = Map.of(cA.getUuid(), cA, cB.getUuid(), cB);
		// P is not a product: findById(P) answers empty.
		when(repository.findById(any())).thenAnswer(i -> Optional.ofNullable(byUuid.get(i.getArgument(0))));
		when(repository.findComponentsByPerspective(perspective.toString())).thenReturn(List.of(cA, cB));
		when(repository.findAllById(anyIterable())).thenAnswer(i -> {
			List<Component> out = new ArrayList<>();
			for (Object u : (Iterable<?>) i.getArgument(0)) {
				if (byUuid.containsKey(u)) out.add(byUuid.get(u));
			}
			return out;
		});
	}

	private GetComponentService service() {
		GetComponentService svc = new GetComponentService(repository);
		ReflectionTestUtils.setField(svc, "ossPerspectiveService", perspectives);
		return svc;
	}

	@Test
	void membersOfAPerspectiveAreItsOrganizationsComponents() {
		when(perspectives.orgOfPerspective(perspective)).thenReturn(Optional.of(orgA));

		List<ComponentData> members = service().listComponentsByPerspective(perspective);

		assertEquals(List.of(cA.getUuid()), members.stream().map(ComponentData::getUuid).toList());
	}

	@Test
	void aPerspectiveWithoutARowHasNoMembers() {
		when(perspectives.orgOfPerspective(perspective)).thenReturn(Optional.empty());

		assertTrue(service().listComponentsByPerspective(perspective).isEmpty());
		verify(repository, never()).findComponentsByPerspective(anyString());
	}

	@Test
	void aProductIsStillReadThroughTheProduct() {
		UUID product = UUID.randomUUID();
		Component productRow = row(product, orgA, ComponentType.PRODUCT, Set.of());
		when(repository.findById(product)).thenReturn(Optional.of(productRow));
		List<ComponentData> throughTheProduct = List.of(ComponentData.dataFromRecord(cA));
		GetComponentService svc = new GetComponentService(repository) {
			@Override public List<ComponentData> listComponentsByProduct(UUID productUuid) {
				return product.equals(productUuid) ? throughTheProduct : List.of();
			}
		};
		ReflectionTestUtils.setField(svc, "ossPerspectiveService", perspectives);

		assertEquals(throughTheProduct, svc.listComponentsByPerspective(product));
		verify(perspectives, never()).orgOfPerspective(any());
	}
}
