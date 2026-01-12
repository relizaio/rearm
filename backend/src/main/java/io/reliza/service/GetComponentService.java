/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.repositories.ComponentRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class GetComponentService {
	private final ComponentRepository repository;
	
	@Autowired
	@Lazy
	private SharedReleaseService sharedReleaseService;
	
	@Autowired
	@Lazy
	private BranchService branchService;
	
	@Autowired
	@Lazy
	private DependencyPatternService dependencyPatternService;

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
		Optional<ComponentData> opd = getComponentData(perspectiveUuid);
		if (opd.isPresent() && opd.get().getType() == ComponentData.ComponentType.PRODUCT) {
			return listComponentsByProduct(perspectiveUuid);
		}
		List<ComponentData> directComponents = repository.findComponentsByPerspective(perspectiveUuid.toString()).stream()
				.map(ComponentData::dataFromRecord).toList();
		
		Set<UUID> allComponentUuids = new LinkedHashSet<>();
		for (ComponentData cd : directComponents) {
			allComponentUuids.add(cd.getUuid());
			if (cd.getType() == ComponentData.ComponentType.PRODUCT) {
				Set<UUID> productComponents = sharedReleaseService.obtainComponentsOfProductOrComponent(cd.getUuid(), allComponentUuids);
				allComponentUuids.addAll(productComponents);
			}
		}
		
		return getListOfComponentData(allComponentUuids);
	}
	
	public List<ComponentData> listComponentsByProduct (UUID productUuid) {
		Set<UUID> allComponentUuids = new LinkedHashSet<>();
		allComponentUuids.add(productUuid);
		Set<UUID> productComponents = sharedReleaseService.obtainComponentsOfProductOrComponent(productUuid, allComponentUuids);
		allComponentUuids.addAll(productComponents);
		
		// Get feature sets of the product and resolve their effective dependencies
		List<Branch> featureSets = branchService.listBranchesOfComponent(productUuid, StatusEnum.ACTIVE);
		for (Branch fs : featureSets) {
			BranchData fsData = BranchData.branchDataFromDbRecord(fs);
			List<ChildComponent> effectiveDeps = dependencyPatternService.resolveEffectiveDependencies(fsData);
			for (ChildComponent dep : effectiveDeps) {
				if (!allComponentUuids.contains(dep.getUuid())) {
					allComponentUuids.add(dep.getUuid());
					// Recursively get child components of this dependency
					Set<UUID> childComponents = sharedReleaseService.obtainComponentsOfProductOrComponent(dep.getUuid(), allComponentUuids);
					allComponentUuids.addAll(childComponents);
				}
			}
		}
		
		return getListOfComponentData(allComponentUuids);
	}
	
}
