/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.EditedListValidation;
import io.reliza.common.SafeRegex;
import io.reliza.common.SafeRegex.MatchResult;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
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

	@Autowired
	private GetComponentService getComponentService;
	
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
				log.debug("Pattern '{}' matched {} components", pattern.getPattern(), matched.size());
				
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
	
	/** How a sweep of an org's components with one pattern ended. */
	public enum PatternSweepOutcome {
		COMPLETED,
		/** The pattern does not compile; nothing matched. */
		INVALID_PATTERN,
		/**
		 * The pattern ran out of match budget on at most {@link SafeRegex#MAX_TRIPS_PER_SWEEP}
		 * component names; those components were left out, every other one was matched.
		 */
		SKIPPED_SOME,
		/**
		 * The pattern ran out of match budget on more than {@link SafeRegex#MAX_TRIPS_PER_SWEEP}
		 * component names and was abandoned: it contributes no components at all.
		 */
		ABANDONED
	}

	/** Why a name was not matched at all; a report key apart from the match results. */
	private enum UnmatchedInput { TOO_LONG }

	/** The active components a pattern matched, and how the sweep that found them ended. */
	public record PatternSweep(List<ComponentData> matched, PatternSweepOutcome outcome) {}

	/**
	 * Find all active components in an organization that match a regex pattern.
	 * 
	 * @param orgUuid organization UUID
	 * @param regexPattern regex pattern to match component names
	 * @return list of matching ComponentData
	 */
	public List<ComponentData> findComponentsByPattern(UUID orgUuid, String regexPattern) {
		return sweepComponentsByPattern(orgUuid, regexPattern).matched();
	}

	/**
	 * {@link #findComponentsByPattern}, telling the caller how the sweep ended. Matched under the
	 * step budget (see {@link SafeRegex}). A component whose name the pattern runs out of budget on
	 * is no match, the same verdict the per-component lookups give, and the sweep goes on; after
	 * more than {@link SafeRegex#MAX_TRIPS_PER_SWEEP} such trips the pattern is abandoned and
	 * contributes nothing. Either way the result does not depend on the order the components come
	 * back in, and a broken pattern costs a few budgets, not one per component of the org. A name
	 * over {@link SafeRegex#MAX_INPUT_LENGTH} trips any pattern on its own; it is no match and
	 * does not count as a trip.
	 */
	public PatternSweep sweepComponentsByPattern(UUID orgUuid, String regexPattern) {
		List<ComponentData> allComponents = componentService.listComponentDataByOrganization(orgUuid, ComponentType.COMPONENT);
		List<ComponentData> matchedComponents = new ArrayList<>();
		if (regexPattern == null) return new PatternSweep(matchedComponents, PatternSweepOutcome.INVALID_PATTERN);
		String where = "org " + orgUuid;
		int trips = 0;
		for (ComponentData c : allComponents) {
			if (c.getStatus() == StatusEnum.ARCHIVED) continue;
			String name = c.getName() == null ? "" : c.getName();
			MatchResult result = match(regexPattern, name, where);
			if (result == MatchResult.MATCH) {
				matchedComponents.add(c);
			} else if (result == MatchResult.INVALID_PATTERN) {
				return new PatternSweep(new ArrayList<>(), PatternSweepOutcome.INVALID_PATTERN);
			} else if (result == MatchResult.BUDGET_EXCEEDED && name.length() <= SafeRegex.MAX_INPUT_LENGTH
					&& ++trips > SafeRegex.MAX_TRIPS_PER_SWEEP) {
				if (SafeRegex.shouldReport(DependencyPatternService.class, PatternSweepOutcome.ABANDONED, where,
						regexPattern)) {
					log.error("Dependency pattern ({}) exceeded the match budget on more than {} component names"
							+ " -- abandoned, it pulls in no components; simplify the pattern (logged once an hour"
							+ " per pattern)", where, SafeRegex.MAX_TRIPS_PER_SWEEP);
				}
				return new PatternSweep(new ArrayList<>(), PatternSweepOutcome.ABANDONED);
			}
		}
		return new PatternSweep(matchedComponents,
				trips == 0 ? PatternSweepOutcome.COMPLETED : PatternSweepOutcome.SKIPPED_SOME);
	}

	/**
	 * Match one dependency pattern against a component name under the step budget (see
	 * {@link SafeRegex}). Patterns are written by anyone who can edit a feature set and run on the
	 * release path and across every component of the org, so an unbounded {@code (.*a){20}} would
	 * hold those threads for minutes per name. Anything but {@link MatchResult#MATCH} is no match;
	 * a runaway pattern is logged at ERROR, once an hour per pattern, because the feature set then
	 * quietly misses the components it was configured to pull in.
	 *
	 * @param where what the pattern belongs to, for the log line
	 */
	private static MatchResult match(String pattern, String componentName, String where) {
		if (componentName.length() > SafeRegex.MAX_INPUT_LENGTH) {
			// The name, not the pattern: no pattern is matched against input this long.
			if (SafeRegex.shouldReport(DependencyPatternService.class, UnmatchedInput.TOO_LONG, where, pattern)) {
				log.error("Dependency pattern ({}) not matched against a component name of {} -- names over {}"
						+ " characters match no pattern, so the component is not pulled in (logged once an hour"
						+ " per pattern)", where, SafeRegex.describeInput(componentName), SafeRegex.MAX_INPUT_LENGTH);
			}
			return MatchResult.BUDGET_EXCEEDED;
		}
		MatchResult result = SafeRegex.matches(pattern, componentName);
		if (result == MatchResult.INVALID_PATTERN) {
			// Writes validate the regex; bad data on read must not break the caller. ERROR: the
			// feature set misses every component the pattern was meant to pull in.
			if (SafeRegex.shouldReport(DependencyPatternService.class, result, where, pattern)) {
				log.error("Dependency pattern {} ({}) does not compile -- it pulls in no components; fix the"
						+ " pattern (logged once an hour per pattern)", SafeRegex.describeInput(pattern), where);
			}
		} else if (result == MatchResult.BUDGET_EXCEEDED) {
			if (SafeRegex.shouldReport(DependencyPatternService.class, result, where, pattern)) {
				log.error("Dependency pattern ({}) exceeded the match budget on component name of {} -- treated as"
						+ " no match, so the component is not pulled in; simplify the pattern (logged once an hour"
						+ " per pattern)", where, SafeRegex.describeInput(componentName));
			}
		}
		return result;
	}

	/**
	 * Write-time check of a feature set's dependency patterns, shared by every path that stores
	 * them: each new pattern must pass {@link SafeRegex#validate}. Only patterns whose text is not
	 * among the {@code stored} ones count as new (see {@link EditedListValidation}): the branch
	 * form always sends the whole list, so a legacy pattern that predates the checks must not
	 * block an unrelated edit of the feature set. A blank pattern is accepted, as it always was,
	 * and matches only an empty name.
	 *
	 * @param stored the feature set's current patterns; null when there are none yet
	 */
	public static void validatePatterns(List<DependencyPattern> patterns, List<DependencyPattern> stored)
			throws RelizaException {
		if (patterns == null) return;
		for (int i = 0; i < patterns.size(); i++) {
			if (patterns.get(i) == null) throw new RelizaException("Dependency pattern [" + i + "] is null");
		}
		EditedListValidation.validateNewEntries(patternTexts(patterns), patternTexts(stored), "Dependency patterns",
				EditedListValidation.NO_CAP, (pattern, i) -> SafeRegex.validate(pattern, "Dependency pattern [" + i + "]"));
	}

	private static List<String> patternTexts(List<DependencyPattern> patterns) {
		if (null == patterns) return null;
		List<String> texts = new ArrayList<>();
		for (DependencyPattern dp : patterns) texts.add(null == dp ? null : dp.getPattern());
		return texts;
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
	 * Reverse-lookup: find feature sets in an org whose dependency patterns match
	 * the given component by name. Does not filter by requirement status — includes
	 * patterns that would produce REQUIRED, TRANSIENT, JOB or IGNORED ChildComponents.
	 *
	 * @param orgUuid organization UUID
	 * @param componentUuid component whose inclusion we want to check
	 * @return list of feature sets whose patterns match this component
	 */
	public List<BranchData> findFeatureSetsMatchingComponentByPattern(UUID orgUuid, UUID componentUuid) {
		Optional<ComponentData> ocd = getComponentService.getComponentData(componentUuid);
		if (ocd.isEmpty()) return new ArrayList<>();
		String componentName = ocd.get().getName();
		List<BranchData> candidates = branchService.findFeatureSetDataWithDependencyPatterns(orgUuid);
		List<BranchData> matched = new ArrayList<>();
		for (BranchData fs : candidates) {
			// exclude self-dependency: the fs's own product component can't depend on itself
			if (componentUuid.equals(fs.getComponent())) continue;
			if (componentMatchesAnyPattern(componentName, fs.getDependencyPatterns())) {
				matched.add(fs);
			}
		}
		return matched;
	}

	/**
	 * Reverse-lookup: find feature sets whose dependency patterns match the given
	 * component AND resolve to the given branch (via targetBranchName or fallback to BASE).
	 *
	 * @param orgUuid organization UUID
	 * @param componentUuid component whose inclusion we want to check
	 * @param branchUuid specific branch we require the pattern to resolve to
	 * @return list of feature sets matching by component+branch via patterns
	 */
	public List<BranchData> findFeatureSetsMatchingBranchByPattern(UUID orgUuid, UUID componentUuid, UUID branchUuid) {
		Optional<ComponentData> ocd = getComponentService.getComponentData(componentUuid);
		if (ocd.isEmpty()) return new ArrayList<>();
		String componentName = ocd.get().getName();
		List<BranchData> candidates = branchService.findFeatureSetDataWithDependencyPatterns(orgUuid);
		List<BranchData> matched = new ArrayList<>();
		for (BranchData fs : candidates) {
			if (componentUuid.equals(fs.getComponent())) continue; // skip self-dependency
			if (fs.getDependencyPatterns() == null) continue;
			boolean hit = false;
			for (DependencyPattern dp : fs.getDependencyPatterns()) {
				if (dp.getPattern() == null) continue;
				if (match(dp.getPattern(), componentName, "feature set " + fs.getUuid()) != MatchResult.MATCH) continue;
				UUID resolvedBranch = findTargetBranch(componentUuid, dp);
				if (branchUuid.equals(resolvedBranch)) {
					hit = true;
					break;
				}
			}
			if (hit) matched.add(fs);
		}
		return matched;
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
			if (pattern == null || pattern.getPattern() == null) continue;
			if (match(pattern.getPattern(), componentName, "pattern " + pattern.getUuid()) == MatchResult.MATCH) {
				return true;
			}
		}
		return false;
	}
	
}
