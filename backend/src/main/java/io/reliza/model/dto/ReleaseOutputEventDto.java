/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.Set;
import java.util.UUID;

import io.reliza.model.ComponentData.EventScope;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.ComponentLock;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReleaseOutputEventDto {
	private UUID uuid;
	private String name;
	private EventType type;
	private ReleaseLifecycle toReleaseLifecycle;
	private UUID integration;
	private Set<UUID> users;
	private String notificationMessage;
	private UUID vcs;
	private String schedule;
	private String clientPayload;
	private String celClientPayload;
	private String eventType;
	private EventScope scope;
	private UUID snapshotApprovalEntry;
	private ReleaseLifecycle snapshotLifecycle;
	private String approvedEnvironment;
	private String checkName;
	private Boolean includeSuppressed;
	private ComponentLock.Scope lockScope;
	private ComponentLock.UnlockLevel lockUnlockLevel;
	private ComponentLock.AttestationRequirement lockAttestationRequirement;
	private String lockReason;

	public static ReleaseOutputEventDto fromData(ReleaseOutputEvent event, EventScope scope) {
		return ReleaseOutputEventDto.builder()
				.uuid(event.getUuid())
				.name(event.getName())
				.type(event.getType())
				.toReleaseLifecycle(event.getToReleaseLifecycle())
				.integration(event.getIntegration())
				.users(event.getUsers())
				.notificationMessage(event.getNotificationMessage())
				.vcs(event.getVcs())
				.schedule(event.getSchedule())
				.clientPayload(event.getClientPayload())
				.celClientPayload(event.getCelClientPayload())
				.eventType(event.getEventType())
				.scope(scope)
				.snapshotApprovalEntry(event.getSnapshotApprovalEntry())
				.snapshotLifecycle(event.getSnapshotLifecycle())
				.approvedEnvironment(event.getApprovedEnvironment())
				.checkName(event.getCheckName())
				// Every field the stored event carries has to be here. A field that is persisted
				// and reads back null is worse than one that is missing: the editor fills in a
				// default for it and the next save silently rewrites the stored policy to that
				// default. ReleaseOutputEventDtoCoverageTest pins the whole set.
				.includeSuppressed(event.getIncludeSuppressed())
				.lockScope(event.getLockScope())
				.lockUnlockLevel(event.getLockUnlockLevel())
				.lockAttestationRequirement(event.getLockAttestationRequirement())
				.lockReason(event.getLockReason())
				.build();
	}
}
