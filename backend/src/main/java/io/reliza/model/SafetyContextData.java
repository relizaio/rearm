/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.Utils.ArtifactBelongsTo;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Fields shared by a staged safety-context proposal and an accepted safety-context record: one
 * safety integrity level that an imported CycloneDX 2.0 asset declares for a component, the
 * scope target it applies to, how the trust gate classified it, and where it came from.
 *
 * <p>Identity (the ACTIVE-record upsert key, see V106): org, scope, scopeUuid, componentPurl,
 * schemeKey, blueprintRefId, assetRefId. The level is a declaration by the issuer;
 * {@link #verificationStatus} says ReARM has not verified it.
 */
@Data
@EqualsAndHashCode(callSuper = false)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class SafetyContextData extends RelizaDataParent implements RelizaObject {

	/** How a level text mapped onto its scheme's scale. */
	public enum LevelNote { OK, UNMAPPED, DECOMPOSITION, CUSTOM_SCHEME }

	/** The level compared with the ACTIVE record at the same identity. */
	public enum LevelChange { NEW, UNCHANGED, UPGRADE, DOWNGRADE, UNCOMPARABLE }

	/** ReARM does not verify imported declarations in v1; every row says so. */
	public enum VerificationStatus { UNVERIFIED }

	/** The trust-gate row that decided the outcome; one value per decision row. */
	public enum GateRule {
		DOWNGRADE, THIRD_PARTY_STAGE, SELF_AUTO, VENDOR_CUSTOM, VENDOR_UNMAPPED,
		VENDOR_DECOMPOSITION, VENDOR_BOTTOM, VENDOR_AUTO, INTRA_ASSET_CONFLICT,
		UNMATCHED, AMBIGUOUS, SBOM_PENDING, UNCOMPARABLE, WIDE_SCOPE
	}

	/** Facts recorded alongside the gate outcome; not decision rows. */
	public enum Note { CROSS_DOC_CONFLICT, USER_MODE_STAGE, OVERRIDE_IGNORED }

	/** How the asset's component purl matched the release inventory. */
	public enum MatchStatus { MATCHED, UNMATCHED, AMBIGUOUS, SBOM_PENDING }

	/** Format of the imported document. */
	public enum SourceFormat { CDX_2_0 }

	@JsonProperty("uuid") private UUID uuid;
	@JsonProperty("org") private UUID org;

	// Identity
	@JsonProperty("scope") private AnalysisScope scope;
	@JsonProperty("scopeUuid") private UUID scopeUuid;
	@JsonProperty("componentPurl") private String componentPurl;
	@JsonProperty("schemeKey") private String schemeKey;
	@JsonProperty("blueprintRefId") private String blueprintRefId;
	@JsonProperty("assetRefId") private String assetRefId;

	// Level
	@JsonProperty("levelRaw") private String levelRaw;
	@JsonProperty("levelNormalized") private String levelNormalized;
	@JsonProperty("levelRank") private Integer levelRank;
	@JsonProperty("levelNote") private LevelNote levelNote;
	@JsonProperty("levelChange") private LevelChange levelChange;

	// Trust
	@JsonProperty("verificationStatus") private VerificationStatus verificationStatus = VerificationStatus.UNVERIFIED;
	@JsonProperty("issuerClassDerived") private IssuerClass issuerClassDerived;
	@JsonProperty("issuerClassEffective") private IssuerClass issuerClassEffective;
	@JsonProperty("originBinding") private ArtifactBelongsTo originBinding;
	@JsonProperty("issuerOverride") private IssuerClass issuerOverride;
	@JsonProperty("overrideHonoured") private boolean overrideHonoured;
	@JsonProperty("elevatedByOverride") private boolean elevatedByOverride;
	@JsonProperty("overrideActor") private UUID overrideActor;
	@JsonProperty("importMode") private ImportMode importMode;
	@JsonProperty("gateRule") private GateRule gateRule;
	@JsonProperty("notes") private List<Note> notes = new ArrayList<>();
	@JsonProperty("matchStatus") private MatchStatus matchStatus;
	@JsonProperty("statusReason") private String statusReason;
	@JsonProperty("conflictFlag") private boolean conflictFlag;

	// Provenance
	@JsonProperty("sourceArtifact") private UUID sourceArtifact;
	@JsonProperty("releaseUuid") private UUID releaseUuid;
	@JsonProperty("sbomComponentUuid") private UUID sbomComponentUuid;
	@JsonProperty("componentRefId") private String componentRefId;
	@JsonProperty("sourceFormat") private SourceFormat sourceFormat = SourceFormat.CDX_2_0;
	@JsonProperty("specCommit") private String specCommit;
	@JsonProperty("docSerialNumber") private String docSerialNumber;
	@JsonProperty("docVersion") private Integer docVersion;
	@JsonProperty("sourceDocumentHash") private String sourceDocumentHash;
	@JsonProperty("sourceAssertionJson") private String sourceAssertionJson;
	@JsonProperty("sourceAssertionHash") private String sourceAssertionHash;
	@JsonProperty("uploadedBy") private UUID uploadedBy;

	@Override
	public UUID getResourceGroup() {
		return null;
	}

	/** Adds {@code note} once. */
	public void addNote(Note note) {
		if (notes == null) notes = new ArrayList<>();
		if (!notes.contains(note)) notes.add(note);
	}

	/** Copies every shared field from {@code other}, except uuid. */
	public void copySharedFrom(SafetyContextData other) {
		this.org = other.org;
		this.scope = other.scope;
		this.scopeUuid = other.scopeUuid;
		this.componentPurl = other.componentPurl;
		this.schemeKey = other.schemeKey;
		this.blueprintRefId = other.blueprintRefId;
		this.assetRefId = other.assetRefId;
		this.levelRaw = other.levelRaw;
		this.levelNormalized = other.levelNormalized;
		this.levelRank = other.levelRank;
		this.levelNote = other.levelNote;
		this.levelChange = other.levelChange;
		this.verificationStatus = other.verificationStatus;
		this.issuerClassDerived = other.issuerClassDerived;
		this.issuerClassEffective = other.issuerClassEffective;
		this.originBinding = other.originBinding;
		this.issuerOverride = other.issuerOverride;
		this.overrideHonoured = other.overrideHonoured;
		this.elevatedByOverride = other.elevatedByOverride;
		this.overrideActor = other.overrideActor;
		this.importMode = other.importMode;
		this.gateRule = other.gateRule;
		this.notes = new ArrayList<>(other.notes == null ? List.of() : other.notes);
		this.matchStatus = other.matchStatus;
		this.statusReason = other.statusReason;
		this.conflictFlag = other.conflictFlag;
		this.sourceArtifact = other.sourceArtifact;
		this.releaseUuid = other.releaseUuid;
		this.sbomComponentUuid = other.sbomComponentUuid;
		this.componentRefId = other.componentRefId;
		this.sourceFormat = other.sourceFormat;
		this.specCommit = other.specCommit;
		this.docSerialNumber = other.docSerialNumber;
		this.docVersion = other.docVersion;
		this.sourceDocumentHash = other.sourceDocumentHash;
		this.sourceAssertionJson = other.sourceAssertionJson;
		this.sourceAssertionHash = other.sourceAssertionHash;
		this.uploadedBy = other.uploadedBy;
	}
}
