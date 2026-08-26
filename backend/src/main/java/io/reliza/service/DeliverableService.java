/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import io.reliza.model.dto.CarryForwardArm;
import io.reliza.model.dto.CarryForwardPairing;
import io.reliza.model.dto.CarryForwardTally;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CdxType;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.tea.TeaChecksumType;
import io.reliza.common.Utils.StripBom;
import io.reliza.common.SidPurlUtils;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.Deliverable;
import io.reliza.model.DeliverableData;
import io.reliza.model.DeliverableData.PackageType;
import io.reliza.model.OrganizationData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.DeliverableDto;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.repositories.DeliverableRepository;
import io.reliza.repositories.ReleaseRepository;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Service
public class DeliverableService {
	
	@Autowired
    private AuditService auditService;

	@Autowired
	private ReleaseRepository releaseRepository;
	
	@Autowired
    private BranchService branchService;
	
	@Autowired
    private GetOrganizationService getOrganizationService;
	
	@Autowired
    private GetComponentService getComponentService;

	@Autowired 
	private ArtifactService artifactService;

	@Autowired
	private SharedArtifactService sharedArtifactService;
	
	@Autowired 
	private GetDeliverableService getDeliverableService;
	
	@Autowired 
	private SharedReleaseService sharedReleaseService;
	
	@Autowired 
	private AcollectionService acollectionService;
			
	private final DeliverableRepository repository;
	
	DeliverableService(DeliverableRepository repository) {
	    this.repository = repository;
	}
	
	@Transactional
	public Deliverable createDeliverable(DeliverableDto deliverableDto, WhoUpdated wu) throws RelizaException{
		Deliverable d = null;
		if(null == deliverableDto.getType())
			throw new RelizaException("Deliverable must have type!");
		// resolve organization via branch
		Optional<BranchData> bdOpt = branchService.getBranchData(deliverableDto.getBranch());
		if (bdOpt.isPresent()) {
			d = new Deliverable();
			UUID component = bdOpt.get().getComponent();
			UUID orgUuid = getComponentService
										.getComponentData(component)
										.get()
										.getOrg();
			if (null == deliverableDto.getOrg())
				deliverableDto.setOrg(orgUuid);
			
			DeliverableData dd = DeliverableData.deliverableDataFactory(deliverableDto);
			Map<String,Object> recordData = Utils.dataToRecord(dd);
			d = saveDeliverable(d, recordData, wu);
		}
		return d;
	}
	
	@Transactional
	private Deliverable saveDeliverable (Deliverable d, Map<String, Object> recordData, WhoUpdated wu) {
		// TODO: add validation
		Optional<Deliverable> od = getDeliverableService.getDeliverable(d.getUuid());
		if (od.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.DELIVERABLES, d);
			d.setRevision(d.getRevision() + 1);
			d.setLastUpdatedDate(ZonedDateTime.now());
		}
		d.setRecordData(recordData);
		d = (Deliverable) WhoUpdated.injectWhoUpdatedData(d, wu);
		d = repository.save(d);
		DeliverableData dd = DeliverableData.dataFromRecord(d);
		Set<UUID> releaseIds = new HashSet<>();
		var ropt = sharedReleaseService.getReleaseByOutboundDeliverable(dd.getUuid(), dd.getOrg());
		if (ropt.isPresent()) releaseIds.add(ropt.get().getUuid());
		dd.getArtifacts().forEach(a -> {
			var releases = sharedReleaseService.findReleasesByReleaseArtifact(a, dd.getOrg());
			releases.forEach(r -> releaseIds.add(r.getUuid()));
		});
		releaseIds.forEach(r -> acollectionService.resolveReleaseCollection(r, wu));
		return d;
	}
	
	public List<UUID> prepareListofDeliverables(List<Map<String, Object>> deliverablesList,
			UUID branchUUID, String version, WhoUpdated wu) throws RelizaException{
		return prepareListofDeliverables(deliverablesList, branchUUID, version, false, wu);
	}
	
	public List<UUID> prepareListofDeliverables(List<Map<String, Object>> deliverablesList, UUID branchUUID,
			String version, Boolean addOnComplete, WhoUpdated wu) throws RelizaException {
		List<UUID> deliverables = new LinkedList<>();
		
		var bd = branchService.getBranchData(branchUUID).orElseThrow();
		ComponentData cd = getComponentService.getComponentData(bd.getComponent()).orElseThrow();
		OrganizationData od = getOrganizationService.getOrganizationData(bd.getOrg()).orElseThrow();
		for (Map<String, Object> deliverableItem : deliverablesList) {
			//extract arts
			@SuppressWarnings("unchecked")
			var arts = (List<Map<String, Object>>) deliverableItem.get("artifacts");
			deliverableItem.remove("artifacts");
			DeliverableDto deliverableDto = Utils.OM.convertValue(deliverableItem,DeliverableDto.class);
			deliverableDto.cleanLegacyDigests();
			
			String purl = SidPurlUtils.pickPreferredPurl(deliverableDto.getIdentifiers())
					.map(RearmIdentifier::getIdValue).orElse(null);
			RebomOptions rebomOptions = new RebomOptions(cd.getName(), od.getName(), version, ArtifactBelongsTo.DELIVERABLE, deliverableDto.getShaDigest(), StripBom.FALSE, purl);
			var artIds = artifactService.uploadListOfArtifacts(od, arts, rebomOptions, wu);
			deliverableDto.setArtifacts(artIds);			
			// if deliverable with this digest already exists for this org, do not create a new one (only software deliverables)
			List<Deliverable> deliverablesByDigest = new LinkedList<>();
			if (null != branchUUID && null != deliverableDto.getSoftwareMetadata() && 
					null != deliverableDto.getSoftwareMetadata().getDigestRecords() &&
					!deliverableDto.getSoftwareMetadata().getDigestRecords().isEmpty()) {
				deliverableDto.getSoftwareMetadata().getDigestRecords().forEach(dd -> {
					deliverablesByDigest.addAll(getDeliverableService.getDeliverablesByDigestRecord(dd, bd.getOrg()));
				});
			}
			
			if (deliverablesByDigest.isEmpty()) {		
				if (null != deliverableDto.getSoftwareMetadata() && 
						null == deliverableDto.getSoftwareMetadata().getPackageType() && deliverableDto.getType() == CdxType.CONTAINER) {
					var sdm = deliverableDto.getSoftwareMetadata();
					sdm.setPackageType(PackageType.CONTAINER);
					deliverableDto.setSoftwareMetadata(sdm);
				}
				
				deliverableDto.setBranch(branchUUID);
	
				// digests may be not present for failed deliverables / deliverable builds - TODO: think more
				if (StringUtils.isEmpty(deliverableDto.getVersion())) {
					deliverableDto.setVersion(version);
				}
				
				// will provide ability for artifacts to be updated to a release during complete or rejected status
				if (addOnComplete) {
					List<TagRecord> artTags = deliverableDto.getTags();
					if (artTags != null) {
						artTags.add(new TagRecord(CommonVariables.ADDED_ON_COMPLETE, "true", Removable.NO));
					} else {
						artTags = List.of(new TagRecord(CommonVariables.ADDED_ON_COMPLETE, "true", Removable.NO));
					}
					deliverableDto.setTags(artTags);
				}
				
				// if( null != deliverableDto.getBomInputs() && deliverableDto.getBomInputs().size() > 0){
				// 	List<InternalBom> boms = new ArrayList<>(); 
				// 	for(RawBomInput bomInput: deliverableDto.getBomInputs()){
				// 		var entryUUID =  rebomService.uploadSbom(bomInput.rawBom(),  new RebomOptions( cd.getName(), od.getName(), version, bomInput.bomType(), deliverableDto.getShaDigest())).uuid();
				// 		boms.add(new InternalBom(entryUUID, bomInput.bomType()));
				// 	}
				
				// 	// TODO should create artifacts here and set artifacts
				
				// 	// artDto.setInternalBoms(boms);
				// }
				Deliverable d = createDeliverable(deliverableDto, wu);
				deliverables.add(d.getUuid());
			} else {
				throw new RelizaException("A deliverable with this exact digest already belongs to another release, first in list = " + deliverablesByDigest.get(0).getUuid().toString());
			}
		}
		return deliverables;
	}

	public Boolean archiveDeliverable(UUID deliverableId, WhoUpdated wu) {
		Boolean archived = false;
		Optional<Deliverable> deliverable = getDeliverableService.getDeliverable(deliverableId);
		if (deliverable.isPresent()) {
			DeliverableData deliverableData = DeliverableData.dataFromRecord(deliverable.get());
			deliverableData.setStatus(StatusEnum.ARCHIVED);
			Map<String,Object> recordData = Utils.dataToRecord(deliverableData);
			saveDeliverable(deliverable.get(), recordData, wu);
			archived = true;
		}
		return archived;
	}

	@Transactional
	public boolean addArtifact(UUID deliverableId, UUID artifactUuid, WhoUpdated wu) throws RelizaException{
		Deliverable deliverable = getDeliverableService.getDeliverable(deliverableId).get();
		DeliverableData dd = DeliverableData.dataFromRecord(deliverable);
		List<UUID> artifacts = dd.getArtifacts();
		artifacts.add(artifactUuid);
		dd.setArtifacts(artifacts);
		Map<String,Object> recordData = Utils.dataToRecord(dd);
		saveDeliverable(deliverable, recordData, wu);
		// Attaching an ALREADY-SCANNED artifact changes a release's rollup without
		// changing the artifact's metrics, so the scan-time touch never fires and
		// the release row itself is untouched (only the deliverable is saved). The
		// retired BY_OUTBOUND_DELIVERABLES finder used to catch this by comparing
		// artifact.lastScanned against release.lastScanned; this touch replaces it.
		releaseRepository.touchReleasesByScannedDeliverableArtifact(artifactUuid.toString());
		return true;
	}
	@Transactional
	public boolean replaceArtifact(UUID deliverableId, UUID artifactIdToReplace, UUID artifactUuid, WhoUpdated wu) throws RelizaException{
		Deliverable deliverable = getDeliverableService.getDeliverable(deliverableId).get();
		DeliverableData dd = DeliverableData.dataFromRecord(deliverable);
		List<UUID> artifacts = dd.getArtifacts();
		artifacts.remove(artifactIdToReplace);
		artifacts.add(artifactUuid);
		dd.setArtifacts(artifacts);
		Map<String,Object> recordData = Utils.dataToRecord(dd);
		saveDeliverable(deliverable, recordData, wu);
		return true;
	}
	
	public void saveAll(List<Deliverable> artifacts){
		repository.saveAll(artifacts);
	}
	
	/**
	 * Migrates all deliverables from deprecated digests field to digestRecords field
	 * This method should be run once to migrate existing data
	 */
	@Transactional
	private void migrateDigestsToDigestRecords() {
		Iterable<Deliverable> allDeliverables = repository.findAll();
		int migratedCount = 0;
		
		for (Deliverable deliverable : allDeliverables) {
			DeliverableData dd = DeliverableData.dataFromRecord(deliverable);
			
			if (dd.getSoftwareMetadata() != null) {
				var softwareMetadata = dd.getSoftwareMetadata();
				Set<String> legacyDigests = softwareMetadata.getDigests();
				
				// Only migrate if there are legacy digests and no digest records yet
				if (legacyDigests != null && !legacyDigests.isEmpty() && 
					(softwareMetadata.getDigestRecords() == null || softwareMetadata.getDigestRecords().isEmpty())) {
					
					Set<DigestRecord> digestRecords = new LinkedHashSet<>();
					
					for (String digestString : legacyDigests) {
						String cleanedDigest = io.reliza.common.Utils.cleanString(digestString);
						if (StringUtils.isNotEmpty(cleanedDigest)) {
							String[] digestParts = cleanedDigest.split(":", 2);
							if (digestParts.length == 2) {
								String digestTypeString = digestParts[0];
								String digestValue = digestParts[1];
								
								TeaChecksumType checksumType = Utils.parseDigestType(digestTypeString);
								if (checksumType != null) {
									digestRecords.add(new DigestRecord(checksumType, digestValue, DigestScope.ORIGINAL_FILE));
								} else {
									// Log and skip unsupported digest types
									log.error("Skipping unsupported digest type: " + digestTypeString + " for deliverable: " + deliverable.getUuid());
								}
							}
						}
					}
					
					if (!digestRecords.isEmpty()) {
						softwareMetadata.setDigestRecords(digestRecords);
						dd.setSoftwareMetadata(softwareMetadata);
						
						Map<String, Object> recordData = io.reliza.common.Utils.dataToRecord(dd);
						saveDeliverable(deliverable, recordData, WhoUpdated.getAutoWhoUpdated());
						migratedCount++;
					}
				}
			}
		}
		
		log.info("Deliverable migration completed. Migrated " + migratedCount + " deliverables from digests to digestRecords.");
	}


	/**
	 * Carry each replaced BOM's findings onto its successor across a CI rebuild.
	 *
	 * <p>{@code addReleaseProgrammatic(rebuildRelease: true)} does not replace artifacts one by one:
	 * it CLEARS the variant's outbound deliverables and builds a fresh set, so there is no
	 * old-to-new artifact mapping to follow and it has to be inferred. Without that inference the
	 * new BOMs are unscanned, the release merge has nothing to merge, and the release reports zero
	 * findings until the scans land -- writing a phantom RESOLVED-then-APPEARED cycle into the
	 * timeline on the way back. See {@code ai-agents/findings-carry-forward-design.md}.
	 *
	 * <p>Pairing is by deliverable {@code displayIdentifier}, corroborated by the purl coordinate.
	 * Measured on production: displayIdentifier is populated on 3332/3332 deliverables, is UNIQUE
	 * within a release (zero collisions), and 240 distinct names cover 3332 deliverables -- 13.9x
	 * reuse, i.e. it is a stable lineage name rather than a per-build value. Purls are present on
	 * 2961/3332 (89%), so where BOTH sides carry one they must agree; a name match with conflicting
	 * purls is treated as no match, because attributing one component's vulnerabilities to another
	 * is worse than the bug being fixed.
	 *
	 * <p>Anything unpaired is simply left alone and collapses exactly as it does today. The failure
	 * mode has to stay "unchanged from today", never "newly wrong".
	 *
	 *
	 * <p><b>REQUIRES_NEW, and its callers defer it to afterCommit.</b> Both halves are load-bearing
	 * and neither works alone. Without REQUIRES_NEW a throw in here marks the CALLER's transaction
	 * rollback-only, so the caller's catch swallows the exception but not the flag and the whole
	 * rebuild dies at commit with UnexpectedRollbackException -- the exact opposite of the
	 * best-effort behaviour the callers claim. Without the afterCommit deferral, REQUIRES_NEW runs
	 * on a SEPARATE connection that cannot see the caller's uncommitted rows, so the freshly created
	 * replacement artifacts are invisible, every lookup comes back empty and the seam silently
	 * carries nothing while logging that it ran. Deferring until after the caller commits gives both:
	 * the rows are visible, and a failure cannot reach a transaction that no longer exists.
	 * @return the tally, so the decline counters are part of the CONTRACT rather than only reaching a
	 *   log line. They are how we learn in production that the heuristic is underperforming; a
	 *   counter that exists solely inside a log statement cannot be asserted, and an unasserted
	 *   diagnostic is one refactor away from silently reporting zero forever.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public CarryForwardTally carryFindingsAcrossRebuild(Collection<UUID> priorDeliverables,
			Collection<UUID> newDeliverables, WhoUpdated wu, UUID releaseUuid) {
		return sharedArtifactService.executeCarryForward(
				pairByDisplayIdentifier(priorDeliverables, newDeliverables),
				CarryForwardArm.DELIVERABLE, releaseUuid, wu);
	}

	/**
	 * Pair deliverables across a rebuild by displayIdentifier, corroborated by purl coordinate.
	 *
	 * <p>Pure: it decides WHAT to seed and hands the decision to the shared tail, which does the
	 * seeding, the outcome classification and the single log line. Deliverables need corroboration
	 * that the flat-artifact arms do not, because two different components can legitimately sit under
	 * one release and a name alone is not identity.
	 *
	 * <p><b>Ambiguity declines on BOTH sides.</b> A name appearing more than once among the priors OR
	 * among the successors is refused outright rather than resolved last-wins. A multi-arch build
	 * emits several deliverables under one displayIdentifier differing only by an arch qualifier, and
	 * {@code purlCoordinateBase} strips version AND qualifiers, so the purl check below would agree
	 * and one image's vulnerabilities would be seeded onto another's BOM. Mispairing is the worst
	 * outcome this seam can produce -- it attributes one component's findings to a different
	 * component, silently, and the resulting phantom events are written to an append-only event
	 * store with no purge. Declining costs only the carry-forward.
	 */
	CarryForwardPairing pairByDisplayIdentifier(Collection<UUID> priorDeliverables,
			Collection<UUID> newDeliverables) {
		if (null == priorDeliverables || priorDeliverables.isEmpty()
				|| null == newDeliverables || newDeliverables.isEmpty()) {
			return CarryForwardPairing.NOTHING;
		}
		Map<String, DeliverableData> priorByName = new LinkedHashMap<>();
		Set<String> ambiguousNames = new LinkedHashSet<>();
		for (UUID du : priorDeliverables) {
			getDeliverableService.getDeliverableData(du)
					.filter(dd -> StringUtils.isNotEmpty(dd.getDisplayIdentifier()))
					.ifPresent(dd -> {
						if (null != priorByName.put(dd.getDisplayIdentifier(), dd)) {
							ambiguousNames.add(dd.getDisplayIdentifier());
						}
					});
		}
		Map<String, Integer> successorNameCounts = new LinkedHashMap<>();
		for (UUID du : newDeliverables) {
			getDeliverableService.getDeliverableData(du)
					.filter(dd -> StringUtils.isNotEmpty(dd.getDisplayIdentifier()))
					.ifPresent(dd -> successorNameCounts.merge(dd.getDisplayIdentifier(), 1, Integer::sum));
		}
		successorNameCounts.forEach((name, count) -> {
			if (count > 1) ambiguousNames.add(name);
		});
		ambiguousNames.forEach(priorByName::remove);

		List<CarryForwardPairing.BomPair> paired = new ArrayList<>();
		int unchanged = 0;
		int unpaired = 0;
		int purlConflicts = 0;
		int ambiguousBoms = 0;
		int noBom = 0;
		for (UUID du : newDeliverables) {
			Optional<DeliverableData> odd = getDeliverableService.getDeliverableData(du);
			if (odd.isEmpty()) {
				// The row vanished between snapshot and here. Counted rather than skipped silently,
				// so seeded plus the declines always reconcile against candidates.
				unpaired++;
				continue;
			}
			DeliverableData successor = odd.get();
			DeliverableData predecessor = priorByName.get(successor.getDisplayIdentifier());
			if (null == predecessor) {
				// No counterpart, or its name was ambiguous and refused above.
				//
				// DELIBERATE CHANGE from the pre-refactor arm, which returned silently the moment the
				// prior map came back empty. That silence is wrong: an all-ambiguous prior set (the
				// multi-arch shape) means NOTHING gets seeded, so every rebuild of that component
				// really does collapse its releases to zero findings until their scans land, and the
				// counters are how that becomes visible at all.
				//
				// It surfaces as INFO here, not as an alert. There is no ERROR gate on this seam -- see
				// executeCarryForward -- because an unpairable component would raise one on every
				// single build with no action that clears it. The per-release signal an operator acts
				// on is [METRICS-LOSS-PROVENANCE], which prints firstScanned=null:findings=0 for a
				// failed pairing against findings=N for a seeded one, with the release uuid attached.
				unpaired++;
				continue;
			}
			String predPurl = purlCoordinateOf(predecessor);
			String succPurl = purlCoordinateOf(successor);
			if (null != predPurl && null != succPurl && !predPurl.equals(succPurl)) {
				// Same name, demonstrably different component. Decline rather than guess.
				purlConflicts++;
				continue;
			}
			List<UUID> predBoms = artifactService.bomsAmong(
					null != predecessor.getArtifacts() ? predecessor.getArtifacts() : List.of());
			List<UUID> succBoms = artifactService.bomsAmong(
					null != successor.getArtifacts() ? successor.getArtifacts() : List.of());
			if (predBoms.isEmpty() || succBoms.isEmpty()) {
				// Carries no SBOM. An ordinary shape with nothing to carry -- deliberately NOT counted
				// as ambiguous, because doing so put routine traffic into the counters an operator reads,
				// making healthy builds look like pairing failures.
				noBom++;
				continue;
			}
			if (predBoms.size() > 1 || succBoms.size() > 1) {
				// Several BOMs, no tiebreaker: artifact-level displayIdentifier would be the natural
				// one but production carries it on 0 of 5891 BOMs. A real decline.
				ambiguousBoms++;
				continue;
			}
			if (predBoms.get(0).equals(succBoms.get(0))) {
				// Not a decline and not a candidate: nothing was SWAPPED, so no carry-forward was ever
				// possible here. Counting it in the denominator broke the reconciliation invariant this
				// method asserts -- a rebuild that reused every BOM digest reported "0 of 3 seeded"
				// with every decline counter at zero, indistinguishable from a total pairing failure.
				// Excluding it also keeps the no-op silent, which is what the flat arm already does by
				// returning candidates=0 for the identical case.
				unchanged++;
				// Same artifact row on both sides: prepareListofDeliverables re-used it (same digest),
				// or CI supplied the existing uuid. Nothing was swapped, and seeding a row from ITSELF
				// would duplicate its whole previousVersions list on every rebuild.
				continue;
			}
			paired.add(new CarryForwardPairing.BomPair(predBoms.get(0), succBoms.get(0)));
		}
		// Both `unchanged` and `noBom` leave the denominator. Neither was ever a candidate: nothing
		// was swapped in one case and there is no SBOM to carry in the other. Subtracting only
		// `unchanged` left an attestation-only deliverable emitting "0 of 1 seeded" on every CI build
		// -- the exact noise the flat arm removed by returning candidates=0 for the identical shape,
		// and the eighth time on this change a fix landed on one arm of a symmetric pair.
		return new CarryForwardPairing(paired, newDeliverables.size() - unchanged - noBom, unpaired,
				purlConflicts, ambiguousBoms, noBom);
	}

	/** The deliverable's preferred purl reduced to its version-agnostic coordinate, or null. */
	private static String purlCoordinateOf(DeliverableData dd) {
		return SidPurlUtils.pickPreferredPurl(dd.getIdentifiers())
				.map(RearmIdentifier::getIdValue)
				.map(Utils::purlCoordinateBase)
				.orElse(null);
	}


}
