/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.tea.Link;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.model.tea.TeaChecksumType;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArtifactData extends RelizaDataParent implements RelizaObject {
	
    public enum ArtifactType {
    	BOM,
        ATTESTATION,
        VDR,
        BOV,
        VEX,
        USER_DOCUMENT,
        DEVELOPMENT_DOCUMENT,
        PROJECT_DOCUMENT,
        MARKETING_DOCUMENT,
        TEST_REPORT,
        CODE_SCANNING_RESULT,
        @Deprecated SARIF, /* CODE_SCANNING_RESULT should be used instead of SARIF with SARIF set as a format*/
        BUILD_META,
        CERTIFICATION,
        FORMULATION,
        LICENSE,
        RELEASE_NOTES,
        SECURITY_TXT,
        THREAT_MODEL,
        SIGNATURE,
        SIGNED_PAYLOAD,
        PUBLIC_KEY,
        CERTIFICATE_X_509,
        CERTIFICATE_PGP,
		RISK_ASSESSMENT,
        OTHER
    }
    
	public enum StoredIn {
		REARM,
		EXTERNALLY
	}
	
    public enum InventoryType {
        SOFTWARE,
        HARDWARE,
        CRYPTOGRAPHY,
        SERVICE,
        VULNERABILITY
    }
    
	// TODO incorporate into TEA - that is already used
	@Deprecated
    public enum IdentityType {
        PURL,
        CPE,
        SWID,
        GAV,
        GTIN,
        GMN,
        MANUFACTURER_VERSION,
        EAN,
        UID;
      }
	
	public enum BomFormat {
		CYCLONEDX,
		SPDX
	}
	
	public enum SerializationFormat {
		JSON,
		YAML,
		XML,
		OTHER
	}
	
	public enum SpecVersion {
		// CycloneDX versions
		CYCLONEDX_1_0,
		CYCLONEDX_1_1,
		CYCLONEDX_1_2,
		CYCLONEDX_1_3,
		CYCLONEDX_1_4,
		CYCLONEDX_1_5,
		CYCLONEDX_1_6,
		CYCLONEDX_1_7,
		// SPDX versions
		SPDX_2_0,
		SPDX_2_1,
		SPDX_2_2,
		SPDX_2_3,
		SPDX_3_0,
		// SARIF versions
		SARIF_2_0,
		SARIF_2_1,
		// OpenVEX versions
		OPENVEX_0_2,
		// CSAF versions
		CSAF_2_0,
		CSAF_2_1
	}

	public enum DigestScope {
		ORIGINAL_FILE, //user provided
		OCI_STORAGE,
		REARM // custom digest calculated only on components and dependencies
	}
	
	/**
	 * Immutable snapshot of an artifact version for version history
	 * Preserves essential artifact state at a specific point in time
	 */
	public record ArtifactVersionSnapshot(
		UUID originalUuid,        // CRITICAL: Preserve original UUID for replacement scenarios
		ZonedDateTime versionDate,
		String version,
		ArtifactType type,
		String displayIdentifier,
		List<Link> downloadLinks,
		List<InventoryType> inventoryTypes,
		BomFormat bomFormat,
		StoredIn storedIn,
		InternalBom internalBom,
		Set<DigestRecord> digestRecords,
		List<TagRecord> tags,
		StatusEnum status,
		List<UUID> artifacts,
		UUID org  // Organization UUID for rebom lookups
	) {
		/**
		 * Creates a version snapshot from current ArtifactData state
		 */
		public static ArtifactVersionSnapshot fromArtifactData(ArtifactData ad) {
			return new ArtifactVersionSnapshot(
				ad.getUuid(),
				ZonedDateTime.now(),
				ad.getVersion(),
				ad.getType(),
				ad.getDisplayIdentifier(),
				ad.getDownloadLinks() != null ? new ArrayList<>(ad.getDownloadLinks()) : new ArrayList<>(),
				ad.getInventoryTypes() != null ? new ArrayList<>(ad.getInventoryTypes()) : new ArrayList<>(),
				ad.getBomFormat(),
				ad.getStoredIn(),
				ad.getInternalBom(),
				ad.getDigestRecords() != null ? new LinkedHashSet<>(ad.getDigestRecords()) : new LinkedHashSet<>(),
				ad.getTags() != null ? new ArrayList<>(ad.getTags()) : new ArrayList<>(),
				ad.getStatus(),
				ad.getArtifacts() != null ? new ArrayList<>(ad.getArtifacts()) : new ArrayList<>(),
				ad.getOrg()
			);
		}
		
		/**
		 * Converts an ArtifactVersionSnapshot back to ArtifactData for GraphQL response
		 */
		public static ArtifactData fromSnapshot(ArtifactVersionSnapshot snapshot) {
			ArtifactData ad = new ArtifactData();
			ad.setUuid(snapshot.originalUuid());
			ad.setCreatedDate(snapshot.versionDate());
			ad.setUpdatedDate(snapshot.versionDate());
			ad.setVersion(snapshot.version());
			ad.setType(snapshot.type());
			ad.setDisplayIdentifier(snapshot.displayIdentifier());
			ad.setDownloadLinks(snapshot.downloadLinks());
			ad.setInventoryTypes(snapshot.inventoryTypes());
			ad.setBomFormat(snapshot.bomFormat());
			ad.setStoredIn(snapshot.storedIn());
			ad.setInternalBom(snapshot.internalBom());
			ad.setDigestRecords(snapshot.digestRecords());
			ad.setTags(snapshot.tags());
			ad.setStatus(snapshot.status());
			ad.setArtifacts(snapshot.artifacts());
			ad.setOrg(snapshot.org());
			// Note: previousVersions is intentionally not set to avoid infinite nesting
			return ad;
		}
	}

	public record DigestRecord(
		TeaChecksumType algo, 
		String digest, 
		@JsonProperty(defaultValue = "ORIGINAL_FILE")
		DigestScope scope
	) {
		public DigestRecord(TeaChecksumType algo, String digest){
			this(algo, digest, DigestScope.ORIGINAL_FILE);
		}
	}
    
    @Data
    @EqualsAndHashCode(callSuper = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DependencyTrackIntegration extends ReleaseMetricsDto {
    	private String dependencyTrackProject;
    	private String uploadToken;
    	private URI dependencyTrackFullUri;
    	private String projectName;
    	private String projectVersion;
    	private Boolean dtrackProjectDeleted = false;
    	private ZonedDateTime uploadDate;
    	
    	public static DependencyTrackIntegration fromReleaseMetricsDto(ReleaseMetricsDto rmd) {
    		DependencyTrackIntegration dti = new DependencyTrackIntegration();
    		
    		// Copy all fields from ReleaseMetricsDto
    		dti.setCritical(rmd.getCritical());
    		dti.setHigh(rmd.getHigh());
    		dti.setMedium(rmd.getMedium());
    		dti.setLow(rmd.getLow());
    		dti.setUnassigned(rmd.getUnassigned());
    		dti.setVulnerabilities(rmd.getVulnerabilities());
    		dti.setVulnerableComponents(rmd.getVulnerableComponents());
    		dti.setComponents(rmd.getComponents());
    		dti.setSuppressed(rmd.getSuppressed());
    		dti.setFindingsTotal(rmd.getFindingsTotal());
    		dti.setFindingsAudited(rmd.getFindingsAudited());
    		dti.setFindingsUnaudited(rmd.getFindingsUnaudited());
    		dti.setInheritedRiskScore(rmd.getInheritedRiskScore());
    		dti.setPolicyViolationsFail(rmd.getPolicyViolationsFail());
    		dti.setPolicyViolationsWarn(rmd.getPolicyViolationsWarn());
    		dti.setPolicyViolationsInfo(rmd.getPolicyViolationsInfo());
    		dti.setPolicyViolationsTotal(rmd.getPolicyViolationsTotal());
    		dti.setPolicyViolationsAudited(rmd.getPolicyViolationsAudited());
    		dti.setPolicyViolationsUnaudited(rmd.getPolicyViolationsUnaudited());
    		dti.setPolicyViolationsSecurityTotal(rmd.getPolicyViolationsSecurityTotal());
    		dti.setPolicyViolationsSecurityAudited(rmd.getPolicyViolationsSecurityAudited());
    		dti.setPolicyViolationsSecurityUnaudited(rmd.getPolicyViolationsSecurityUnaudited());
    		dti.setPolicyViolationsLicenseTotal(rmd.getPolicyViolationsLicenseTotal());
    		dti.setPolicyViolationsLicenseAudited(rmd.getPolicyViolationsLicenseAudited());
    		dti.setPolicyViolationsLicenseUnaudited(rmd.getPolicyViolationsLicenseUnaudited());
    		dti.setPolicyViolationsOperationalTotal(rmd.getPolicyViolationsOperationalTotal());
    		dti.setPolicyViolationsOperationalAudited(rmd.getPolicyViolationsOperationalAudited());
    		dti.setPolicyViolationsOperationalUnaudited(rmd.getPolicyViolationsOperationalUnaudited());
    		dti.setLastScanned(rmd.getLastScanned());
    		dti.setViolationDetails(rmd.getViolationDetails());
    		dti.setVulnerabilityDetails(rmd.getVulnerabilityDetails());
    		dti.setWeaknessDetails(rmd.getWeaknessDetails());
    		
    		// DependencyTrack-specific fields remain null
    		dti.setDependencyTrackProject(null);
    		dti.setUploadToken(null);
    		dti.setDependencyTrackFullUri(null);
    		dti.setProjectName(null);
    		dti.setProjectVersion(null);
    		
    		return dti;
    	}
    }

	@Getter(AccessLevel.PUBLIC)
	@Setter(AccessLevel.PRIVATE)
	private UUID uuid;

	private UUID org;
	
	private ArtifactType type;
	
	private String displayIdentifier;
	private List<Link> downloadLinks = new ArrayList<>();
	private List<InventoryType> inventoryTypes = new ArrayList<>();
	private BomFormat bomFormat; // FIND ON create
	private SpecVersion specVersion;
	private SerializationFormat serializationFormat;
	private ZonedDateTime date;
	private StoredIn storedIn; 
	private InternalBom internalBom; // GENERATE
	@Deprecated
	private Set<String> digests = new LinkedHashSet<>(); // GENERATE FOR INTERNAL
	private Set<DigestRecord> digestRecords = new LinkedHashSet<>();
	private List<TagRecord> tags = new ArrayList<>();
	private StatusEnum status; // HOW TO ASSIGN, ANY FOR NOW
	private String version; // FIGURE OUT VERSIONING 
	private DependencyTrackIntegration metrics = new DependencyTrackIntegration();
	/**
	 * Artifact may have its own artifacts - this is for signature related artifacts, maybe there will be more use cases discovered later
	 */
	private List<UUID> artifacts = new ArrayList<>();
	
	@Getter(AccessLevel.PUBLIC)
	@Setter(AccessLevel.PRIVATE)
	private List<ArtifactVersionSnapshot> previousVersions = new ArrayList<>();

	// FIND OUT UNIQUENESS HERE?


	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		// TODO Auto-generated method stub
		return null;
	}
	
	public static ArtifactData dataFromRecord (Artifact a) {
		if (a.getSchemaVersion() != 0) { // we'll be adding new schema versions later as required, if schema version is not supported, throw exception
			throw new IllegalStateException("Artifact repository schema version is " + a.getSchemaVersion() + ", which is not currently supported");
		}
		Map<String,Object> recordData = a.getRecordData();
		ArtifactData ad = Utils.OM.convertValue(recordData, ArtifactData.class);
		ad.setUuid(a.getUuid());
		ad.setCreatedDate(a.getCreatedDate());
		ad.setUpdatedDate(a.getLastUpdatedDate());
		return ad;
	}


	public static ArtifactData artifactDataFactory(ArtifactDto artifactDto, UUID artifactUuid) {
		ArtifactData ad = new ArtifactData();
		if (null != artifactUuid) {
			ad.setUuid(artifactUuid);
		} else if (null != artifactDto.getUuid()) {
			ad.setUuid(artifactDto.getUuid());
		} else {
			ad.setUuid(null);
		}
		ad.setType(artifactDto.getType());
		ad.setDisplayIdentifier(artifactDto.getDisplayIdentifier());
		if (null != artifactDto.getDownloadLinks()) ad.setDownloadLinks(new ArrayList<>(artifactDto.getDownloadLinks()));
		if (null != artifactDto.getInventoryTypes()) ad.setInventoryTypes(new ArrayList<>(artifactDto.getInventoryTypes()));
		ad.setBomFormat(artifactDto.getBomFormat());
		ad.setSpecVersion(artifactDto.getSpecVersion());
		ad.setSerializationFormat(artifactDto.getSerializationFormat());
		ad.setDate(artifactDto.getDate());
		ad.setStoredIn(artifactDto.getStoredIn());
		ad.setInternalBom(artifactDto.getInternalBom());
		if (null != artifactDto.getDigestRecords()) ad.setDigestRecords(new LinkedHashSet<>(artifactDto.getDigestRecords()));;
		if (null != artifactDto.getTags()) ad.setTags(new ArrayList<>(artifactDto.getTags()));
		ad.setStatus(artifactDto.getStatus());
		ad.setVersion(artifactDto.getVersion());
		ad.setOrg(artifactDto.getOrg());
		if (null != artifactDto.getDtur()) {
			ad.metrics.dependencyTrackProject = artifactDto.getDtur().projectId();
			ad.metrics.uploadToken = artifactDto.getDtur().token();
			ad.metrics.projectName = artifactDto.getDtur().projectName();
			ad.metrics.projectVersion = artifactDto.getDtur().projectVersion();
			ad.metrics.dependencyTrackFullUri = artifactDto.getDtur().fullProjectUri();
		} else if (null != artifactDto.getRmd()) {
			ad.metrics = DependencyTrackIntegration.fromReleaseMetricsDto(artifactDto.getRmd());
		}
		if (null != artifactDto.getArtifacts()) {
			ad.setArtifacts(artifactDto.getArtifacts());
		}
		return ad;
	}
	
	@JsonIgnore
	public boolean isSignatureType() {
		if (this.type == ArtifactType.SIGNATURE || this.type == ArtifactType.CERTIFICATE_PGP || this.type == ArtifactType.CERTIFICATE_X_509 
				|| this.type == ArtifactType.PUBLIC_KEY || this.type == ArtifactType.SIGNED_PAYLOAD) return true;
			else return false;
	}
	
	/**
	 * Adds a version snapshot to the history in chronological order
	 * This is the only way to modify version history - maintains immutability from external access
	 */
	public void addVersionSnapshot(ArtifactVersionSnapshot snapshot) {
		if (this.previousVersions == null) {
			this.previousVersions = new ArrayList<>();
		}
		this.previousVersions.add(snapshot);
	}
	
	/**
	 * Transfers version history from another artifact (used during replacement scenarios)
	 * Maintains chronological order by appending existing history first, then the replaced artifact
	 */
	public void transferVersionHistory(ArtifactData replacedArtifact) {
		if (this.previousVersions == null) {
			this.previousVersions = new ArrayList<>();
		}
		
		// First, add all existing version history from the replaced artifact
		if (replacedArtifact.getPreviousVersions() != null) {
			this.previousVersions.addAll(replacedArtifact.getPreviousVersions());
		}
		
		// Then add the replaced artifact itself as a version
		ArtifactVersionSnapshot replacedSnapshot = ArtifactVersionSnapshot.fromArtifactData(replacedArtifact);
		this.previousVersions.add(replacedSnapshot);
	}
}
