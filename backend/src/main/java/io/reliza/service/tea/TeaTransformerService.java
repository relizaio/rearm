/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables;
import io.reliza.model.AcollectionData;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.BomFormat;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.ComponentData;
import io.reliza.model.DeliverableData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ParentRelease;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.tea.TeaArtifact;
import io.reliza.model.tea.TeaArtifactFormat;
import io.reliza.model.tea.TeaArtifactType;
import io.reliza.model.tea.TeaChecksum;
import io.reliza.model.tea.TeaCle;
import io.reliza.model.tea.TeaCleEvent;
import io.reliza.model.tea.TeaCleEventType;
import io.reliza.model.tea.TeaCleVersionSpecifier;
import io.reliza.model.tea.TeaCollection;
import io.reliza.model.tea.TeaCollectionBelongsToType;
import io.reliza.model.tea.TeaCollectionUpdateReason;
import io.reliza.model.tea.TeaComponent;
import io.reliza.model.tea.TeaComponentRef;
import io.reliza.model.tea.TeaComponentReleaseWithCollection;
import io.reliza.model.tea.TeaDiscoveryInfo;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.tea.TeaIdentifier;
import io.reliza.model.tea.TeaIdentifierType;
import io.reliza.model.tea.TeaProduct;
import io.reliza.model.tea.TeaProductRelease;
import io.reliza.model.tea.TeaRelease;
import io.reliza.model.tea.TeaReleaseDistribution;
import io.reliza.model.tea.TeaTeaServerInfo;
import io.reliza.service.AcollectionService;
import io.reliza.service.ArtifactService;
import io.reliza.service.GetComponentService;
import io.reliza.service.GetDeliverableService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;
import io.reliza.service.VariantService;
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
	
	@Autowired
	private VariantService variantService;
	
	@Autowired
	private GetDeliverableService getDeliverableService;
	
	private RelizaConfigProps relizaConfigProps;
	
	@Autowired
    public void setProps(RelizaConfigProps relizaConfigProps) {
        this.relizaConfigProps = relizaConfigProps;
    }
    
	/**
	 * TEA-boundary filter: keep only identifiers whose type name exists in the
	 * code-generated {@link TeaIdentifierType} (CPE/TEI/PURL/COMPLIANCE_DOCUMENT);
	 * ReARM-internal types (UDI, UDI_DI, UDI_PI, SERIAL, LOT) never leave via TEA.
	 */
	public static List<TeaIdentifier> toTeaIdentifiers(List<RearmIdentifier> identifiers) {
		List<TeaIdentifier> out = new LinkedList<>();
		if (identifiers == null) return out;
		for (RearmIdentifier ri : identifiers) {
			if (ri == null || ri.getIdType() == null || ri.getIdValue() == null) continue;
			TeaIdentifierType teaType;
			try {
				teaType = TeaIdentifierType.fromValue(ri.getIdType().name());
			} catch (IllegalArgumentException e) {
				continue;
			}
			TeaIdentifier ti = new TeaIdentifier();
			ti.setIdType(teaType);
			ti.setIdValue(ri.getIdValue());
			out.add(ti);
		}
		return out;
	}

	public TeaProduct transformProductToTea(ComponentData rearmCD) {
		if (rearmCD.getType() != ComponentType.PRODUCT) {
			throw new RuntimeException("Wrong component type");
		}
		TeaProduct tp = new TeaProduct();
		tp.setUuid(rearmCD.getUuid());
		tp.setName(rearmCD.getName());
		tp.setIdentifiers(toTeaIdentifiers(rearmCD.getIdentifiers()));
		return tp;
	}
	
	public TeaProductRelease transformProductReleaseToTea (ReleaseData rd) {
		UUID productUuid = rd.getComponent();
		ComponentData cd = getComponentService.getComponentData(productUuid).get();
		TeaProductRelease tpr = new TeaProductRelease();
		if (cd.getType() == ComponentType.COMPONENT) {
			TeaComponentRef tcr = new TeaComponentRef();
			tcr.setUuid(cd.getUuid());
			tcr.setRelease(rd.getUuid());
			tpr.setComponents(List.of(tcr));
		} else if (cd.getType() == ComponentType.PRODUCT) {
			tpr.setComponents(rd.getParentReleases().stream().map(pr -> transformParentReleaseToComponentRef(pr)).toList());	
		} else {
			throw new RuntimeException("Wrong component type");
		}
		tpr.setUuid(rd.getUuid());
		OffsetDateTime releaseDate = rd.getCreatedDate().toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS);
		tpr.setCreatedDate(releaseDate);
		tpr.setReleaseDate(releaseDate); // TODO (consider using date when release set to shipped or assembled - potentially make configurable)
		tpr.setIdentifiers(toTeaIdentifiers(rd.getIdentifiers()));
		tpr.setPreRelease(false);
		tpr.setProduct(productUuid);
		tpr.setProductName(cd.getName());
		tpr.setVersion(rd.getVersion());
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
		tc.setIdentifiers(toTeaIdentifiers(rearmCD.getIdentifiers()));
		return tc;
	}
	
	public TeaRelease transformReleaseToTea(ReleaseData rearmRD) {
		TeaRelease tr = new TeaRelease();
		tr.setUuid(rearmRD.getUuid());
		tr.setVersion(rearmRD.getVersion());
		tr.setPreRelease(false);
		OffsetDateTime releaseDate = rearmRD.getCreatedDate().toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS);
		tr.setReleaseDate(releaseDate); // TODO consider separate release date
		tr.setCreatedDate(releaseDate);
		tr.setIdentifiers(toTeaIdentifiers(rearmRD.getIdentifiers()));
		ComponentData cd = getComponentService.getComponentData(rearmRD.getComponent()).get();
		tr.setComponent(rearmRD.getComponent());
		tr.setComponentName(cd.getName());
		var distributions = gatherDistributionsPerRelease(rearmRD);
		tr.setDistributions(distributions);
		return tr;
	}
	
	private List<TeaReleaseDistribution> gatherDistributionsPerRelease(ReleaseData rearmRD) {
		List<TeaReleaseDistribution> distributions = new LinkedList<>();
		var vd = variantService.getBaseVariantForRelease(rearmRD);
		if (null != vd.getOutboundDeliverables() && !vd.getOutboundDeliverables().isEmpty()) {
			for (UUID delUuid : vd.getOutboundDeliverables()) {
				var deliverableData = getDeliverableService
											.getDeliverableData(delUuid)
											.get();
				var trd = transformDeliverableToTeaDistribution(deliverableData);
				distributions.add(trd);
			}
		}
		return distributions;
	}
	
	private TeaReleaseDistribution transformDeliverableToTeaDistribution (DeliverableData dd) {
		TeaReleaseDistribution trd = new TeaReleaseDistribution();
		trd.setDescription(dd.getNotes());
		trd.setIdentifiers(toTeaIdentifiers(dd.getIdentifiers()));
		if (null != dd.getSoftwareMetadata()) {
			var tcList = transformDigestRecordToTeaChecksum(dd.getSoftwareMetadata().getDigestRecords());
			trd.setChecksums(tcList);
			var downloadLinks = dd.getSoftwareMetadata().getDownloadLinks();
			if (null != downloadLinks && !downloadLinks.isEmpty()) {
				trd.setUrl(downloadLinks.iterator().next().getUri());
			}
		}
		// trd.setDistributionType(dd.getSoftwareMetadata().getPackageType()); TODO + refer to https://github.com/CycloneDX/transparency-exchange-api/issues/198
		// trd.setSignatureUrl(); // TODO
		return trd;
	}
	
	public TeaComponentReleaseWithCollection transformComponentReleaseWithCollectionToTea(ReleaseData rearmRD) {
		TeaComponentReleaseWithCollection tcr = new TeaComponentReleaseWithCollection();
		TeaRelease tr = transformReleaseToTea(rearmRD);
		tcr.setRelease(tr);
		var acd = acollectionService.getLatestCollectionDataOfRelease(rearmRD.getUuid());
		var teaAcd = transformAcollectionToTea(acd);
		tcr.setLatestCollection(teaAcd);
		return tcr;
	}
	
	public List<TeaDiscoveryInfo> performTeiDiscovery (String decodedTei) {
		Set<UUID> foundReleases = new LinkedHashSet<>();
		var releasesByTei = sharedReleaseService.findReleasesByOrgAndIdentifier(UserService.USER_ORG, RearmIdentifierType.TEI, decodedTei); // TODO handle other orgs
		if (!releasesByTei.isEmpty()) foundReleases.addAll(releasesByTei.stream().map(x -> x.getUuid()).toList());
		if (foundReleases.isEmpty()) {
			String[] teiEls = decodedTei.split(":");
			if ("uuid".equals(teiEls[2])) {
				String uuidStr = teiEls[teiEls.length - 1];
				UUID releaseUuid = UUID.fromString(uuidStr);
				var optRelease = sharedReleaseService.getRelease(releaseUuid, UserService.USER_ORG); // TODO handle other orgs
				if (optRelease.isPresent()) foundReleases.add(releaseUuid);
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
				var releasesByPurl = sharedReleaseService.findReleasesByOrgAndIdentifier(UserService.USER_ORG, RearmIdentifierType.PURL, purl); // TODO handle other orgs
				if (!releasesByPurl.isEmpty()) foundReleases.addAll(releasesByPurl.stream().map(x -> x.getUuid()).toList());
			}
		}

		List<TeaDiscoveryInfo> teaDiscoveryList = new LinkedList<>();
		if (!foundReleases.isEmpty()) {
			teaDiscoveryList = foundReleases.stream().map(x -> convertReleaseUuidToTdi(x)).toList();
		}
		return teaDiscoveryList;
	}
	
	private TeaDiscoveryInfo convertReleaseUuidToTdi (UUID releaseUuid) {
		TeaDiscoveryInfo tdi = new TeaDiscoveryInfo();
		tdi.setProductReleaseUuid(releaseUuid);
		TeaTeaServerInfo ttsi = new TeaTeaServerInfo();
		ttsi.setRootUrl(URI.create(getServerBaseUri()));
		ttsi.setVersions(List.of("0.4.0"));
		ttsi.setPriority((float) 1.0);
		tdi.setServers(List.of(ttsi));
		return tdi;
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
			case RISK_ASSESSMENT:
			case CODE_SCANNING_RESULT:
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
	
	private List<TeaChecksum> transformDigestRecordToTeaChecksum (Collection<DigestRecord> digestRecords) {
		List<TeaChecksum> tcList = new LinkedList<>();
		if (null != digestRecords && !digestRecords.isEmpty()) {
			digestRecords.stream().filter(d -> d.scope() == DigestScope.ORIGINAL_FILE).forEach(d -> {
				TeaChecksum tc = new TeaChecksum();
				tc.setAlgType(d.algo());
				tc.setAlgValue(d.digest());
				tcList.add(tc);	
			});
		}
		return tcList;
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
			taf.setDescription(String.format("%s%s Raw Artifact as Uploaded", bomFormatDisplay, rearmAD.getType()));
			var mediaTypeTag = rearmAD.getTags().stream().filter(t -> CommonVariables.MEDIA_TYPE_FIELD.equals(t.key())).findAny();
			if (mediaTypeTag.isPresent()) taf.setMediaType(resolveMediaType(rearmAD.getBomFormat(), mediaTypeTag.get().value()));
			taf.setUrl(relizaConfigProps.getBaseuri() + "/downloadArtifact/raw/" + rearmAD.getUuid());
			Optional<ArtifactData> optSignatureAD = artifactService.getArtifactSignature(rearmAD);
			if (optSignatureAD.isPresent()) {
				taf.setSignatureUrl(relizaConfigProps.getBaseuri() + "/downloadArtifact/raw/" + optSignatureAD.get().getUuid());
			} else taf.setSignatureUrl(null);
			var tcList = transformDigestRecordToTeaChecksum(rearmAD.getDigestRecords());
			taf.setChecksums(tcList);
			tafList.add(taf);
			
			if (rearmAD.getType() == ArtifactType.BOM && rearmAD.getBomFormat() == BomFormat.CYCLONEDX) {
				TeaArtifactFormat tafAugmented = new TeaArtifactFormat();
				tafAugmented.setDescription("CycloneDX BOM Artifact Augmented by Rebom");
				if (mediaTypeTag.isPresent()) tafAugmented.setMediaType(resolveMediaType(rearmAD.getBomFormat(), mediaTypeTag.get().value()));
				tafAugmented.setUrl(relizaConfigProps.getBaseuri() + "/downloadArtifact/augmented/" + rearmAD.getUuid());
				tafAugmented.setSignatureUrl(null); // TODO
				tafList.add(tafAugmented);
			}
		} else {
			for (var dlink : rearmAD.getDownloadLinks()) {
				TeaArtifactFormat taf = new TeaArtifactFormat();
				taf.setDescription("External Artifact");
				taf.setMediaType(resolveMediaType(rearmAD.getBomFormat(), dlink.getContent().getContentString()));
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
	
	/**
	 * Single-release CLE: events derived from this release's own LIFECYCLE
	 * updateEvents, plus a synthetic {@code released} when the release was
	 * created directly at GA (no <GA→GA transition exists in history). Used
	 * by the {@code /componentRelease/{uuid}/cle} endpoint and as the
	 * building block for the component-level aggregate.
	 */
	public TeaCle transformReleaseToCle(ReleaseData rd) {
		List<TeaCleEvent> events = new LinkedList<>(buildReleaseCleCandidates(rd));
		// Single release scope — no minute-merging across releases. Just
		// renumber + sort newest-first by effective.
		renumberAndSortNewestFirst(events);
		return new TeaCle(events);
	}

	/**
	 * Component-level CLE: chronological union of every release's LIFECYCLE
	 * events (one {@code released} per crossing &lt; GA → ≥ GA, plus the
	 * post-GA transitions) merged by (eventType, minute). Component-level
	 * NAME changes from {@code component.updateEvents} surface as
	 * {@code componentRenamed} events. {@code released} stays one event per
	 * release per spec (single {@code version} field, not a range).
	 */
	public TeaCle transformComponentToCle(ComponentData cd) {
		return transformComponentOrProductToCle(cd);
	}

	public TeaCle transformProductToCle(ComponentData cd) {
		return transformComponentOrProductToCle(cd);
	}

	private TeaCle transformComponentOrProductToCle(ComponentData cd) {
		List<TeaCleEvent> all = new ArrayList<>();
		// 300-cap mirrors the rest of the TEA surface; pagination is a TODO.
		List<ReleaseData> releases = sharedReleaseService.listReleaseDatasOfComponent(cd.getUuid(), 300, 0);
		for (ReleaseData rd : releases) {
			all.addAll(buildReleaseCleCandidates(rd));
		}
		all.addAll(buildComponentRenameCandidates(cd));
		List<TeaCleEvent> merged = mergeNonReleasedByMinute(all);
		renumberAndSortNewestFirst(merged);
		return new TeaCle(merged);
	}

	/**
	 * Convert a release's LIFECYCLE history into TEA CLE event candidates.
	 * <ul>
	 *   <li>Crossing {@code <GA → ≥GA} ⇒ a {@code released} event whose
	 *       {@code version} is the release version.</li>
	 *   <li>Any other lifecycle change ⇒ the matching post-release CLE
	 *       event type (END_OF_*, etc.) carrying a single-version
	 *       {@code versions} entry.</li>
	 *   <li>Release was minted directly at ≥ GA via CLI (no LIFECYCLE
	 *       events in history) ⇒ synthesize a {@code released} event at
	 *       {@code release.createdDate}.</li>
	 * </ul>
	 * Each candidate is stamped with id=-1; callers renumber after merging.
	 */
	private List<TeaCleEvent> buildReleaseCleCandidates(ReleaseData rd) {
		List<TeaCleEvent> out = new LinkedList<>();
		boolean sawAnyLifecycleEvent = false;
		boolean sawReleasedEvent = false;
		if (rd.getUpdateEvents() != null) {
			for (var ue : rd.getUpdateEvents()) {
				if (ue.rus() != ReleaseUpdateScope.LIFECYCLE) continue;
				sawAnyLifecycleEvent = true;
				ReleaseLifecycle oldLc = parseLifecycle(ue.oldValue());
				ReleaseLifecycle newLc = parseLifecycle(ue.newValue());
				if (newLc == null) continue;
				OffsetDateTime ts = ue.date().toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS);
				if (isCrossingIntoGa(oldLc, newLc)) {
					out.add(makeReleasedEvent(rd.getVersion(), ts));
					sawReleasedEvent = true;
				} else {
					TeaCleEventType cleType = mapLifecycleToCleEventType(newLc);
					if (cleType == null) continue;
					out.add(makeVersionEvent(cleType, rd.getVersion(), ts));
				}
			}
		}
		// Direct-create-at-GA fallback: if the release has no LIFECYCLE
		// events at all *and* its current lifecycle is ≥ GA (e.g.
		// `rearm addrelease --lifecycle ASSEMBLED` then never transitioned,
		// or — more typically — `--lifecycle GA` minted directly), still
		// emit a single `released` keyed at createdDate so the CLE story
		// reflects reality.
		if (!sawAnyLifecycleEvent && !sawReleasedEvent
				&& rd.getLifecycle() != null
				&& rd.getLifecycle().ordinal() >= ReleaseLifecycle.GENERAL_AVAILABILITY.ordinal()
				&& rd.getCreatedDate() != null) {
			OffsetDateTime ts = rd.getCreatedDate().toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS);
			out.add(makeReleasedEvent(rd.getVersion(), ts));
		}
		return out;
	}

	private List<TeaCleEvent> buildComponentRenameCandidates(ComponentData cd) {
		List<TeaCleEvent> out = new LinkedList<>();
		if (cd.getUpdateEvents() == null) return out;
		for (var ue : cd.getUpdateEvents()) {
			if (ue.cus() != ComponentData.ComponentUpdateScope.NAME) continue;
			if (ue.cua() != ComponentData.ComponentUpdateAction.CHANGED) continue;
			OffsetDateTime ts = ue.date().toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS);
			TeaCleEvent ev = new TeaCleEvent(-1, TeaCleEventType.COMPONENT_RENAMED, ts, ts);
			// Per spec 7.8 — `identifiers[]` carries the NEW identifiers for
			// the component. The TEA enum doesn't include NAME (only
			// CPE/TEI/PURL/COMPLIANCE_DOCUMENT), so we snapshot the component's
			// TEA-exportable identifiers at emit time and put the
			// human-readable old→new name in description.
			List<TeaIdentifier> renameIds = toTeaIdentifiers(cd.getIdentifiers());
			if (!renameIds.isEmpty()) {
				ev.setIdentifiers(renameIds);
			}
			if (StringUtils.isNotEmpty(ue.newValue())) {
				ev.setDescription(StringUtils.isNotEmpty(ue.oldValue())
						? "Renamed: " + ue.oldValue() + " → " + ue.newValue()
						: "Renamed to: " + ue.newValue());
			}
			out.add(ev);
		}
		return out;
	}

	private TeaCleEvent makeReleasedEvent(String version, OffsetDateTime ts) {
		TeaCleEvent ev = new TeaCleEvent(-1, TeaCleEventType.RELEASED, ts, ts);
		// Spec 7.1 — released carries a single `version` field (string),
		// never the `versions` array. Don't merge across releases.
		ev.setVersion(version);
		return ev;
	}

	private TeaCleEvent makeVersionEvent(TeaCleEventType type, String version, OffsetDateTime ts) {
		TeaCleEvent ev = new TeaCleEvent(-1, type, ts, ts);
		// Spec 7.2-7.6 — Version Events use `versions[]` of VERS specifiers.
		// One specifier per concrete version; merging happens in
		// mergeNonReleasedByMinute when multiple releases hit the same
		// (type, minute).
		ev.setVersions(new ArrayList<>(List.of(versionSpecifierFor(version))));
		return ev;
	}

	// vers:semver/<v> when the version parses as semver, else
	// vers:generic/<v>. We don't implement full VERS ranges in v1 — each
	// entry is a single concrete version. Range field is used because the
	// spec expects vers: URIs there; version field stays bare for max
	// compatibility with consumers that read either.
	private static final Pattern SEMVER_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+(?:[-+].*)?$");

	private static TeaCleVersionSpecifier versionSpecifierFor(String version) {
		TeaCleVersionSpecifier s = new TeaCleVersionSpecifier();
		s.setVersion(version);
		String scheme = (version != null && SEMVER_PATTERN.matcher(version).matches())
				? "semver"
				: "generic";
		s.setRange("vers:" + scheme + "/" + (version == null ? "" : version));
		return s;
	}

	private static boolean isCrossingIntoGa(ReleaseLifecycle oldLc, ReleaseLifecycle newLc) {
		if (newLc == null) return false;
		if (newLc.ordinal() < ReleaseLifecycle.GENERAL_AVAILABILITY.ordinal()) return false;
		// Old null = first lifecycle event we know of for this release. If
		// new ≥ GA, treat as a crossing.
		if (oldLc == null) return true;
		return oldLc.ordinal() < ReleaseLifecycle.GENERAL_AVAILABILITY.ordinal();
	}

	private static ReleaseLifecycle parseLifecycle(String s) {
		if (s == null) return null;
		try { return ReleaseLifecycle.valueOf(s); }
		catch (IllegalArgumentException e) { return null; }
	}

	/**
	 * Map a post-GA lifecycle state to its TEA CLE event type. Pre-GA states
	 * have no CLE counterpart — those changes don't surface in the CLE
	 * stream. CANCELLED / REJECTED are treated as terminal-end-of-life
	 * (per the spec, withdrawn is a meta event for revoking other events,
	 * not a lifecycle state).
	 */
	private TeaCleEventType mapLifecycleToCleEventType(ReleaseLifecycle lc) {
		if (lc == null) return null;
		return switch (lc) {
			case END_OF_MARKETING -> TeaCleEventType.END_OF_MARKETING;
			case END_OF_DISTRIBUTION -> TeaCleEventType.END_OF_DISTRIBUTION;
			case END_OF_SUPPORT -> TeaCleEventType.END_OF_SUPPORT;
			case END_OF_LIFE, CANCELLED, REJECTED -> TeaCleEventType.END_OF_LIFE;
			default -> null;
		};
	}

	/**
	 * Merge candidates of the same (TeaCleEventType, effective-truncated-to-minute)
	 * into a single TEA CLE event whose {@code versions[]} is the union
	 * of the source events' specifiers. {@code released} events are
	 * passed through unchanged (single-version field, never merged).
	 * COMPONENT_RENAMED stays one event per rename.
	 */
	private List<TeaCleEvent> mergeNonReleasedByMinute(List<TeaCleEvent> in) {
		Map<String, TeaCleEvent> bucket = new LinkedHashMap<>();
		List<TeaCleEvent> passthrough = new LinkedList<>();
		for (TeaCleEvent ev : in) {
			if (ev.getType() == TeaCleEventType.RELEASED
					|| ev.getType() == TeaCleEventType.COMPONENT_RENAMED) {
				passthrough.add(ev);
				continue;
			}
			OffsetDateTime minute = ev.getEffective() != null
					? ev.getEffective().truncatedTo(ChronoUnit.MINUTES)
					: null;
			String key = ev.getType().toString() + "|" + minute;
			TeaCleEvent existing = bucket.get(key);
			if (existing == null) {
				// First sighting at this (type, minute) — adopt as-is but
				// snap the effective timestamp down to the minute boundary
				// so consumers see a clean per-minute aggregate.
				if (minute != null) ev.setEffective(minute);
				if (ev.getPublished() != null) ev.setPublished(minute);
				bucket.put(key, ev);
			} else {
				// Subsequent — merge versions[]. Avoid duplicates by
				// comparing the bare version field (which is what we set in
				// versionSpecifierFor for both semver and generic schemes).
				if (ev.getVersions() != null) {
					for (var spec : ev.getVersions()) {
						boolean dup = existing.getVersions() != null
								&& existing.getVersions().stream().anyMatch(s ->
										java.util.Objects.equals(s.getVersion(), spec.getVersion()));
						if (!dup) {
							if (existing.getVersions() == null) existing.setVersions(new ArrayList<>());
							existing.getVersions().add(spec);
						}
					}
				}
			}
		}
		List<TeaCleEvent> out = new LinkedList<>(bucket.values());
		out.addAll(passthrough);
		return out;
	}

	// Spec 6.1 — `$schema` is a stable per-version URI; until ECMA publish a
	// canonical one, the spec uses cle.example.com as the documentation
	// placeholder. Match that so consumers can recognise the version.
	private static final String CLE_SCHEMA_URI = "https://cle.example.com/schema/cle-1.0.0.schema.json";

	/**
	 * Wrap a {@link TeaCle} (events list) with the CLE-spec-required
	 * top-level fields ({@code $schema}, {@code identifier},
	 * {@code updatedAt}) so the result is a self-contained CLE-1.0.0 JSON
	 * document. Used by the {@code exportComponentCleManual} /
	 * {@code exportReleaseCleManual} GraphQL queries.
	 *
	 * @param cle the events container produced by {@code transform*ToCle}
	 * @param identifiers PURL identifiers for the component this document
	 *                    describes; may be null/empty (then the field is
	 *                    emitted as an empty array)
	 */
	public tools.jackson.databind.JsonNode wrapAsCleDocument(TeaCle cle, java.util.List<RearmIdentifier> identifiers) {
		tools.jackson.databind.node.ObjectNode root = io.reliza.common.Utils.OM.createObjectNode();
		root.put("$schema", CLE_SCHEMA_URI);
		// identifier field — spec accepts string OR array. Always array for
		// uniformity. PURLs only, since spec ties this to the PURL format.
		tools.jackson.databind.node.ArrayNode idArr = root.putArray("identifier");
		if (identifiers != null) {
			for (var id : identifiers) {
				if (id == null) continue;
				if (id.getIdType() == RearmIdentifierType.PURL && id.getIdValue() != null) {
					idArr.add(id.getIdValue());
				}
			}
		}
		root.put("updatedAt", OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString());
		root.set("events", io.reliza.common.Utils.OM.valueToTree(cle.getEvents()));
		if (cle.getDefinitions() != null) {
			root.set("definitions", io.reliza.common.Utils.OM.valueToTree(cle.getDefinitions()));
		}
		return root;
	}

	private static void renumberAndSortNewestFirst(List<TeaCleEvent> events) {
		events.sort(Comparator.comparing(
				(TeaCleEvent e) -> e.getEffective(),
				Comparator.nullsLast(Comparator.reverseOrder())));
		int id = 0;
		for (TeaCleEvent ev : events) ev.setId(id++);
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
