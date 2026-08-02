/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.UserGroupStatus;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.GlobalTeamAssignmentRule;
import io.reliza.model.UserGroupData;
import io.reliza.model.WhoUpdated;
import lombok.extern.slf4j.Slf4j;

/**
 * Org-wide team-assignment rules (RFC Phase 5 / T2): assign a durable owner team
 * by pattern instead of picking one by hand on every component.
 *
 * <p>Deliberately shaped after {@code OrgApprovalPolicyService} -- same
 * fully-anchored regex convention, same {@code ANY} type filter, same
 * first-match-wins priority contract -- so operators learn one rule model rather
 * than two subtly different ones.
 *
 * <p><strong>Precedence</strong> (DECIDED 2026-07-29, "Option A"): a per-component
 * stored owner always wins; only when there is none does the rule list decide.
 * A match SETS the owner rather than living in a parallel "assigned team" field,
 * so there is exactly one answer to "who owns this".
 *
 * <p>Resolution is pure and read-time -- nothing is written onto the component.
 * Editing a pattern therefore takes effect immediately, a rule and a stored value
 * can never drift apart, and no bulk write can corrupt an inventory.
 */
@Slf4j
@Service
public class OrgTeamAssignmentRuleService {

	/**
	 * Longest pattern we will accept. A cap alone does not stop catastrophic
	 * backtracking, but it bounds how much rope an operator gets and keeps the
	 * cache keys small.
	 */
	public static final int MAX_PATTERN_LENGTH = 512;

	/**
	 * Match-step budget. Guards against catastrophic backtracking: a pattern like
	 * {@code (.*a){20}} is perfectly valid and compiles fine, but can run for
	 * minutes on a short input. Since rules are evaluated on EVERY ownership read
	 * of EVERY component in the org, an unbounded match would let one saved rule
	 * burn request threads indefinitely -- a denial of service that an org admin
	 * could trigger for the whole instance. The budget makes a pathological
	 * pattern fail fast (and loudly) instead.
	 */
	private static final int MATCH_STEP_BUDGET = 200_000;

	/**
	 * Compiled-pattern cache. Without it every rule is recompiled for every
	 * component on the report path. Bounded by (orgs x rules) and keyed by the
	 * pattern text, so identical patterns across orgs share one entry.
	 */
	private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

	@Autowired
	private UserGroupService userGroupService;

	@Autowired
	private OrganizationService organizationService;

	/** A rule that matched, together with the team it resolves to. */
	public record TeamAssignmentMatch (GlobalTeamAssignmentRule rule, UserGroupData team) {}

	/** Raised when a pattern exceeds {@link #MATCH_STEP_BUDGET} steps. */
	private static final class MatchBudgetExceededException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	/**
	 * A CharSequence that counts reads and aborts once the budget is spent.
	 * java.util.regex has no timeout, but it interrogates the input through
	 * charAt() -- so counting those reads is the standard way to bound a match
	 * without spawning a watchdog thread per evaluation.
	 */
	private static final class BudgetedCharSequence implements CharSequence {
		private final CharSequence delegate;
		private int budget;
		BudgetedCharSequence (CharSequence delegate, int budget) {
			this.delegate = delegate;
			this.budget = budget;
		}
		@Override public int length () { return delegate.length(); }
		@Override public char charAt (int index) {
			if (--budget < 0) throw new MatchBudgetExceededException();
			return delegate.charAt(index);
		}
		@Override public CharSequence subSequence (int start, int end) {
			return new BudgetedCharSequence(delegate.subSequence(start, end), budget);
		}
		@Override public String toString () { return delegate.toString(); }
	}

	/** Compile once (cached) and match under a step budget. */
	private boolean matchesSafely (String pattern, String name, String ruleName, UUID orgUuid) {
		Pattern p;
		try {
			p = patternCache.computeIfAbsent(pattern, Pattern::compile);
		} catch (PatternSyntaxException e) {
			// Defensive -- writes validate the regex, but bad data on read must
			// not kill the whole ownership resolver.
			log.warn("Bad regex in org team-assignment rule '{}' (org {}): {}", ruleName, orgUuid, e.getMessage());
			return false;
		}
		try {
			return p.matcher(new BudgetedCharSequence(name, MATCH_STEP_BUDGET)).matches();
		} catch (MatchBudgetExceededException e) {
			log.warn("Team-assignment rule '{}' (org {}) exceeded the match budget on name '{}'"
					+ " -- treating as no match; simplify the pattern", ruleName, orgUuid, name);
			return false;
		}
	}

	/**
	 * The first rule (in list order) that matches this component AND still
	 * resolves to a live team in the same org. A rule whose team was deleted is
	 * skipped rather than winning-and-orphaning, so one stale rule cannot mask
	 * every rule behind it.
	 */
	public Optional<TeamAssignmentMatch> matchFor (ComponentData cd, OrganizationData od) {
		return matchFor(cd, od, List.of());
	}

	/**
	 * As above, but resolving the rule's team from an already-loaded org group
	 * list when possible. Callers that loop over components (the ownership
	 * report) hoist that list once; without this the rule path would go back to
	 * the DB per component per rule -- reintroducing exactly the N+1 the hoisting
	 * exists to prevent.
	 */
	public Optional<TeamAssignmentMatch> matchFor (ComponentData cd, OrganizationData od,
			List<UserGroupData> orgGroups) {
		if (null == cd || null == od || null == od.getGlobalTeamAssignmentRules()) return Optional.empty();
		String name = StringUtils.defaultString(cd.getName(), "");
		ComponentType cType = cd.getType();
		for (GlobalTeamAssignmentRule rule : od.getGlobalTeamAssignmentRules()) {
			if (null == rule || StringUtils.isBlank(rule.getNamePattern())) continue;
			if (!typeFilterMatches(rule.getComponentType(), cType)) continue;
			if (!matchesSafely(rule.getNamePattern(), name, rule.getName(), od.getUuid())) continue;
			UserGroupData team = resolveTeam(rule, od.getUuid(), orgGroups);
			if (null == team) continue;
			return Optional.of(new TeamAssignmentMatch(rule, team));
		}
		return Optional.empty();
	}

	private boolean typeFilterMatches (ComponentType ruleType, ComponentType componentType) {
		// Rule's type filter -- null and ANY both mean "match any".
		if (null == ruleType || ComponentType.ANY == ruleType) return true;
		return ruleType == componentType;
	}

	/**
	 * The rule's team if it still exists in this org. An INACTIVE (archived) team
	 * is deliberately still returned: ownership resolution reports that as
	 * DEGRADED, which is more useful than silently falling through to the next
	 * rule and hiding that the intended owner was archived.
	 */
	private UserGroupData resolveTeam (GlobalTeamAssignmentRule rule, UUID orgUuid,
			List<UserGroupData> orgGroups) {
		if (null == rule.getOwnerTeam()) return null;
		if (null != orgGroups) {
			Optional<UserGroupData> hoisted = orgGroups.stream()
					.filter(g -> rule.getOwnerTeam().equals(g.getUuid())).findFirst();
			if (hoisted.isPresent()) {
				return orgUuid.equals(hoisted.get().getOrg()) ? hoisted.get() : null;
			}
		}
		Optional<UserGroupData> ougd = userGroupService.getUserGroupData(rule.getOwnerTeam());
		if (ougd.isEmpty()) return null;
		UserGroupData ugd = ougd.get();
		if (!orgUuid.equals(ugd.getOrg())) return null;
		return ugd;
	}

	/**
	 * Write-time validation. Mirrors the approval-policy rule contract: named,
	 * uniquely named, compilable regex, and a team that actually exists in this
	 * org. Rejecting here keeps the read path free of surprises.
	 */
	/**
	 * Validate + persist in one call, mirroring {@code OrgApprovalPolicyService.setRules}.
	 * Keeping this the ONLY write entry point means a future caller (CLI, import,
	 * bulk tooling) cannot reach the persist step without validation -- which is
	 * why {@link #validate} is private.
	 */
	@Transactional
	public OrganizationData setRules (UUID orgUuid, List<GlobalTeamAssignmentRule> rules, WhoUpdated wu)
			throws RelizaException {
		List<GlobalTeamAssignmentRule> normalized = (null == rules) ? new LinkedList<>() : rules;
		validate(orgUuid, normalized);
		return organizationService.setGlobalTeamAssignmentRules(orgUuid, new LinkedList<>(normalized), wu);
	}

	void validate (UUID orgUuid, List<GlobalTeamAssignmentRule> rules) throws RelizaException {
		if (null == rules) return;
		LinkedHashSet<String> seenLowerNames = new LinkedHashSet<>();
		for (int i = 0; i < rules.size(); i++) {
			GlobalTeamAssignmentRule r = rules.get(i);
			String idx = "[" + i + "]";
			if (null == r) throw new RelizaException("Rule " + idx + " is null");
			if (StringUtils.isBlank(r.getName())) {
				throw new RelizaException("Rule " + idx + " has a blank name");
			}
			if (!seenLowerNames.add(r.getName().toLowerCase())) {
				throw new RelizaException("Duplicate rule name (case-insensitive): '" + r.getName() + "'");
			}
			if (StringUtils.isBlank(r.getNamePattern())) {
				throw new RelizaException("Rule '" + r.getName() + "' has a blank namePattern");
			}
			if (r.getNamePattern().length() > MAX_PATTERN_LENGTH) {
				throw new RelizaException("Rule '" + r.getName() + "' namePattern exceeds "
						+ MAX_PATTERN_LENGTH + " characters");
			}
			try {
				Pattern.compile(r.getNamePattern());
			} catch (PatternSyntaxException e) {
				throw new RelizaException("Rule '" + r.getName() + "' has an invalid regex: " + e.getMessage());
			}
			if (null == r.getOwnerTeam()) {
				throw new RelizaException("Rule '" + r.getName() + "' has no ownerTeam set");
			}
			UserGroupData team = resolveTeam(r, orgUuid, List.of());
			if (null == team) {
				throw new RelizaException("Rule '" + r.getName()
						+ "' ownerTeam is missing or belongs to a different org");
			}
			if (UserGroupStatus.INACTIVE == team.getStatus()) {
				throw new RelizaException("Rule '" + r.getName()
						+ "' points at an archived team; restore the team or pick another");
			}
			// Type filter is permissive -- null/ANY both mean "match any".
		}
	}
}
