/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EmailWebDto {
	@JsonProperty(CommonVariables.EMAIL_FIELD)
	private String email;
	@JsonProperty(CommonVariables.IS_PRIMARY_FIELD)
	private Boolean isPrimary;
	@JsonProperty(CommonVariables.IS_VERIFIED_FIELD)
	private Boolean isVerified;
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private Set<UUID> organizations; // optional, needed if this is a specific organization email - to show for admin instead of primary
	@JsonProperty(CommonVariables.IS_ACCEPT_MARKETING)
	private Boolean isAcceptMarketing;
}
