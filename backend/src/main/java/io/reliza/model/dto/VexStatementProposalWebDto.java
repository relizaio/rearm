/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import io.reliza.model.AnalysisJustification;
import io.reliza.model.AnalysisResponse;
import io.reliza.model.AnalysisScope;
import io.reliza.model.AnalysisState;
import io.reliza.model.FindingType;
import io.reliza.model.IssuerClass;
import io.reliza.model.LocationType;
import io.reliza.model.ProposalStatus;
import io.reliza.model.SignatureStatus;
import io.reliza.model.SourceFormat;
import io.reliza.model.VexImportMode;
import io.reliza.model.VexStatementProposalData;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import lombok.Data;

@Data
public class VexStatementProposalWebDto {
	private UUID uuid;
	private UUID org;
	private UUID sourceArtifact;
	private SourceFormat sourceFormat;
	private String sourceStatementJson;
	private SignatureStatus signatureStatus;
	private String issuerIdentity;
	private AnalysisScope scope;
	private UUID scopeUuid;
	private String location;
	private String rawLocation;
	private LocationType locationType;
	private String findingId;
	private List<String> findingAliases;
	private FindingType findingType;
	private AnalysisState analysisState;
	private AnalysisJustification analysisJustification;
	private String details;
	private VulnerabilitySeverity severity;
	private List<AnalysisResponse> responses;
	private String recommendation;
	private String workaround;
	private ProposalStatus status;
	private String statusReason;
	private UUID targetVulnAnalysis;
	private UUID mitigationAttestation;
	private ZonedDateTime actedAt;
	private UUID actedBy;
	private List<String> translationNotes;
	private IssuerClass issuerClass;
	private VexImportMode userImportMode;
	private IssuerClass userIssuerClassOverride;
	private String demotionReason;

	public static VexStatementProposalWebDto fromData(VexStatementProposalData d) {
		VexStatementProposalWebDto w = new VexStatementProposalWebDto();
		w.uuid = d.getUuid();
		w.org = d.getOrg();
		w.sourceArtifact = d.getSourceArtifact();
		w.sourceFormat = d.getSourceFormat();
		w.sourceStatementJson = d.getSourceStatementJson();
		w.signatureStatus = d.getSignatureStatus();
		w.issuerIdentity = d.getIssuerIdentity();
		w.scope = d.getScope();
		w.scopeUuid = d.getScopeUuid();
		w.location = d.getLocation();
		w.rawLocation = d.getRawLocation();
		w.locationType = d.getLocationType();
		w.findingId = d.getFindingId();
		w.findingAliases = d.getFindingAliases();
		w.findingType = d.getFindingType();
		w.analysisState = d.getAnalysisState();
		w.analysisJustification = d.getAnalysisJustification();
		w.details = d.getDetails();
		w.severity = d.getSeverity();
		w.responses = d.getResponses();
		w.recommendation = d.getRecommendation();
		w.workaround = d.getWorkaround();
		w.status = d.getStatus();
		w.statusReason = d.getStatusReason();
		w.targetVulnAnalysis = d.getTargetVulnAnalysis();
		w.mitigationAttestation = d.getMitigationAttestation();
		w.actedAt = d.getActedAt();
		w.actedBy = d.getActedBy();
		w.translationNotes = d.getTranslationNotes();
		w.issuerClass = d.getIssuerClass();
		w.userImportMode = d.getUserImportMode();
		w.userIssuerClassOverride = d.getUserIssuerClassOverride();
		w.demotionReason = d.getDemotionReason();
		return w;
	}
}
