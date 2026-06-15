/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ProgrammaticType;
import io.reliza.common.Utils;
import io.reliza.model.AcollectionData.ArtifactChangelog;
import io.reliza.model.AcollectionData.DiffComponent;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.NotificationDeliveryOrigin;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationOutboxStatus;
import io.reliza.model.ReleaseData;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.UserData;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.dto.notifications.BomComponentChange;
import io.reliza.model.dto.notifications.ReleaseRef;
import io.reliza.repositories.NotificationOutboxEventRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared helper for release-event notification producers: builds the
 * {@link ReleaseRef} payload header and writes {@code notification_outbox_events}
 * rows. Extracted from {@link ReleaseChangeHookImpl} so the SAAS-only
 * approval-event producer ({@code ApprovalEventNotifier}) can reuse the
 * same ref-building and outbox-write logic without duplicating it or
 * pulling the whole hook into Pro.
 *
 * <p>The write helpers run inside the caller's transaction (no own
 * {@code @Transactional}); the producer methods that call them carry the
 * propagation contract.
 */
@Service
@Slf4j
public class ReleaseNotificationSupport {

	@Autowired
	private NotificationOutboxEventRepository outboxRepo;

	@Autowired
	private GetComponentService getComponentService;

	@Autowired
	private BranchService branchService;

	@Autowired
	private GetSourceCodeEntryService getSourceCodeEntryService;

	@Autowired
	private VcsRepositoryService vcsRepositoryService;

	private final UserService userService;

	@Autowired
	public ReleaseNotificationSupport(@Lazy UserService userService) {
		this.userService = userService;
	}

	/**
	 * External-org releases never notified on the legacy path; we keep that
	 * exclusion. Also guards a null org (outbox rows require one).
	 */
	public boolean isExternalOrIncomplete(ReleaseData rd) {
		return rd.getOrg() == null || CommonVariables.EXTERNAL_PROJ_ORG_UUID.equals(rd.getOrg());
	}

	public ReleaseRef buildReleaseRef(ReleaseData rd) {
		Optional<ComponentData> ocd = getComponentService.getComponentData(rd.getComponent());
		if (ocd.isEmpty()) return null;
		ComponentData cd = ocd.get();

		String branchName = branchService.getBranchData(rd.getBranch())
				.map(BranchData::getName).orElse(null);

		String commitHash = null;
		String commitUri = null;
		String commitMessage = null;
		if (rd.getSourceCodeEntry() != null && cd.getVcs() != null) {
			Optional<SourceCodeEntryData> osce = getSourceCodeEntryService
					.getSourceCodeEntryData(rd.getSourceCodeEntry());
			Optional<VcsRepositoryData> ovcs = vcsRepositoryService.getVcsRepositoryData(cd.getVcs());
			if (osce.isPresent() && ovcs.isPresent()) {
				String fullCommit = osce.get().getCommit();
				if (StringUtils.isNotBlank(fullCommit)) {
					commitHash = fullCommit.substring(0, Math.min(fullCommit.length(), 7));
					commitUri = Utils.linkifyCommit(ovcs.get().getUri(), fullCommit);
				}
				commitMessage = osce.get().getCommitMessage();
			}
		}

		String updatedByName = null;
		String updatedByEmail = null;
		if (rd.getLastUpdatedBy() != null
				&& (rd.getCreatedType() == ProgrammaticType.MANUAL
						|| rd.getCreatedType() == ProgrammaticType.MANUAL_AND_AUTO)) {
			Optional<UserData> oud = userService.getUserData(rd.getLastUpdatedBy());
			if (oud.isPresent()) {
				UserData ud = oud.get();
				updatedByName = StringUtils.isNotEmpty(ud.getName()) ? ud.getName() : ud.getEmail();
				updatedByEmail = ud.getEmail();
			}
		}

		return new ReleaseRef(
				rd.getUuid(),
				rd.getVersion(),
				cd.getUuid(),
				cd.getName(),
				cd.getType(),
				rd.getBranch(),
				branchName,
				rd.getLifecycle(),
				commitHash,
				commitUri,
				commitMessage,
				updatedByName,
				updatedByEmail);
	}

	public static List<BomComponentChange> mapDiff(Collection<DiffComponent> diff) {
		if (diff == null) return List.of();
		return diff.stream()
				.filter(dc -> dc != null)
				.map(dc -> new BomComponentChange(dc.purl(), dc.version()))
				.collect(Collectors.toList());
	}

	/** Best-effort write: swallows failures so losing one notification never
	 *  rolls back the business write that triggered it. */
	public void writeOutboxEvent(UUID org, NotificationEventType type, String dedupKey, Object payload) {
		try {
			writeOutboxEventStrict(org, type, dedupKey, payload);
		} catch (RuntimeException e) {
			log.warn("Failed to write release notification outbox row org={} type={} key={}: {}",
					org, type, dedupKey, e.getMessage());
		}
	}

	/** Strict write: rethrows, for producers where the notification IS the
	 *  point of the mutation (e.g. APPROVAL_REQUESTED). */
	public void writeOutboxEventStrict(UUID org, NotificationEventType type, String dedupKey, Object payload) {
		NotificationOutboxEvent event = new NotificationOutboxEvent();
		event.setOrg(org);
		event.setEventType(type);
		event.setStatus(NotificationOutboxStatus.PENDING);
		event.setOrigin(NotificationDeliveryOrigin.REAL);
		event.setDedupKey(dedupKey);
		event.setOccurredAt(ZonedDateTime.now());
		@SuppressWarnings("unchecked")
		Map<String, Object> recordData = Utils.OM.convertValue(payload, Map.class);
		event.setRecordData(recordData != null ? recordData : new HashMap<>());
		outboxRepo.save(event);
	}
}
