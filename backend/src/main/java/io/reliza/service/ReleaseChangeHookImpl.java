/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.AcollectionData.ArtifactChangelog;
import io.reliza.model.NotificationEventType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseApprovalEvent;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.SubmissionNotice;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.dto.notifications.BomComponentChange;
import io.reliza.model.dto.notifications.ReleaseBomDiffPayload;
import io.reliza.model.dto.notifications.ReleaseCreatedPayload;
import io.reliza.model.dto.notifications.ReleaseLifecycleChangedPayload;
import io.reliza.model.dto.notifications.ReleaseRef;
import io.reliza.model.dto.notifications.ReleaseSubmissionPayload;
import io.reliza.model.dto.notifications.ReleaseTeaPublicationPayload;
import io.reliza.service.TeaPublicationService.TeaExposureState;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
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

	@Autowired
	@Lazy
	private TeaPublicationService teaPublicationService;

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
		support.writeOutboxEvent(rd.getOrg(), NotificationEventType.RELEASE_CREATED, dedupKey, payload,
				support.buildAffectedReleases(rd, ref));
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
		support.writeOutboxEvent(rd.getOrg(), NotificationEventType.RELEASE_LIFECYCLE_CHANGED, dedupKey, payload,
				support.buildAffectedReleases(rd, ref));
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
		support.writeOutboxEvent(rd.getOrg(), NotificationEventType.RELEASE_BOM_DIFF, dedupKey, payload,
				support.buildAffectedReleases(rd, ref));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public void onReleaseTeaPublished(ReleaseData rd, TeaPublicationData publication, int collectionVersion,
			TeaExposureState exposure, int childrenPublished) {
		if (rd == null || publication == null || support.isExternalOrIncomplete(rd)) return;
		ReleaseRef ref = support.buildReleaseRef(rd);
		if (ref == null) return;
		ReleaseTeaPublicationPayload payload = new ReleaseTeaPublicationPayload(ref, publication.getUuid(),
				TeaPublicationState.PUBLISHED, collectionVersion, exposure, publicUrlOf(publication), childrenPublished);
		String dedupKey = "tea-published:" + publication.getUuid() + ":" + collectionVersion;
		support.writeOutboxEvent(rd.getOrg(), NotificationEventType.RELEASE_TEA_PUBLISHED, dedupKey, payload,
				support.buildAffectedReleases(rd, ref));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public void onReleaseTeaHidden(ReleaseData rd, TeaPublicationData publication) {
		if (rd == null || publication == null || support.isExternalOrIncomplete(rd)) return;
		ReleaseRef ref = support.buildReleaseRef(rd);
		if (ref == null) return;
		ReleaseTeaPublicationPayload payload = new ReleaseTeaPublicationPayload(ref, publication.getUuid(),
				TeaPublicationState.HIDDEN, null, null, publicUrlOf(publication), 0);
		String dedupKey = "tea-hidden:" + publication.getUuid() + ":"
				+ Instant.now().truncatedTo(ChronoUnit.MINUTES);
		support.writeOutboxEvent(rd.getOrg(), NotificationEventType.RELEASE_TEA_HIDDEN, dedupKey, payload,
				support.buildAffectedReleases(rd, ref));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public void onReleaseSubmissionCreated(ReleaseData rd, SubmissionNotice notice) {
		if (rd == null || notice == null || support.isExternalOrIncomplete(rd)) return;
		ReleaseRef ref = support.buildReleaseRef(rd);
		if (ref == null) return;
		ReleaseSubmissionPayload payload = new ReleaseSubmissionPayload(ref, notice.submission(), notice.kind(),
				notice.state(), notice.version(), notice.act());
		// one per version: a resubmission always writes the next version, so the key never repeats
		String dedupKey = "submission-created:" + notice.submission() + ":" + notice.version();
		support.writeOutboxEvent(rd.getOrg(), NotificationEventType.RELEASE_SUBMISSION_CREATED, dedupKey, payload,
				support.buildAffectedReleases(rd, ref));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public void onReleaseSubmissionWithdrawn(ReleaseData rd, SubmissionNotice notice) {
		if (rd == null || notice == null || support.isExternalOrIncomplete(rd)) return;
		ReleaseRef ref = support.buildReleaseRef(rd);
		if (ref == null) return;
		ReleaseSubmissionPayload payload = new ReleaseSubmissionPayload(ref, notice.submission(), notice.kind(),
				notice.state(), notice.version(), notice.act());
		// the version the withdrawal tombstones: every withdrawal follows a distinct ACTIVE version (F-7)
		String dedupKey = "submission-withdrawn:" + notice.submission() + ":" + notice.version();
		support.writeOutboxEvent(rd.getOrg(), NotificationEventType.RELEASE_SUBMISSION_WITHDRAWN, dedupKey, payload,
				support.buildAffectedReleases(rd, ref));
	}

	private String publicUrlOf(TeaPublicationData publication) {
		if (null == teaPublicationService) return null;
		return TeaPublicationService.publicUrl(teaPublicationService.apiBaseOf(publication.getOrg()), publication);
	}

	@Override
	public void onReleaseApprovalEvents(ReleaseData rd, List<ReleaseApprovalEvent> newEvents) {
		// Approvals are SAAS-only; CE has no notifier bean and no approvals.
		if (approvalEventNotifier != null) {
			approvalEventNotifier.onReleaseApprovalEvents(rd, newEvents);
		}
	}
}
