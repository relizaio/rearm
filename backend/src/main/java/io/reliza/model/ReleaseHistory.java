/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ProgrammaticType;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;

/**
 * Transient class to return audit change history for Releases
 * @author pavel
 *
 */
@Data
public class ReleaseHistory {
	
	// defines:
	// 1. type of object of change (project release, product release, property, settings)
	// 2. action of change (add, remove, update)
	// 3. revision number
	// 4. date
	// 5. source state
	// 6. target state
	// 7. updated by
	
	public enum ReleaseChangeType {
		APPROVALS,
		STATUS,
		COMPONENTS,
		ARTIFACTS,
		DEPLOYMENT,
		NOTES
		;
		
		private ReleaseChangeType () {}
	}

	@Setter(AccessLevel.PRIVATE)
	private UUID uuid; // uuid of audit tuple
	
	@Setter(AccessLevel.PRIVATE)
	@JsonProperty(CommonVariables.RELEASE_FIELD)
	private UUID release; // release uuid, just in case, so we wouldn't mix up
	
	@Setter(AccessLevel.PRIVATE)
	@JsonProperty(CommonVariables.REVISION_FIELD)
	private Integer revision;
	
	@Setter(AccessLevel.PRIVATE)
	@JsonProperty(CommonVariables.DATE_ACTUAL_FIELD)
	private ZonedDateTime dateActual; // change recorded date
	
	@Setter(AccessLevel.PRIVATE)
	@JsonProperty(CommonVariables.UPDATE_TYPE_FIELD)
	private ProgrammaticType updateType;
	
	@Setter(AccessLevel.PRIVATE)
	@JsonProperty(CommonVariables.LAST_UPDATED_BY_FIELD)
	private UUID updatedBy;
	
	@JsonProperty(CommonVariables.TYPE_FIELD)
	private Map<ReleaseChangeType, String> type = new LinkedHashMap<>();
	
	@Setter(AccessLevel.PRIVATE)
	@JsonIgnore
	private String rawJson;

	private ReleaseHistory() {}

	public static ReleaseHistory releaseHistoryFactory(UUID auditUuid, UUID release, Integer revision, ZonedDateTime date, 
			ProgrammaticType updateType, UUID updatedByUser, Map<ReleaseChangeType, String> changeType, String rawJson) {
		ReleaseHistory ih = new ReleaseHistory();
		ih.setUuid(auditUuid);
		ih.setRelease(release);
		ih.setRevision(revision);
		ih.setDateActual(date);
		ih.setUpdateType(updateType);
		ih.setUpdatedBy(updatedByUser);
		ih.setType(changeType);
		ih.setRawJson(rawJson);
		return ih;
	}
}
