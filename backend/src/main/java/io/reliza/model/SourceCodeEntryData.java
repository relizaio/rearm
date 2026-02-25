/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.model.dto.SceDto;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Setter;

/**
 * This class references specific entry inside a release, i.e. details on specific project
 * within a larger release.
 * It is also a parent for Artifacts, so that zero or more artifacts belong to a release entry.
 * The idea here is that release entry references specific commit or vcs tag, however we assume that 
 * multiple artifacts could be built of this task. For example, we could build both a jar file and a 
 * docker image with the same jar file inside, thus having 2 artifacts in this case per release entry.
 * @author pavel
 *
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SourceCodeEntryData extends RelizaDataParent implements RelizaObject {
	
	private static final String DEMO_COMMIT_EMAIL = "info@reliza.io";

	@Setter(AccessLevel.PRIVATE)
	private UUID uuid;
	
	@Setter(AccessLevel.PRIVATE)
	private UUID branch; // source code entry may reference a branch of a project
	
	@Setter(AccessLevel.PRIVATE)
	private UUID vcs; // source code entry must reference a vcs repository
	
	@JsonProperty(CommonVariables.VCS_BRANCH_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private String vcsBranch; // source code entry may reference vcs branch
	
	@JsonProperty(CommonVariables.COMMIT_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private String commit; // i.e. svn revision or git commit hash
	
	@JsonProperty(CommonVariables.COMMIT_MESSAGE_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private String commitMessage; // actual commit message
	
	@JsonProperty(CommonVariables.COMMIT_AUTHOR_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private String commitAuthor; // commit author name
	
	@JsonProperty(CommonVariables.COMMIT_EMAIL_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private String commitEmail; // commit author email
	
	@JsonProperty(CommonVariables.VCS_TAG_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private String vcsTag; // i.e. svn tag or git tag
	
	@JsonProperty(CommonVariables.NOTES_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private String notes; // any additional meta may go here
	
	@Setter(AccessLevel.PRIVATE)
	private UUID org;
	
	@JsonProperty(CommonVariables.DATE_ACTUAL_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private ZonedDateTime dateActual;

	@JsonProperty(CommonVariables.TICKET_FIELD)
	@Setter(AccessLevel.PRIVATE)
	private UUID ticket;

	@Setter()
	@JsonProperty(CommonVariables.ARTIFACTS_FIELD)
	private List<SCEArtifact> artifacts = new ArrayList<>();
	
	private SourceCodeEntryData () {}
	
	public record SCEArtifact (UUID artifactUuid, UUID componentUuid) {}

	public static SourceCodeEntryData scEntryDataFactory(SceDto sceDto) {
		SourceCodeEntryData sced = new SourceCodeEntryData();
		sced.setBranch(sceDto.getBranch());
		sced.setVcs(sceDto.getVcs());
		sced.setOrg(sceDto.getOrganizationUuid());
		sced.setVcsBranch(sceDto.getVcsBranch());
		sced.setCommit(sceDto.getCommit());
		sced.setCommitMessage(sceDto.getCommitMessage());
		sced.setVcsTag(sceDto.getVcsTag());
		sced.setNotes(sceDto.getNotes());
		sced.setDateActual(sceDto.getDate());
		sced.setTicket(sceDto.getTicket());
		sced.setCommitAuthor(sceDto.getCommitAuthor());
		sced.setCommitEmail(sceDto.getCommitEmail());
		if (null != sceDto.getArtifacts()) sced.setArtifacts(sceDto.getArtifacts());
		return sced;
	}
	
	public static SourceCodeEntryData obtainNullSceData() {
		SourceCodeEntryData sced = new SourceCodeEntryData();
		sced.uuid = new UUID(0,0);
		sced.setCommitMessage(CommonVariables.DETAILS_UNAVAILABLE_MESSAGE);
		return sced;
	}

	public static SourceCodeEntryData dataFromRecord (SourceCodeEntry sce) {
		return dataFromRecord(sce, false);
	}
	
	public static SourceCodeEntryData dataFromRecord (SourceCodeEntry sce, boolean isDemo) {
		if (sce.getSchemaVersion() != 0) { // we'll be adding new schema versions later as required, if schema version is not supported, throw exception
			throw new IllegalStateException("Release entry schema version is " + sce.getSchemaVersion() + ", which is not currently supported");
		}
		Map<String,Object> recordData = sce.getRecordData();
		SourceCodeEntryData red = Utils.OM.convertValue(recordData, SourceCodeEntryData.class);
		red.setUuid(sce.getUuid());
		red.setCreatedDate(sce.getCreatedDate());
		red.setUpdatedDate(sce.getLastUpdatedDate());
		if (isDemo) {
			red.setCommitEmail(DEMO_COMMIT_EMAIL);
		}
		return red;
	}

	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		// TODO Auto-generated method stub
		return null;
	}
}
