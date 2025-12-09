/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
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
        String purl,
        RootComponentMergeMode rootComponentMergeMode,
        String bomVersion  // Rearm-managed version for SPDX (1, 2, 3...)
    ) {
        // create opts
        public RebomOptions(String name, String group,  String version, ArtifactBelongsTo belongsTo, String hash, Boolean tldOnly, BomStructureType structure, String notes, StripBom stripBom, String serialNumber, String bomDigest, String purl) {
            this(name, group, version, belongsTo, hash, tldOnly, false, structure, notes, stripBom, serialNumber, bomDigest, purl, RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }

        public RebomOptions(String name, String group,  String version, ArtifactBelongsTo belongsTo, String hash, Boolean tldOnly, Boolean ignoreDev, BomStructureType structure, String notes, StripBom stripBom, String serialNumber, String bomDigest, String purl) {
            this(name, group, version, belongsTo, hash, tldOnly, ignoreDev, structure, notes, stripBom, serialNumber, bomDigest, purl, RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }

        public RebomOptions(String name, String group,  String version, ArtifactBelongsTo belongsTo, String hash, Boolean tldOnly, Boolean ignoreDev, BomStructureType structure, String notes, StripBom stripBom, String serialNumber, String bomDigest, String purl, RootComponentMergeMode rootComponentMergeMode) {
            this(name, group, version, belongsTo, hash, tldOnly, ignoreDev, structure, notes, stripBom, serialNumber, bomDigest, purl, rootComponentMergeMode, null);
        }

        public RebomOptions(ArtifactBelongsTo belongsTo, Boolean tldOnly,  BomStructureType structure, RootComponentMergeMode rootComponentMergeMode) {
            this(null, null,  null, belongsTo, null, tldOnly, false, structure, "sent from ReArm", StripBom.TRUE, "", "", "", rootComponentMergeMode, null);
        }
        public RebomOptions() {
            this(null, null, null, ArtifactBelongsTo.RELEASE, null, false, false, BomStructureType.FLAT, "sent from ReArm", StripBom.TRUE, "", "", "", RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }
    

        public RebomOptions(String name, String group,  String version, ArtifactBelongsTo belongsTo, String hash, StripBom stripBom, String purl) {
            this(name, group,  version, belongsTo, hash, false, false, BomStructureType.FLAT, "sent from ReArm", stripBom, "", "", purl, RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }
        // merge opts
        public RebomOptions(ArtifactBelongsTo belongsTo, Boolean tldOnly,  BomStructureType structure) {
            this(null, null,  null, belongsTo, null, tldOnly, false, structure, "sent from ReArm", StripBom.TRUE, "", "", "", RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }

        public RebomOptions(ArtifactBelongsTo belongsTo, Boolean tldOnly, Boolean ignoreDev, BomStructureType structure) {
            this(null, null,  null, belongsTo, null, tldOnly, ignoreDev, structure, "sent from ReArm", StripBom.TRUE, "", "", "", RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }
    }

    public record RebomResponse(UUID uuid, OASResponseDto bom, RebomOptions meta, Boolean duplicate) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InternalBom (UUID id, ArtifactBelongsTo belongsTo){}

}
