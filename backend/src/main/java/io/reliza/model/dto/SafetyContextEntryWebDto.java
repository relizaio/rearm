/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.List;
import java.util.UUID;

import io.reliza.model.AnalysisScope;
import io.reliza.model.SafetyContextData;
import io.reliza.model.SafetyContextProposalData;
import io.reliza.model.SafetyContextRecordData;
import lombok.Data;

/**
 * GraphQL view of a safety-context proposal or record (GraphQL type SafetyContextEntry). The raw
 * asset JSON is deliberately absent; it is served only by getSafetyContextSourceAssertion. Enum
 * values are rendered as their names; timestamps as UTC instants.
 */
@Data
public class SafetyContextEntryWebDto {

	public enum Kind { PROPOSAL, RECORD }

	private UUID uuid;
	private Kind kind;
	private UUID org;
	private String status;
	private AnalysisScope scope;
	private UUID scopeUuid;
	private String componentPurl;
	private UUID sbomComponentUuid;
	private String schemeKey;
	private String blueprintRefId;
	private String assetRefId;
	private String levelRaw;
	private String levelNormalized;
	private Integer levelRank;
	private String levelNote;
	private String levelChange;
	private Boolean componentMostConservative;
	private String verificationStatus;
	private String issuerClassDerived;
	private String issuerClassEffective;
	private String originBinding;
	private String issuerOverride;
	private boolean overrideHonoured;
	private boolean elevatedByOverride;
	private UUID overrideActor;
	private String overrideActorName;
	private String importMode;
	private String gateRule;
	private List<String> notes;
	private String matchStatus;
	private String statusReason;
	private boolean conflictFlag;
	private UUID sourceArtifact;
	private Boolean sourceDeleted;
	private UUID releaseUuid;
	private String sourceFormat;
	private String specCommit;
	private String docSerialNumber;
	private Integer docVersion;
	private String sourceDocumentHash;
	private String sourceAssertionHash;
	private UUID uploadedBy;
	private String lineageKey;
	private UUID actedBy;
	private String actedAt;
	private String acceptComment;
	private String rejectReason;
	private UUID acceptedRecordUuid;
	private UUID proposalUuid;
	private String acceptedVia;
	private UUID acceptedBy;
	private String acceptedByName;
	private String acceptedAt;
	private boolean acceptedByUploader;
	private UUID supersededBy;

	public static SafetyContextEntryWebDto fromProposal(SafetyContextProposalData d) {
		SafetyContextEntryWebDto dto = shared(d, Kind.PROPOSAL);
		dto.setStatus(name(d.getStatus()));
		dto.setLineageKey(d.getLineageKey());
		dto.setActedBy(d.getActedBy());
		dto.setActedAt(d.getActedAt() == null ? null : d.getActedAt().toString());
		dto.setAcceptComment(d.getAcceptComment());
		dto.setRejectReason(d.getRejectReason());
		dto.setAcceptedRecordUuid(d.getAcceptedRecordUuid());
		return dto;
	}

	public static SafetyContextEntryWebDto fromRecord(SafetyContextRecordData d) {
		SafetyContextEntryWebDto dto = shared(d, Kind.RECORD);
		dto.setStatus(name(d.getStatus()));
		dto.setProposalUuid(d.getProposalUuid());
		dto.setAcceptedVia(name(d.getAcceptedVia()));
		dto.setAcceptedBy(d.getAcceptedBy());
		dto.setAcceptedAt(d.getAcceptedAt() == null ? null : d.getAcceptedAt().toString());
		dto.setAcceptedByUploader(d.isAcceptedByUploader());
		dto.setAcceptComment(d.getAcceptComment());
		dto.setSupersededBy(d.getSupersededBy());
		return dto;
	}

	private static SafetyContextEntryWebDto shared(SafetyContextData d, Kind kind) {
		SafetyContextEntryWebDto dto = new SafetyContextEntryWebDto();
		dto.setUuid(d.getUuid());
		dto.setKind(kind);
		dto.setOrg(d.getOrg());
		dto.setScope(d.getScope());
		dto.setScopeUuid(d.getScopeUuid());
		dto.setComponentPurl(d.getComponentPurl());
		dto.setSbomComponentUuid(d.getSbomComponentUuid());
		dto.setSchemeKey(d.getSchemeKey());
		dto.setBlueprintRefId(d.getBlueprintRefId());
		dto.setAssetRefId(d.getAssetRefId());
		dto.setLevelRaw(d.getLevelRaw());
		dto.setLevelNormalized(d.getLevelNormalized());
		dto.setLevelRank(d.getLevelRank());
		dto.setLevelNote(name(d.getLevelNote()));
		dto.setLevelChange(name(d.getLevelChange()));
		dto.setVerificationStatus(name(d.getVerificationStatus()));
		dto.setIssuerClassDerived(name(d.getIssuerClassDerived()));
		dto.setIssuerClassEffective(name(d.getIssuerClassEffective()));
		dto.setOriginBinding(name(d.getOriginBinding()));
		dto.setIssuerOverride(name(d.getIssuerOverride()));
		dto.setOverrideHonoured(d.isOverrideHonoured());
		dto.setElevatedByOverride(d.isElevatedByOverride());
		dto.setOverrideActor(d.getOverrideActor());
		dto.setImportMode(name(d.getImportMode()));
		dto.setGateRule(name(d.getGateRule()));
		dto.setNotes(d.getNotes() == null ? List.of() : d.getNotes().stream().map(Enum::name).toList());
		dto.setMatchStatus(name(d.getMatchStatus()));
		dto.setStatusReason(d.getStatusReason());
		dto.setConflictFlag(d.isConflictFlag());
		dto.setSourceArtifact(d.getSourceArtifact());
		dto.setReleaseUuid(d.getReleaseUuid());
		dto.setSourceFormat(name(d.getSourceFormat()));
		dto.setSpecCommit(d.getSpecCommit());
		dto.setDocSerialNumber(d.getDocSerialNumber());
		dto.setDocVersion(d.getDocVersion());
		dto.setSourceDocumentHash(d.getSourceDocumentHash());
		dto.setSourceAssertionHash(d.getSourceAssertionHash());
		dto.setUploadedBy(d.getUploadedBy());
		return dto;
	}

	private static String name(Enum<?> e) {
		return e == null ? null : e.name();
	}
}
