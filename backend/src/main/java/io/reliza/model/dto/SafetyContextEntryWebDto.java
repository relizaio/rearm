/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.List;
import java.util.UUID;

import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.AnalysisScope;
import io.reliza.model.ImportMode;
import io.reliza.model.IssuerClass;
import io.reliza.model.ProposalStatus;
import io.reliza.model.SafetyContextData;
import io.reliza.model.SafetyContextData.GateRule;
import io.reliza.model.SafetyContextData.LevelChange;
import io.reliza.model.SafetyContextData.LevelNote;
import io.reliza.model.SafetyContextData.MatchStatus;
import io.reliza.model.SafetyContextData.Note;
import io.reliza.model.SafetyContextData.SourceFormat;
import io.reliza.model.SafetyContextData.VerificationStatus;
import io.reliza.model.SafetyContextProposalData;
import io.reliza.model.SafetyContextRecordData;
import io.reliza.model.SafetyContextRecordData.AcceptedVia;
import io.reliza.model.SafetyContextRecordData.RecordStatus;
import lombok.Data;

/**
 * GraphQL view of a safety-context proposal or record (GraphQL type SafetyContextEntry). The raw
 * asset JSON is deliberately absent; it is served only by getSafetyContextSourceAssertion.
 * Timestamps are UTC instants.
 */
@Data
public class SafetyContextEntryWebDto {

	public enum Kind { PROPOSAL, RECORD }

	private UUID uuid;
	private Kind kind;
	private UUID org;
	private ProposalStatus proposalStatus;
	private RecordStatus recordStatus;
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
	private LevelNote levelNote;
	private LevelChange levelChange;
	private Boolean componentMostConservative;
	private VerificationStatus verificationStatus;
	private IssuerClass issuerClassDerived;
	private IssuerClass issuerClassEffective;
	private ArtifactBelongsTo originBinding;
	private IssuerClass issuerOverride;
	private boolean overrideHonoured;
	private boolean elevatedByOverride;
	private UUID overrideActor;
	private String overrideActorName;
	private ImportMode importMode;
	private GateRule gateRule;
	private List<Note> notes;
	private MatchStatus matchStatus;
	private String statusReason;
	private boolean conflictFlag;
	private UUID sourceArtifact;
	private Boolean sourceDeleted;
	private UUID releaseUuid;
	private SourceFormat sourceFormat;
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
	private AcceptedVia acceptedVia;
	private UUID acceptedBy;
	private String acceptedByName;
	private String acceptedAt;
	private boolean acceptedByUploader;
	private UUID supersededBy;

	public static SafetyContextEntryWebDto fromProposal(SafetyContextProposalData d) {
		SafetyContextEntryWebDto dto = shared(d, Kind.PROPOSAL);
		dto.setProposalStatus(d.getStatus());
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
		dto.setRecordStatus(d.getStatus());
		dto.setProposalUuid(d.getProposalUuid());
		dto.setAcceptedVia(d.getAcceptedVia());
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
		dto.setLevelNote(d.getLevelNote());
		dto.setLevelChange(d.getLevelChange());
		dto.setVerificationStatus(d.getVerificationStatus());
		dto.setIssuerClassDerived(d.getIssuerClassDerived());
		dto.setIssuerClassEffective(d.getIssuerClassEffective());
		dto.setOriginBinding(d.getOriginBinding());
		dto.setIssuerOverride(d.getIssuerOverride());
		dto.setOverrideHonoured(d.isOverrideHonoured());
		dto.setElevatedByOverride(d.isElevatedByOverride());
		dto.setOverrideActor(d.getOverrideActor());
		dto.setImportMode(d.getImportMode());
		dto.setGateRule(d.getGateRule());
		dto.setNotes(d.getNotes() == null ? List.of() : List.copyOf(d.getNotes()));
		dto.setMatchStatus(d.getMatchStatus());
		dto.setStatusReason(d.getStatusReason());
		dto.setConflictFlag(d.isConflictFlag());
		dto.setSourceArtifact(d.getSourceArtifact());
		dto.setReleaseUuid(d.getReleaseUuid());
		dto.setSourceFormat(d.getSourceFormat());
		dto.setSpecCommit(d.getSpecCommit());
		dto.setDocSerialNumber(d.getDocSerialNumber());
		dto.setDocVersion(d.getDocVersion());
		dto.setSourceDocumentHash(d.getSourceDocumentHash());
		dto.setSourceAssertionHash(d.getSourceAssertionHash());
		dto.setUploadedBy(d.getUploadedBy());
		return dto;
	}
}
