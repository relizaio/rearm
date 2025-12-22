/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import io.reliza.common.Utils;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;

@JsonIgnoreProperties(ignoreUnknown = true)
public class VulnAnalysisData extends RelizaDataParent implements RelizaObject {
	
	/**
	 * Inner class representing a single entry in the analysis history
	 */
	public static class AnalysisHistory {
		@JsonProperty("state")
		private AnalysisState state;
		
		@JsonProperty("justification")
		private AnalysisJustification justification;
		
		@JsonProperty("details")
		private String details;
		
		@JsonProperty("createdDate")
		private ZonedDateTime createdDate;
		
		@JsonProperty("whoUpdated")
		private WhoUpdated whoUpdated;
		
		@JsonProperty("severity")
		private VulnerabilitySeverity severity;
		
		public AnalysisHistory() {}
		
		public AnalysisHistory(AnalysisState state, AnalysisJustification justification, String details, VulnerabilitySeverity severity, WhoUpdated wu) {
			this.state = state;
			this.justification = justification;
			this.details = details;
			this.severity = severity;
			this.createdDate = ZonedDateTime.now();
			this.whoUpdated = wu;
		}
		
		public AnalysisState getState() {
			return state;
		}
		
		public void setState(AnalysisState state) {
			this.state = state;
		}
		
		public AnalysisJustification getJustification() {
			return justification;
		}
		
		public void setJustification(AnalysisJustification justification) {
			this.justification = justification;
		}
		
		public String getDetails() {
			return details;
		}
		
		public void setDetails(String details) {
			this.details = details;
		}
		
		public ZonedDateTime getCreatedDate() {
			return createdDate;
		}
		
		public void setCreatedDate(ZonedDateTime createdDate) {
			this.createdDate = createdDate;
		}
		
		public WhoUpdated getWhoUpdated() {
			return whoUpdated;
		}
		
		public void setWhoUpdated(WhoUpdated whoUpdated) {
			this.whoUpdated = whoUpdated;
		}
		
		public VulnerabilitySeverity getSeverity() {
			return severity;
		}
		
		public void setSeverity(VulnerabilitySeverity severity) {
			this.severity = severity;
		}
	}
	
	private UUID uuid;
	
	@JsonProperty("org")
	private UUID org;
	
	@JsonProperty("location")
	private String location;
	
	@JsonProperty("rawLocation")
	private String rawLocation;
	
	@JsonProperty("locationType")
	private LocationType locationType;
	
	@JsonProperty("findingId")
	private String findingId;
	
	@JsonProperty("findingAliases")
	private List<String> findingAliases = new LinkedList<>();
	
	@JsonProperty("findingType")
	private FindingType findingType;
	
	@JsonProperty("scope")
	private AnalysisScope scope;
	
	@JsonProperty("scopeUuid")
	private UUID scopeUuid;
	
	@JsonProperty("analysisState")
	private AnalysisState analysisState;
	
	@JsonProperty("analysisJustification")
	private AnalysisJustification analysisJustification;
	
	@JsonProperty("analysisHistory")
	private List<AnalysisHistory> analysisHistory = new LinkedList<>();
	
	@JsonProperty("severity")
	private VulnerabilitySeverity severity;
	
	private VulnAnalysisData() {}
	
	public UUID getUuid() {
		return uuid;
	}
	
	private void setUuid(UUID uuid) {
		this.uuid = uuid;
	}
	
	public UUID getOrg() {
		return org;
	}
	
	private void setOrg(UUID org) {
		this.org = org;
	}
	
	public String getLocation() {
		return location;
	}
	
	public void setLocation(String location) {
		this.location = location;
	}
	
	public String getRawLocation() {
		return rawLocation;
	}
	
	public void setRawLocation(String rawLocation) {
		this.rawLocation = rawLocation;
	}
	
	public LocationType getLocationType() {
		return locationType;
	}
	
	public void setLocationType(LocationType locationType) {
		this.locationType = locationType;
	}
	
	public String getFindingId() {
		return findingId;
	}
	
	public void setFindingId(String findingId) {
		this.findingId = findingId;
	}
	
	public List<String> getFindingAliases() {
		return new LinkedList<>(findingAliases);
	}
	
	public void setFindingAliases(List<String> findingAliases) {
		this.findingAliases = new LinkedList<>(findingAliases);
	}
	
	public void addFindingAlias(String alias) {
		if (!this.findingAliases.contains(alias)) {
			this.findingAliases.add(alias);
		}
	}
	
	public FindingType getFindingType() {
		return findingType;
	}
	
	public void setFindingType(FindingType findingType) {
		this.findingType = findingType;
	}
	
	public AnalysisScope getScope() {
		return scope;
	}
	
	public void setScope(AnalysisScope scope) {
		this.scope = scope;
	}
	
	public UUID getScopeUuid() {
		return scopeUuid;
	}
	
	public void setScopeUuid(UUID scopeUuid) {
		this.scopeUuid = scopeUuid;
	}
	
	public AnalysisState getAnalysisState() {
		return analysisState;
	}
	
	public void setAnalysisState(AnalysisState analysisState) {
		this.analysisState = analysisState;
	}
	
	public AnalysisJustification getAnalysisJustification() {
		return analysisJustification;
	}
	
	public void setAnalysisJustification(AnalysisJustification analysisJustification) {
		this.analysisJustification = analysisJustification;
	}
	
	public List<AnalysisHistory> getAnalysisHistory() {
		return new LinkedList<>(analysisHistory);
	}
	
	public void setAnalysisHistory(List<AnalysisHistory> analysisHistory) {
		this.analysisHistory = new LinkedList<>(analysisHistory);
	}
	
	public VulnerabilitySeverity getSeverity() {
		return severity;
	}
	
	public void setSeverity(VulnerabilitySeverity severity) {
		this.severity = severity;
	}
	
	/**
	 * Adds a new entry to the analysis history and updates current state
	 */
	public void addAnalysisHistoryEntry(AnalysisState state, AnalysisJustification justification, String details, VulnerabilitySeverity severity, WhoUpdated wu) {
		AnalysisHistory entry = new AnalysisHistory(state, justification, details, severity, wu);
		this.analysisHistory.add(entry);
		this.analysisState = state;
		this.analysisJustification = justification;
		this.severity = severity;
	}
	
	/**
	 * Factory method to create a new VulnAnalysisData instance
	 */
	public static VulnAnalysisData createVulnAnalysisData(
			UUID org,
			String location,
			String rawLocation,
			LocationType locationType,
			String findingId,
			List<String> findingAliases,
			FindingType findingType,
			AnalysisScope scope,
			UUID scopeUuid,
			AnalysisState initialState,
			AnalysisJustification initialJustification,
			String initialDetails,
			WhoUpdated wu) {
		
		VulnAnalysisData vad = new VulnAnalysisData();
		vad.setUuid(UUID.randomUUID());
		vad.setOrg(org);
		vad.setLocation(location);
		vad.setRawLocation(rawLocation);
		vad.setLocationType(locationType);
		vad.setFindingId(findingId);
		vad.setFindingAliases(findingAliases != null ? findingAliases : new LinkedList<>());
		vad.setFindingType(findingType);
		vad.setScope(scope);
		vad.setScopeUuid(scopeUuid);
		vad.addAnalysisHistoryEntry(initialState, initialJustification, initialDetails, null, wu);
		
		return vad;
	}
	
	/**
	 * Converts VulnAnalysis entity to VulnAnalysisData
	 */
	public static VulnAnalysisData dataFromRecord(VulnAnalysis va) {
		VulnAnalysisData vad = Utils.OM.convertValue(va.getRecordData(), new TypeReference<VulnAnalysisData>() {});
		vad.setUuid(va.getUuid());
		return vad;
	}
	
	/**
	 * Converts VulnAnalysisData to Map for storage
	 */
	public Map<String, Object> toRecordData() {
		return Utils.OM.convertValue(this, new TypeReference<Map<String, Object>>() {});
	}

	@Override
	public UUID getResourceGroup() {
		// TODO Auto-generated method stub
		return null;
	}
}
