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

/**
 * Every collection here is NULL-MEANS-KEEP. A caller that omits a field leaves
 * the stored value alone; a caller that sends an empty collection REPLACES it
 * with nothing. Partially-loaded editors must omit -- sending {@code []} from a
 * form that never loaded the real value is how a roster gets silently emptied,
 * and there is no undo.
 */
@Data
@Builder
public class UpdateTeamDto {
	@JsonProperty("teamId")
	private UUID teamId;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.DESCRIPTION_FIELD)
	private String description;
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private TeamStatus status;
	/** Individually-added members. */
	@JsonProperty("members")
	private Set<UUID> members;
	/** Permission groups whose members are also on this team. */
	@JsonProperty("userGroups")
	private Set<UUID> userGroups;
	/** Channels this team is reachable on. */
	@JsonProperty("notificationChannels")
	private Set<UUID> notificationChannels;
	/** Members who may administer this team. Must be on the effective roster. */
	@JsonProperty("leads")
	private Set<UUID> leads;
	/**
	 * "Notify this team about the components it owns." Null-means-keep like the
	 * collections above -- omitting it leaves the stored preference alone, which
	 * is what an editor that only changed the roster must do.
	 */
	@JsonProperty("ownedComponentNotifications")
	private OwnedComponentNotifications ownedComponentNotifications;
}
