/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.model.TeamData.OwnedComponentNotifications;
import io.reliza.model.TeamStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TeamWebDto {
	@JsonProperty(CommonVariables.UUID_FIELD)
	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.DESCRIPTION_FIELD)
	private String description;
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private TeamStatus status;
	@JsonProperty("members")
	private Set<UUID> members;
	@JsonProperty("userGroups")
	private Set<UUID> userGroups;
	@JsonProperty("notificationChannels")
	private Set<UUID> notificationChannels;
	@JsonProperty("leads")
	private Set<UUID> leads;
	/** Null when the team never configured it; the UI reads that as off. */
	@JsonProperty("ownedComponentNotifications")
	private OwnedComponentNotifications ownedComponentNotifications;
}
