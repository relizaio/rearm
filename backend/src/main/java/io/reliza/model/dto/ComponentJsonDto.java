/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.model.AcollectionData.ArtifactChangelog;
import io.reliza.service.ReleaseService.ChangeRecord;
import io.reliza.service.ReleaseService.ReleaseRecord;
import io.reliza.service.ReleaseService.TicketRecord;
import io.reliza.model.dto.VulnerabilityChangesDto.VulnerabilityChangesRecord;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ComponentJsonDto {
	@JsonProperty(CommonVariables.UUID_FIELD)
	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org;
	@JsonProperty(CommonVariables.FIRST_RELEASE_FIELD)
	private ReleaseRecord firstRelease;
	@JsonProperty(CommonVariables.LAST_RELEASE_FIELD)
	private ReleaseRecord lastRelease;
	@JsonProperty(CommonVariables.CHANGES_FIELD)
	private List<ChangeRecord> changes;
	@JsonProperty(CommonVariables.RELEASES_FIELD)
	private List<ReleaseRecord> releases;
	@JsonProperty(CommonVariables.TICKETS_FIELD)
	private List<TicketRecord> tickets;
	@JsonProperty(CommonVariables.COMPONENTS_FIELD)
	private List<ComponentJsonDto> components;
	@JsonProperty(CommonVariables.BRANCHES_FIELD)
	private List<ComponentJsonDto> branches;
	@JsonProperty("sbomChanges")
	private ArtifactChangelog sbomChanges;
	@JsonProperty("vulnerabilityChanges")
	private VulnerabilityChangesRecord vulnerabilityChanges;
}