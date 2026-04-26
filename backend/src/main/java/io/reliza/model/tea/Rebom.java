/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.tea;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.StripBom;
import io.reliza.model.dto.OASResponseDto;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.common.Utils.RootComponentMergeMode;
@JsonIgnoreProperties(ignoreUnknown = true)
public class Rebom {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RebomOptions(
        String name,
        String group,
        String version,
        ArtifactBelongsTo belongsTo,
        String hash,
        Boolean tldOnly,
        Boolean ignoreDev,
        BomStructureType structure,
        String notes,
        StripBom stripBom,
        String serialNumber,
        String bomDigest,
        String originalFileDigest,  // SHA256 of original file (for SPDX, this is the original SPDX file hash)
        Long originalFileSize,      // Size of original file in bytes (for SPDX)
        String originalMediaType,   // Media type of original file (for SPDX)
        String purl,
        RootComponentMergeMode rootComponentMergeMode,
        String bomVersion  // Rearm-managed version for SPDX (1, 2, 3...)
    ) {
        // create opts
        public RebomOptions(String name, String group,  String version, ArtifactBelongsTo belongsTo, String hash, Boolean tldOnly, BomStructureType structure, String notes, StripBom stripBom, String serialNumber, String bomDigest, String purl) {
            this(name, group, version, belongsTo, hash, tldOnly, false, structure, notes, stripBom, serialNumber, bomDigest, null, null, null, purl, RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }

        public RebomOptions(String name, String group,  String version, ArtifactBelongsTo belongsTo, String hash, Boolean tldOnly, Boolean ignoreDev, BomStructureType structure, String notes, StripBom stripBom, String serialNumber, String bomDigest, String purl) {
            this(name, group, version, belongsTo, hash, tldOnly, ignoreDev, structure, notes, stripBom, serialNumber, bomDigest, null, null, null, purl, RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }

        public RebomOptions(String name, String group,  String version, ArtifactBelongsTo belongsTo, String hash, Boolean tldOnly, Boolean ignoreDev, BomStructureType structure, String notes, StripBom stripBom, String serialNumber, String bomDigest, String purl, RootComponentMergeMode rootComponentMergeMode) {
            this(name, group, version, belongsTo, hash, tldOnly, ignoreDev, structure, notes, stripBom, serialNumber, bomDigest, null, null, null, purl, rootComponentMergeMode, null);
        }

        public RebomOptions(ArtifactBelongsTo belongsTo, Boolean tldOnly,  BomStructureType structure, RootComponentMergeMode rootComponentMergeMode) {
            this(null, null,  null, belongsTo, null, tldOnly, false, structure, "sent from ReArm", StripBom.TRUE, "", "", null, null, null, "", rootComponentMergeMode, null);
        }
        public RebomOptions() {
            this(null, null, null, ArtifactBelongsTo.RELEASE, null, false, false, BomStructureType.FLAT, "sent from ReArm", StripBom.TRUE, "", "", null, null, null, "", RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }
    

        public RebomOptions(String name, String group,  String version, ArtifactBelongsTo belongsTo, String hash, StripBom stripBom, String purl) {
            this(name, group,  version, belongsTo, hash, false, false, BomStructureType.FLAT, "sent from ReArm", stripBom, "", "", null, null, null, purl, RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }
        // merge opts
        public RebomOptions(ArtifactBelongsTo belongsTo, Boolean tldOnly,  BomStructureType structure) {
            this(null, null,  null, belongsTo, null, tldOnly, false, structure, "sent from ReArm", StripBom.TRUE, "", "", null, null, null, "", RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }

        public RebomOptions(ArtifactBelongsTo belongsTo, Boolean tldOnly, Boolean ignoreDev, BomStructureType structure) {
            this(null, null,  null, belongsTo, null, tldOnly, ignoreDev, structure, "sent from ReArm", StripBom.TRUE, "", "", null, null, null, "", RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }
    }

    public record RebomResponse(
        UUID uuid,
        OASResponseDto bom,
        RebomOptions meta,
        Boolean duplicate
    ) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InternalBom (UUID id, ArtifactBelongsTo belongsTo){}

    /**
     * Parsed SBOM component as returned by rebom's parseBomById query.
     * canonicalPurl is the purl with qualifiers + subpath stripped; fullPurl
     * preserves the original purl exactly as it appeared in the BOM.
     * isRoot is true for the node synthesised from bom.metadata.component.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParsedBomComponent(
        String canonicalPurl,
        String fullPurl,
        String type,
        String group,
        String name,
        String version,
        Boolean isRoot
    ) {}

    /**
     * One declared dependency edge as returned by rebom's parseBomById query.
     * Both endpoints have been resolved through a bom-ref → purl index, so
     * every edge is guaranteed to name components that will be present in
     * the components list above.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParsedBomDependency(
        String sourceCanonicalPurl,
        String sourceFullPurl,
        String targetCanonicalPurl,
        String targetFullPurl,
        String relationshipType
    ) {}

    /** Combined response of rebom's parseBomById query. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParsedBom(
        java.util.List<ParsedBomComponent> components,
        java.util.List<ParsedBomDependency> dependencies
    ) {}

}
