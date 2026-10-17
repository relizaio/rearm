/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.tea;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

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
        // Merge only: leave components of type file out of the merged BOM (SCORE-11). Part of
        // the merged-BOM cache key. Null when off and then never serialised, so a request
        // without it reaches rebom, and is stored on the release, exactly as before it existed.
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean excludeFileComponents,
        // Merge only: the merge rule version a stored merge was built with (SCORE-13,
        // ReleaseService.MERGE_VERSION). Part of the merged-BOM cache key, so merges stored
        // under older rules (null) are rebuilt on their next request. Null is never serialised.
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer mergeVersion,
        // Product merge only: the component releases that yielded no BOM (SCORE-15 ADR-1). rebom
        // lists each as a placeholder component with unknown dependencies. Derived from the
        // release graph at generation time like the BOM ids, so NOT part of the merged-BOM cache
        // key and never on a stored ReleaseBom row. Null when empty, and then never serialised.
        @JsonInclude(JsonInclude.Include.NON_NULL) List<MissingSbomComponent> missingSbomComponents,
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
            this(name, group, version, belongsTo, hash, tldOnly, false, null, null, null, structure, notes, stripBom, serialNumber, bomDigest, null, null, null, purl, RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }

        public RebomOptions(String name, String group,  String version, ArtifactBelongsTo belongsTo, String hash, Boolean tldOnly, Boolean ignoreDev, BomStructureType structure, String notes, StripBom stripBom, String serialNumber, String bomDigest, String purl) {
            this(name, group, version, belongsTo, hash, tldOnly, ignoreDev, null, null, null, structure, notes, stripBom, serialNumber, bomDigest, null, null, null, purl, RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }

        public RebomOptions(String name, String group,  String version, ArtifactBelongsTo belongsTo, String hash, Boolean tldOnly, Boolean ignoreDev, BomStructureType structure, String notes, StripBom stripBom, String serialNumber, String bomDigest, String purl, RootComponentMergeMode rootComponentMergeMode) {
            this(name, group, version, belongsTo, hash, tldOnly, ignoreDev, null, null, null, structure, notes, stripBom, serialNumber, bomDigest, null, null, null, purl, rootComponentMergeMode, null);
        }

        public RebomOptions(String name, String group,  String version, ArtifactBelongsTo belongsTo, String hash, Boolean tldOnly, Boolean ignoreDev, Boolean excludeFileComponents, BomStructureType structure, String notes, StripBom stripBom, String serialNumber, String bomDigest, String purl, RootComponentMergeMode rootComponentMergeMode) {
            this(name, group, version, belongsTo, hash, tldOnly, ignoreDev, excludeFileComponents, null, null, structure, notes, stripBom, serialNumber, bomDigest, null, null, null, purl, rootComponentMergeMode, null);
        }

        public RebomOptions(ArtifactBelongsTo belongsTo, Boolean tldOnly,  BomStructureType structure, RootComponentMergeMode rootComponentMergeMode) {
            this(null, null, null, belongsTo, null, tldOnly, false, null, null, null, structure, "sent from ReArm", StripBom.TRUE, "", "", null, null, null, "", rootComponentMergeMode, null);
        }
        public RebomOptions() {
            this(null, null, null, ArtifactBelongsTo.RELEASE, null, false, false, null, null, null, BomStructureType.FLAT, "sent from ReArm", StripBom.TRUE, "", "", null, null, null, "", RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }
    

        public RebomOptions(String name, String group,  String version, ArtifactBelongsTo belongsTo, String hash, StripBom stripBom, String purl) {
            this(name, group, version, belongsTo, hash, false, false, null, null, null, BomStructureType.FLAT, "sent from ReArm", stripBom, "", "", null, null, null, purl, RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }
        // merge opts
        public RebomOptions(ArtifactBelongsTo belongsTo, Boolean tldOnly,  BomStructureType structure) {
            this(null, null, null, belongsTo, null, tldOnly, false, null, null, null, structure, "sent from ReArm", StripBom.TRUE, "", "", null, null, null, "", RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }

        public RebomOptions(ArtifactBelongsTo belongsTo, Boolean tldOnly, Boolean ignoreDev, BomStructureType structure) {
            this(null, null, null, belongsTo, null, tldOnly, ignoreDev, null, null, null, structure, "sent from ReArm", StripBom.TRUE, "", "", null, null, null, "", RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }

        public RebomOptions(ArtifactBelongsTo belongsTo, Boolean tldOnly, Boolean ignoreDev, Boolean excludeFileComponents, BomStructureType structure) {
            this(null, null, null, belongsTo, null, tldOnly, ignoreDev, excludeFileComponents, null, null, structure, "sent from ReArm", StripBom.TRUE, "", "", null, null, null, "", RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null);
        }

        /** The same options, for a merge built under merge rule version {@code mergeVersion}. */
        public RebomOptions withMergeVersion(Integer mergeVersion) {
            return new RebomOptions(name, group, version, belongsTo, hash, tldOnly, ignoreDev, excludeFileComponents,
                    mergeVersion, missingSbomComponents, structure, notes, stripBom, serialNumber, bomDigest,
                    originalFileDigest, originalFileSize, originalMediaType, purl, rootComponentMergeMode, bomVersion);
        }

        /**
         * The same options, listing {@code missing} as the component releases without a BOM; an
         * empty or null list is carried as null, so the request keeps the shape it had before.
         */
        public RebomOptions withMissingSbomComponents(List<MissingSbomComponent> missing) {
            return new RebomOptions(name, group, version, belongsTo, hash, tldOnly, ignoreDev, excludeFileComponents,
                    mergeVersion, null == missing || missing.isEmpty() ? null : List.copyOf(missing), structure, notes,
                    stripBom, serialNumber, bomDigest, originalFileDigest, originalFileSize, originalMediaType, purl,
                    rootComponentMergeMode, bomVersion);
        }
    }

    /**
     * A component release of a product that yielded no BOM, as rebom lists it in the product's
     * merged BOM (SCORE-15 ADR-2): a component with the bom-ref {@code urn:rearm:release:<releaseUuid>},
     * the property {@code reliza:sbom:missing = reason} and unknown dependencies. {@code purl} is
     * null when the release records none; it is never made up.
     */
    public record MissingSbomComponent(
        String releaseUuid,
        String name,
        String version,
        String type,
        String supplierName,
        String group,
        String purl,
        MissingSbomReason reason
    ) {}

    /**
     * Why a product's component release yields no BOM to the product's merge (SCORE-15 ADR-2); rebom
     * writes the name as the value of the component's {@code reliza:sbom:missing} property.
     */
    public enum MissingSbomReason {
        /** The release has no BOM artifact of the requested kind. */
        NO_SBOM_ARTIFACT,
        /** The release has BOM artifacts, and the export's coverage filter left every one out. */
        COVERAGE_EXCLUDED
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
     * canonicalPurl is the purl with qualifiers + subpath stripped (required
     * qualifiers preserved for julia/swid/oci); fullPurl preserves the original
     * purl exactly as it appeared in the BOM. isRoot is true for the node
     * synthesised from bom.metadata.component.
     *
     * cpe is the declared CPE coordinate (null when absent). licenses passes
     * through declared licenses in the exact CycloneDX array shape (each item is
     * {license:{id|name,...}} or {expression}); empty list when absent.
     *
     * NB: kept as the raw CDX array (not the typed org.cyclonedx LicenseChoice)
     * because this record is deserialized from rebom's response by the app's
     * Jackson 3 mapper, and the cyclonedx-core-java library is Jackson 2 — its
     * LicenseChoice class-level @JsonDeserialize is invisible to Jackson 3, so a
     * LicenseChoice field fails to deserialize from the array. The typed
     * LicenseChoice is materialized only at the BOM-emit boundary via
     * CdxLicenseUtil (which uses a Jackson 2 mapper).
     *
     * TODO: when rebom emits a richer {scheme,value} identities array (swid,
     * swhid, omniborid, and identities for no-purl components), consume it here
     * instead of synthesising identities backend-side from canonicalPurl + cpe.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParsedBomComponent(
        String canonicalPurl,
        String fullPurl,
        String type,
        String group,
        String name,
        String version,
        Boolean isRoot,
        String cpe,
        java.util.List<java.util.Map<String, Object>> licenses
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

    /** One hardware node from rebom's parseHbomById query (type device/firmware). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParsedHbomComponent(
        String bomRef,
        String type,
        String operator,
        String name,
        String version,
        String description,
        String category,
        String subcategory,
        java.util.List<io.reliza.model.cdx.Party> parties,
        java.util.List<io.reliza.model.cdx.ComponentIdentifier> identifiers,
        String boardLocation,
        String deviceType,
        Integer quantity,
        String parentRef,
        Boolean isRoot
    ) {}

    /** Combined response of rebom's parseHbomById query. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParsedHbom(
        java.util.List<ParsedHbomComponent> components
    ) {}

}
