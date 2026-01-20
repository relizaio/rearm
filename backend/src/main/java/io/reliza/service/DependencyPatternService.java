/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.BranchData.DependencyPattern;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class DependencyPatternService {
	
	@Autowired
	private BranchService branchService;
	
	@Autowired
	private ComponentService componentService;
	
	/**
	 * Resolve effective dependencies for a feature set.
	 * Combines: pattern-resolved dependencies + overrides + manual dependencies
	 * 
	 * @param featureSet the feature set (branch) to resolve dependencies for
	 * @return list of effective ChildComponent dependencies
	 */
	public List<ChildComponent> resolveEffectiveDependencies(BranchData featureSet) {
		Map<UUID, ChildComponent> effectiveDeps = new LinkedHashMap<>();
		log.debug("Resolving effective dependencies for feature set {} with {} patterns", 
			featureSet.getUuid(), featureSet.getDependencyPatterns() != null ? featureSet.getDependencyPatterns().size() : 0);
		
		// 1. Resolve patterns first
		if (featureSet.getDependencyPatterns() != null) {
			for (DependencyPattern pattern : featureSet.getDependencyPatterns()) {
				List<ComponentData> matched = findComponentsByPattern(featureSet.getOrg(), pattern.getPattern());
				log.info("Pattern '{}' matched {} components", pattern.getPattern(), matched.size());
				
				for (ComponentData comp : matched) {
					// Skip if same as product component (no self-dependency)
					if (comp.getUuid().equals(featureSet.getComponent())) continue;
					
					// Find target branch
					UUID branchUuid = findTargetBranch(comp.getUuid(), pattern);
					if (branchUuid == null) {
						log.info("No matching branch found for component {} with pattern {}", 
							comp.getName(), pattern.getPattern());
						continue;
					}
					
					// Check if branch is archived
					Optional<BranchData> branchData = branchService.getBranchData(branchUuid);
					if (branchData.isEmpty() || branchData.get().getStatus() == StatusEnum.ARCHIVED) {
						continue;
					}
					
					StatusEnum status = pattern.getDefaultStatus() != null ? 
						pattern.getDefaultStatus() : StatusEnum.REQUIRED;
					
					ChildComponent cc = ChildComponent.builder()
						.uuid(comp.getUuid())
						.branch(branchUuid)
						.status(status)
						.build();
					
					effectiveDeps.put(comp.getUuid(), cc);
				}
			}
		}
		
		// 2. Manual dependencies override everything
		if (featureSet.getDependencies() != null) {
			for (ChildComponent manual : featureSet.getDependencies()) {
				effectiveDeps.put(manual.getUuid(), manual);
			}
		}
		
		return new ArrayList<>(effectiveDeps.values());
	}
	
	/**
	 * Find all active components in an organization that match a regex pattern.
	 * 
	 * @param orgUuid organization UUID
	 * @param regexPattern regex pattern to match component names
	 * @return list of matching ComponentData
	 */
	public List<ComponentData> findComponentsByPattern(UUID orgUuid, String regexPattern) {
		List<ComponentData> allComponents = componentService.listComponentDataByOrganization(orgUuid, ComponentType.COMPONENT);
		List<ComponentData> matchedComponents = new ArrayList<>();
		try {
			Pattern pattern = Pattern.compile(regexPattern);
			matchedComponents = allComponents.stream()
				.filter(c -> c.getStatus() != StatusEnum.ARCHIVED)
				.filter(c -> pattern.matcher(c.getName()).matches())
				.collect(Collectors.toList());
		} catch (PatternSyntaxException e) {
			log.error("Invalid regex pattern: {}", regexPattern, e);
		}
		return matchedComponents;
	}
	
	/**
	 * Find the target branch for a component based on the pattern's branch selection criteria.
	 * 
	 * @param componentUuid the component UUID
	 * @param pattern the dependency pattern with branch selection criteria
	 * @return branch UUID or null if not found
	 */
	private UUID findTargetBranch(UUID componentUuid, DependencyPattern pattern) {
		// By branch name (e.g., "develop")
		if (pattern.getTargetBranchName() != null) {
			Optional<BranchData> branch = branchService.findBranchByComponentAndName(
				componentUuid, pattern.getTargetBranchName());
			if (branch.isPresent()) {
				return branch.get().getUuid();
			}
			// Branch name specified but not found - check fallback setting
			// Default is ENABLED (fall back to BASE) for backward compatibility
			if (pattern.getFallbackToBase() == BranchData.FallbackToBase.DISABLED) {
				return null;  // Skip this component
			}
		}
		
		// Default: BASE branch (either no branch name specified, or fallback is enabled)
		Optional<Branch> baseBranch = branchService.getBaseBranchOfComponent(componentUuid);
		return baseBranch.map(Branch::getUuid).orElse(null);
	}
	
	/**
	 * Check if a component matches any pattern in a feature set's dependency patterns.
	 * 
	 * @param componentName the component name to check
	 * @param patterns the list of dependency patterns
	 * @return true if the component matches at least one pattern
	 */
	public boolean componentMatchesAnyPattern(String componentName, List<DependencyPattern> patterns) {
		if (patterns == null || patterns.isEmpty()) {
			return false;
		}
		
		for (DependencyPattern pattern : patterns) {
			try {
				if (Pattern.compile(pattern.getPattern()).matcher(componentName).matches()) {
					return true;
				}
			} catch (PatternSyntaxException e) {
				log.warn("Invalid pattern: {}", pattern.getPattern());
			}
		}
		return false;
	}
	
}
