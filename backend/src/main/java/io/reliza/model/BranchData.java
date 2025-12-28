/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.PullRequestState;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Branch is the child of Project and VcsRepository
 * It is created under project, but then vcs repository gets assigned to it
 * @author pavel
 *
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class BranchData extends RelizaDataParent implements RelizaObject {
	
	@Builder
	@Data
	public static class ChildComponent {
		@JsonProperty(CommonVariables.STATUS_FIELD)
		private StatusEnum status;
		@JsonProperty(CommonVariables.UUID_FIELD)
		private UUID uuid; // this is project UUID
		@JsonProperty(CommonVariables.BRANCH_FIELD)
		private UUID branch; // this is optional, and if set would be used for the auto-integrate functionality
		@JsonProperty(CommonVariables.RELEASE_FIELD)
		private UUID release; // this is also optional, if present this would essentially pin release to this UUID on auto-integrate rather than exploring latest or some other logic
		@JsonProperty
		private Boolean isFollowVersion;
	}
	
	/**
	 * Pattern-based dependency matching for auto-integrate.
	 * Allows regex patterns to automatically include matching components.
	 */
	@Builder
	@Data
	public static class DependencyPattern {
		@JsonProperty(CommonVariables.UUID_FIELD)
		private UUID uuid;
		
		@JsonProperty
		private String pattern;  // include regex pattern, e.g., "^reliza-.*"
		
		@JsonProperty
		private String targetBranchName;  // specific branch name like "main"
		
		@JsonProperty
		private StatusEnum defaultStatus;  //
	}
	
	public enum BranchType {
		BASE, // main branch
		FEATURE,
		REGULAR, // legacy type or for unknown
		RELEASE,
		PULL_REQUEST,
		TAG,
		DEVELOP,
		HOTFIX;
	}
	
	public enum AutoIntegrateState {
		ENABLED,
		DISABLED;
	}
	
	private UUID uuid;
	@JsonProperty(CommonVariables.NAME_FIELD)
	private String name;
	@JsonProperty(CommonVariables.COMPONENT_FIELD)
	private UUID component;
	@JsonProperty(CommonVariables.STATUS_FIELD)
	private StatusEnum status;
	@JsonProperty(CommonVariables.TYPE_FIELD)
	private BranchType type;
	private UUID vcs;
	/**
	 * This field corresponds to branch name in vcs; by default we use branch name, but this
	 * field allows to make it different
	 */
	@JsonProperty(CommonVariables.VCS_BRANCH_FIELD)
	private String vcsBranch;
	
	private List<ChildComponent> dependencies = new LinkedList<>();
	
	private List<DependencyPattern> dependencyPatterns = new LinkedList<>();
	
	private UUID org;
	
	/**
	 * This version schema field is normally based on project's version schema but uses some elements
	 * to pin parent's schema
	 * I.e., if parent schema is YYYY.0M.Modifier.Minor.Micro+Metadata,
	 * branch's schema could be 2020.01.Latest.Minor.Micro+Metadata
	 * this would mean that "2020.01.Latest" will be constant part for every release of the branch
	 * while Minor.Micro+Metadata would be variable part
	 */
	@JsonProperty(CommonVariables.VERSION_SCHEMA_FIELD)
	private String versionSchema;

	@JsonProperty(CommonVariables.MARKETING_VERSION_SCHEMA_FIELD)
	private String marketingVersionSchema;
	
	/**
	 * This field is used to supply metadata for version generations
	 * if supplied, metadata would be added to release
	 */
	@JsonProperty(CommonVariables.METADATA_FIELD)
	private String metadata;
	
	private AutoIntegrateState autoIntegrate = AutoIntegrateState.DISABLED;

	@Builder
	@Data
	public static class PullRequestData {
		@JsonProperty(CommonVariables.STATE_FIELD)
		private PullRequestState state;
		
		@JsonProperty(CommonVariables.TARGET_BRANCH_FIELD)
		private UUID targetBranch; // Branch UUID of the target for this PR

		@JsonProperty(CommonVariables.NUMBER_FIELD)
		private Integer number; // Number for this PR

		@JsonProperty(CommonVariables.ENDPOINT_FIELD)
		private URI endpoint; // Endpoint if this PR

		@JsonProperty(CommonVariables.TITLE_FIELD)
		private String title; // Endpoint if this PR

		@JsonProperty(CommonVariables.CREATED_DATE_FIELD)
		private ZonedDateTime createdDate; // Endpoint if this PR

		@JsonProperty(CommonVariables.CLOSED_DATE_FIELD)
		private ZonedDateTime closedDate; // Endpoint if this PR

		@JsonProperty(CommonVariables.MERGED_DATE_FIELD)
		private ZonedDateTime mergedDate; // Endpoint if this PR

		@JsonProperty(CommonVariables.COMMITS_FIELD)
		private List<UUID> commits;
		
	}
	
	@JsonProperty(CommonVariables.PULL_REQUEST_DATA_FEILD)
	private Map<Integer, PullRequestData> pullRequestData = new LinkedHashMap<>();
	
	private BranchData () {}
	
	public void setPullRequestData(Map<Integer, PullRequestData> pullRequestData) {
		if(null != pullRequestData && !pullRequestData.isEmpty())
			this.pullRequestData = new LinkedHashMap<>(pullRequestData);
		else
			this.pullRequestData = new LinkedHashMap<>();
	}
	
	
	public Optional<ChildComponent> getFollowedVersionComponent () {
		Optional<ChildComponent> followedVersionComponent = Optional.empty();
		if (null != this.dependencies && !this.dependencies.isEmpty()) {
			var depIter = this.dependencies.iterator();
			while (followedVersionComponent.isEmpty() && depIter.hasNext()) {
				var dep = depIter.next();
				if (null != dep.isFollowVersion && dep.isFollowVersion) {
					followedVersionComponent = Optional.of(dep);
				}
			}
		}
		return followedVersionComponent;
	}

	public static BranchData branchDataFactory (String name, UUID component, UUID orgUuid,
												StatusEnum status, BranchType bType,
												UUID vcs, String vcsBranch, String versionPin, String marketingVersionPin) {
		BranchData bd = new BranchData();
		bd.setName(name);
		bd.setComponent(component);
		bd.setStatus(status);
		bd.setType(bType);
		bd.setVcs(vcs);
		bd.setVcsBranch(vcsBranch);
		bd.setVersionSchema(versionPin);
		bd.setMarketingVersionSchema(marketingVersionPin);
		bd.setOrg(orgUuid);

		return bd;
	}
	
	public static BranchType resolveBranchTypeByName (String name) {
		BranchType bt = BranchType.REGULAR;
		if (name.startsWith("pull/") || name.startsWith("pullrequest/")) {
			bt = BranchType.PULL_REQUEST;
		} else if (name.startsWith("tags/")) {
			bt = BranchType.TAG;
		} else if (name.toLowerCase().contains("hotfix")) {
			bt = BranchType.HOTFIX;
		} else if (name.toLowerCase().contains("release/")) {
			bt = BranchType.RELEASE;
		} else if (name.equalsIgnoreCase("develop")) {
			bt = BranchType.DEVELOP;
		} else if (name.toLowerCase().contains("feature/")
				|| name.toLowerCase().contains("feature-") 
				|| name.toLowerCase().contains("-feature")) {
			bt = BranchType.FEATURE;
		}
		return bt;
	}
	
	public static BranchData branchDataFromDbRecord (Branch b) {
		if (b.getSchemaVersion() != 0) { // we'll be adding new schema versions later as required, if schema version is not supported, throw exception
			throw new IllegalStateException("Branch schema version is " + b.getSchemaVersion() + ", which is not currently supported");
		}
		Map<String,Object> recordData = b.getRecordData();
		BranchData bd = Utils.OM.convertValue(recordData, BranchData.class);
		bd.setUuid(b.getUuid());
		bd.setCreatedDate(b.getCreatedDate());
		return bd;
	}
	
	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		// TODO Auto-generated method stub
		return null;
	}
}
