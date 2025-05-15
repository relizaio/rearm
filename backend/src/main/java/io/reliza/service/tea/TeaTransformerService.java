/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;
import io.reliza.model.tea.TeaArtifact;
import io.reliza.model.tea.TeaArtifactChecksum;
import io.reliza.model.tea.TeaArtifactChecksumType;
import io.reliza.model.tea.TeaArtifactFormat;
import io.reliza.model.tea.TeaArtifactType;
import io.reliza.model.tea.TeaComponent;
import io.reliza.model.tea.TeaProduct;
import io.reliza.model.tea.TeaRelease;
import io.reliza.service.SharedReleaseService;
import io.reliza.ws.RelizaConfigProps;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class TeaTransformerService {
	
	@Autowired
	SharedReleaseService sharedReleaseService;
	
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
		tp.setComponents(new LinkedList<>(sharedReleaseService.obtainComponentsOfProductOrComponent(rearmCD.getUuid(), new LinkedHashSet<>())));
		return tp;
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
				tat = TeaArtifactType.VULNERABILITIES;
				break;
			case USER_DOCUMENT:
			case DEVELOPMENT_DOCUMENT:
			case PROJECT_DOCUMENT:
			case MARKETING_DOCUMENT:
			case TEST_REPORT:
			case SARIF:
			case OTHER:
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
	
	private List<TeaArtifactChecksum> transformDigestsToTea (Collection<String> digests) {
		List<TeaArtifactChecksum> tacList = new LinkedList<>();
		for (String d: digests) {
			String[] digestEls = d.split(":", 2);
			TeaArtifactChecksumType tact = null;
			String rearmDigestTypeString = StringUtils.upperCase(digestEls[0]);
			switch (rearmDigestTypeString) {
				case "MD5":
					tact = TeaArtifactChecksumType.MD5;
					break;
				case "SHA1":
					tact = TeaArtifactChecksumType.SHA_1;
					break;
				case "SHA256":
					tact = TeaArtifactChecksumType.SHA_256;
					break;
				case "SHA384":
					tact = TeaArtifactChecksumType.SHA_384;
					break;
				case "SHA512":
					tact = TeaArtifactChecksumType.SHA_512;
					break;
				case "SHA3256":
					tact = TeaArtifactChecksumType.SHA3_256;
					break;
				case "SHA3384":
					tact = TeaArtifactChecksumType.SHA3_384;
					break;
				case "SHA3512":
					tact = TeaArtifactChecksumType.SHA3_512;
					break;
				case "BLAKE2B256":
					tact = TeaArtifactChecksumType.BLAKE2B_256;
					break;
				case "BLAKE2B384":
					tact = TeaArtifactChecksumType.BLAKE2B_384;
					break;
				case "BLAKE2B512":
					tact = TeaArtifactChecksumType.BLAKE2B_512;
					break;
				case "BLAKE3":
					tact = TeaArtifactChecksumType.BLAKE3;
					break;
				default:
					log.warn("Unsupported by TEA ReARM digest: " + rearmDigestTypeString);
					break;
			}
			if (null != tact) {
				TeaArtifactChecksum tac = new TeaArtifactChecksum();
				tac.setAlgType(tact);
				tac.setAlgValue(digestEls[1]);
				tacList.add(tac);
			}
		}
		return tacList;
	}
	
	public TeaArtifact transformArtifactToTea(ArtifactData rearmAD) {
		TeaArtifact ta = new TeaArtifact();
		ta.setUuid(rearmAD.getUuid());
		TeaArtifactType tat = transformArtifactTypeToTea(rearmAD.getType());
		ta.setType(tat);
		ta.setName(rearmAD.getDisplayIdentifier());
		
		TeaArtifactFormat taf = new TeaArtifactFormat();
		var mediaTypeTag = rearmAD.getTags().stream().filter(t -> CommonVariables.MEDIA_TYPE_FIELD.equals(t.key())).findAny();
		if (mediaTypeTag.isPresent()) taf.setMimeType(mediaTypeTag.get().value());
		taf.setChecksums(transformDigestsToTea(rearmAD.getDigests()));
		taf.setUrl(relizaConfigProps.getBaseuri() + "/api/manual/v1/artifact/" + rearmAD.getUuid() + "/rawdownload");
		taf.setSignatureUrl(null); // TODO
		ta.setFormats(List.of(taf));
		return ta;
	}
    
}
