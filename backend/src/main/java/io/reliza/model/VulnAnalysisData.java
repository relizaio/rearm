/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
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
		
		@JsonProperty("responses")
		@JsonSetter(nulls = Nulls.AS_EMPTY)
		private List<AnalysisResponse> responses = new LinkedList<>();
		
		@JsonProperty("recommendation")
		private String recommendation;
		
		@JsonProperty("workaround")
		private String workaround;
		
		public AnalysisHistory() {}
		
		public AnalysisHistory(AnalysisState state, AnalysisJustification justification, String details, VulnerabilitySeverity severity, WhoUpdated wu) {
			this(state, justification, details, severity, null, null, null, wu);
		}
		
		public AnalysisHistory(AnalysisState state, AnalysisJustification justification, String details,
				VulnerabilitySeverity severity, List<AnalysisResponse> responses, String recommendation,
				String workaround, WhoUpdated wu) {
			this.state = state;
			this.justification = justification;
			this.details = details;
			this.severity = severity;
			this.responses = responses != null ? new LinkedList<>(responses) : new LinkedList<>();
			this.recommendation = recommendation;
			this.workaround = workaround;
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
		
		public List<AnalysisResponse> getResponses() {
			return responses != null ? new LinkedList<>(responses) : new LinkedList<>();
		}
		
		public void setResponses(List<AnalysisResponse> responses) {
			this.responses = responses != null ? new LinkedList<>(responses) : new LinkedList<>();
		}
		
		public String getRecommendation() {
			return recommendation;
		}
		
		public void setRecommendation(String recommendation) {
			this.recommendation = recommendation;
		}
		
		public String getWorkaround() {
			return workaround;
		}
		
		public void setWorkaround(String workaround) {
			this.workaround = workaround;
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
	private List<AnalysisHistory> analysisHistory = new LinkedList<>();
	
	@JsonProperty("severity")
	private VulnerabilitySeverity severity;
	
	@JsonProperty("responses")
	@JsonSetter(nulls = Nulls.AS_EMPTY)
	private List<AnalysisResponse> responses = new LinkedList<>();
	
	@JsonProperty("recommendation")
	private String recommendation;
	
	@JsonProperty("workaround")
	private String workaround;
	
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
	
	public UUID getRelease() {
		return release;
	}
	
	public void setRelease(UUID release) {
		this.release = release;
	}
	
	public UUID getBranch() {
		return branch;
	}
	
	public void setBranch(UUID branch) {
		this.branch = branch;
	}
	
	public UUID getComponent() {
		return component;
	}
	
	public void setComponent(UUID component) {
		this.component = component;
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
	
	public List<AnalysisResponse> getResponses() {
		return responses != null ? new LinkedList<>(responses) : new LinkedList<>();
	}
	
	public void setResponses(List<AnalysisResponse> responses) {
		this.responses = responses != null ? new LinkedList<>(responses) : new LinkedList<>();
	}
	
	public String getRecommendation() {
		return recommendation;
	}
	
	public void setRecommendation(String recommendation) {
		this.recommendation = recommendation;
	}
	
	public String getWorkaround() {
		return workaround;
	}
	
	public void setWorkaround(String workaround) {
		this.workaround = workaround;
	}
	
	/**
	 * Adds a new entry to the analysis history and updates current state.
	 * Kept for backwards compatibility; delegates to the full-parameter overload.
	 */
	public void addAnalysisHistoryEntry(AnalysisState state, AnalysisJustification justification, String details, VulnerabilitySeverity severity, WhoUpdated wu) {
		addAnalysisHistoryEntry(state, justification, details, severity, null, null, null, wu);
	}
	
	/**
	 * Adds a new entry to the analysis history and updates current state, including
	 * CISA-VEX action-statement fields (responses, recommendation, workaround).
	 */
	public void addAnalysisHistoryEntry(AnalysisState state, AnalysisJustification justification, String details,
			VulnerabilitySeverity severity, List<AnalysisResponse> responses, String recommendation,
			String workaround, WhoUpdated wu) {
		AnalysisHistory entry = new AnalysisHistory(state, justification, details, severity, responses, recommendation, workaround, wu);
		this.analysisHistory.add(entry);
		// Top-level mirror reflects the "current" analysis. We only overwrite a field when the caller
		// explicitly provided a value, so partial updates (including the 5-arg legacy overload that
		// passes null for the CISA fields) don't silently wipe previously-stored data.
		if (state != null) {
			this.analysisState = state;
		}
		if (justification != null) {
			this.analysisJustification = justification;
		}
		if (severity != null) {
			this.severity = severity;
		}
		if (responses != null) {
			this.responses = new LinkedList<>(responses);
		}
		if (recommendation != null) {
			this.recommendation = recommendation;
		}
		if (workaround != null) {
			this.workaround = workaround;
		}
	}
	
	/**
	 * Factory method to create a new VulnAnalysisData instance (backwards-compatible overload).
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
			UUID release,
			UUID branch,
			UUID component,
			AnalysisState initialState,
			AnalysisJustification initialJustification,
			String initialDetails,
			WhoUpdated wu) {
		return createVulnAnalysisData(org, location, rawLocation, locationType, findingId, findingAliases,
				findingType, scope, scopeUuid, release, branch, component, initialState, initialJustification,
				initialDetails, null, null, null, wu);
	}
	
	/**
	 * Factory method to create a new VulnAnalysisData instance with CISA-VEX fields.
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
			UUID release,
			UUID branch,
			UUID component,
			AnalysisState initialState,
			AnalysisJustification initialJustification,
			String initialDetails,
			List<AnalysisResponse> initialResponses,
			String initialRecommendation,
			String initialWorkaround,
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
		vad.setRelease(release);
		vad.setBranch(branch);
		vad.setComponent(component);
		vad.addAnalysisHistoryEntry(initialState, initialJustification, initialDetails, null,
				initialResponses, initialRecommendation, initialWorkaround, wu);
		
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
	
	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		if (!super.equals(o)) return false;
		VulnAnalysisData that = (VulnAnalysisData) o;
		return java.util.Objects.equals(uuid, that.uuid) &&
				java.util.Objects.equals(org, that.org) &&
				java.util.Objects.equals(location, that.location) &&
				java.util.Objects.equals(rawLocation, that.rawLocation) &&
				locationType == that.locationType &&
				java.util.Objects.equals(findingId, that.findingId) &&
				java.util.Objects.equals(findingAliases, that.findingAliases) &&
				findingType == that.findingType &&
				scope == that.scope &&
				java.util.Objects.equals(scopeUuid, that.scopeUuid) &&
				java.util.Objects.equals(release, that.release) &&
				java.util.Objects.equals(branch, that.branch) &&
				java.util.Objects.equals(component, that.component) &&
				analysisState == that.analysisState &&
				analysisJustification == that.analysisJustification &&
				java.util.Objects.equals(severity, that.severity) &&
				java.util.Objects.equals(responses, that.responses) &&
				java.util.Objects.equals(recommendation, that.recommendation) &&
				java.util.Objects.equals(workaround, that.workaround);
	}
	
	@Override
	public int hashCode() {
		return java.util.Objects.hash(super.hashCode(), uuid, org, location, rawLocation, 
				locationType, findingId, findingAliases, findingType, scope, scopeUuid,
				release, branch, component, analysisState, analysisJustification, severity,
				responses, recommendation, workaround);
	}
}
