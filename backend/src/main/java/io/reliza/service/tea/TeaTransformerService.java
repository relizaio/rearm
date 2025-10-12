/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables;
import io.reliza.model.AcollectionData;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ParentRelease;
import io.reliza.model.ReleaseData;
import io.reliza.model.tea.TeaArtifact;
import io.reliza.model.tea.TeaArtifactFormat;
import io.reliza.model.tea.TeaArtifactType;
import io.reliza.model.tea.TeaChecksum;
import io.reliza.model.tea.TeaCollection;
import io.reliza.model.tea.TeaCollectionBelongsToType;
import io.reliza.model.tea.TeaCollectionUpdateReason;
import io.reliza.model.tea.TeaComponent;
import io.reliza.model.tea.TeaComponentRef;
import io.reliza.model.tea.TeaComponentReleaseWithCollection;
import io.reliza.model.tea.TeaDiscoveryInfo;
import io.reliza.model.tea.TeaProduct;
import io.reliza.model.tea.TeaProductRelease;
import io.reliza.model.tea.TeaRelease;
import io.reliza.service.AcollectionService;
import io.reliza.service.ArtifactService;
import io.reliza.service.GetComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.ws.RelizaConfigProps;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class TeaTransformerService {
	
	@Autowired
	private SharedReleaseService sharedReleaseService;
	
	@Autowired
	private ArtifactService artifactService;
	
	@Autowired
	private GetComponentService getComponentService;
	
	@Autowired
	private AcollectionService acollectionService;
	
	private RelizaConfigProps relizaConfigProps;
	
	@Autowired
    public void setProps(RelizaConfigProps relizaConfigProps) {
        this.relizaConfigProps = relizaConfigProps;
    }
    
	public TeaProduct transformProductToTea(ComponentData rearmCD) {
		if (rearmCD.getType() != ComponentType.PRODUCT) {
			throw new RuntimeException("Wrong component type");
		}
		TeaProduct tp = new TeaProduct();
		tp.setUuid(rearmCD.getUuid());
		tp.setName(rearmCD.getName());
		tp.setIdentifiers(rearmCD.getIdentifiers());
		return tp;
	}
	
	public TeaProductRelease transformProductReleaseToTea (ReleaseData rd) {
		UUID productUuid = rd.getComponent();
		ComponentData cd = getComponentService.getComponentData(productUuid).get();
		if (cd.getType() != ComponentType.PRODUCT) {
			throw new RuntimeException("Wrong component type");
		}
		TeaProductRelease tpr = new TeaProductRelease();
		tpr.setUuid(rd.getUuid());
		OffsetDateTime releaseDate = rd.getCreatedDate().toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS);
		tpr.setCreatedDate(releaseDate);
		tpr.setReleaseDate(releaseDate); // TODO (consider using date when release set to shipped or assembled - potentially make configurable)
		tpr.setIdentifiers(rd.getIdentifiers());
		tpr.setPreRelease(false);
		tpr.setProduct(productUuid);
		tpr.setProductName(cd.getName());
		tpr.setVersion(rd.getVersion());
		tpr.setComponents(rd.getParentReleases().stream().map(pr -> transformParentReleaseToComponentRef(pr)).toList());
		return tpr;
	}
	
	private TeaComponentRef transformParentReleaseToComponentRef(ParentRelease pr) {
		TeaComponentRef tcr = new TeaComponentRef();
		ReleaseData rearmRd = sharedReleaseService.getReleaseData(pr.getRelease()).get();
		tcr.setUuid(rearmRd.getComponent());
		tcr.setRelease(pr.getRelease());
		return tcr;
	}
	
	public TeaComponent transformComponentToTea(ComponentData rearmCD) {
		if (rearmCD.getType() != ComponentType.COMPONENT) {
			throw new RuntimeException("Wrong component type");
		}
		TeaComponent tc = new TeaComponent();
		tc.setUuid(rearmCD.getUuid());
		tc.setName(rearmCD.getName());
		tc.setIdentifiers(rearmCD.getIdentifiers());
		return tc;
	}
	
	public TeaRelease transformReleaseToTea(ReleaseData rearmRD) {
		TeaRelease tr = new TeaRelease();
		tr.setUuid(rearmRD.getUuid());
		tr.setVersion(rearmRD.getVersion());
		tr.setPreRelease(false);
		OffsetDateTime releaseDate = rearmRD.getCreatedDate().toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS);
		tr.setReleaseDate(releaseDate);
		tr.setIdentifiers(rearmRD.getIdentifiers());
		return tr;
	}
	
	public TeaComponentReleaseWithCollection transformComponentReleaseWithCollectionToTea(ReleaseData rearmRD) {
		TeaComponentReleaseWithCollection tcr = new TeaComponentReleaseWithCollection();
		TeaRelease tr = new TeaRelease();
		tr.setUuid(rearmRD.getUuid());
		tr.setVersion(rearmRD.getVersion());
		tr.setPreRelease(false);
		OffsetDateTime releaseDate = rearmRD.getCreatedDate().toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS);
		tr.setReleaseDate(releaseDate); // TODO consider separate release date
		tr.setCreatedDate(releaseDate);
		tr.setIdentifiers(rearmRD.getIdentifiers());
		ComponentData cd = getComponentService.getComponentData(rearmRD.getComponent()).get();
		tr.setComponent(rearmRD.getComponent());
		tr.setComponentName(cd.getName());
		tcr.setRelease(tr);
		var acd = acollectionService.getLatestCollectionDataOfRelease(rearmRD.getUuid());
		var teaAcd = transformAcollectionToTea(acd);
		tcr.setLatestCollection(teaAcd);
		return tcr;
	}
	
	public Optional<TeaDiscoveryInfo> performTeiDiscovery (String decodedTei) {
		String[] teiEls = decodedTei.split(":");
		UUID foundRelease = null;
		if ("uuid".equals(teiEls[2])) {
			String uuidStr = teiEls[teiEls.length - 1];
			UUID releaseUuid = UUID.fromString(uuidStr);
			var optRelease = sharedReleaseService.getRelease(releaseUuid, UserService.USER_ORG); // TODO handle other orgs
			if (optRelease.isPresent()) foundRelease = releaseUuid;
		} else if ("purl".equals(teiEls[2])) {
			StringBuilder purlBuilder = new StringBuilder();
			boolean foundPurlStart = false;
			for (int i = 4; i < teiEls.length; i++) {
				if (!foundPurlStart && "pkg".equals(teiEls[i])) foundPurlStart = true;
				if (foundPurlStart) {
					purlBuilder.append(teiEls[i]);
					if (i < teiEls.length - 1) purlBuilder.append(":");
				}
			}
			String purl = purlBuilder.toString();
			var optRelease = sharedReleaseService.findReleaseDataByOrgAndPurl(UserService.USER_ORG, purl); // TODO handle other orgs
			if (optRelease.isPresent()) foundRelease = optRelease.get().getUuid();
		}
		Optional<TeaDiscoveryInfo> otdi = Optional.empty();
		if (null != foundRelease) {
			TeaDiscoveryInfo tdi = new TeaDiscoveryInfo();
			tdi.setRootUrl(URI.create(relizaConfigProps.getBaseuri()));
			tdi.setVersions(List.of("0.2.0-beta.2"));
			tdi.setProductReleaseUuid(foundRelease);
			otdi = Optional.of(tdi);
		}
		return otdi;
	}
	
	private TeaArtifactType transformArtifactTypeToTea (ArtifactType rearmAT) {
		TeaArtifactType tat = null;
		
		switch (rearmAT) {
			case BOM:
				tat = TeaArtifactType.BOM;
				break;
			case ATTESTATION:
				tat = TeaArtifactType.ATTESTATION;
				break;
			case VDR:
			case VEX:
			case BOV:
				tat = TeaArtifactType.VULNERABILITIES;
				break;
			case USER_DOCUMENT:
			case DEVELOPMENT_DOCUMENT:
			case PROJECT_DOCUMENT:
			case MARKETING_DOCUMENT:
			case TEST_REPORT:
			case SARIF:
			case SIGNED_PAYLOAD:
			case OTHER:
			case SIGNATURE:
			case PUBLIC_KEY:
			case CERTIFICATE_X_509:
			case CERTIFICATE_PGP:
				tat = TeaArtifactType.OTHER;
				break;
			case BUILD_META:
				tat = TeaArtifactType.BUILD_META;
				break;
			case CERTIFICATION:
				tat = TeaArtifactType.CERTIFICATION;
				break;
			case FORMULATION:
				tat = TeaArtifactType.FORMULATION;
				break;
			case LICENSE:
				tat = TeaArtifactType.LICENSE;
				break;
			case RELEASE_NOTES:
				tat = TeaArtifactType.RELEASE_NOTES;
				break;
			case SECURITY_TXT:
				tat = TeaArtifactType.SECURITY_TXT;
				break;
			case THREAT_MODEL:
				tat = TeaArtifactType.THREAT_MODEL;
				break;
		}
		
		return tat;
	}
	
	private String resolveMediaType(BomFormat bomFormat, String initialType) {
		String resolvedType;
		if ("application/json".equals(initialType) && bomFormat == BomFormat.CYCLONEDX) {
			resolvedType = "vnd.cyclonedx+json";
		} else if ("application/xml".equals(initialType) && bomFormat == BomFormat.CYCLONEDX){
			resolvedType = "vnd.cyclonedx+xml";
		} else {
			resolvedType = initialType;
		}
		return resolvedType;
	}
	
	public TeaArtifact transformArtifactToTea(ArtifactData rearmAD) {
		TeaArtifact ta = new TeaArtifact();
		ta.setUuid(rearmAD.getUuid());
		TeaArtifactType tat = transformArtifactTypeToTea(rearmAD.getType());
		ta.setType(tat);
		String name = StringUtils.isNotEmpty(rearmAD.getDisplayIdentifier()) ? rearmAD.getDisplayIdentifier() : rearmAD.getUuid().toString();  
		ta.setName(name);

		List<TeaArtifactFormat> tafList = new LinkedList<>();
		if (rearmAD.getStoredIn() == StoredIn.REARM) {
			TeaArtifactFormat taf = new TeaArtifactFormat();
			String bomFormatDisplay = (rearmAD.getBomFormat() != null) ? rearmAD.getBomFormat().toString() + " " : "";
			taf.setDescription(String.format("%s%s Raw Artifact as Uploaded to ReARM", bomFormatDisplay, rearmAD.getType()));
			var mediaTypeTag = rearmAD.getTags().stream().filter(t -> CommonVariables.MEDIA_TYPE_FIELD.equals(t.key())).findAny();
			if (mediaTypeTag.isPresent()) taf.setMimeType(resolveMediaType(rearmAD.getBomFormat(), mediaTypeTag.get().value()));
			taf.setUrl(relizaConfigProps.getBaseuri() + "/downloadArtifact/raw/" + rearmAD.getUuid());
			Optional<ArtifactData> optSignatureAD = artifactService.getArtifactSignature(rearmAD);
			if (optSignatureAD.isPresent()) {
				taf.setSignatureUrl(relizaConfigProps.getBaseuri() + "/downloadArtifact/raw/" + optSignatureAD.get().getUuid());
			} else taf.setSignatureUrl(null);
			List<TeaChecksum> tcList = new LinkedList<>();
			rearmAD.getDigestRecords().stream().filter(d -> d.scope() == DigestScope.ORIGINAL_FILE).forEach(d -> {
				TeaChecksum tc = new TeaChecksum();
				tc.setAlgType(d.algo());
				tc.setAlgValue(d.digest());
				tcList.add(tc);	
			});
			taf.setChecksums(tcList);
			tafList.add(taf);
			
			if (rearmAD.getType() == ArtifactType.BOM && rearmAD.getBomFormat() == BomFormat.CYCLONEDX) {
				TeaArtifactFormat tafAugmented = new TeaArtifactFormat();
				tafAugmented.setDescription("CycloneDX BOM Artifact Uploaded to ReARM, Augmented by Rebom");
				if (mediaTypeTag.isPresent()) tafAugmented.setMimeType(resolveMediaType(rearmAD.getBomFormat(), mediaTypeTag.get().value()));
				tafAugmented.setUrl(relizaConfigProps.getBaseuri() + "/downloadArtifact/augmented/" + rearmAD.getUuid());
				tafAugmented.setSignatureUrl(null); // TODO
				tafList.add(tafAugmented);
			}
		} else {
			for (var dlink : rearmAD.getDownloadLinks()) {
				TeaArtifactFormat taf = new TeaArtifactFormat();
				taf.setDescription("External Artifact Linked from ReARM");
				taf.setMimeType(resolveMediaType(rearmAD.getBomFormat(), dlink.getContent().getContentString()));
				List<TeaChecksum> tcList = new LinkedList<>();
				rearmAD.getDigestRecords().stream().filter(d -> d.scope() == DigestScope.ORIGINAL_FILE).forEach(d -> {
					TeaChecksum tc = new TeaChecksum();
					tc.setAlgType(d.algo());
					tc.setAlgValue(d.digest());
					tcList.add(tc);	
				});
				taf.setChecksums(tcList);
				taf.setUrl(dlink.getUri());
				taf.setSignatureUrl(null); // TODO
				tafList.add(taf);
			}
		}
		ta.setFormats(tafList);
		return ta;
	}
	
	public TeaCollection transformAcollectionToTea(AcollectionData acd) {
		TeaCollection tc = new TeaCollection();
		tc.setUuid(acd.getRelease());
		Integer cVersion = acd.getVersion().intValue();
		tc.setVersion(cVersion);
		TeaCollectionUpdateReason tcur = new TeaCollectionUpdateReason();
		tcur.setType(acd.getUpdateReason());
		tc.setUpdateReason(tcur);
		OffsetDateTime collectionDate = acd.getCreatedDate().toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS);
		tc.setDate(collectionDate);
		List<TeaArtifact> teaArtifacts = acd.getArtifacts().stream().map(x -> {
			Optional<ArtifactData> oad = artifactService.getArtifactData(x.artifactUuid());
			if (oad.isEmpty() || !acd.getOrg().equals(oad.get().getOrg())) {
				log.error("Mismatching org: ace uuid = " + acd.getUuid() + ", art UUID = " + x.artifactUuid());
				throw new IllegalStateException("Incorrect Artifact Data, Please contact administrator");
			}
			return transformArtifactToTea(oad.get());
		}).toList();
		tc.setArtifacts(teaArtifacts);
		ReleaseData rd = sharedReleaseService.getReleaseData(acd.getRelease()).get();
		ComponentData cd = getComponentService.getComponentData(rd.getComponent()).get();
		TeaCollectionBelongsToType belongsToType = (cd.getType() == ComponentType.COMPONENT) ? TeaCollectionBelongsToType.COMPONENT_RELEASE : TeaCollectionBelongsToType.PRODUCT_RELEASE;
		tc.setBelongsTo(belongsToType);
		return tc;
	}
	
	public String getServerBaseUri() {
		return relizaConfigProps.getBaseuri() + "/tea";
	}
    
}
