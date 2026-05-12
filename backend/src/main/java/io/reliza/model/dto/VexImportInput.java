/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.UUID;

import io.reliza.model.AnalysisScope;
import io.reliza.model.IssuerClass;
import io.reliza.model.SourceFormat;
import io.reliza.model.VexImportMode;
import lombok.Data;

/**
 * Input to {@link io.reliza.service.VexImportService#importPipeline}. v1.2 shape: the artifact
 * is always pre-persisted by the upload path, the dispatch helper derives issuerClass +
 * restrictToRelease from binding context, and the user's per-upload picks (scope, mode,
 * issuerClass override) flow through here.
 */
@Data
public class VexImportInput {
    /** Org under which the import happens. */
    private UUID org;
    /** Already-persisted source artifact UUID. */
    private UUID sourceArtifact;
    /** Raw VEX document content (UTF-8 JSON). */
    private String content;
    /** Source format: OPENVEX or CDX_VEX. */
    private SourceFormat format;
    /** Optional human-readable note. */
    private String note;

    /** Trust class derived from binding context (RELEASE/SCE/outbound DELIVERABLE → SELF; inbound DELIVERABLE → VENDOR; else THIRD_PARTY). */
    private IssuerClass issuerClass;
    /** Provenance binding to a release; restricts RELEASE-scoped matches to this release only. */
    private UUID restrictToRelease;

    /** User-picked scope (RELEASE/BRANCH/COMPONENT/ORG). v1.1 parity: dispatch defaults to RELEASE. */
    private AnalysisScope vexScope;
    /** User-picked import mode (AUTO_ACCEPT/STAGE/REJECT). Default: AUTO_ACCEPT. */
    private VexImportMode vexImportMode;
    /** User-supplied override for the derived issuerClass; when non-null, replaces the heuristic. */
    private IssuerClass userIssuerClassOverride;
}
