/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.model.SbomProfileData.SbomDependencyDepth;
import io.reliza.model.SbomProfileData.SbomInternalMetadata;
import io.reliza.model.SbomProfileData.SbomOptionalDependencies;
import io.reliza.model.SbomProfileData.SbomSourceKind;
import io.reliza.model.SbomProfileData.SbomSupportMetadata;
import io.reliza.model.SbomProfileData.SbomVulnerabilityDocuments;
import io.reliza.model.ScopedSettingsMode;
import io.reliza.service.RebomService.BomStructureType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The {@code SbomProfileInput} of an SBOM profile save (task TEA-27). Every field is nullable at
 * the boundary, because a FOLLOW_PERSPECTIVE row sends no recipe field; {@code SbomProfileService}
 * decides what each scope and mode requires, and never defaults an omitted field. The name and the
 * key (org, scope, object) come as arguments, not in the input.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SbomProfileInputDto {
	private ScopedSettingsMode mode;
	private UUID followedPerspective;
	private String description;
	private SbomDependencyDepth dependencyDepth;
	private SbomOptionalDependencies optionalDependencies;
	private BomStructureType structure;
	private Set<SbomSourceKind> sources;
	private Set<ArtifactCoverageType> excludedCoverage;
	private SbomSupportMetadata supportMetadata;
	private SbomInternalMetadata internalMetadata;
	private SbomVulnerabilityDocuments vulnerabilityDocuments;
}
