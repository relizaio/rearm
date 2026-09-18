/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import lombok.Builder;
import lombok.Data;

/**
 * Creation carries only the identity fields, mirroring
 * {@link CreateUserGroupDto}. Roster, channels and leads all arrive through
 * {@link UpdateTeamDto}, so every one of them is validated by exactly one code
 * path instead of two that can drift.
 */
@Data
@Builder
public class CreateTeamDto {
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.DESCRIPTION_FIELD)
	private String description;
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;
}
