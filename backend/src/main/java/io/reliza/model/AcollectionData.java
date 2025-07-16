/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.common.Utils;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.tea.TeaCollectionUpdateReasonType;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AcollectionData extends RelizaDataParent implements RelizaObject {
	public record VersionedArtifact(UUID artifactUuid, Long version, ArtifactType type) {}
	public record DiffComponent(String purl, String version) {}
	public record ArtifactChangelog (Set<DiffComponent> added, Set<DiffComponent> removed) {}
	public record ArtifactComparison (ArtifactChangelog changelog, UUID comparedReleaseUuid) {}
	private UUID uuid;
	private UUID release;
	private UUID org;
	private Long version;
	private TeaCollectionUpdateReasonType updateReason;
	@Deprecated
	private ArtifactChangelog artifactChangelog;
	private ArtifactComparison artifactComparison;
	private Set<VersionedArtifact> artifacts = new HashSet<>();
		
	public static AcollectionData acollectionDataFactory(UUID org, UUID releaseUuid, Long version, Collection<VersionedArtifact> artifacts) {
		AcollectionData acd = new AcollectionData();
		acd.setUuid(UUID.randomUUID());
		acd.setOrg(org);
		acd.setRelease(releaseUuid);
		acd.setVersion(version);
		acd.setArtifacts(new HashSet<>(artifacts));
		return acd;
	}
	
	public static AcollectionData dataFromRecord (Acollection ac) {
		if (ac.getSchemaVersion() != 0) { // we'll be adding new schema versions later as required, if schema version is not supported, throw exception
			throw new IllegalStateException("A collection schema version is " + ac.getSchemaVersion() + ", which is not currently supported");
		}
		Map<String,Object> recordData = ac.getRecordData();
		AcollectionData acd = Utils.OM.convertValue(recordData, AcollectionData.class);
		acd.setUuid(ac.getUuid());
		acd.setCreatedDate(ac.getCreatedDate());
		acd.setUpdatedDate(ac.getLastUpdatedDate());
		return acd;
	}
	

	@JsonIgnore
	@Override
	public UUID getResourceGroup() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public UUID getOrg() {
		return org;
	}
	
    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), uuid, release, org, version, updateReason, artifacts);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AcollectionData)) return false;
        if (!super.equals(o)) return false;
        AcollectionData that = (AcollectionData) o;
        return Objects.equals(uuid, that.uuid) &&
               Objects.equals(release, that.release) &&
               Objects.equals(org, that.org) &&
               Objects.equals(version, that.version) &&
               Objects.equals(updateReason, that.updateReason) &&
               Objects.equals(artifacts, that.artifacts);
    }
    
    public TeaCollectionUpdateReasonType resolveUpdateReason (AcollectionData oldCollection) {
    	TeaCollectionUpdateReasonType tcurt = null;
    	if (oldCollection == null) {
    		tcurt = TeaCollectionUpdateReasonType.INITIAL_RELEASE;
    	} else {
	    	if (!oldCollection.getRelease().equals(this.release)) {
	    		throw new IllegalStateException("Collection does not belong to the same release!");
	    	}
	    	if (this.version - oldCollection.getVersion() != 1) {
	    		throw new IllegalStateException("Can only compare sequential collection versions!");
	    	}
	    	if (oldCollection.getArtifacts().size() == artifacts.size()) {
	    		// separate case for art updated and VEX updated
	    		var thisArtIter = artifacts.iterator();
	    		var oldArtIter = oldCollection.getArtifacts().iterator();
	    		while (null == tcurt && thisArtIter.hasNext()) {
	    			var thisArt = thisArtIter.next();
	    			var oldArt = oldArtIter.next();
	    			if (thisArt.version() > oldArt.version() && thisArt.type() == ArtifactType.VEX) {
	    				tcurt = TeaCollectionUpdateReasonType.VEX_UPDATED;
	    			} else if (thisArt.version() > oldArt.version()) {
	    				tcurt = TeaCollectionUpdateReasonType.ARTIFACT_UPDATED;
	    			}
	    		}
	    	} else if (oldCollection.getArtifacts().size() < artifacts.size()) {
	    		tcurt = TeaCollectionUpdateReasonType.ARTIFACT_ADDED;
	    	} else {
	    		tcurt = TeaCollectionUpdateReasonType.ARTIFACT_REMOVED;
	    	}
    	}
    	this.updateReason = tcurt;
    	return tcurt;
    }
}
