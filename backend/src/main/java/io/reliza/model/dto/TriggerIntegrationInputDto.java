/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.UUID;

import io.reliza.model.IntegrationData.IntegrationType;
import lombok.Data;

@Data
public class TriggerIntegrationInputDto {
	private UUID org;
	private String secret;
	private IntegrationType type;
	private String schedule;
	private String uri;
	private String frontendUri;
	private String tenant;
	private String client;
	private String note;
	private String identifier;
}
