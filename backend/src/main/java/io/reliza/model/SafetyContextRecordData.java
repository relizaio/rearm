/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.core.type.TypeReference;

import io.reliza.common.Utils;
import lombok.Data;

/**
 * Typed JSONB shape shared by staged proposals and accepted safety-context records.
 * acceptedVia records the import path and is not approval of the source decision.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SafetyContextRecordData implements RelizaObject {
    @JsonProperty("uuid") private UUID uuid;
    @JsonProperty("org") private UUID org;
    @JsonProperty("sourceArtifact") private UUID sourceArtifact;
    @JsonProperty("sourceFormat") private String sourceFormat;
    @JsonProperty("sourceDocumentHash") private String sourceDocumentHash;
    @JsonProperty("sourceAssertionHash") private String sourceAssertionHash;
    @JsonProperty("sourceDocumentJson") private String sourceDocumentJson;
    @JsonProperty("sourceAssertionJson") private String sourceAssertionJson;
    @JsonProperty("releaseUuid") private UUID releaseUuid;
    @JsonProperty("sbomComponentUuid") private UUID sbomComponentUuid;
    @JsonProperty("scope") private AnalysisScope scope = AnalysisScope.COMPONENT;
    @JsonProperty("scopeUuid") private UUID scopeUuid;
    @JsonProperty("componentPurl") private String componentPurl;
    @JsonProperty("componentRefId") private String componentRefId;
    @JsonProperty("componentBomRef") private String componentBomRef;
    @JsonProperty("assertionRefId") private String assertionRefId;
    @JsonProperty("outcome") private String outcome;
    @JsonProperty("rationale") private String rationale;
    @JsonProperty("assertionStatus") private String assertionStatus;
    @JsonProperty("classificationJson") private List<String> classificationJson = new ArrayList<>();
    @JsonProperty("evidenceJson") private List<String> evidenceJson = new ArrayList<>();
    @JsonProperty("matchStatus") private String matchStatus;
    @JsonProperty("status") private ProposalStatus status = ProposalStatus.PENDING;
    @JsonProperty("statusReason") private String statusReason;
    @JsonProperty("acceptedVia") private String acceptedVia;
    @JsonProperty("acceptedAt") private ZonedDateTime acceptedAt;
    @JsonProperty("acceptedBy") private UUID acceptedBy;
    @JsonProperty("acceptedRecordUuid") private UUID acceptedRecordUuid;

    public static SafetyContextRecordData dataFromRecord(SafetyContextProposal entity) {
        SafetyContextRecordData data =
            Utils.OM.convertValue(entity.getRecordData(), new TypeReference<SafetyContextRecordData>() {});
        data.setUuid(entity.getUuid());
        return data;
    }
    public static SafetyContextRecordData dataFromRecord(SafetyContext entity) {
        SafetyContextRecordData data =
            Utils.OM.convertValue(entity.getRecordData(), new TypeReference<SafetyContextRecordData>() {});
        data.setUuid(entity.getUuid());
        return data;
    }
    public Map<String, Object> toRecordData() {
        return Utils.OM.convertValue(this, new TypeReference<Map<String, Object>>() {});
    }
    @Override public UUID getResourceGroup() { return null; }
}
