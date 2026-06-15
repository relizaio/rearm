/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.AcollectionData.ArtifactChangelog;
import io.reliza.model.NotificationEventType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseApprovalEvent;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.notifications.BomComponentChange;
import io.reliza.model.dto.notifications.ReleaseBomDiffPayload;
import io.reliza.model.dto.notifications.ReleaseCreatedPayload;
import io.reliza.model.dto.notifications.ReleaseLifecycleChangedPayload;
import io.reliza.model.dto.notifications.ReleaseRef;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared implementation of {@link ReleaseChangeHook}. Replaces the legacy
 * {@code NotificationService.processReleaseEvent} / {@code sendBomDiffAlert}
 * chain: instead of dispatching Slack/Teams inline, it writes one row into
 * {@code notification_outbox_events} for the fan-out worker, so release
 * events flow through the same subscription / route / channel machinery as
 * the vuln and VEX events. CE-available: the ref-building + outbox write
 * live in shared {@link ReleaseNotificationSupport}.
 *
 * <p>Approval events are a SAAS-only feature; {@link #onReleaseApprovalEvents}
 * delegates to the optional {@link ApprovalEventNotifier} seam (no impl on
 * CE → no approval notifications, which is correct since CE has no approvals).
 *
 * <p><b>Transactional contract.</b> {@code onReleaseCreated} /
 * {@code onReleaseLifecycleChanged} run {@link Propagation#MANDATORY} (their
 * callers are transactional, so the outbox row commits with the release
 * record). {@code onReleaseBomDiff} stays {@link Propagation#REQUIRED}: its
 * caller is intentionally non-transactional and the once-per-release
 * guarantee comes from an atomic claim UPDATE, not a shared transaction.
 */
@Service
@Slf4j
public class ReleaseChangeHookImpl implements ReleaseChangeHook {

	@Autowired
	private ReleaseNotificationSupport support;

	// SAAS-only; absent on CE (no approval notifications there).
	@Autowired(required = false)
	private ApprovalEventNotifier approvalEventNotifier;

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void onReleaseCreated(ReleaseData rd, boolean scheduled) {
		if (rd == null || support.isExternalOrIncomplete(rd)) return;
		ReleaseRef ref = support.buildReleaseRef(rd);
		if (ref == null) return;
		ReleaseCreatedPayload payload = new ReleaseCreatedPayload(ref, scheduled);
		String dedupKey = "release:created:" + rd.getUuid();
		support.writeOutboxEvent(rd.getOrg(), NotificationEventType.RELEASE_CREATED, dedupKey, payload);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void onReleaseLifecycleChanged(ReleaseData rd, ReleaseLifecycle oldLifecycle,
			ReleaseLifecycle newLifecycle) {
		if (rd == null || newLifecycle == null || support.isExternalOrIncomplete(rd)) return;
		ReleaseRef ref = support.buildReleaseRef(rd);
		if (ref == null) return;
		ReleaseLifecycleChangedPayload payload =
				new ReleaseLifecycleChangedPayload(ref, oldLifecycle, newLifecycle);
		// New lifecycle is part of the key so DRAFT -> ASSEMBLED produces two
		// distinct events rather than collapsing inside the dedup window.
		String dedupKey = "release:lc:" + rd.getUuid() + ":" + newLifecycle.name();
		support.writeOutboxEvent(rd.getOrg(), NotificationEventType.RELEASE_LIFECYCLE_CHANGED, dedupKey, payload);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public void onReleaseBomDiff(ReleaseData rd, ArtifactChangelog changelog) {
		if (rd == null || changelog == null || support.isExternalOrIncomplete(rd)) return;
		List<BomComponentChange> added = ReleaseNotificationSupport.mapDiff(changelog.added());
		List<BomComponentChange> removed = ReleaseNotificationSupport.mapDiff(changelog.removed());
		// Mirror the legacy gate: only notify when there is something on both sides.
		if (added.isEmpty() || removed.isEmpty()) return;
		ReleaseRef ref = support.buildReleaseRef(rd);
		if (ref == null) return;
		ReleaseBomDiffPayload payload = new ReleaseBomDiffPayload(ref, added, removed);
		String dedupKey = "release:bomdiff:" + rd.getUuid();
		support.writeOutboxEvent(rd.getOrg(), NotificationEventType.RELEASE_BOM_DIFF, dedupKey, payload);
	}

	@Override
	public void onReleaseApprovalEvents(ReleaseData rd, List<ReleaseApprovalEvent> newEvents) {
		// Approvals are SAAS-only; CE has no notifier bean and no approvals.
		if (approvalEventNotifier != null) {
			approvalEventNotifier.onReleaseApprovalEvents(rd, newEvents);
		}
	}
}
