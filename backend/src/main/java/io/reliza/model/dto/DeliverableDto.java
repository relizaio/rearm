/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.model.ArtifactData.Identity;
import io.reliza.model.DeliverableData.BelongsToOrganization;
import io.reliza.model.DeliverableData.CPUArchitecture;
import io.reliza.model.DeliverableData.OS;
import io.reliza.model.DeliverableData.SoftwareDeliverableMetadata;
import io.reliza.model.tea.TeaIdentifier;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DeliverableDto {
	
	public record AddOutboundDeliverablesInput (UUID release, UUID variant, List<DeliverableDto> deliverables) {}
	
	@JsonProperty(CommonVariables.UUID_FIELD)
	private UUID uuid;
	private String displayIdentifier;
	private List<Identity> identities;
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID org; // if branch uuid is specified, organization should match that of branch
	@JsonProperty(CommonVariables.BRANCH_FIELD)
	private UUID branch; // deliverables should belong to a project's branch for internal artifacts
	private CdxType type;
	@JsonProperty(CommonVariables.NOTES_FIELD)
	private String notes; // any unstructured metadata we want about this artifact
	@JsonProperty(CommonVariables.TAGS_FIELD)
	private List<TagRecord> tags;
	private String version;
	@JsonProperty(CommonVariables.PUBLISHER_FIELD)
	private String publisher;
	@JsonProperty(CommonVariables.GROUP_FIELD)
	private String group;
	@JsonProperty(CommonVariables.BOM_FIELD)
	private JsonNode bom;
	private List<OS> supportedOs;
	private List<CPUArchitecture> supportedCpuArchitectures;
	private StatusEnum status;
	
	private SoftwareDeliverableMetadata softwareMetadata;
	
	private List<UUID> artifacts;
	private BelongsToOrganization isInternal;

	@JsonProperty
	private List<TeaIdentifier> identifiers;
	
	public void cleanDigests() {
		if (null != this.softwareMetadata) {
			var dirtyDigests = this.getSoftwareMetadata().getDigests();
			if (null != dirtyDigests && !dirtyDigests.isEmpty()) {
				var cleanDigests = dirtyDigests
										.stream()
										.map(d -> Utils.cleanString(d))
										.collect(Collectors.toSet());
				this.softwareMetadata.setDigests(cleanDigests);
			}	
		}

	}

	public String getShaDigest() {
		String shaDigest = null;
		if (null != this.softwareMetadata) {
			var digests = this.getSoftwareMetadata().getDigests();
			if (null != digests && !digests.isEmpty()) {
				shaDigest = digests
							.stream()
							.filter(digest -> digest.startsWith("sha256:")).findFirst().orElse(null);
			}
		}
		return shaDigest;
	}
}
