/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.model.AnalysisJustification;
import io.reliza.model.AnalysisScope;
import io.reliza.model.AnalysisState;
import io.reliza.model.FindingType;
import io.reliza.model.LocationType;
import io.reliza.model.VulnAnalysisData;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import lombok.Data;

@Data
public class VulnAnalysisWebDto {
	
	@Data
	public static class AnalysisHistoryWebDto {
		@JsonProperty("state")
		private AnalysisState state;
		
		@JsonProperty("justification")
		private AnalysisJustification justification;
		
		@JsonProperty("details")
		private String details;
		
		@JsonProperty("createdDate")
		private ZonedDateTime createdDate;
		
		@JsonProperty("severity")
		private VulnerabilitySeverity severity;
		
		public static AnalysisHistoryWebDto fromAnalysisHistory(VulnAnalysisData.AnalysisHistory history) {
			AnalysisHistoryWebDto dto = new AnalysisHistoryWebDto();
			dto.setState(history.getState());
			dto.setJustification(history.getJustification());
			dto.setDetails(history.getDetails());
			dto.setCreatedDate(history.getCreatedDate());
			dto.setSeverity(history.getSeverity());
			return dto;
		}
	}
	
	@JsonProperty("uuid")
	private UUID uuid;
	
	@JsonProperty("org")
	private UUID org;
	
	@JsonProperty("location")
	private String location;
	
	@JsonProperty("locationType")
	private LocationType locationType;
	
	@JsonProperty("findingId")
	private String findingId;
	
	@JsonProperty("findingAliases")
	private List<String> findingAliases;
	
	@JsonProperty("findingType")
	private FindingType findingType;
	
	@JsonProperty("scope")
	private AnalysisScope scope;
	
	@JsonProperty("scopeUuid")
	private UUID scopeUuid;
	
	@JsonProperty("release")
	private UUID release;
	
	@JsonProperty("branch")
	private UUID branch;
	
	@JsonProperty("component")
	private UUID component;
	
	@JsonProperty("analysisState")
	private AnalysisState analysisState;
	
	@JsonProperty("analysisJustification")
	private AnalysisJustification analysisJustification;
	
	@JsonProperty("analysisHistory")
	private List<AnalysisHistoryWebDto> analysisHistory;
	
	@JsonProperty("severity")
	private VulnerabilitySeverity severity;
	
	public static VulnAnalysisWebDto fromVulnAnalysisData(VulnAnalysisData vad) {
		VulnAnalysisWebDto dto = new VulnAnalysisWebDto();
		dto.setUuid(vad.getUuid());
		dto.setOrg(vad.getOrg());
		dto.setLocation(vad.getLocation());
		dto.setLocationType(vad.getLocationType());
		dto.setFindingId(vad.getFindingId());
		dto.setFindingAliases(vad.getFindingAliases());
		dto.setFindingType(vad.getFindingType());
		dto.setScope(vad.getScope());
		dto.setScopeUuid(vad.getScopeUuid());
		dto.setRelease(vad.getRelease());
		dto.setBranch(vad.getBranch());
		dto.setComponent(vad.getComponent());
		dto.setAnalysisState(vad.getAnalysisState());
		dto.setAnalysisJustification(vad.getAnalysisJustification());
		dto.setSeverity(vad.getSeverity());
		dto.setAnalysisHistory(vad.getAnalysisHistory().stream()
				.map(AnalysisHistoryWebDto::fromAnalysisHistory)
				.toList());
		return dto;
	}
}
