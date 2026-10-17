/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.StatusEnum;
import lombok.extern.slf4j.Slf4j;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.VcsRepository;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.tracker.TrackerProvider;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.BranchRepository;
import io.reliza.repositories.ComponentRepository;
import io.reliza.repositories.VcsRepositoryRepository;

@Slf4j
@Service
public class VcsRepositoryService {
	
	@Autowired
    private AuditService auditService;

	private final VcsRepositoryRepository repository;
	
	private final ComponentRepository componentRepository;
	
	private final BranchRepository branchRepository;
	
	VcsRepositoryService(VcsRepositoryRepository repository, ComponentRepository componentRepository, BranchRepository branchRepository) {
	    this.repository = repository;
	    this.componentRepository = componentRepository;
	    this.branchRepository = branchRepository;
	}
	
	public Optional<VcsRepository> getVcsRepository (UUID uuid) {
		Optional<VcsRepository> ovr = Optional.empty();
		if (null != uuid) {
			ovr = repository.findById(uuid);
		}
		return ovr;
	}
	
	public Optional<VcsRepositoryData> getVcsRepositoryData (UUID uuid) {
		Optional<VcsRepositoryData> vcsData = Optional.empty();
		Optional<VcsRepository> vr = getVcsRepository(uuid);
		if (vr.isPresent()) {
			vcsData = Optional
							.of(
									VcsRepositoryData
									.dataFromRecord(vr
										.get()
								));
		}
		return vcsData;
	}
	
	/**
	 * The VCS repository's data only if it belongs to {@code org}. Empty when either
	 * argument is null, the repository does not exist, or it lives in another
	 * organization, so callers refuse a missing and a foreign repository alike.
	 */
	public Optional<VcsRepositoryData> getVcsRepositoryData (UUID uuid, UUID org) {
		if (null == org) return Optional.empty();
		return getVcsRepositoryData(uuid).filter(vrd -> org.equals(vrd.getOrg()));
	}

	@Transactional
	public Optional<VcsRepository> getVcsRepositoryWriteLocked (UUID uuid) {
		return repository.findByIdWriteLocked(uuid);
	}
	
	/**
	 * Find VCS repository data by URI within an organization (private helper).
	 * 
	 * @param orgUuid Organization UUID
	 * @param uri VCS repository URI
	 * @return Optional of VcsRepository
	 */
	/**
	 * One repository row for one repository, case included.
	 *
	 * <p>Byte-equal first, which is the indexed path and the common case. On a miss, and only when
	 * the host is one that resolves owner and repository case-insensitively (GitHub and friends,
	 * per {@link TrackerProvider}), look again folding case.
	 *
	 * <p>Without that second look, {@code github.com/Acme/Widget} and {@code github.com/acme/widget}
	 * are two rows: a board source typed in lower case would mint a duplicate of the row a push
	 * created with capitals, and the documents-repository check would then compare against the
	 * wrong one. The row's stored URI is never rewritten -- this changes lookup, not storage.
	 *
	 * <p>Where several rows already match, the oldest wins and the duplicates are logged: merging
	 * them is an operator action, and silently picking a different one each time would be worse
	 * than either.
	 */
	private Optional<VcsRepository> findVcsRepositoryByOrgAndUri(UUID orgUuid, String uri) {
		String normalizedUri = Utils.normalizeVcsUri(uri);
		Optional<VcsRepository> exact = repository.findByOrgAndUri(orgUuid.toString(), normalizedUri);
		if (exact.isPresent() || !hostFoldsCase(normalizedUri)) return exact;
		List<VcsRepository> folded = repository.findByOrgAndUriFoldingCase(orgUuid.toString(), normalizedUri);
		if (folded.isEmpty()) return Optional.empty();
		if (folded.size() > 1) {
			log.error("Organization {} has {} repository rows differing only by case for {}: {}."
					+ " Using the oldest; merging them is an operator action.",
					orgUuid, folded.size(), normalizedUri,
					folded.stream().map(r -> VcsRepositoryData.dataFromRecord(r).getUri()).toList());
		}
		return Optional.of(folded.get(0));
	}

	/** Whether this URI's host is one the tracker enum knows to be case-insensitive. */
	private static boolean hostFoldsCase(String normalizedUri) {
		if (StringUtils.isBlank(normalizedUri)) return false;
		String canonical = Utils.canonicalVcsUri(normalizedUri);
		int slash = canonical.indexOf('/');
		String host = slash > 0 ? canonical.substring(0, slash) : canonical;
		return TrackerProvider.byHost(host).foldsProjectCase();
	}
	
	/**
	 * Find VCS repository by organization and URI.
	 * @param orgUuid Organization UUID
	 * @param uri VCS repository URI
	 * @return Optional of VcsRepositoryData
	 */
	public Optional<VcsRepositoryData> getVcsRepositoryDataByUri(UUID orgUuid, String uri) {
		// Same canonicalization as the createIfMissing lookup below: rows are STORED
		// in the cleaned form (no scheme/user@/git@/.git/scp-colon), so a read with
		// the raw form -- notably the ordinary '.git' clone URL every CI passes --
		// misses the row the create path finds. That split is what minted a new
		// component per CI run against the same VCS row (30+ observed in prod):
		// resolution never found the VCS, creation always did.
		// One canonicaliser for every registration path. The two-step form handled http, https
		// and the scp-style colon but not ssh:// or git://, so the same repository registered from
		// a board, from CI and from an agent's remote could land under different keys -- and the
		// uuid-pointer design depends on all of them converging on one row.
		uri = Utils.canonicalVcsUri(uri);
		Optional<VcsRepositoryData> vcsData = Optional.empty();
		Optional<VcsRepository> vr = findVcsRepositoryByOrgAndUri(orgUuid, uri);
		if (vr.isPresent()) {
			vcsData = Optional.of(VcsRepositoryData.dataFromRecord(vr.get()));
		}
		return vcsData;
	}
	
	public Optional<VcsRepository> getVcsRepositoryByUri (UUID orgUuid, String uri, String displayName, VcsType type, boolean createIfMissing, WhoUpdated wu) {
		// Canonicalize once so the lookup key matches what createVcsRepository
		// stores: normalizeVcsUri strips scheme and user@, cleanVcsUri then strips
		// git@ / trailing .git and the scp-style colon. Looking up with the raw
		// form missed the stored (cleaned) row for e.g. '.git'-suffixed URIs, so
		// createIfMissing re-inserted the same cleaned URI and collided with the
		// org+uri unique index -- durably poisoning the org's row now that
		// provisionVcsRepository commits via REQUIRES_NEW.
		// One canonicaliser for every registration path. The two-step form handled http, https
		// and the scp-style colon but not ssh:// or git://, so the same repository registered from
		// a board, from CI and from an agent's remote could land under different keys -- and the
		// uuid-pointer design depends on all of them converging on one row.
		uri = Utils.canonicalVcsUri(uri);
		Optional<VcsRepository> ovr = findVcsRepositoryByOrgAndUri(orgUuid, uri);
		if (ovr.isEmpty() && createIfMissing) {
			String vcsName = (displayName != null && !displayName.isEmpty()) ? displayName : Utils.deriveVcsNameFromUri(uri);
			ovr = Optional.of(createVcsRepository(vcsName, orgUuid, uri, type, wu));
		}
		return ovr;
	}

	/**
	 * Look up (or create) the org's VCS repository for {@code vcsUri} and return
	 * its committed UUID.
	 *
	 * <p>Runs in its own {@code REQUIRES_NEW} transaction so the row is durably
	 * committed before any downstream {@code REQUIRES_NEW} work reads it -- notably
	 * {@link SourceCodeEntryService#createSourceCodeEntry}, which validates
	 * {@code sceDto.vcs}. The auto-VCS {@code addrelease} path used to create the
	 * row in the caller's still-open outer tx, where the inner tx could not see it
	 * and NPE'd on the lookup (PR #217 dropped the lookup as a self-heal; this is
	 * the structural fix that lets it return safely).
	 *
	 * <p>Linking the repo to its branch is deliberately left to the caller's outer
	 * transaction. The branch row may already be write-locked or freshly inserted
	 * by that same outer tx (unarchive-on-addrelease, branch auto-create by name),
	 * so updating it from here -- on a separate connection -- would either
	 * self-deadlock against the outer tx's lock or fail to see the not-yet-committed
	 * row. Only the VCS repo, which is independent of the branch row, belongs in
	 * this REQUIRES_NEW boundary.
	 *
	 * <p>Accepted trade-off: if the caller's outer transaction later rolls back,
	 * this VCS row survives as a benign orphan -- the next {@code addrelease} reuses
	 * it by URI via {@link #getVcsRepositoryByUri}. There is no FK to
	 * garbage-collect against; the orphan is recoverable on retry.
	 *
	 * @return the committed VCS repository UUID
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public UUID provisionVcsRepository (UUID orgUuid, String vcsUri, VcsType vcsType, WhoUpdated wu) {
		return getVcsRepositoryByUri(orgUuid, vcsUri, null, vcsType, true, wu).get().getUuid();
	}
	
	public List<VcsRepository> listVcsReposByOrg(UUID orgUuid) {
		return repository.findVcsReposByOrganization(orgUuid
															.toString());
	}
	
	public List<VcsRepositoryData> listVcsRepoDataByOrg(UUID orgUuid) {
		List<VcsRepository> vcsRepoList = listVcsReposByOrg(orgUuid);
		return vcsRepoList
							.stream()
							.map(VcsRepositoryData::dataFromRecord)
							.collect(Collectors.toList());
	}
	
	public List<VcsRepository> getVcsRepositorys (Iterable<UUID> uuids) {
		return (List<VcsRepository>) repository.findAllById(uuids);
	}
	
	public List<VcsRepositoryData> getVcsRepositoryDataList (Iterable<UUID> uuids) {
		List<VcsRepository> branches = getVcsRepositorys(uuids);
		return branches.stream().map(VcsRepositoryData::dataFromRecord).collect(Collectors.toList());
	}

	public VcsRepository createVcsRepository (String name, UUID organization, 
													String uri, VcsType type, WhoUpdated wu) {
		// Validate VCS repository name
		if (StringUtils.isBlank(name)) {
			throw new IllegalArgumentException("VCS repository name cannot be empty");
		}
		
		VcsRepository vr = new VcsRepository();
		uri = Utils.cleanVcsUri(uri);
		VcsRepositoryData vrd = VcsRepositoryData.vcsRepositoryFactory(name, organization, uri, type);
		Map<String,Object> recordData = Utils.dataToRecord(vrd);
		return saveVcsRepository(vr, recordData, wu);
	}
	
	private VcsRepository saveVcsRepository (VcsRepository vr, Map<String,Object> recordData, WhoUpdated wu) {
		// let's add some validation here
		// TODO: add validation
		Optional<VcsRepository> ovr = getVcsRepository(vr.getUuid());
		if (ovr.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.VCS_REPOSITORIES, vr);
			vr.setRevision(vr.getRevision() + 1);
			vr.setLastUpdatedDate(ZonedDateTime.now());
		}
		vr.setRecordData(recordData);
		vr = (VcsRepository) WhoUpdated.injectWhoUpdatedData(vr, wu);
		return repository.save(vr);
	}
	
	public VcsRepository updateVcsRepository (VcsRepositoryData vrd, WhoUpdated wu) throws RelizaException {
		return updateVcsRepository(vrd.getUuid(), vrd.getName(), vrd.getUri(), wu);
	}
	
	@Transactional
	public VcsRepository updateVcsRepository (UUID vcsUuid, String name, String uri, WhoUpdated wu) throws RelizaException {
		VcsRepository vcsRepo = null;
		Optional<VcsRepository> vOpt = getVcsRepository(vcsUuid);
		if (vOpt.isPresent()) {
			VcsRepository v = vOpt.get();
			VcsRepositoryData vrd = VcsRepositoryData.dataFromRecord(v);
			if (StringUtils.isNotEmpty(name)) vrd.setName(name);
			if (StringUtils.isNotEmpty(uri)) vrd.setUri(uri);
			Map<String,Object> recordData = Utils.dataToRecord(vrd);
			vcsRepo = saveVcsRepository(v, recordData, wu);
		}
		return vcsRepo;
	}

	/**
	 * Replace the VCS repository's outputTriggers list with the supplied
	 * one. Validates the ≤1 / EXTERNAL_VALIDATION-only invariant before
	 * persisting; an empty list clears the trigger entirely.
	 */
	@Transactional
	public VcsRepository setVcsRepositoryOutputTriggers(UUID vcsUuid,
			java.util.List<io.reliza.model.ComponentData.ReleaseOutputEvent> triggers, WhoUpdated wu)
			throws RelizaException {
		Optional<VcsRepository> vOpt = getVcsRepository(vcsUuid);
		if (vOpt.isEmpty()) {
			throw new RelizaException("VCS repository not found: " + vcsUuid);
		}
		VcsRepositoryData.validateOutputTriggers(triggers);
		// Assign UUIDs to any new triggers so the policy/event audit trail
		// can reference them by stable id (mirrors ComponentData behaviour).
		java.util.List<io.reliza.model.ComponentData.ReleaseOutputEvent> normalized =
				new java.util.LinkedList<>();
		if (triggers != null) {
			for (var t : triggers) {
				if (t.getUuid() == null) t.setUuid(UUID.randomUUID());
				normalized.add(t);
			}
		}
		VcsRepositoryData vrd = VcsRepositoryData.dataFromRecord(vOpt.get());
		vrd.setOutputTriggers(normalized);
		Map<String, Object> recordData = Utils.dataToRecord(vrd);
		return saveVcsRepository(vOpt.get(), recordData, wu);
	}

//	public void moveVcsToNewOrg(UUID vcsRepository, UUID orgUuid, WhoUpdated wu) {
//		VcsRepository vcsr = getVcsRepository(vcsRepository).get();
//		VcsRepositoryData vcsrd = VcsRepositoryData.dataFromRecord(vcsr);
//		vcsrd.setOrg(orgUuid);
//		saveVcsRepository(vcsr, Utils.dataToRecord(vcsrd), wu);
//	}


	public void saveAll(List<VcsRepository> vcsRepositories){
		repository.saveAll(vcsRepositories);
	}
	
	/**
	 * Archive a VCS repository (soft delete).
	 * Only allowed if no active components or branches are attached to this VCS repository.
	 * 
	 * @param vcsUuid UUID of the VCS repository to archive
	 * @param wu WhoUpdated information
	 * @return true if archived successfully
	 * @throws RelizaException if components or branches are still attached or repository not found
	 */
	public Boolean archiveVcsRepository(UUID vcsUuid, WhoUpdated wu) throws RelizaException {
		Boolean archived = false;
		Optional<VcsRepository> ovr = getVcsRepository(vcsUuid);
		if (ovr.isPresent()) {
			// Check if any active components are attached to this VCS repository
			List<Component> attachedComponents = componentRepository.findComponentsByVcs(vcsUuid.toString());
			if (!attachedComponents.isEmpty()) {
				throw new RelizaException("Cannot archive VCS repository: " + attachedComponents.size() + 
					" active component(s) are still attached. Please remove or reassign components first.");
			}
			
			// Check if any active branches are attached to this VCS repository
			List<Branch> attachedBranches = branchRepository.findBranchesByVcs(vcsUuid.toString());
			if (!attachedBranches.isEmpty()) {
				throw new RelizaException("Cannot archive VCS repository: " + attachedBranches.size() + 
					" active branch(es) are still attached. Please remove or reassign branches first.");
			}
			
			VcsRepositoryData vrd = VcsRepositoryData.dataFromRecord(ovr.get());
			vrd.setStatus(StatusEnum.ARCHIVED);
			Map<String, Object> recordData = Utils.dataToRecord(vrd);
			saveVcsRepository(ovr.get(), recordData, wu);
			archived = true;
		}
		return archived;
	}
	
	/**
	 * Find VCS repository by URI including archived ones (for restore logic).
	 * 
	 * @param orgUuid Organization UUID
	 * @param uri VCS repository URI
	 * @return Optional of VcsRepository
	 */
	private Optional<VcsRepository> findVcsRepositoryByOrgAndUriIncludingArchived(UUID orgUuid, String uri) {
		String normalizedUri = Utils.normalizeVcsUri(uri);
		return repository.findByOrgAndUriIncludingArchived(orgUuid.toString(), normalizedUri);
	}
	
	/**
	 * Restore an archived VCS repository.
	 * 
	 * @param vcsUuid UUID of the VCS repository to restore
	 * @param wu WhoUpdated information
	 * @return restored VcsRepository
	 * @throws RelizaException if repository not found
	 */
	public VcsRepository restoreVcsRepository(UUID vcsUuid, WhoUpdated wu) throws RelizaException {
		Optional<VcsRepository> ovr = getVcsRepository(vcsUuid);
		if (ovr.isEmpty()) {
			throw new RelizaException("VCS repository not found");
		}
		VcsRepositoryData vrd = VcsRepositoryData.dataFromRecord(ovr.get());
		vrd.setStatus(StatusEnum.ACTIVE);
		Map<String, Object> recordData = Utils.dataToRecord(vrd);
		return saveVcsRepository(ovr.get(), recordData, wu);
	}
	
	/**
	 * Check if a VCS repository with the given URI exists (including archived).
	 * If it exists and is archived, restore it. Otherwise throw an error for duplicates.
	 * 
	 * @param name VCS repository name
	 * @param organization Organization UUID
	 * @param uri VCS repository URI
	 * @param type VCS type
	 * @param wu WhoUpdated information
	 * @return VcsRepository (either restored or newly created)
	 * @throws RelizaException if an active VCS repository with the same URI already exists
	 */
	public VcsRepository createOrRestoreVcsRepository(String name, UUID organization, 
			String uri, VcsType type, WhoUpdated wu) throws RelizaException {
		uri = Utils.cleanVcsUri(uri);
		// Check if an archived VCS repo with this URI already exists
		Optional<VcsRepository> existingArchived = findVcsRepositoryByOrgAndUriIncludingArchived(organization, uri);
		if (existingArchived.isPresent()) {
			VcsRepositoryData vrd = VcsRepositoryData.dataFromRecord(existingArchived.get());
			if (vrd.getStatus() == StatusEnum.ARCHIVED) {
				// Restore the archived repository
				vrd.setStatus(StatusEnum.ACTIVE);
				if (StringUtils.isNotEmpty(name)) vrd.setName(name);
				if (null != type) vrd.setType(type);
				Map<String, Object> recordData = Utils.dataToRecord(vrd);
				return saveVcsRepository(existingArchived.get(), recordData, wu);
			} else {
				// Already exists and is active - throw error
				throw new RelizaException("A VCS repository with this URI already exists in this organization.");
			}
		}
		// Create new VCS repository
		return createVcsRepository(name, organization, uri, type, wu);
	}
	
}
