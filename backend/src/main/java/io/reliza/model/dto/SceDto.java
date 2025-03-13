/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import io.reliza.common.CommonVariables;
import io.reliza.common.DateDeserializer;
import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import lombok.Builder;
import lombok.Data;

/**
 * Source Code Entry DTO
 *
 */
@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class SceDto {
	@JsonProperty(CommonVariables.UUID_FIELD)
	private UUID uuid;
	
	private UUID branch;
	
	private UUID vcs;
	
	private String vcsBranch;
	
	private String commit; // i.e. svn revision or git commit hash
	
	private String commitMessage; // actual commit message
	
	@JsonProperty(CommonVariables.COMMIT_AUTHOR_FIELD)
	private String commitAuthor;
	
	@JsonProperty(CommonVariables.COMMIT_EMAIL_FIELD)
	private String commitEmail;
	
	@JsonProperty(CommonVariables.VCS_TAG_FIELD)
	private String vcsTag; // i.e. svn tag or git tag
	
	@JsonProperty(CommonVariables.NOTES_FIELD)
	private String notes; // any additional meta may go here
	
	@JsonProperty(CommonVariables.ORGANIZATION_FIELD)
	private UUID organizationUuid; // for releases
	
	@JsonDeserialize(using = DateDeserializer.class)
	@JsonProperty(CommonVariables.DATE_ACTUAL_FIELD)
	private ZonedDateTime date;

	@JsonProperty(CommonVariables.TICKET_FIELD)
	private UUID ticket;

	@JsonProperty(CommonVariables.ARTIFACTS_FIELD)
	private List<UUID> artifacts;

	/**
	 * This is vcs uri - not part of actual sce
	 */
	@JsonProperty(CommonVariables.URI_FIELD)
	private String uri;
	
	/**
	 * This is vcs type - not part of actual sce
	 */
	@JsonProperty(CommonVariables.TYPE_FIELD)
	private VcsType type;
	
	private static String cleanCommitMessage (String commitMessage) {
		return commitMessage.replaceAll("(\\r\\n|\\n)", System.lineSeparator());
	}
	
	public void cleanMessage () {
		if (StringUtils.isNotEmpty(this.commitMessage)) {
			this.commitMessage = cleanCommitMessage(this.commitMessage);
		}
		if (StringUtils.isNotEmpty(this.commitAuthor)) {
			this.commitAuthor = Utils.cleanString(this.commitAuthor);
		}
		if (StringUtils.isNotEmpty(this.commitEmail)) {
			this.commitEmail = Utils.cleanString(this.commitEmail);
		}
	}
}
