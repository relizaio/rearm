/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import io.reliza.common.Utils;
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
        // Merge only: what ReARM holds about the release the merged root describes (SCORE-30
        // ADR-7); rebom fills the root's supplier and hashes from it where the root has none.
        // Derived from the release at generation time like missingSbomComponents, so NOT part of
        // the merged-BOM cache key and never on a stored ReleaseBom row. Null when it holds
        // nothing, and then never serialised.
        @JsonInclude(JsonInclude.Include.NON_NULL) ReleaseNodeFacts releaseNode,
        // Merge only: the ids among {@code ids} that are the merging release's own RELEASE-attached
        // BOM artifacts (SCORE-57 ADR-11); rebom folds those inputs' roots into the merged root and
        // no other. Derived from the release's artifact rows at generation time like
        // missingSbomComponents, so NOT part of the merged-BOM cache key and never on a stored
        // ReleaseBom row. Null when empty, and then never serialised, so a request without it keeps
        // its shape. A product merge lists the product's own RELEASE-attached uploads (SCORE-62
        // ADR-3).
        @JsonInclude(JsonInclude.Include.NON_NULL) List<UUID> releaseRootIds,
        // Merge only: the ids among {@code ids} that are the BOM artifacts attached to the merging
        // release's own source code entry and to the outbound deliverables of its base variant
        // (SCORE-63 ADR-5); never an inbound deliverable's BOM, which is merged but is not something
        // the release ships (ADR-4 (vi)). rebom writes the declared licence on those inputs' roots
        // where the upload wrote none, and on no other. Derived from the release's artifact rows at
        // generation time like releaseRootIds, so NOT part of the merged-BOM cache key and never on a
        // stored ReleaseBom row. Null when empty, and then never serialised, so a request without it
        // keeps its shape. A product merge lists its own SCE-attached uploads (a product gathers no
        // deliverable block) and never a component row.
        @JsonInclude(JsonInclude.Include.NON_NULL) List<UUID> attachedRootIds,
        // Upload only: the supplier ReARM names for the root of this upload (SCORE-30 ADR-12,
        // SbomSupplierNames): the deliverable's publisher, else the organization for its own
        // software; null for third-party software. rebom fills the root's supplier from it where
        // the root names none. Null is never serialised, so a request without it keeps its shape.
        @JsonInclude(JsonInclude.Include.NON_NULL) String supplierName,
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
        String bomVersion,  // Rearm-managed version for SPDX (1, 2, 3...)
        // Stored merge rows only: the key of the release node facts the merge was built with
        // (SCORE-39 ADR-5, ReleaseNodeFacts.key). Part of the merged-BOM cache key, so a row built
        // before the release's declared licence, supplier or hashes changed is not served. Never
        // sent to rebom (RebomService.mergeAndStoreBoms drops it); null is never serialised.
        @JsonInclude(JsonInclude.Include.NON_NULL) String releaseNodeFactsKey,
        // Product merge only: what the merge does with a component release that yields no BOM (TEA-15,
        // MissingSbomMode). Part of the merged-BOM cache key and stored on the ReleaseBom row, so the
        // placeholder document and the omitting document of one release never serve each other. Null is
        // PLACEHOLDER and is never serialised, so every row stored before the mode existed, and every
        // request and row of the placeholder mode, keeps exactly the shape it had. Never sent to rebom
        // (RebomService.mergeAndStoreBoms drops it): rebom's RebomOptions input does not define it, and
        // rebom needs nothing beyond the absent missingSbomComponents list to leave a child out.
        @JsonInclude(JsonInclude.Include.NON_NULL) MissingSbomMode missingSbomMode
    ) {
        // create opts
        public RebomOptions(String name, String group,  String version, ArtifactBelongsTo belongsTo, String hash, Boolean tldOnly, BomStructureType structure, String notes, StripBom stripBom, String serialNumber, String bomDigest, String purl) {
            this(name, group, version, belongsTo, hash, tldOnly, false, null, null, null, null, null, null, null, structure, notes, stripBom, serialNumber, bomDigest, null, null, null, purl, RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null, null, null);
        }

        public RebomOptions(String name, String group,  String version, ArtifactBelongsTo belongsTo, String hash, Boolean tldOnly, Boolean ignoreDev, BomStructureType structure, String notes, StripBom stripBom, String serialNumber, String bomDigest, String purl) {
            this(name, group, version, belongsTo, hash, tldOnly, ignoreDev, null, null, null, null, null, null, null, structure, notes, stripBom, serialNumber, bomDigest, null, null, null, purl, RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null, null, null);
        }

        public RebomOptions(String name, String group,  String version, ArtifactBelongsTo belongsTo, String hash, Boolean tldOnly, Boolean ignoreDev, BomStructureType structure, String notes, StripBom stripBom, String serialNumber, String bomDigest, String purl, RootComponentMergeMode rootComponentMergeMode) {
            this(name, group, version, belongsTo, hash, tldOnly, ignoreDev, null, null, null, null, null, null, null, structure, notes, stripBom, serialNumber, bomDigest, null, null, null, purl, rootComponentMergeMode, null, null, null);
        }

        public RebomOptions(String name, String group,  String version, ArtifactBelongsTo belongsTo, String hash, Boolean tldOnly, Boolean ignoreDev, Boolean excludeFileComponents, BomStructureType structure, String notes, StripBom stripBom, String serialNumber, String bomDigest, String purl, RootComponentMergeMode rootComponentMergeMode) {
            this(name, group, version, belongsTo, hash, tldOnly, ignoreDev, excludeFileComponents, null, null, null, null, null, null, structure, notes, stripBom, serialNumber, bomDigest, null, null, null, purl, rootComponentMergeMode, null, null, null);
        }

        public RebomOptions(ArtifactBelongsTo belongsTo, Boolean tldOnly,  BomStructureType structure, RootComponentMergeMode rootComponentMergeMode) {
            this(null, null, null, belongsTo, null, tldOnly, false, null, null, null, null, null, null, null, structure, "sent from ReArm", StripBom.TRUE, "", "", null, null, null, "", rootComponentMergeMode, null, null, null);
        }
        public RebomOptions() {
            this(null, null, null, ArtifactBelongsTo.RELEASE, null, false, false, null, null, null, null, null, null, null, BomStructureType.FLAT, "sent from ReArm", StripBom.TRUE, "", "", null, null, null, "", RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null, null, null);
        }
    

        public RebomOptions(String name, String group,  String version, ArtifactBelongsTo belongsTo, String hash, StripBom stripBom, String purl) {
            this(name, group, version, belongsTo, hash, false, false, null, null, null, null, null, null, null, BomStructureType.FLAT, "sent from ReArm", stripBom, "", "", null, null, null, purl, RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null, null, null);
        }
        // merge opts
        public RebomOptions(ArtifactBelongsTo belongsTo, Boolean tldOnly,  BomStructureType structure) {
            this(null, null, null, belongsTo, null, tldOnly, false, null, null, null, null, null, null, null, structure, "sent from ReArm", StripBom.TRUE, "", "", null, null, null, "", RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null, null, null);
        }

        public RebomOptions(ArtifactBelongsTo belongsTo, Boolean tldOnly, Boolean ignoreDev, BomStructureType structure) {
            this(null, null, null, belongsTo, null, tldOnly, ignoreDev, null, null, null, null, null, null, null, structure, "sent from ReArm", StripBom.TRUE, "", "", null, null, null, "", RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null, null, null);
        }

        public RebomOptions(ArtifactBelongsTo belongsTo, Boolean tldOnly, Boolean ignoreDev, Boolean excludeFileComponents, BomStructureType structure) {
            this(null, null, null, belongsTo, null, tldOnly, ignoreDev, excludeFileComponents, null, null, null, null, null, null, structure, "sent from ReArm", StripBom.TRUE, "", "", null, null, null, "", RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, null, null, null);
        }

        /** A copy of these options with {@code belongsTo} replaced; every other component kept. */
        public RebomOptions withBelongsTo(ArtifactBelongsTo newBelongsTo) {
            return new RebomOptions(name, group, version, newBelongsTo, hash, tldOnly, ignoreDev, excludeFileComponents,
                    mergeVersion, missingSbomComponents, releaseNode, releaseRootIds, attachedRootIds, supplierName, structure, notes, stripBom, serialNumber,
                    bomDigest, originalFileDigest, originalFileSize, originalMediaType, purl, rootComponentMergeMode, bomVersion, releaseNodeFactsKey, missingSbomMode);
        }

        /** The same options, for a merge built under merge rule version {@code mergeVersion}. */
        public RebomOptions withMergeVersion(Integer mergeVersion) {
            return new RebomOptions(name, group, version, belongsTo, hash, tldOnly, ignoreDev, excludeFileComponents,
                    mergeVersion, missingSbomComponents, releaseNode, releaseRootIds, attachedRootIds, supplierName, structure, notes, stripBom, serialNumber,
                    bomDigest, originalFileDigest, originalFileSize, originalMediaType, purl, rootComponentMergeMode, bomVersion, releaseNodeFactsKey, missingSbomMode);
        }

        /**
         * The same options, listing {@code missing} as the component releases without a BOM; an
         * empty or null list is carried as null, so the request keeps the shape it had before.
         */
        public RebomOptions withMissingSbomComponents(List<MissingSbomComponent> missing) {
            return new RebomOptions(name, group, version, belongsTo, hash, tldOnly, ignoreDev, excludeFileComponents,
                    mergeVersion, null == missing || missing.isEmpty() ? null : List.copyOf(missing), releaseNode,
                    releaseRootIds, attachedRootIds, supplierName, structure, notes, stripBom, serialNumber, bomDigest, originalFileDigest, originalFileSize,
                    originalMediaType, purl, rootComponentMergeMode, bomVersion, releaseNodeFactsKey, missingSbomMode);
        }

        /**
         * The same options, carrying {@code facts} about the release the merged root describes
         * (SCORE-30 ADR-7). A blank supplier name and an empty hash list are carried as null, and
         * facts holding neither as no facts at all, so a request without them keeps the shape it
         * had before.
         */
        public RebomOptions withReleaseNode(ReleaseNodeFacts facts) {
            return new RebomOptions(name, group, version, belongsTo, hash, tldOnly, ignoreDev, excludeFileComponents,
                    mergeVersion, missingSbomComponents, ReleaseNodeFacts.orNull(facts), releaseRootIds, attachedRootIds, supplierName, structure, notes,
                    stripBom, serialNumber, bomDigest, originalFileDigest, originalFileSize, originalMediaType, purl,
                    rootComponentMergeMode, bomVersion, releaseNodeFactsKey, missingSbomMode);
        }

        /**
         * The same options, listing {@code ids} as the merging release's own RELEASE-attached BOM
         * artifacts among the merge inputs (SCORE-57 ADR-11), whose roots rebom folds into the
         * merged root; an empty or null list is carried as null, so the request keeps the shape it
         * had before.
         */
        public RebomOptions withReleaseRootIds(List<UUID> ids) {
            return new RebomOptions(name, group, version, belongsTo, hash, tldOnly, ignoreDev, excludeFileComponents,
                    mergeVersion, missingSbomComponents, releaseNode, null == ids || ids.isEmpty() ? null : List.copyOf(ids),
                    attachedRootIds, supplierName, structure, notes, stripBom, serialNumber, bomDigest, originalFileDigest, originalFileSize,
                    originalMediaType, purl, rootComponentMergeMode, bomVersion, releaseNodeFactsKey, missingSbomMode);
        }

        /**
         * The same options, listing {@code ids} as the BOM artifacts attached to the merging
         * release's own source code entry and to the outbound deliverables of its base variant
         * among the merge inputs (SCORE-63 ADR-5), whose roots rebom fills with the declared licence
         * where the upload wrote none; an empty or null list is carried as null, so the request
         * keeps the shape it had before.
         */
        public RebomOptions withAttachedRootIds(List<UUID> ids) {
            return new RebomOptions(name, group, version, belongsTo, hash, tldOnly, ignoreDev, excludeFileComponents,
                    mergeVersion, missingSbomComponents, releaseNode, releaseRootIds,
                    null == ids || ids.isEmpty() ? null : List.copyOf(ids), supplierName, structure, notes, stripBom,
                    serialNumber, bomDigest, originalFileDigest, originalFileSize, originalMediaType, purl,
                    rootComponentMergeMode, bomVersion, releaseNodeFactsKey, missingSbomMode);
        }

        /**
         * The same options, naming {@code name} as the supplier of the uploaded root (SCORE-30
         * ADR-12); a blank or null name is carried as null, so the request keeps the shape it had
         * before.
         */
        public RebomOptions withSupplierName(String name) {
            return new RebomOptions(this.name, group, version, belongsTo, hash, tldOnly, ignoreDev, excludeFileComponents,
                    mergeVersion, missingSbomComponents, releaseNode, releaseRootIds, attachedRootIds, null == name || name.isBlank() ? null : name, structure, notes,
                    stripBom, serialNumber, bomDigest, originalFileDigest, originalFileSize, originalMediaType, purl,
                    rootComponentMergeMode, bomVersion, releaseNodeFactsKey, missingSbomMode);
        }

        /**
         * The same options, recording {@code key} as the key of the release node facts the stored
         * merge was built with (SCORE-39 ADR-5); null when none.
         */
        public RebomOptions withReleaseNodeFactsKey(String key) {
            return new RebomOptions(name, group, version, belongsTo, hash, tldOnly, ignoreDev, excludeFileComponents,
                    mergeVersion, missingSbomComponents, releaseNode, releaseRootIds, attachedRootIds, supplierName, structure, notes, stripBom,
                    serialNumber, bomDigest, originalFileDigest, originalFileSize, originalMediaType, purl,
                    rootComponentMergeMode, bomVersion, key, missingSbomMode);
        }

        /**
         * The same options, for a product merge that treats a component release without a BOM as
         * {@code mode} says (TEA-15). {@link MissingSbomMode#PLACEHOLDER} is carried as null, so a
         * placeholder request or row never serialises the field and keeps the shape it had before.
         */
        public RebomOptions withMissingSbomMode(MissingSbomMode mode) {
            return new RebomOptions(name, group, version, belongsTo, hash, tldOnly, ignoreDev, excludeFileComponents,
                    mergeVersion, missingSbomComponents, releaseNode, releaseRootIds, attachedRootIds, supplierName, structure, notes, stripBom,
                    serialNumber, bomDigest, originalFileDigest, originalFileSize, originalMediaType, purl,
                    rootComponentMergeMode, bomVersion, releaseNodeFactsKey,
                    MissingSbomMode.PLACEHOLDER == mode ? null : mode);
        }

        /**
         * The missing-SBOM mode these options ask for (TEA-15): null, the shape of every row stored
         * before the mode existed, is {@link MissingSbomMode#PLACEHOLDER}. Every reader compares
         * through this, never through the raw component.
         */
        public MissingSbomMode effectiveMissingSbomMode() {
            return null == missingSbomMode ? MissingSbomMode.PLACEHOLDER : missingSbomMode;
        }
    }

    /**
     * One CycloneDX {@code hashes[]} entry (SCORE-30): {@code alg} a CycloneDX hash algorithm name
     * ({@code SHA-256}, {@code SHA-512}, ...), {@code content} the hex value.
     */
    public record BomHash(String alg, String content) {}

    /**
     * What ReARM holds about a release, for the node that describes it in a merged BOM (SCORE-30
     * ADR-1 to ADR-3, ADR-11; SCORE-39 ADR-3): {@code supplierName} as SbomSupplierNames names it,
     * {@code hashes} the digest records of the one deliverable the release ships, and the declared
     * licence as DeclaredLicenses resolves it ({@code licenseExpression}, a canonical SPDX
     * expression, and {@code licenseUrl}). rebom writes each onto the node only where the node has
     * none. The member names are rebom's ReleaseNodeFactsInput field names.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReleaseNodeFacts(String supplierName, List<BomHash> hashes, String licenseExpression,
            String licenseUrl) {

        /** Writes the facts for {@link #key}: sorted members, no nulls, compact. */
        private static final JsonMapper KEY_MAPPER = JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .disable(MapperFeature.SORT_CREATOR_PROPERTIES_FIRST)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .changeDefaultPropertyInclusion(incl -> JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .build();

        /**
         * {@code facts} with a blank name, licence expression or URL and an empty list carried as
         * null, or null when it holds none of them.
         */
        static ReleaseNodeFacts orNull(ReleaseNodeFacts facts) {
            if (null == facts) return null;
            String name = blankToNull(facts.supplierName());
            List<BomHash> hashes = null == facts.hashes() || facts.hashes().isEmpty() ? null : List.copyOf(facts.hashes());
            String expression = blankToNull(facts.licenseExpression());
            String url = blankToNull(facts.licenseUrl());
            return null == name && null == hashes && null == expression && null == url ? null
                    : new ReleaseNodeFacts(name, hashes, expression, url);
        }

        /**
         * The key of {@code facts} on a stored merge row (SCORE-39 ADR-5): the lower-case hex
         * SHA-256 of the compact JSON of {@link #orNull} with members sorted and nulls left out
         * ({@code {}} when there are no facts), so equal facts give equal keys and "no facts" has
         * one too.
         */
        public static String key(ReleaseNodeFacts facts) {
            ReleaseNodeFacts normalised = orNull(facts);
            String json = null == normalised ? "{}" : KEY_MAPPER.writeValueAsString(normalised);
            return Utils.bytesToHexSha256(json.getBytes(StandardCharsets.UTF_8));
        }

        private static String blankToNull(String s) {
            return null == s || s.isBlank() ? null : s;
        }
    }

    /**
     * A component release of a product that yielded no BOM, as rebom lists it in the product's
     * merged BOM (SCORE-15 ADR-2): a component with the bom-ref {@code urn:rearm:release:<releaseUuid>},
     * the property {@code reliza:sbom:missing = reason} and unknown dependencies. {@code purl} is
     * null when the release records none; it is never made up. {@code hashes} are the digests of
     * the one deliverable the release ships (SCORE-30 ADR-3), null when there are none, and then
     * never serialised; {@code supplierName} as SbomSupplierNames names it (ADR-2), null when it
     * names none; {@code licenseExpression} and {@code licenseUrl} the declared licence as
     * DeclaredLicenses resolves it (SCORE-39 ADR-3), null when none, and then never serialised.
     */
    public record MissingSbomComponent(
        String releaseUuid,
        String name,
        String version,
        String type,
        String supplierName,
        String group,
        String purl,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<BomHash> hashes,
        @JsonInclude(JsonInclude.Include.NON_NULL) String licenseExpression,
        @JsonInclude(JsonInclude.Include.NON_NULL) String licenseUrl,
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

    /** What a product merge does with a component release that yields no BOM (TEA-15). */
    public enum MissingSbomMode {
        /** Listed as a MissingSbomComponent placeholder: internal exports, scoring, the VDR. */
        PLACEHOLDER,
        /** Left out of the document: the TEA publication path. */
        OMIT
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
