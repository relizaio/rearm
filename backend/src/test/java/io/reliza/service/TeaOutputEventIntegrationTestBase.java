/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.reliza.common.CommonVariables.ApprovalState;
import io.reliza.common.CommonVariables.ProgrammaticType;
import io.reliza.common.Utils;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseInputEvent;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.FlowControl;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData.ReleaseApprovalEvent;
import io.reliza.model.ReleaseData.ReleaseApprovalInput;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ComponentDto;
import io.reliza.model.dto.saas.ApprovalEntryDto;
import io.reliza.model.saas.ApprovalEntryData.ApprovalRequirement;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.service.saas.ApprovalEntryService;
import io.reliza.service.saas.ApprovalPolicyService;
import io.reliza.service.saas.PerspectiveService;
import io.reliza.service.saas.ReleaseApprovalService;
import io.reliza.service.saas.TeaOutputEventService;
import io.reliza.service.saas.TeaWiringAuthorization;

/**
 * TEA-7, section 4.3: what the TEA_PUBLISH and TEA_HIDE database classes share on top of TEA-5's
 * fixture: an approval entry and a vote, a component rule with TEA actions, and reads of the
 * release row's TEA act queue keys. Subclasses decide whether the after-commit hop runs (the
 * queue classes mock the dispatcher and run the worker from the test thread; the hop class keeps
 * it real).
 */
abstract class TeaOutputEventIntegrationTestBase extends TeaPublicationIntegrationTestBase {

	@Autowired TeaOutputEventService teaOutputEventService;
	@Autowired ReleaseRepository releaseRepository;
	@Autowired ApprovalEntryService approvalEntryService;
	@Autowired ReleaseApprovalService releaseApprovalService;
	@Autowired ApprovalPolicyService approvalPolicyService;
	@Autowired PerspectiveService perspectiveService;
	@Autowired JdbcTemplate jdbcTemplate;

	/** An approval entry of the fixture's organization: one approval by role QA. */
	UUID approvalEntry() throws Exception {
		ApprovalRequirement req = new ApprovalRequirement();
		req.setAllowedApprovalRoleIds(Set.of("QA"));
		req.setRequiredNumberOfApprovals(1);
		return approvalEntryService.createApprovalEntry(ApprovalEntryDto.builder().org(org.getUuid())
				.approvalName("tea-7-" + UUID.randomUUID()).approvalRequirements(List.of(req)).build(), WU).getUuid();
	}

	/** A person's vote, through the path whose save runs the trigger pass. */
	void vote(UUID release, UUID entry, ApprovalState state) throws Exception {
		releaseApprovalService.addApproalsFromWs(release,
				List.of(new ReleaseApprovalInput(entry, "QA", state, "tea-7 " + state)),
				WhoUpdated.getWhoUpdated(ProgrammaticType.MANUAL, UUID.randomUUID(), null));
	}

	void approve(UUID release, UUID entry) throws Exception {
		vote(release, entry, ApprovalState.APPROVED);
	}

	static String approved(UUID entry) {
		return "release.approvals[\"" + entry + "\"] == \"APPROVED\"";
	}

	static String disapproved(UUID entry) {
		return "release.approvals[\"" + entry + "\"] == \"DISAPPROVED\"";
	}

	static ReleaseOutputEvent teaAction(EventType type, String name) {
		return ReleaseOutputEvent.builder().uuid(UUID.randomUUID()).name(name).type(type).build();
	}

	static ReleaseInputEvent rule(String name, String cel, ReleaseOutputEvent... actions) {
		ReleaseInputEvent r = new ReleaseInputEvent();
		r.setUuid(UUID.randomUUID());
		r.setName(name);
		r.setCelExpression(cel);
		r.setEnabled(true);
		r.setOutputEvents(Set.copyOf(Arrays.stream(actions).map(ReleaseOutputEvent::getUuid).toList()));
		return r;
	}

	/** The component's rules and actions, written straight through the service as PendingReleaseRulesTest does. */
	void configure(UUID component, ReleaseInputEvent rule, ReleaseOutputEvent... actions) throws Exception {
		configure(component, List.of(rule), actions);
	}

	void configure(UUID component, List<ReleaseInputEvent> rules, ReleaseOutputEvent... actions) throws Exception {
		stampAsAdmin(component, rules, List.of(actions), null);
		componentService.updateComponent(ComponentDto.builder().uuid(component).releaseInputTriggers(rules)
				.outputTriggers(List.of(actions)).build(), WU);
	}

	/**
	 * Round 5: wiring written straight through the service is the admin's, so it carries the
	 * stamp the fetcher's gate would write (TeaWiringAuthorization.stampAll for the component).
	 */
	static void stampAsAdmin(UUID component, List<ReleaseInputEvent> rules, List<ReleaseOutputEvent> actions,
			List<io.reliza.model.ComponentData.GlobalInputEventRef> refs) {
		io.reliza.model.ComponentData wiring = new io.reliza.model.ComponentData();
		wiring.setUuid(component);
		wiring.setReleaseInputTriggers(rules);
		wiring.setOutputTriggers(actions);
		wiring.setGlobalInputEventRefs(refs);
		TeaWiringAuthorization.stampAll(wiring, null, io.reliza.model.ComponentData.TeaGrantBasis.ORG_ADMIN);
	}

	/**
	 * A person's vote written to the release row with no trigger pass: stands in for a rule that
	 * started to match while no pass could act on it (round 2, T-3: the hide rule's pass ran
	 * before there was anything to hide).
	 */
	void voteSilently(UUID release, UUID entry, ApprovalState state) throws Exception {
		ReleaseApprovalEvent vote = io.reliza.model.ReleaseData.approvalEventFromInput(
				new ReleaseApprovalInput(entry, "QA", state, "tea-7 silent " + state),
				WhoUpdated.getWhoUpdated(ProgrammaticType.MANUAL, UUID.randomUUID(), null));
		int n = jdbcTemplate.update("UPDATE rearm.releases SET approval_events = coalesce(approval_events, '[]'::jsonb) "
				+ "|| CAST(? AS jsonb) WHERE uuid = ?", Utils.OM.writeValueAsString(List.of(vote)), release);
		if (1 != n) throw new IllegalStateException("no vote written on " + release);
	}

	/** Puts a retry fence {@code interval} ahead (e.g. '60 seconds') on the row's TEA act. */
	void fence(UUID release, String interval, Integer failures) {
		int n = jdbcTemplate.update("UPDATE rearm.releases SET flow_control = flow_control || jsonb_build_object("
				+ "'teaActSkipUntil', (now() + CAST(? AS interval))::text) WHERE uuid = ?", interval, release);
		if (1 != n) throw new IllegalStateException("no fence on " + release);
		if (null != failures) {
			jdbcTemplate.update("UPDATE rearm.releases SET flow_control = flow_control || jsonb_build_object("
					+ "'teaActFailureCount', ?) WHERE uuid = ?", failures, release);
		}
	}

	boolean fencedAhead(UUID release) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT (flow_control->>'teaActSkipUntil')::timestamptz "
				+ "> now() FROM rearm.releases WHERE uuid = ?", Boolean.class, release));
	}

	/** Is the pending request newer than the claim, as the clear's lost-wakeup branch compares them? */
	boolean requestedAfterClaim(UUID release) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT (flow_control->>'teaActRequestedAt')::timestamptz "
				+ "> (flow_control->>'teaActClaimedAt')::timestamptz FROM rearm.releases WHERE uuid = ?", Boolean.class,
				release));
	}

	/** The GUARD lines that say a TEA_PUBLISH was withheld because a TEA_HIDE matches. */
	List<ReleaseUpdateEvent> withheld(UUID release) {
		return events(release, ReleaseUpdateScope.GUARD).stream()
				.filter(e -> "TEA_PUBLISH withheld: HIDE_RULE_MATCHES".equals(e.newValue())).toList();
	}

	/** How many merges the engine asked of rebom for this release. */
	long mergeCountOf(UUID release) {
		return mergeOfRelease.values().stream().filter(release::equals).count();
	}

	/** The release row's flow_control, read fresh. */
	FlowControl teaAct(UUID release) {
		return releaseRepository.findById(release).map(Release::getFlowControl).orElse(null);
	}

	boolean queued(UUID release) {
		FlowControl fc = teaAct(release);
		return null != fc && null != fc.teaActRequestedAt();
	}

	/** No teaAct key at all is left on the row. */
	boolean noTeaActKey(UUID release) {
		String json = jdbcTemplate.queryForObject("SELECT coalesce(flow_control::text, '{}') FROM rearm.releases "
				+ "WHERE uuid = ?", String.class, release);
		return !json.contains("teaAct");
	}

	boolean pending(UUID release) {
		return releaseRepository.findUuidsOfReleasesPendingTeaAct(10000, 900).contains(release);
	}

	/** Moves a timestamp key of the row's teaAct state by {@code interval} (e.g. '-16 minutes'). */
	void shift(UUID release, String key, String interval) {
		int n = jdbcTemplate.update("UPDATE rearm.releases SET flow_control = jsonb_set(flow_control, CAST(ARRAY[?] AS text[]), "
				+ "to_jsonb(((flow_control->>?)::timestamptz + CAST(? AS interval))::text), true) WHERE uuid = ?",
				key, key, interval, release);
		if (1 != n) throw new IllegalStateException("no row shifted for " + release);
	}

	List<ReleaseUpdateEvent> triggerRows(UUID release, UUID action) {
		return events(release, ReleaseUpdateScope.TRIGGER).stream().filter(e -> action.equals(e.objectId())).toList();
	}

	Optional<io.reliza.model.TeaPublicationData> head(UUID release) {
		return teaPublicationService.getPublicationOfRelease(release);
	}

	UUID componentOf(UUID release) {
		return stored(release).getComponent();
	}
}
