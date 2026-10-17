/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.TxUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.TableName;

import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.BranchData;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.SourceCodeEntryData.SCEArtifact;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.SceDto;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SourceCodeEntryRepository;
import io.reliza.versioning.VersionApi;
import io.reliza.versioning.VersionApi.ActionEnum;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class SourceCodeEntryService {
	
	@Autowired
	private AcollectionService acollectionService;

	@Autowired
	private ReleaseRepository releaseRepository;

	@Autowired
	private ReleaseMetricsTouchService releaseMetricsTouchService;
	
	@Autowired
	private AuditService auditService;
	
	@Autowired
	private BranchService branchService;
	
	@Autowired
	private GetComponentService getComponentService;
	
	@Autowired
	private GetSourceCodeEntryService getSourceCodeEntryService;

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private VcsRepositoryService vcsRepositoryService;

	@Autowired
	private AgentService agentService;

	@Autowired
	private AgentSessionService agentSessionService;

	/**
	 * Lookup of artifact rows during SCE-attach dedup (see
	 * {@link #addArtifact}). Lazy because {@link ArtifactService} pulls
	 * in {@code SharedReleaseService} which depends on this service —
	 * eager injection would form a cycle.
	 */
	@Autowired
	@Lazy
	private ArtifactService artifactService;

	/**
	 * Self-injection so the routine can call {@link #createSourceCodeEntry} through
	 * the Spring proxy and pick up its REQUIRES_NEW propagation. A direct
	 * {@code this.*} call bypasses AOP — a unique-violation in the create would
	 * mark the routine's transaction rollback-only and prevent the catch-and-recover
	 * merge path from completing.
	 */
	@Autowired
	@Lazy
	private SourceCodeEntryService self;

	private final SourceCodeEntryRepository repository;
	
	private static final String NO_BRANCH_VCS_MESSAGE = "Branch does not have linked VCS repository and no VCS data provided";

	SourceCodeEntryService(SourceCodeEntryRepository repository) {
	    this.repository = repository;
	}

	@Transactional
	public Optional<SourceCodeEntryData> populateSourceCodeEntryByVcsAndCommit(
		SceDto sceDto,
		boolean createIfMissing,
		WhoUpdated wu) throws RelizaException {
			Optional<SourceCodeEntryData> osced = Optional.empty();
			VcsType vcsType = sceDto.getType();
			Optional<BranchData> obd =  branchService.getBranchData(sceDto.getBranch());
			if(obd.isEmpty())
				return null;
			
			BranchData bd = obd.get();
			// The caller names the org it writes for (the one it authorized: release create and
			// CycloneDX import both set it from their own branch/component), and the branch must
			// be in it. The org is never derived from the branch, which a client may have chosen.
			UUID sceOrg = sceDto.getOrganizationUuid();
			if (null == sceOrg || !sceOrg.equals(bd.getOrg())) {
				log.error("SECURITY: source code entry for org {} submitted on branch {} of another org",
						sceOrg, bd.getUuid());
				throw new RelizaException(CommonVariables.SCE_NOT_RESOLVABLE_MESSAGE);
			}
			// check vs branch vcs and warn if doesn't match
			UUID vcsUuidFromBranch = bd.getVcs();
			// Read-only lookup. SCE-level dedup that previously relied on this
			// pessimistic lock is now enforced by the V26 unique index on
			// source_code_entries (vcs, commit), with catch-and-recover in the
			// routine handling the race for the loser.
			// Org-scoped: a branch whose stored vcs is missing or belongs to another org
			// (written before updateBranch checked it) is treated as having no usable
			// repository, so the (vcs, commit) lookup below can only ever land on this
			// org's repository.
			Optional<VcsRepositoryData> ovrd = vcsRepositoryService.getVcsRepositoryData(vcsUuidFromBranch, sceOrg);
			if (null != vcsUuidFromBranch && ovrd.isEmpty()) {
				log.warn("LEGACY-REF: branch {} in org {} links vcs {} that is missing or outside the org; not using it",
						bd.getUuid(), sceOrg, vcsUuidFromBranch);
			}
			String vcsUri = sceDto.getUri();
			// Every refusal happens before anything is written: RelizaException is checked, so
			// a refusal after provisioning a repository or relinking the branch would commit
			// them. Find the repository this build would land on without creating it, and refuse
			// now if another org owns the entry for this commit there. The routine repeats the
			// check under the row lock for a concurrent create.
			UUID targetVcs = landingVcs(ovrd.map(VcsRepositoryData::getUuid).orElse(null), sceOrg, vcsUri);
			requireNoForeignSce(targetVcs, sceDto.getCommit(), sceOrg);
			UUID resolvedVcsUuid;
			if (StringUtils.isNotEmpty(vcsUri) && ovrd.isPresent() && !Utils.uriEquals(vcsUri, ovrd.get().getUri())) {
				// Deliberately a warn, not an error. uriEquals equates the common
				// spellings of one repository (scheme, git@/user@, .git, scp colon),
				// but URI forms it cannot reconcile are legitimately in flight on
				// this path -- Azure DevOps https vs ssh split forms, CycloneDX
				// pedigree commit URLs, UI-edited stored URIs -- and this check
				// never fired historically (uriEquals self-compared and was always
				// true), so rejecting here would break flows that work today. The
				// SCE binds to the branch's linked repository either way; the warn
				// makes a genuine cross-repo submission visible in logs.
				log.warn("Supplied VCS URI '{}' does not match branch {} linked VCS repository '{}' - binding SCE for commit {} to the branch's repository",
						vcsUri, bd.getUuid(), ovrd.get().getUri(), sceDto.getCommit());
				resolvedVcsUuid = ovrd.get().getUuid();
			} else if (ovrd.isEmpty() && StringUtils.isNotEmpty(vcsUri) && null != vcsType) {	// branch does not have a usable vcs repo
				// Create/commit the VCS repo in its own REQUIRES_NEW tx so it is
				// visible to the routine's REQUIRES_NEW createSourceCodeEntry lookup
				// -- creating it inline in this outer tx left the row invisible to
				// the inner tx and NPE'd the auto-VCS addrelease path (PR #217).
				resolvedVcsUuid = vcsRepositoryService.provisionVcsRepository(
						sceOrg, vcsUri, vcsType, wu);
				// Link the repo to the branch in THIS outer tx, not the REQUIRES_NEW
				// one above. The branch row may be write-locked or freshly inserted
				// by the outer tx (unarchive-on-addrelease, branch auto-create), so
				// a cross-connection REQUIRES_NEW update would self-deadlock against
				// that lock or miss the uncommitted row.
				BranchDto branchDto = BranchDto.builder()
											.uuid(bd.getUuid())
											.vcs(resolvedVcsUuid)
											.vcsBranch(sceDto.getVcsBranch())
											.build();
				branchService.updateBranch(branchDto, wu);
			} else if (ovrd.isEmpty() && null == bd.getVcs()) {
				// fail if no vcs data is provided and branch does not have vcs linked already
				throw new RelizaException(NO_BRANCH_VCS_MESSAGE);
			} else if (ovrd.isEmpty()) {
				// The branch links a repository that is missing or another org's, and the
				// request lacks the uri and type needed to relink it. One message for both.
				// ERROR: this fails a customer build until they supply the VCS data.
				log.error("LEGACY-REF: branch {} in org {} links vcs {} that is missing or outside the org and the request has no VCS uri and type to relink it; build refused",
						bd.getUuid(), sceOrg, vcsUuidFromBranch);
				throw new RelizaException(CommonVariables.BRANCH_VCS_UNUSABLE_MESSAGE);
			} else {
				// branch already carries a (matching) VCS, or the supplied uri
				// matched the existing one -- use the row resolved off the branch.
				resolvedVcsUuid = ovrd.get().getUuid();
			}

			// construct source code entry itself
			sceDto.setBranch(bd.getUuid());
			sceDto.setVcs(resolvedVcsUuid);
			Optional<SourceCodeEntry> osce = populateSourceCodeEntryByVcsAndCommitRoutine(sceDto, createIfMissing, wu);
			if (osce.isPresent()) osced = Optional.of(SourceCodeEntryData.dataFromRecord(osce.get()));
			return osced;
	}
	
	@Transactional
	private Optional<SourceCodeEntry> populateSourceCodeEntryByVcsAndCommitRoutine (SceDto sceDto, boolean createIfMissing, WhoUpdated wu) throws RelizaException {
		Optional<SourceCodeEntry> osce = repository.findByCommitAndVcs(sceDto.getCommit(), sceDto.getVcs().toString());
		if (osce.isEmpty() && !createIfMissing) return Optional.empty();
		if (osce.isEmpty()) {
			log.debug("osce is empty creating new ...");
			try {
				// The create runs REQUIRES_NEW (createSourceCodeEntryTx via the
				// proxy) -- keeps a unique-violation from the V26 (vcs, commit)
				// index from poisoning this routine's tx.
				return Optional.of(createSourceCodeEntry(sceDto, wu));
			} catch (DataIntegrityViolationException dive) {
				// Lost the race with a concurrent SCE create on the same (vcs, commit).
				// Re-read the winner's row and fall through to the merge branch so the
				// loser's incoming artifact list still lands on the canonical SCE.
				osce = repository.findByCommitAndVcs(sceDto.getCommit(), sceDto.getVcs().toString());
				if (osce.isEmpty()) throw dive;
				log.info("SCE create raced for commit {} on vcs {}, merging into existing {}",
						sceDto.getCommit(), sceDto.getVcs(), osce.get().getUuid());
			}
		}
		// Take a row-level write lock on the existing SCE before reading its
		// current artifacts. Concurrent monorepo addRelease calls land on the
		// same SCE and would otherwise both compute audit revision N+1 from a
		// stale revision N read, then collide on audit_revision_unique. The
		// lock serializes the merge → save → audit-write critical section
		// without affecting the create-side race (which is still handled by
		// the REQUIRES_NEW + DataIntegrityViolation catch path above).
		var sce = repository.findByIdWriteLocked(osce.get().getUuid()).orElseThrow();
		log.debug("Existing sce found, updating ...: {}", sce);
		SourceCodeEntryData existingSceData = SourceCodeEntryData.dataFromRecord(sce);
		requireSceOwnedBy(sce, sceDto.getOrganizationUuid(), sceDto.getCommit());
		SourceCodeEntryData sced = SourceCodeEntryData.scEntryDataFactory(sceDto);
		// Preserve commit metadata that an earlier addrelease populated when
		// the current caller didn't supply a value. Two consumers
		// addrelease-ing the same (vcs, commit) race — one with full
		// metadata, another with partial — and we don't want the partial
		// caller to stomp the proper values to null/blank. Mirrors the
		// artifact-merge logic below; same defence, different field set.
		sced.preserveScalarsFrom(existingSceData);
		// Re-resolve agent attribution against the (preserved) commit
		// message we'll persist. scEntryDataFactory leaves agent and
		// agentSession null — they're only set by the trailer parser,
		// which runs from createSourceCodeEntry but not from this merge
		// path. Without this call, a second addrelease against an
		// already-attributed SCE silently drops the agent linkage and
		// the downstream signature verifier lands on ERRORED ("trailer
		// present but no resolved agent"). Parser is no-op when the
		// message carries no trailers.
		UUID mergeResolvedSession = resolveAndAttributeTrailers(sced);
		if (null != existingSceData.getArtifacts() && !existingSceData.getArtifacts().isEmpty()) {
			if (null != sced.getArtifacts() && !sced.getArtifacts().isEmpty()) {
				// Same commit gets built twice (feature branch + ff-merge to main); each upload
				// produces fresh artifact UUIDs but identical canonical content. Dedup on
				// type + canonical-content signature so the merged SCE keeps existing arts.
				List<SCEArtifact> mergedArts = new ArrayList<>();
				Set<String> seenUuidComp = new HashSet<>();
				Set<String> seenSigs = new HashSet<>();
				for (SCEArtifact esda : existingSceData.getArtifacts()) {
					mergedArts.add(esda);
					seenUuidComp.add(esda.artifactUuid().toString() + esda.componentUuid());
					String sig = sceArtSemanticKey(esda);
					if (sig != null) seenSigs.add(sig);
				}
				for (SCEArtifact n : sced.getArtifacts()) {
					String uuidKey = n.artifactUuid().toString() + n.componentUuid();
					if (seenUuidComp.contains(uuidKey)) continue;
					String sig = sceArtSemanticKey(n);
					if (sig != null && seenSigs.contains(sig)) {
						log.info("SCE merge: dropping new art {} (semantic duplicate of an existing art on this SCE)", n.artifactUuid());
						continue;
					}
					mergedArts.add(n);
					seenUuidComp.add(uuidKey);
					if (sig != null) seenSigs.add(sig);
				}
				sced.setArtifacts(mergedArts);
			} else {
				sced.setArtifacts(existingSceData.getArtifacts());
			}
		}
		Map<String,Object> recordData = Utils.dataToRecord(sced);
		SourceCodeEntry mergedSce = saveSourceCodeEntry(sce, recordData, wu);
		// Mirror createSourceCodeEntry's post-save reverse-index update so
		// the session's commit list picks up a merge-resolved attribution
		// just like a fresh-create one would. Deferred to after this tx
		// commits and run on its own connection, so it can neither hold
		// the session row lock for the rest of the request nor mark this
		// tx rollback-only if it fails.
		if (mergeResolvedSession != null) {
			scheduleRecordCommit(mergeResolvedSession, mergedSce.getUuid(), wu);
		}
		return Optional.of(mergedSce);
	}

	/**
	 * Read-only check of a whole build before any of its commits is recorded: refuses it when
	 * any commit would land on an entry {@code bd}'s org does not own, or when the branch has no
	 * usable repository and the first commit does not supply a uri and type to link one (the
	 * write path's refusals, in its order). Each commit is recorded on
	 * its own (repository provisioning and entry creation commit in their own transactions, and
	 * RelizaException is checked, so it does not roll the rest back), so a refusal found at a
	 * later commit would leave the earlier commits, a branch relink and uploaded artifacts behind.
	 * The per-commit check in populateSourceCodeEntryByVcsAndCommit stays, for a concurrent
	 * create.
	 *
	 * @param sces the build's entries in the order they will be recorded; nulls are skipped
	 */
	public void requireBuildRecordable(BranchData bd, List<SceDto> sces) throws RelizaException {
		UUID org = bd.getOrg();
		UUID branchVcs = vcsRepositoryService.getVcsRepositoryData(bd.getVcs(), org)
				.map(VcsRepositoryData::getUuid).orElse(null);
		boolean linked = null != branchVcs;
		for (SceDto sceDto : sces) {
			if (null == sceDto) continue;
			UUID targetVcs = linked ? branchVcs : landingVcs(null, org, sceDto.getUri());
			requireNoForeignSce(targetVcs, sceDto.getCommit(), org);
			boolean relinks = StringUtils.isNotEmpty(sceDto.getUri()) && null != sceDto.getType();
			if (!linked && !relinks && StringUtils.isNotEmpty(sceDto.getCommit())) {
				throw new RelizaException(null == bd.getVcs() ? NO_BRANCH_VCS_MESSAGE
						: CommonVariables.BRANCH_VCS_UNUSABLE_MESSAGE);
			}
			if (!linked && relinks) {
				// this commit links the branch to the repository at its uri (an existing one, or a
				// new and so empty one), and the later commits land there
				branchVcs = targetVcs;
				linked = true;
			}
		}
	}

	/**
	 * The repository a commit is recorded on for {@code org}, without creating it: the branch's
	 * usable repository, else the org's repository at {@code vcsUri}; null when neither exists yet.
	 */
	private UUID landingVcs(UUID usableBranchVcs, UUID org, String vcsUri) {
		UUID targetVcs = usableBranchVcs;
		if (null == targetVcs && StringUtils.isNotEmpty(vcsUri)) {
			targetVcs = vcsRepositoryService.getVcsRepositoryDataByUri(org, vcsUri)
					.map(VcsRepositoryData::getUuid).orElse(null);
		}
		return targetVcs;
	}

	/** Refuses when the entry for {@code commit} on {@code vcs} exists and {@code org} does not own it. */
	private void requireNoForeignSce(UUID vcs, String commit, UUID org) throws RelizaException {
		if (null != vcs && StringUtils.isNotEmpty(commit)) {
			Optional<SourceCodeEntry> existing = repository.findByCommitAndVcs(commit, vcs.toString());
			if (existing.isPresent()) {
				requireSceOwnedBy(existing.get(), org, commit);
			}
		}
	}

	/**
	 * Refuses to merge into or re-home an existing entry owned by another org.
	 *
	 * <p>The (vcs, commit) key is unique across orgs and the caller's vcs is its own org's
	 * (checked by populateSourceCodeEntryByVcsAndCommit), so an existing entry owned by another
	 * org can only be a stored cross-org row: one created through a branch that pointed at this
	 * org's repository before updateBranch checked the reference. Merging would hand that org's
	 * artifacts to the caller, and saving would re-home the entry away from it. A second entry
	 * cannot be created for the same (vcs, commit), and silently dropping the commit from the
	 * release would lose data, so the build is refused. Ownership is decided by
	 * GetSourceCodeEntryService.belongsTo, the write rule for entries.
	 */
	private void requireSceOwnedBy(SourceCodeEntry sce, UUID org, String commit) throws RelizaException {
		if (!getSourceCodeEntryService.belongsTo(SourceCodeEntryData.dataFromRecord(sce), org)) {
			log.error("SECURITY: source code entry {} for commit {} belongs to another org; refusing to merge into it for org {}",
					sce.getUuid(), commit, org);
			throw new RelizaException(CommonVariables.SCE_NOT_RESOLVABLE_MESSAGE);
		}
	}

	/**
	 * Read-only: the commit message stored for {@code commit} on the branch's own-org
	 * repository, if this org has recorded it. Never writes and never throws for a missing,
	 * foreign or legacy reference -- those read as "no message".
	 *
	 * @param bd the authorized branch the caller is versioning
	 */
	public Optional<String> findStoredCommitMessage(BranchData bd, String commit) {
		if (null == bd || StringUtils.isEmpty(commit)) return Optional.empty();
		Optional<VcsRepositoryData> ovrd = vcsRepositoryService.getVcsRepositoryData(bd.getVcs(), bd.getOrg());
		if (ovrd.isEmpty()) return Optional.empty();
		return repository.findByCommitAndVcs(commit, ovrd.get().getUuid().toString())
				.map(SourceCodeEntryData::dataFromRecord)
				.filter(sced -> getSourceCodeEntryService.belongsTo(sced, bd.getOrg()))
				.map(SourceCodeEntryData::getCommitMessage)
				.filter(StringUtils::isNotEmpty);
	}

	/**
	 * Create an SCE in its own committed transaction, then queue the
	 * session reverse-index write. Deliberately NOT transactional: the
	 * persist happens in {@link #createSourceCodeEntryTx} (REQUIRES_NEW,
	 * via the proxy), and the reverse-index write is scheduled only once
	 * that has returned, so it binds to the caller's transaction (the
	 * request-wide one on the addrelease path) or runs inline when there
	 * is none (direct GraphQL mutation). Scheduling it from inside the
	 * REQUIRES_NEW tx would fire the hook while that tx's connection is
	 * still bound, so one thread would hold three pooled connections at
	 * once (caller + create + reverse-index) instead of two.
	 */
	public SourceCodeEntry createSourceCodeEntry (SceDto sceDto, WhoUpdated wu) throws RelizaException {
		SourceCodeEntry saved = self.createSourceCodeEntryTx(sceDto, wu);
		// Record the SCE on the session's reverse index outside the create
		// tx. Failure there is logged but cannot roll back the SCE creation
		// -- the forward pointer on the SCE row is the source of truth for
		// the read path, the reverse index is an optimisation.
		UUID resolvedSession = SourceCodeEntryData.dataFromRecord(saved).getAgentSession();
		if (resolvedSession != null) {
			scheduleRecordCommit(resolvedSession, saved.getUuid(), wu);
		}
		return saved;
	}

	// REQUIRES_NEW so a unique-violation on (vcs, commit) rolls back only this
	// attempt's tx, letting the routine's catch-and-recover proceed. Callers
	// go through createSourceCodeEntry, which adds the reverse-index write.
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public SourceCodeEntry createSourceCodeEntryTx (SceDto sceDto, WhoUpdated wu) throws RelizaException {
		// Phase 2 follow-up to PR #217: the VCS row is now committed before this
		// REQUIRES_NEW tx runs -- on the auto-VCS path by
		// VcsRepositoryService.provisionVcsRepository, on the direct mutation path
		// by the caller's pre-existing repo -- so this lookup is safe and restores the
		// sanity check the self-heal had to drop. Produces a clean RelizaException
		// instead of the opaque SERVICE_ERROR an unread .get() would have thrown.
		VcsRepositoryData vrd = vcsRepositoryService.getVcsRepositoryData(sceDto.getVcs())
				.orElseThrow(() -> new RelizaException("VCS " + sceDto.getVcs() + " not found"));
		SourceCodeEntry sce = new SourceCodeEntry();
		// resolve organization via branch
		Optional<BranchData> bdOpt = branchService.getBranchData(sceDto.getBranch());
		if (bdOpt.isPresent()) {
			UUID projUuid = bdOpt.get().getComponent();
			UUID orgUuid = getComponentService
										.getComponentData(projUuid)
										.get()
										.getOrg();
			if (null == sceDto.getOrganizationUuid())
				sceDto.setOrganizationUuid(orgUuid);
		}

		// Defense-in-depth: never let a source code entry in one org bind to
		// another org's VCS repository. The vcs uuid arrives on the inbound dto
		// and the org is resolved above, so compare them before persisting.
		if (null != sceDto.getOrganizationUuid() && !vrd.getOrg().equals(sceDto.getOrganizationUuid())) {
			throw new RelizaException("VCS " + sceDto.getVcs()
					+ " belongs to a different organization than the source code entry");
		}

		SourceCodeEntryData sced = SourceCodeEntryData.scEntryDataFactory(sceDto);

		// Parse the PR 2 agent commit trailers off the message and resolve
		// them to a (leaf agent, root session) pair before saving the SCE.
		// Resolution failures are non-fatal: we want the SCE to land
		// regardless of whether its agent attribution survives —
		// untracked commits are still releaseable. See
		// {@code ai-plans/agentic/README.md} §6-7 for the contract.
		// The resolved session lands on the SCE row as the forward pointer;
		// createSourceCodeEntry reads it back for the reverse index.
		resolveAndAttributeTrailers(sced);

		Map<String,Object> recordData = Utils.dataToRecord(sced);
		return saveSourceCodeEntry(sce, recordData, wu);
	}

	/**
	 * Push an SCE onto the session's reverse index without ever doing it
	 * inside the caller's transaction. Same shape as
	 * {@code SharedReleaseService.scheduleFindingChangeEventEmit}: with a
	 * transaction active the write is registered as an {@code afterCommit}
	 * synchronization, so it runs only once the SCE row is durable and the
	 * caller's connection is the only one still bound; otherwise it runs
	 * inline. Either way {@link AgentSessionService#recordCommit} is
	 * REQUIRES_NEW (see its javadoc for the self-deadlock that motivates
	 * this), and a failure is caught and logged, never propagated into the
	 * caller's tx.
	 */
	private void scheduleRecordCommit(UUID sessionUuid, UUID sceUuid, WhoUpdated wu) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					recordCommitBestEffort(sessionUuid, sceUuid, wu);
				}
			});
		} else {
			recordCommitBestEffort(sessionUuid, sceUuid, wu);
		}
	}

	/**
	 * Invokes the REQUIRES_NEW reverse-index write, swallowing any failure.
	 * Logged with the stack trace: the original deadlock hid for weeks
	 * behind a message-only line that did not say which statement had
	 * timed out or from where.
	 */
	private void recordCommitBestEffort(UUID sessionUuid, UUID sceUuid, WhoUpdated wu) {
		try {
			agentSessionService.recordCommit(sessionUuid, sceUuid, wu);
		} catch (Exception e) {
			log.warn("Failed to record SCE {} on session {}", sceUuid, sessionUuid, e);
		}
	}

	/**
	 * Parse {@code ReARM-Agent} + {@code ReARM-Agentic-Session} trailers
	 * off the SCE's commit message, mutate the SCE data in place with
	 * the resolved leaf-agent + root-session pointers, and return the
	 * session uuid (or null) so the caller can record the SCE on the
	 * reverse index after save.
	 *
	 * Returns null and logs warnings for any of: missing trailers,
	 * malformed agent uuid, unknown agent, unknown session, agent and
	 * session orgs disagreeing with the SCE org. The SCE is still
	 * saved without attribution in all these cases.
	 */
	private UUID resolveAndAttributeTrailers(SourceCodeEntryData sced) {
		String msg = sced.getCommitMessage();
		if (StringUtils.isBlank(msg)) {
			sced.setAttributionState(SourceCodeEntryData.AttributionState.UNATTRIBUTED);
			return null;
		}
		AgentCommitTrailerParser.AgentCommitAttribution parsed;
		try {
			parsed = AgentCommitTrailerParser.parse(msg);
		} catch (RelizaException e) {
			log.warn("Rejected trailer block on commit {}: {}", sced.getCommit(), e.getMessage());
			rejectAttribution(sced, "Trailer block invalid: " + e.getMessage());
			return null;
		}
		if (!parsed.hasAgent() && !parsed.hasSession()) {
			sced.setAttributionState(SourceCodeEntryData.AttributionState.UNATTRIBUTED);
			return null;
		}
		if (!parsed.isFullAttribution()) {
			// Either trailer present in isolation: claim is incomplete,
			// so it can't resolve — flag as REJECTED so policies can gate.
			String which = parsed.agentUuid() == null ? "ReARM-Agent" : "ReARM-Agentic-Session";
			rejectAttribution(sced, "Partial attribution — missing " + which + " trailer");
			return null;
		}
		if (parsed.clientSessionId() != null
				&& !AgentCommitTrailerParser.CLIENT_SESSION_ID_PATTERN.matcher(parsed.clientSessionId()).matches()) {
			rejectAttribution(sced, "ReARM-Agentic-Session value '" + parsed.clientSessionId()
					+ "' does not match the canonical clientSessionId pattern "
					+ AgentCommitTrailerParser.CLIENT_SESSION_ID_PATTERN.pattern());
			return null;
		}
		var agentOpt = agentService.getAgentData(parsed.agentUuid());
		if (agentOpt.isEmpty()) {
			log.warn("ReARM-Agent {} not found — SCE {} saved without attribution",
					parsed.agentUuid(), sced.getCommit());
			rejectAttribution(sced, "ReARM-Agent " + parsed.agentUuid() + " not found");
			return null;
		}
		// Past this point the agent uuid is valid AND the agent exists in
		// DB. Pin it on the SCE so the signature verifier can still match
		// the commit signature to the agent's enrolled keys even if a
		// later step (cross-org, session not found, …) rejects the
		// overall attribution. CEL gates discriminate via
		// commit.attribution.state instead of commit.agent presence.
		var agent = agentOpt.get();
		sced.setAgent(parsed.agentUuid());
		if (sced.getOrg() != null && !sced.getOrg().equals(agent.getOrg())) {
			log.warn("ReARM-Agent {} belongs to org {} but SCE org is {} — refusing cross-org attribution",
					parsed.agentUuid(), agent.getOrg(), sced.getOrg());
			rejectAttribution(sced, "ReARM-Agent " + parsed.agentUuid()
					+ " belongs to a different org than the SCE — cross-org attribution refused");
			return null;
		}
		io.reliza.model.AgentData root;
		try {
			root = agentService.resolveRoot(agent);
		} catch (RelizaException e) {
			log.warn("Could not resolve root for agent {}: {}", parsed.agentUuid(), e.getMessage());
			rejectAttribution(sced, "Could not resolve root for ReARM-Agent " + parsed.agentUuid()
					+ ": " + e.getMessage());
			return null;
		}
		var sessionOpt = agentSessionService.getByClientSessionId(
				root.getOrg(), root.getUuid(), parsed.clientSessionId());
		if (sessionOpt.isEmpty()) {
			log.warn("Session clientId='{}' not found under root agent {} — SCE {} saved without attribution",
					parsed.clientSessionId(), root.getUuid(), sced.getCommit());
			rejectAttribution(sced, "ReARM-Agentic-Session '" + parsed.clientSessionId()
					+ "' not found under root agent " + root.getUuid());
			return null;
		}
		var session = sessionOpt.get();
		// BLOCKED sessions are persisted-but-terminal: they exist in the
		// audit (so operators see the rejected attempt + policyEvents),
		// but commits can't bind to them. Surface as REJECTED attribution
		// so policies can gate symmetric with other rejection reasons,
		// rather than letting the trailer silently resolve to a session
		// that was never allowed to do work.
		if (session.getStatus() == io.reliza.model.AgentSessionData.SessionStatus.BLOCKED) {
			log.warn("Session clientId='{}' under root agent {} is BLOCKED — refusing attribution on SCE {}",
					parsed.clientSessionId(), root.getUuid(), sced.getCommit());
			rejectAttribution(sced, "ReARM-Agentic-Session '" + parsed.clientSessionId()
					+ "' is BLOCKED (failed an INPUT policy on init). Commits cannot resolve to a"
					+ " BLOCKED session; agent should retry with a fresh clientSessionId once the"
					+ " policy is satisfied.");
			return null;
		}
		sced.setAgent(parsed.agentUuid());
		sced.setAgentSession(session.getUuid());
		sced.setAttributionState(SourceCodeEntryData.AttributionState.RESOLVED);
		sced.setAttributionReason(null);
		log.info("Attributed SCE commit={} to session {} (agent={}, root={})",
				sced.getCommit(), session.getUuid(), parsed.agentUuid(), root.getUuid());
		return session.getUuid();
	}

	private void rejectAttribution(SourceCodeEntryData sced, String reason) {
		sced.setAttributionState(SourceCodeEntryData.AttributionState.REJECTED);
		sced.setAttributionReason(reason);
		// agentSession is the resolved-only pointer — clear it. agent
		// is set earlier IFF the agent uuid validated AND the agent
		// exists in DB (verifier needs that to pick the right key
		// scope), so we leave it as the caller set it.
		sced.setAgentSession(null);
	}

	@Transactional
	public void updateVcsTag(UUID sceUuid, String vcsTag, WhoUpdated wu) throws RelizaException {
		Optional<SourceCodeEntry> osce = getSourceCodeEntryService.getSourceCodeEntry(sceUuid);
		if (osce.isEmpty()) {
			throw new RelizaException("SCE not found: " + sceUuid);
		}
		SourceCodeEntry sce = osce.get();
		SourceCodeEntryData sced = SourceCodeEntryData.dataFromRecord(sce);
		if (StringUtils.isEmpty(sced.getVcsTag())) {
			SceDto updateDto = SceDto.builder()
					.uuid(sceUuid)
					.branch(sced.getBranch())
					.vcs(sced.getVcs())
					.vcsBranch(sced.getVcsBranch())
					.commit(sced.getCommit())
					.commitMessage(sced.getCommitMessage())
					.commitAuthor(sced.getCommitAuthor())
					.commitEmail(sced.getCommitEmail())
					.vcsTag(vcsTag)
					.organizationUuid(sced.getOrg())
					.build();
			SourceCodeEntryData updatedSced = SourceCodeEntryData.scEntryDataFactory(updateDto);
			saveSourceCodeEntry(sce, Utils.dataToRecord(updatedSced), wu);
		}
	}

	@Transactional
	public boolean addArtifact(UUID sceUuid, SCEArtifact art, WhoUpdated wu) throws RelizaException{
		SourceCodeEntry sce = getSourceCodeEntryService.getSourceCodeEntry(sceUuid).get();
		SourceCodeEntryData sced = SourceCodeEntryData.dataFromRecord(sce);

		// Defense-in-depth for the GraphQL addArtifact mutation; the typical CI flow
		// hits populateSourceCodeEntryByVcsAndCommit, which dedups in the merge step.
		if (isSemanticDuplicateOnSce(sced, art)) {
			log.info("SCE {} already carries a semantic duplicate of artifact {}; skipping attach",
					sceUuid, art.artifactUuid());
			return false;
		}

		List<SCEArtifact> artifacts = sced.getArtifacts();
		artifacts.add(art);
		sced.setArtifacts(artifacts);
		Map<String,Object> recordData = Utils.dataToRecord(sced);
		saveSourceCodeEntry(sce, recordData, wu);
		// See DeliverableService.addArtifact: attaching an already-scanned artifact
		// moves a release's rollup without touching the artifact's metrics or the
		// release row, so the scan-time touch cannot fire. This replaces what the
		// retired BY_SCE finder used to catch. After commit for the same reason as
		// DeliverableService.addArtifact: the touch must postdate any compute that ran
		// while this transaction was open.
		TxUtils.afterCommitOrNow(() -> releaseMetricsTouchService.touchBySceArtifact(art.artifactUuid().toString()));
		return true;
	}

	private boolean isSemanticDuplicateOnSce(SourceCodeEntryData sced, SCEArtifact candidate) {
		if (candidate == null || candidate.artifactUuid() == null) return false;
		Optional<io.reliza.model.ArtifactData> oCand = artifactService.getArtifactData(candidate.artifactUuid());
		if (oCand.isEmpty()) return false;
		String candSig = canonicalContentSignature(oCand.get());
		for (SCEArtifact existing : sced.getArtifacts()) {
			if (candidate.artifactUuid().equals(existing.artifactUuid())) return true;
			if (candSig == null) continue;
			Optional<io.reliza.model.ArtifactData> oEx = artifactService.getArtifactData(existing.artifactUuid());
			if (oEx.isEmpty() || oEx.get().getType() != oCand.get().getType()) continue;
			if (candSig.equals(canonicalContentSignature(oEx.get()))) return true;
		}
		return false;
	}

	private String canonicalContentSignature(io.reliza.model.ArtifactData art) {
		if (art == null || art.getDigestRecords() == null) return null;
		io.reliza.model.ArtifactData.DigestScope preferred =
				art.getType() == io.reliza.model.ArtifactData.ArtifactType.BOM
				? io.reliza.model.ArtifactData.DigestScope.REARM
				: io.reliza.model.ArtifactData.DigestScope.ORIGINAL_FILE;
		return art.getDigestRecords().stream()
				.filter(d -> d.scope() == preferred)
				.map(io.reliza.model.ArtifactData.DigestRecord::digest)
				.findFirst().orElse(null);
	}

	private String sceArtSemanticKey(SCEArtifact sceArt) {
		if (sceArt == null || sceArt.artifactUuid() == null) return null;
		Optional<io.reliza.model.ArtifactData> oad = artifactService.getArtifactData(sceArt.artifactUuid());
		if (oad.isEmpty()) return null;
		io.reliza.model.ArtifactData ad = oad.get();
		String sig = canonicalContentSignature(ad);
		if (sig == null) return null;
		return ad.getType() + ":" + sig;
	}
	@Transactional
	public boolean replaceArtifact(UUID sceUuid, SCEArtifact replaceArt, SCEArtifact art, WhoUpdated wu) throws RelizaException{
		SourceCodeEntry sce = getSourceCodeEntryService.getSourceCodeEntry(sceUuid).get();
		SourceCodeEntryData sced = SourceCodeEntryData.dataFromRecord(sce);
		List<SCEArtifact> artifacts = sced.getArtifacts();
		// Match on artifactUuid only -- the stored entry's component tag may be a
		// concrete uuid (component-scoped) or null (commit-scoped), and the caller
		// can't know which without reading the SCE first.
		artifacts.removeIf(a -> a.artifactUuid().equals(replaceArt.artifactUuid()));
		artifacts.add(art);
		sced.setArtifacts(artifacts);
		Map<String,Object> recordData = Utils.dataToRecord(sced);
		saveSourceCodeEntry(sce, recordData, wu);
		return true;
	}
	
	@Transactional
	private SourceCodeEntry saveSourceCodeEntry (SourceCodeEntry sce, Map<String,Object> recordData, WhoUpdated wu) {
		// let's add some validation here
		// TODO: add validation
		Optional<SourceCodeEntry> osce = getSourceCodeEntryService.getSourceCodeEntry(sce.getUuid());
		if (osce.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.SOURCE_CODE_ENTRIES, sce);
			sce.setRevision(sce.getRevision() + 1);
			sce.setLastUpdatedDate(ZonedDateTime.now());
		}
		sce.setRecordData(recordData);
		sce = (SourceCodeEntry) WhoUpdated.injectWhoUpdatedData(sce, wu);
		sce = repository.save(sce);
		SourceCodeEntryData sced = SourceCodeEntryData.dataFromRecord(sce);
		// a legacy entry stored without an org resolves its releases in its branch's org
		UUID owner = getSourceCodeEntryService.ownerOrg(sced);
		Set<UUID> affectedReleases = new HashSet<>();
		if (null != owner) {
			var sceReleases = sharedReleaseService.findReleasesBySce(sce.getUuid(), owner);
			sceReleases.forEach(r -> affectedReleases.add(r.getUuid()));
			sced.getArtifacts().forEach(a -> {
				var releases = sharedReleaseService.findReleasesByReleaseArtifact(a.artifactUuid(), owner);
				releases.forEach(r -> affectedReleases.add(r.getUuid()));
			});
		}
		affectedReleases.forEach(r -> acollectionService.resolveReleaseCollection(r, wu));
		return sce;
	}
	
//	public boolean moveScesOfComponentToNewOrg (UUID projectUuid, UUID newOrg, WhoUpdated wu) {
//		boolean moved = false;
//		// locate sces
//		List<SourceCodeEntry> sceList = listSceByComponent(projectUuid);
//		if (!sceList.isEmpty()) {
//			for (SourceCodeEntry sce : sceList) {
//				SourceCodeEntryData sced = SourceCodeEntryData.dataFromRecord(sce);
//				sced.setOrg(newOrg);
//				// save
//				saveSourceCodeEntry(sce, Utils.dataToRecord(sced), wu);
//				moved = true;
//			}
//		}
//		return moved;
//	}
	
	/**
	 * Mutates commits
	 * @param sceMap
	 * @param commits
	 */
	public void normalizeSceMapAndCommits(Map<String, Object> sceMap, List<Map<String, Object>> commits) {
		
	}

	
	/**
	 * <b>TODO</b> pass sceDto object instead of Map and List?
	 * <p>This function returns the bump action that should be taken based on a commit message.
	 * The commit message comes from either the soureCodeEntry map, or the commits list. All
	 * commit messages present in sceMap or commits list will be parsed, and the largest bump
	 * action will be returned.
	 * 
	 * @param sceMap {@code Map<String, Object>} object representing a SourceCodeEntryInput
	 * @param commits {@code List<Map<String, Object>>} commits list object
	 * @param rejectedCommits commits of previously rejected releases, skipped
	 * @param bd the authorized branch being versioned; the stored-message lookup is scoped to it
	 * @return {@code ActionEnum} the largest action parsed from commit message contents, or null if no valid commit message is present in SCE.
	 */
	public ActionEnum getBumpActionFromSourceCodeEntryInput(SceDto sceMap, List<SceDto> commits, Set<String> rejectedCommits,
			BranchData bd) throws RelizaException{
		// make sure all commit messages use System line seperator for newlines
		// this can be removed once versioning library updated to at least commit db5c3387a1a1b31d0f248cac82251ba3f4783638
		if (sceMap != null && StringUtils.isNotEmpty(sceMap.getCommitMessage())) {
			sceMap.cleanMessage();
		}
		
		// If SCE specifies a commit but no commitMessage, try and find matching commit in repo
		if (sceMap != null && StringUtils.isNotEmpty(sceMap.getCommit()) 
				&& null != sceMap.getVcs() && StringUtils.isEmpty(sceMap.getCommitMessage())) {
			// Read-only lookup on the authorized branch's own repository. The input's
			// branch, vcs and org are the client's and are not used: this used to run the
			// write path with them, merging into (and reading the message of) whatever
			// entry they named.
			Optional<String> storedMessage = findStoredCommitMessage(bd, sceMap.getCommit());
			if (storedMessage.isPresent()) {
				sceMap.setCommitMessage(storedMessage.get());
			} else {
				// if commit message not found, null sceMap so we don't try to parse non-existent commit message field
				sceMap = null;
			}
		}
		
		// Check what input is present. If commits list is null, simply parse from sceMap
		if (sceMap != null && (commits == null || commits.isEmpty()) && StringUtils.isNotEmpty(sceMap.getCommitMessage())) {
			return VersionApi.getActionFromRawCommit(sceMap.getCommitMessage());
		// Otherwise, if commits list is present, parse every commit message and return largest action
		} else if (commits != null && !commits.isEmpty()) {
			// Build a local iteration list that conditionally folds in sceMap.
			// MUST NOT mutate the input `commits` list: it's the same reference
			// that ReleaseService.createReleaseFromVersion later iterates, and
			// appending sceMap there would smuggle its artifactInputs into the
			// commit loop, double-uploading the same artMaps and NPE-ing the
			// second pass once the first stripped MultipartFile out.
			List<SceDto> iterCommits;
			if (sceMap != null && StringUtils.isNotEmpty(sceMap.getCommitMessage())
					&& !sceMap.getCommit().equalsIgnoreCase(commits.get(0).getCommit())) {
				iterCommits = new ArrayList<>(commits);
				iterCommits.add(sceMap);
			} else {
				iterCommits = commits;
			}

			// Find largest action from list
			ActionEnum largestAction = null;
			for (SceDto commit : iterCommits) {
				try {
					if (!rejectedCommits.contains(commit.getCommit())) {
						ActionEnum action = VersionApi.getActionFromRawCommit(commit.getCommitMessage());
						// Check if action is greater than largestAction we have parsed so far
						if (action != null && (largestAction == null || action.compareTo(largestAction) > 0)) {
							largestAction = action;
						}
					}
				} catch (IllegalArgumentException e) {
					// if commit message does not meet spec for some reason, just ignore it
				}
			}
			// return largest action, may be null if we could not find a valid commit message in the commits list
			return largestAction;
		} else {
			// sceMap is null (or does not contain CommitMessage field) and commits list is null, nothing to parse action from, return null
			return null;
		}
	}
	
	public void saveAll(List<SourceCodeEntry> sourceCodeEntries){
		repository.saveAll(sourceCodeEntries);
	}
}
