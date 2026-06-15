/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.List;
import java.util.UUID;

import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseApprovalEvent;
import io.reliza.model.ReleaseData.ReleaseApprovalRequest;
import io.reliza.model.UserData;
import io.reliza.model.dto.notifications.ApprovalRequestEntryRef;

/**
 * Producer for approval-related notification events (APPROVAL_REQUESTED /
 * APPROVAL_RESOLVED). Release approvals are a SAAS-only feature, so this
 * seam is implemented only in the Pro backend; the shared
 * {@link ReleaseChangeHookImpl} delegates to it via
 * {@code @Autowired(required=false)} and the CE edition (no impl bean)
 * simply produces no approval notifications.
 *
 * <p>Kept separate from {@link ReleaseChangeHook} because the approval
 * producers need Pro-only types ({@code ApprovalEntryData},
 * {@code ApprovalEntryService}) that must not leak into the shared
 * release-event hook.
 */
public interface ApprovalEventNotifier {

	/**
	 * Process newly-appended approval vote events: stamp resolution on any
	 * now-satisfied/ disapproved approval requests (mutates {@code rd}, which
	 * the caller persists) and emit APPROVAL_RESOLVED events for entries that
	 * just reached a terminal state.
	 */
	void onReleaseApprovalEvents(ReleaseData rd, List<ReleaseApprovalEvent> newEvents);

	/**
	 * Emit an APPROVAL_REQUESTED event for a newly-created approval request.
	 * Called directly by the SAAS-only {@code ApprovalRequestService}; uses a
	 * strict (rethrowing) outbox write since notifying approvers is the point
	 * of the mutation.
	 */
	void onApprovalRequested(ReleaseData rd, ReleaseApprovalRequest rar, UserData requester,
			List<ApprovalRequestEntryRef> entries, List<UUID> targetUsers);
}
