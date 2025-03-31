/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.Identity;
import io.reliza.model.ArtifactData.InventoryType;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.tea.Link;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.service.IntegrationService.DependencyTrackUploadResult;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ArtifactDto {
	//identifiers for objects to attach to
	@JsonProperty(CommonVariables.UUID_FIELD)
	private UUID uuid;

	@JsonProperty("type")
	private ArtifactType type;
	
	@JsonProperty("displayIdentifier")
	private String displayIdentifier;

	@JsonProperty("identities")
	private List<Identity> identities;

	@JsonProperty("downloadLinks")
	private List<Link> downloadLinks;

	@JsonProperty("inventoryTypes")
	private List<InventoryType> inventoryTypes;

	@JsonProperty("bomFormat")
	private BomFormat bomFormat;

	@JsonProperty("date")
	private ZonedDateTime date;

	@JsonProperty("storedIn")
	private StoredIn storedIn;
	
	@JsonProperty("internalBom")
	private InternalBom internalBom;

	@JsonProperty("digests")
	private Set<String> digests;
	
	@JsonProperty("tags")
	private List<TagRecord> tags;
	
	@JsonProperty("status")	
	private StatusEnum status;
	
	@JsonProperty("version")
	private String version;
	
	@JsonProperty("org")
	private UUID org;

	@JsonProperty("stripBom")
	private Boolean stripBom;
	
	@JsonProperty
	private DependencyTrackUploadResult dtur; 
}
