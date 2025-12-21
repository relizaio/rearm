/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.repositories.ComponentRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class GetComponentService {
	private final ComponentRepository repository;

	GetComponentService(
		ComponentRepository repository
	) {
	    this.repository = repository;
	}
	
	public Optional<Component> getComponent (UUID uuid) {
		return repository.findById(uuid);
	}
	
	public Optional<ComponentData> getComponentData (UUID uuid) {
		Optional<ComponentData> pData = Optional.empty();
		Optional<Component> p = getComponent(uuid);
		if (p.isPresent()) {
			pData = Optional
							.of(
								ComponentData
									.dataFromRecord(p
										.get()
								));
		}
		return pData;
	}
	
	/**
	 * Get Component by branch UUID
	 * @param branchUuid - UUID of branch for which we're locating parent product
	 * @return Optional of Component
	 */
	private Optional<Component> getComponentByBranch (UUID branchUuid) {
		Optional<Component> proj = Optional.empty();
		List<Component> Components = repository.findComponentsByBranch(branchUuid.toString());
		if (!Components.isEmpty()) {
			proj = Optional.of(Components.get(0));
		}
		return proj;
	}
	
	public Optional<ComponentData> getComponentDataByBranch (UUID branchUuid) {
		Optional<ComponentData> opd = Optional.empty();
		Optional<Component> proj = getComponentByBranch(branchUuid);
		if (proj.isPresent()) {
			opd = Optional.of(ComponentData.dataFromRecord(proj.get()));
		}
		return opd;
	}
	
	private List<Component> getListOfComponents (Collection<UUID> components) {
		return (List<Component>) repository.findAllById(components);
	}
	
	public List<ComponentData> getListOfComponentData (Collection<UUID> ComponentUuids) {
		List<Component> Components = getListOfComponents(ComponentUuids);
		return Components.stream()
						.map(ComponentData::dataFromRecord)
						.collect(Collectors.toList());
	}
	
	public List<ComponentData> listComponentsByPerspective (UUID perspectiveUuid) {
		return repository.findComponentsByPerspective(perspectiveUuid.toString()).stream()
				.map(ComponentData::dataFromRecord).toList();
	}
	
}
