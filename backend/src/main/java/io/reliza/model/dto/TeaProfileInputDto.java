/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaDependencyDepth;
import io.reliza.model.TeaProfileData.TeaInternalMetadata;
import io.reliza.model.TeaProfileData.TeaOptionalDependencies;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaSbomSource;
import io.reliza.model.TeaProfileData.TeaSupportMetadata;
import io.reliza.model.TeaProfileData.TeaTei;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaProfileData.TeaVulnerabilityDocuments;
import io.reliza.service.RebomService.BomStructureType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The {@code TeaProfileInput} of a TEA profile save (task TEA-2). Every field is nullable at the
 * boundary, because a FOLLOW_PERSPECTIVE row sends no profile field; {@code TeaProfileService}
 * decides what each scope and mode requires, and never defaults an omitted field.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TeaProfileInputDto {
	private TeaComponentProfileMode mode;
	private UUID followedPerspective;
	private TeaPublishing publishing;
	private TeaVisibility visibility;
	private TeaDependencyDepth dependencyDepth;
	private TeaOptionalDependencies optionalDependencies;
	private BomStructureType structure;
	private Set<TeaSbomSource> sources;
	private Set<ArtifactCoverageType> excludedCoverage;
	private TeaSupportMetadata supportMetadata;
	private TeaInternalMetadata internalMetadata;
	private TeaRawArtifacts rawArtifacts;
	private TeaProductComponents productComponents;
	private ReleaseLifecycle minimumLifecycle;
	private TeaTei tei;
	private String teiDomain;
	private TeaVulnerabilityDocuments vulnerabilityDocuments;
}
