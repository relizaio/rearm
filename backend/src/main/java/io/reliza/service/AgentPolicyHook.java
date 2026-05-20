/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;

/**
 * Shared-edition hook the {@link AgentSessionService} calls at
 * session lifecycle points. The CE backend has no implementation
 * bean — sessions land without policy gates. The SAAS backend
 * provides {@code service.saas.AgentPolicyHookImpl}, which loads
 * the org's {@code AgentPolicy} rows, evaluates them through CEL,
 * and returns the verdict tuple.
 *
 * The hook is wired into {@link AgentSessionService} via
 * {@code @Autowired(required = false)} so the shared layer keeps
 * the saas / shared boundary clean. Session calls check the
 * Optional and skip the evaluation when no hook is present.
 *
 * <h3>BLOCK semantics</h3>
 * When an input policy at {@code severity = BLOCK} fails on session
 * init, the impl throws {@link PolicyBlockedException} — the
 * caller surfaces the message as a GraphQL error and the session is
 * never persisted. WARN-severity failures and post-init evaluations
 * (artifact attach / commit) record the verdict on the session's
 * {@code policyEvents} jsonb but do not throw.
 */
public interface AgentPolicyHook {

	/**
	 * Evaluate INPUT-kind policies at session initialize. Throws when a
	 * BLOCK-severity policy fails so the SCE / session never persists.
	 */
	List<PolicyEvent> evaluateOnSessionInit(AgentSessionData session, AgentData rootAgent)
			throws PolicyBlockedException, RelizaException;

	/**
	 * Evaluate INPUT + OUTPUT policies after an artifact has been
	 * attached. Never throws — verdicts are recorded on the session
	 * by the caller. Best-effort; failures are logged.
	 */
	List<PolicyEvent> evaluateOnArtifactAttach(AgentSessionData session, AgentData rootAgent,
			UUID artifactUuid) throws RelizaException;

	/**
	 * Evaluate OUTPUT policies after a commit (SCE) has been
	 * attributed to the session via the PR 2 trailer parser. Never
	 * throws — verdicts are recorded on the session by the caller.
	 */
	List<PolicyEvent> evaluateOnCommitAttributed(AgentSessionData session, AgentData rootAgent,
			UUID sceUuid) throws RelizaException;

	/**
	 * Verdict for a single policy evaluation. Records the policy
	 * identity, the state, and the wall-clock evaluation time so the
	 * session's policy log can rehydrate without re-evaluating.
	 *
	 * {@code message} is the operator-facing explanation: for FAILED
	 * verdicts it's typically "Policy &lt;name&gt; failed: &lt;cel&gt;";
	 * for PASSED it's null. Returned to the dashboard via the
	 * {@code Session.policyEvents} GraphQL field.
	 */
	record PolicyEvent(UUID policyUuid, String policyName, PolicyKind kind,
			PolicySeverity severity, PolicyState state, String message,
			ZonedDateTime evaluatedAt) {}

	enum PolicyKind { INPUT, OUTPUT }
	enum PolicySeverity { BLOCK, WARN }
	enum PolicyState { PASSED, WARNING, FAILED, AWAITING }

	/**
	 * Thrown when a BLOCK-severity input policy fails on session init.
	 * The data fetcher wraps it as a GraphQL access error so the
	 * agent's mutation fails with the policy's reason text.
	 */
	class PolicyBlockedException extends RelizaException {
		private static final long serialVersionUID = 234739;
		private final transient PolicyEvent failingEvent;
		public PolicyBlockedException(PolicyEvent failingEvent) {
			super("Session blocked by policy '" + failingEvent.policyName() + "': " + failingEvent.message());
			this.failingEvent = failingEvent;
		}
		public PolicyEvent getFailingEvent() { return failingEvent; }
	}

	/**
	 * Convenience adapter used by the no-op CE path — keeps the
	 * shared service code uniform whether or not a hook bean is
	 * present.
	 */
	static List<PolicyEvent> noOp() { return List.of(); }

	@SuppressWarnings("unused")
	static Optional<AgentPolicyHook> empty() { return Optional.empty(); }
}
