/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;


import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.model.UserPermission.Permissions;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserWebDto {
	@JsonProperty(CommonVariables.UUID_FIELD)
	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.EMAIL_FIELD)
	private String email; // primary email
	@JsonProperty(CommonVariables.ALL_EMAILS_FIELD)
	private List<EmailWebDto> allEmails;
	@JsonProperty(CommonVariables.GITHUB_ID_FIELD)
	private String githubId;
	@JsonProperty(CommonVariables.OAUTH_ID_FIELD)
	private String oauthId;
	@JsonProperty(CommonVariables.OAUTH_TYPE_FIELD)
	private OauthType oauthType;
	private Set<UUID> organizations;
	@JsonProperty(CommonVariables.POLICIES_ACCEPTED_FIELD)
	private Boolean policiesAccepted;
	@JsonProperty(CommonVariables.PERMISSIONS_FIELD)
	private Permissions permissions;
	@JsonProperty(CommonVariables.GLOBAL_ADMIN_FIELD)
	private Boolean isGlobalAdmin;
	private Boolean systemSealed;
	private String installationType;
}
