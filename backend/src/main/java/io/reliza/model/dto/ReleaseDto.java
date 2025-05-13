/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.model.ParentRelease;
import io.reliza.model.ReleaseData.ReleaseBom;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.tea.TeaIdentifier;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReleaseDto {
	@JsonProperty(CommonVariables.UUID_FIELD)
	private UUID uuid;
	@JsonProperty(CommonVariables.VERSION_FIELD)
	private String version;
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private ReleaseStatus status;
	@JsonProperty
	private UUID org;
	@JsonProperty(CommonVariables.COMPONENT_FIELD)
	private UUID component; // data denormalization, branch is still prevalent
	@JsonProperty(CommonVariables.BRANCH_FIELD)
	private UUID branch;
	@JsonProperty(CommonVariables.PARENT_RELEASES_FIELD)
	private List<ParentRelease> parentReleases;
	@JsonProperty(CommonVariables.SOURCE_CODE_ENTRY_FIELD)
	private UUID sourceCodeEntry; // this is like export source code entry, for now we allow only one 
	// own source code entry per releases - hopefully that would be enough
	@JsonProperty(CommonVariables.ARTIFACTS_FIELD)
	private List<UUID> artifacts; // there may be several artifacts per release,
	// i.e. docker image and binary and tgz vs zip
	@JsonProperty(CommonVariables.NOTES_FIELD)
	private String notes;
	@JsonProperty(CommonVariables.ENDPOINT_FIELD)
	private URI endpoint;
	@JsonProperty(CommonVariables.COMMITS_FIELD)
	private List<UUID> commits;
	@JsonProperty(CommonVariables.TICKETS_FIELD)
	private Set<UUID> tickets;
	@JsonProperty(CommonVariables.TAGS_FIELD)
	private List<TagRecord> tags;
	
	@JsonProperty
	private ReleaseLifecycle lifecycle;

	@JsonProperty(CommonVariables.REBOMS_FIELD)
	private List<ReleaseBom> reboms;
	
	@JsonProperty
	private List<TeaIdentifier> identifiers;

	@JsonProperty
	private List<UUID> inboundDeliverables;
	@JsonProperty
	private List<UUID> outboundDeliverables;

	@JsonProperty(CommonVariables.FS_BOM_FIELD)
	private Object fsBom;
	
	public static boolean isAssemblyRequested (ReleaseDto rDto) {
		boolean isAssemblyRequested = false;
		if (null != rDto.getParentReleases()
				|| null != rDto.getSourceCodeEntry()
				|| null != rDto.getCommits()
				|| null != rDto.getInboundDeliverables()
				|| null != rDto.getOutboundDeliverables()
			) isAssemblyRequested = true;
		return isAssemblyRequested;
	}
}
