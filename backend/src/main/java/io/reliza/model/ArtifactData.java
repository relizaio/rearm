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

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.tea.Link;
import io.reliza.model.tea.Rebom.InternalBom;
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
        UID;
      }
	
	public enum BomFormat {
		CYCLONEDX,
		SPDX
	}

    public record Identity(IdentityType identityType, String identity) {}
    
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
	private Set<String> digests = new LinkedHashSet<>(); // GENERATE FOR INTERNAL
	private List<TagRecord> tags = new ArrayList<>();
	private StatusEnum status; // HOW TO ASSIGN, ANY FOR NOW
	private String version; // FIGURE OUT VERSIONING 
	private DependencyTrackIntegration metrics = new DependencyTrackIntegration();

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
			ad.setUuid(UUID.randomUUID());
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
		if (null != artifactDto.getDigests()) ad.setDigests(new LinkedHashSet<>(artifactDto.getDigests()));
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
		// TODO Auto-generated method stub
		return ad;
	}
}
