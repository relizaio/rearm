/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;

/**
 * DTO for CVE search results organized by Component -> Branch -> Releases
 */
public class CveSearchResultDto {
	
	/**
	 * Branch with its releases
	 */
	public static record BranchWithReleases(
			@JsonProperty("uuid") UUID uuid,
			@JsonProperty("name") String name,
			@JsonProperty("status") StatusEnum status,
			@JsonProperty("versionSchema") String versionSchema,
			@JsonProperty("latestReleaseVersion") String latestReleaseVersion,
			@JsonProperty("releases") List<ReleaseData> releases
	) {}
	
	/**
	 * Component with its branches and releases
	 */
	public static record ComponentWithBranches(
			@JsonProperty("uuid") UUID uuid,
			@JsonProperty("name") String name,
			@JsonProperty("type") ComponentType type,
			@JsonProperty("versionSchema") String versionSchema,
			@JsonProperty("branches") List<BranchWithReleases> branches
	) {}
}
