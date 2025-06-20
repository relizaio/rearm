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
import io.reliza.model.tea.TeaArtifactChecksumType;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArtifactData extends RelizaDataParent implements RelizaObject {
	
    public enum ArtifactType {
    	BOM,
        ATTESTATION,
        VDR,
        VEX,
        USER_DOCUMENT,
        DEVELOPMENT_DOCUMENT,
        PROJECT_DOCUMENT,
        MARKETING_DOCUMENT,
        TEST_REPORT,
        SARIF,
        BUILD_META,
        CERTIFICATION,
        FORMULATION,
        LICENSE,
        RELEASE_NOTES,
        SECURITY_TXT,
        THREAT_MODEL,
        SIGNATURE,
        PUBLIC_KEY,
        CERTIFICATE_X_509,
        CERTIFICATE_PGP,
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

	public enum DigestScope {
		ORIGINAL_FILE, //user provided
		OCI_STORAGE,
		REARM // custom digest calculated only on components and dependencies
	}

    public record Identity(IdentityType identityType, String identity) {}

	public record DigestRecord(
		TeaArtifactChecksumType algo, 
		String digest, 
		@JsonProperty(defaultValue = "ORIGINAL_FILE")
		DigestScope scope
	) {
		public DigestRecord(TeaArtifactChecksumType algo, String digest){
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
    }

	
	private UUID uuid;
	private UUID org;
	
	private ArtifactType type;
	
	private String displayIdentifier;
	private List<Identity> identities = new ArrayList<>();
	private List<Link> downloadLinks = new ArrayList<>();
	private List<InventoryType> inventoryTypes = new ArrayList<>();
	private BomFormat bomFormat; // FIND ON create
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


	public static ArtifactData artifactDataFactory(ArtifactDto artifactDto) {
		ArtifactData ad = new ArtifactData();
		if (null != artifactDto.getUuid()) {
			ad.setUuid(artifactDto.getUuid());
		} else {
			ad.setUuid(null);
		}
		ad.setType(artifactDto.getType());
		ad.setDisplayIdentifier(artifactDto.getDisplayIdentifier());
		if (null != artifactDto.getIdentities()) ad.setIdentities(new ArrayList<>(artifactDto.getIdentities()));
		if (null != artifactDto.getDownloadLinks()) ad.setDownloadLinks(new ArrayList<>(artifactDto.getDownloadLinks()));
		if (null != artifactDto.getInventoryTypes()) ad.setInventoryTypes(new ArrayList<>(artifactDto.getInventoryTypes()));
		ad.setBomFormat(artifactDto.getBomFormat());
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
		}
		if (null != artifactDto.getArtifacts()) {
			ad.setArtifacts(artifactDto.getArtifacts());
		}
		return ad;
	}
	
	@JsonIgnore
	public boolean isSignatureType() {
		if (this.type == ArtifactType.SIGNATURE || this.type == ArtifactType.CERTIFICATE_PGP || this.type == ArtifactType.CERTIFICATE_X_509 
				|| this.type == ArtifactType.PUBLIC_KEY) return true;
			else return false;
	}
}
