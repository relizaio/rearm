/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseInputEvent;
import io.reliza.model.ComponentData.ReleaseOutputEvent;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.VersionAssignment.VersionTypeEnum;
import io.reliza.model.tea.TeaIdentifier;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateComponentDto {
	
	@Data
	public static class ReleaseOutputEventInput {
		private UUID uuid;
		private String name;
		private EventType type;
		private ReleaseLifecycle toReleaseLifecycle;
		private UUID integration;
		private Set<UUID> users;
		private String notificationMessage;
		private UUID vcs;
		private String schedule;
		private String clientPayload; // i.e. additional GitHub parameters
		private String eventType;
	}
	
	@JsonProperty(CommonVariables.UUID_FIELD)
	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID organization;
	@JsonProperty(CommonVariables.TYPE_FIELD)
	private ComponentType type;
	private ComponentKind kind;
	@JsonProperty(CommonVariables.VERSION_SCHEMA_FIELD)
	private String versionSchema;
	@JsonProperty(CommonVariables.MARKETING_VERSION_SCHEMA_FIELD)
	private String marketingVersionSchema;
	@JsonProperty(CommonVariables.VERSION_TYPE_FIELD)
	private VersionTypeEnum versionType;
	private UUID vcs;
	@JsonProperty(CommonVariables.FEATURE_BRANCH_VERSION_FIELD)
	private String featureBranchVersioning;
	@JsonProperty(CommonVariables.IS_REPOSITORY_ENABLED)
	private Integer repositoryEnabled;
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private StatusEnum status;
	@JsonProperty(CommonVariables.API_KEY_ID_FIELD)
	private String apiKeyId;
	@JsonProperty(CommonVariables.API_KEY_FIELD)
	private String apiKey;
	@JsonProperty
	private UUID resourceGroup;
	@JsonProperty
	private String defaultConfig;
	@JsonProperty
	private UUID parent;
	@JsonProperty
	private UUID approvalPolicy;
	@JsonProperty
	private List<ReleaseInputEvent> releaseInputTriggers;
	@JsonProperty
	private List<ReleaseOutputEventInput> outputTriggers;
	@JsonProperty
	private List<TeaIdentifier> identifiers;
	
	public static ReleaseOutputEvent convertReleaseOutputEventFromInput (ReleaseOutputEventInput roei,
			IntegrationType it) throws JsonMappingException, JsonProcessingException {
		var builder = ReleaseOutputEvent.builder()
									.integration(roei.getIntegration())
									.name(roei.getName())
									.uuid(roei.getUuid())
									.type(roei.getType())
									.users(roei.getUsers())
									.notificationMessage(roei.getNotificationMessage())
									.vcs(roei.getVcs())
									.schedule(roei.getSchedule())
									.eventType(roei.getEventType())
									.toReleaseLifecycle(roei.getToReleaseLifecycle());
		if (it == IntegrationType.GITHUB || it == IntegrationType.ADO) {
			if (StringUtils.isNotEmpty(roei.getClientPayload())) {
				builder.clientPayload(roei.getClientPayload());
			}
		}
		return builder.build();
	}
	
	public static ComponentDto convertToComponentDto (UpdateComponentDto ucd,
			List<ReleaseOutputEvent> triggers) {
		ComponentDto cdto = ComponentDto.builder()
								.uuid(ucd.getUuid())
								.name(ucd.getName())
								.organization(ucd.getOrganization())
								.type(ucd.getType())
								.kind(ucd.getKind())
								.versionSchema(ucd.getVersionSchema())
								.marketingVersionSchema(ucd.getMarketingVersionSchema())
								.versionType(ucd.getVersionType())
								.vcs(ucd.getVcs())
								.featureBranchVersioning(ucd.getFeatureBranchVersioning())
								.repositoryEnabled(ucd.getRepositoryEnabled())
								.status(ucd.getStatus())
								.apiKeyId(ucd.getApiKeyId())
								.apiKey(ucd.getApiKey())
								.resourceGroup(ucd.getResourceGroup())
								.defaultConfig(ucd.getDefaultConfig())
								.parent(ucd.getParent())
								.approvalPolicy(ucd.getApprovalPolicy())
								.releaseInputTriggers(ucd.getReleaseInputTriggers())
								.outputTriggers(triggers)
								.identifiers(ucd.getIdentifiers())
								.build();
		return cdto;
	}
}