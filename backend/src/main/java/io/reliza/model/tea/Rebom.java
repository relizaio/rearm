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
@JsonIgnoreProperties(ignoreUnknown = true)
public class Rebom {
    public record BomInput(JsonNode bom, RebomOptions rebomOptions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RebomOptions(
        String name,
        String group,
        String version,
        ArtifactBelongsTo belongsTo,
        String hash,
        Boolean tldOnly,
        BomStructureType structure,
        String notes,
        StripBom stripBom,
        String serialNumber,
        String bomDigest,
        String purl
    ) {
        // create opts
        public RebomOptions() {
            this(null, null, null, ArtifactBelongsTo.RELEASE, null, false, BomStructureType.FLAT, "sent from ReArm", StripBom.TRUE, "", "", "");
        }
        public RebomOptions(String name, String group,  String version, ArtifactBelongsTo belongsTo, String hash, StripBom stripBom, String purl) {
            this(name, group,  version, belongsTo, hash, false, BomStructureType.FLAT, "sent from ReArm", stripBom, "", "", purl);
        }
        // merge opts
        public RebomOptions(ArtifactBelongsTo belongsTo, Boolean tldOnly,  BomStructureType structure) {
            this(null, null,  null, belongsTo, null, tldOnly, structure, "sent from ReArm", StripBom.TRUE, "", "", "");
        }
    }

    public record RebomResponse(UUID uuid, OASResponseDto bom, RebomOptions meta, Boolean duplicate) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record InternalBom (UUID id, ArtifactBelongsTo belongsTo){}

}
