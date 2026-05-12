/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import io.reliza.common.Utils;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class VexStatementProposalData extends RelizaDataParent implements RelizaObject {

	@JsonProperty("uuid") private UUID uuid;
	@JsonProperty("org") private UUID org;

	// Provenance
	@JsonProperty("sourceArtifact") private UUID sourceArtifact;
	@JsonProperty("sourceFormat") private SourceFormat sourceFormat;
	@JsonProperty("sourceStatementHash") private String sourceStatementHash;
	@JsonProperty("sourceStatementJson") private String sourceStatementJson;
	@JsonProperty("signatureStatus") private SignatureStatus signatureStatus = SignatureStatus.UNSIGNED;
	@JsonProperty("issuerIdentity") private String issuerIdentity;

	// Match keys (5-tuple, names mirror VulnAnalysisData)
	@JsonProperty("scope") private AnalysisScope scope;
	@JsonProperty("scopeUuid") private UUID scopeUuid;
	@JsonProperty("location") private String location;
	@JsonProperty("rawLocation") private String rawLocation;
	@JsonProperty("locationType") private LocationType locationType;
	@JsonProperty("findingId") private String findingId;
	@JsonProperty("findingAliases") private List<String> findingAliases = new LinkedList<>();
	@JsonProperty("findingType") private FindingType findingType;

	// Prepared CDX payload
	@JsonProperty("analysisState") private AnalysisState analysisState;
	@JsonProperty("analysisJustification") private AnalysisJustification analysisJustification;
	@JsonProperty("details") private String details;
	@JsonProperty("severity") private VulnerabilitySeverity severity;
	@JsonProperty("responses") private List<AnalysisResponse> responses = new LinkedList<>();
	@JsonProperty("recommendation") private String recommendation;
	@JsonProperty("workaround") private String workaround;

	// Status
	@JsonProperty("status") private ProposalStatus status = ProposalStatus.PENDING;
	@JsonProperty("statusReason") private String statusReason;
	@JsonProperty("targetVulnAnalysis") private UUID targetVulnAnalysis;
	@JsonProperty("mitigationAttestation") private UUID mitigationAttestation;

	// Audit
	@JsonProperty("actedAt") private ZonedDateTime actedAt;
	@JsonProperty("actedBy") private UUID actedBy;

	// Audit (v1.2 — user-controllable upload flow)
	@JsonProperty("issuerClass") private IssuerClass issuerClass;
	@JsonProperty("userImportMode") private VexImportMode userImportMode;
	@JsonProperty("userIssuerClassOverride") private IssuerClass userIssuerClassOverride;
	@JsonProperty("demotionReason") private String demotionReason;

	// Translation provenance
	@JsonProperty("translationNotes") private List<String> translationNotes = new LinkedList<>();

	public static VexStatementProposalData createVexStatementProposalData(
			UUID org, UUID sourceArtifact, SourceFormat sourceFormat,
			String sourceStatementHash, String sourceStatementJson,
			AnalysisScope scope, UUID scopeUuid,
			String location, String rawLocation, LocationType locationType,
			String findingId, List<String> findingAliases, FindingType findingType,
			AnalysisState analysisState, AnalysisJustification analysisJustification,
			String details, VulnerabilitySeverity severity,
			List<AnalysisResponse> responses, String recommendation, String workaround,
			List<String> translationNotes,
			IssuerClass issuerClass, VexImportMode userImportMode, IssuerClass userIssuerClassOverride) {
		VexStatementProposalData d = new VexStatementProposalData();
		d.uuid = UUID.randomUUID();
		d.org = org;
		d.sourceArtifact = sourceArtifact;
		d.sourceFormat = sourceFormat;
		d.sourceStatementHash = sourceStatementHash;
		d.sourceStatementJson = sourceStatementJson;
		d.scope = scope;
		d.scopeUuid = scopeUuid;
		d.location = location;
		d.rawLocation = rawLocation;
		d.locationType = locationType;
		d.findingId = findingId;
		d.findingAliases = findingAliases != null ? new LinkedList<>(findingAliases) : new LinkedList<>();
		d.findingType = findingType;
		d.analysisState = analysisState;
		d.analysisJustification = analysisJustification;
		d.details = details;
		d.severity = severity;
		d.responses = responses != null ? new LinkedList<>(responses) : new LinkedList<>();
		d.recommendation = recommendation;
		d.workaround = workaround;
		d.translationNotes = translationNotes != null ? new LinkedList<>(translationNotes) : new LinkedList<>();
		d.issuerClass = issuerClass;
		d.userImportMode = userImportMode;
		d.userIssuerClassOverride = userIssuerClassOverride;
		return d;
	}

	public static VexStatementProposalData dataFromRecord(VexStatementProposal e) {
		VexStatementProposalData d = Utils.OM.convertValue(e.getRecordData(), new TypeReference<VexStatementProposalData>() {});
		d.setUuid(e.getUuid());
		return d;
	}

	public static VexStatementProposalData dataFromMap(Map<String, Object> map, UUID uuid) {
		VexStatementProposalData d = Utils.OM.convertValue(map, new TypeReference<VexStatementProposalData>() {});
		d.setUuid(uuid);
		return d;
	}

	public Map<String, Object> toRecordData() {
		return Utils.OM.convertValue(this, new TypeReference<Map<String, Object>>() {});
	}

	@Override
	public UUID getResourceGroup() {
		return null;
	}
}
