/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.DeliverableData.BelongsToOrganization;
import io.reliza.model.DeliverableData.CPUArchitecture;
import io.reliza.model.DeliverableData.OS;
import io.reliza.model.DeliverableData.SoftwareDeliverableMetadata;
import io.reliza.model.tea.TeaChecksumType;
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
	private List<TeaIdentifier> identifiers;
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
	
	public void cleanLegacyDigests() {
		if (null != this.softwareMetadata) {
			var dirtyDigests = this.getSoftwareMetadata().getDigests();
			if (null != dirtyDigests && !dirtyDigests.isEmpty()) {
				Set<DigestRecord> digestRecords = new LinkedHashSet<>();
				
				for (String digestString : dirtyDigests) {
					Optional<DigestRecord> odr = Utils.convertDigestStringToRecord(digestString);
					if (odr.isPresent()) digestRecords.add(odr.get());
				}
				
				this.softwareMetadata.setDigestRecords(digestRecords);
			}	
		}
	}

	public String getShaDigest() {
		String shaDigest = null;
		if (null != this.softwareMetadata) {
			var digests = this.getSoftwareMetadata().getDigestRecords();
			if (null != digests && !digests.isEmpty()) {
				var shaDigestRec = digests
							.stream()
							.filter(digest -> digest.algo() == TeaChecksumType.SHA_256).findFirst().orElse(null);
				shaDigest = "sha256:" + shaDigestRec.digest();
			}
		}
		return shaDigest;
	}
}
