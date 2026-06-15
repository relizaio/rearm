/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.List;

import io.reliza.model.AcollectionData.ArtifactChangelog;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseApprovalEvent;
import io.reliza.model.ReleaseData.ReleaseLifecycle;

/**
 * Shared-edition hook the OSS release path ({@code OssReleaseService},
 * {@code SbomComponentService}) calls when a release is created, changes
 * lifecycle, or produces a BOM diff. The CE backend has no implementation
 * bean — release operations complete without producing notification
 * events. The SAAS backend provides
 * {@code service.saas.ReleaseChangeHookImpl}, which writes one row into
 * {@code notification_outbox_events} per event for the fan-out worker.
 *
 * <p>The hook is wired via {@code @Autowired(required = false)} and callers
 * null-check before invocation, so the shared layer stays free of SAAS
 * imports — matching {@link VulnerabilityRecordChangeHook} /
 * {@code AgentPolicyHook}.
 *
 * <h3>Transactional contract</h3>
 * Unlike {@link VulnerabilityRecordChangeHook} (which mandates an open
 * transaction), this hook's implementation uses {@code Propagation.REQUIRED}:
 * the three call sites have varied transaction context (the lifecycle path
 * is {@code @Transactional}, the post-reconcile BOM-diff path is a drain
 * step), and a release row is always already saved by the time the hook
 * fires. Joining the caller's transaction when present keeps the
 * outbox-row-with-business-change invariant; standing one up otherwise
 * still records the event rather than dropping it.
 *
 * <p>This hook replaces the legacy
 * {@code NotificationService.processReleaseEvent} /
 * {@code sendBomDiffAlert} chain that dispatched Slack/Teams inline.
 */
public interface ReleaseChangeHook {

	/**
	 * Fires when a new release is created. {@code scheduled} is true when
	 * the release landed in PENDING lifecycle (the legacy
	 * {@code RELEASE_SCHEDULED} case), false otherwise (legacy
	 * {@code NEW_RELEASE}). The SAAS impl emits a {@code RELEASE_CREATED}
	 * outbox event.
	 */
	void onReleaseCreated(ReleaseData rd, boolean scheduled);

	/**
	 * Fires when a release lifecycle transition occurs that the legacy
	 * Slack/Teams path notified on (DRAFT / ASSEMBLED / CANCELLED /
	 * REJECTED). The caller gates which transitions reach this method to
	 * preserve pre-Phase-2b-2 behaviour; the SAAS impl emits a
	 * {@code RELEASE_LIFECYCLE_CHANGED} event carrying old → new.
	 */
	void onReleaseLifecycleChanged(ReleaseData rd, ReleaseLifecycle oldLifecycle, ReleaseLifecycle newLifecycle);

	/**
	 * Fires once per release when the post-reconcile BOM diff has both
	 * additions and removals. The SAAS impl emits a {@code RELEASE_BOM_DIFF}
	 * event; the caller has already claimed the one-shot flag.
	 */
	void onReleaseBomDiff(ReleaseData rd, ArtifactChangelog changelog);

	/**
	 * Fires when approval votes land on a release. Contract: the caller has
	 * already appended {@code newEvents} to the tail of
	 * {@code rd.getApprovalEvents()} but has NOT yet saved the release — the
	 * implementation may mutate {@code rd} (e.g. stamp resolution timestamps
	 * on its approval requests) and the caller's subsequent save persists
	 * both atomically. The SAAS impl emits one {@code APPROVAL_RESOLVED}
	 * outbox event per approval entry these votes drove to a terminal state
	 * (satisfied or disapproved).
	 */
	void onReleaseApprovalEvents(ReleaseData rd, List<ReleaseApprovalEvent> newEvents);
}
