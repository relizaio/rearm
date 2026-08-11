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
import java.util.Set;
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
import io.reliza.model.dto.notifications.AffectedRelease;
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

	/**
	 * Single-element {@code affectedReleases} list stamped onto release-scoped
	 * outbox payloads so the inbox visibility query can see them — the
	 * perspective arm (via {@code perspectives}) and the component-team arm
	 * (via {@code componentUuid}). Vuln events resolve this lazily at fan-out
	 * because the connecting artifact metrics aren't written yet; release
	 * events have the component + perspectives in hand at produce-time, so we
	 * build it here from the already-resolved {@link ReleaseRef} plus one
	 * component lookup for the perspective set.
	 *
	 * <p>Returns an empty list (never null) when the component can't be
	 * resolved, so the caller can pass it straight through.
	 */
	public List<AffectedRelease> buildAffectedReleases(ReleaseData rd, ReleaseRef ref) {
		if (ref == null) return List.of();
		Set<UUID> perspectives = getComponentService.getComponentData(rd.getComponent())
				.map(ComponentData::getPerspectives)
				.filter(ps -> ps != null && !ps.isEmpty())
				.map(Set::copyOf)
				.orElse(Set.of());
		return List.of(new AffectedRelease(
				ref.releaseUuid(),
				ref.componentUuid(),
				ref.componentName(),
				ref.version(),
				ref.branchName(),
				ref.lifecycle(),
				List.of(),
				perspectives));
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
		writeOutboxEvent(org, type, dedupKey, payload, null);
	}

	/** Best-effort write that also stamps {@code affectedReleases} onto the
	 *  payload (for inbox perspective / component-team visibility). */
	public void writeOutboxEvent(UUID org, NotificationEventType type, String dedupKey, Object payload,
			List<AffectedRelease> affectedReleases) {
		try {
			writeOutboxEventStrict(org, type, dedupKey, payload, affectedReleases);
		} catch (RuntimeException e) {
			log.warn("Failed to write release notification outbox row org={} type={} key={}: {}",
					org, type, dedupKey, e.getMessage());
		}
	}

	/** Strict write: rethrows, for producers where the notification IS the
	 *  point of the mutation (e.g. APPROVAL_REQUESTED). */
	public void writeOutboxEventStrict(UUID org, NotificationEventType type, String dedupKey, Object payload) {
		writeOutboxEventStrict(org, type, dedupKey, payload, null);
	}

	/** Strict write that also stamps {@code affectedReleases} onto the payload.
	 *  When non-empty, the list is merged into the converted record_data under
	 *  the {@code affectedReleases} key the inbox visibility query walks. */
	public void writeOutboxEventStrict(UUID org, NotificationEventType type, String dedupKey, Object payload,
			List<AffectedRelease> affectedReleases) {
		NotificationOutboxEvent event = new NotificationOutboxEvent();
		event.setOrg(org);
		event.setEventType(type);
		event.setStatus(NotificationOutboxStatus.PENDING);
		event.setOrigin(NotificationDeliveryOrigin.REAL);
		event.setDedupKey(dedupKey);
		event.setOccurredAt(ZonedDateTime.now());
		@SuppressWarnings("unchecked")
		Map<String, Object> recordData = Utils.OM.convertValue(payload, Map.class);
		if (recordData == null) recordData = new HashMap<>();
		if (affectedReleases != null && !affectedReleases.isEmpty()) {
			recordData.put("affectedReleases", Utils.OM.convertValue(affectedReleases, List.class));
		}
		event.setRecordData(recordData);
		outboxRepo.save(event);
	}
}
