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
import io.reliza.common.CommonVariables.TableName;

import io.reliza.common.Utils;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.BranchData;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.SourceCodeEntryData.SCEArtifact;
import io.reliza.model.VcsRepository;
import io.reliza.model.VcsRepositoryData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.SceDto;
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
			// check vs branch vcs and return error if doesn't match
			UUID vcsUuidFromBranch = bd.getVcs();
			// Read-only lookup. SCE-level dedup that previously relied on this
			// pessimistic lock is now enforced by the V26 unique index on
			// source_code_entries (vcs, commit), with catch-and-recover in the
			// routine handling the race for the loser.
			Optional<VcsRepository> ovr = vcsRepositoryService.getVcsRepository(vcsUuidFromBranch);
			String vcsUri = sceDto.getUri();
			if (StringUtils.isNotEmpty(vcsUri) && ovr.isPresent() && !Utils.uriEquals(vcsUri, VcsRepositoryData.dataFromRecord(ovr.get()).getUri())) {
				throw new RelizaException("VCS repository mismatch: branch VCS does not match the supplied URI");
			} else if (ovr.isEmpty() && StringUtils.isNotEmpty(vcsUri) && null != vcsType) {	// branch does not have vcs repo set
				ovr = vcsRepositoryService.getVcsRepositoryByUri(sceDto.getOrganizationUuid(), vcsUri, null, vcsType, true, wu);
				// update branch with correct vcs repo
				try {
					BranchDto branchDto = BranchDto.builder()
												.uuid(bd.getUuid())
												.vcs(ovr.get().getUuid())
												.vcsBranch(sceDto.getVcsBranch())
												.build();
					branchService.updateBranch(branchDto, wu);
				} catch (RelizaException re) {
					throw new RuntimeException(re.getMessage());
				} 
			} else if (ovr.isEmpty() && null == bd.getVcs()) {
				// fail if no vcs data is provided and branch does not have vcs linked already
				throw new RelizaException("Branch does not have linked VCS repository and no VCS data provided");
			}

			// construct source code entry itself
			sceDto.setBranch(bd.getUuid());
			sceDto.setVcs(ovr.get().getUuid());
			Optional<SourceCodeEntry> osce = populateSourceCodeEntryByVcsAndCommitRoutine(sceDto, createIfMissing, wu);
			if (osce.isPresent()) osced = Optional.of(SourceCodeEntryData.dataFromRecord(osce.get()));
			return osced;
	}
	
	@Transactional
	private Optional<SourceCodeEntry> populateSourceCodeEntryByVcsAndCommitRoutine (SceDto sceDto, boolean createIfMissing, WhoUpdated wu) {
		Optional<SourceCodeEntry> osce = repository.findByCommitAndVcs(sceDto.getCommit(), sceDto.getVcs().toString());
		if (osce.isEmpty() && createIfMissing) {
			log.debug("osce is empty creating new ...");
			try {
				// REQUIRES_NEW via the proxy — keeps a unique-violation from the
				// V26 (vcs, commit) index from poisoning this routine's tx.
				return Optional.of(self.createSourceCodeEntry(sceDto, wu));
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
			Set<String> dedupArts = new HashSet<>();
			if (null != sced.getArtifacts() && !sced.getArtifacts().isEmpty()) {
				var dedupList = sced.getArtifacts().stream()
					.map(x -> x.artifactUuid().toString() + x.componentUuid()).toList();
				dedupArts.addAll(dedupList);
				for (var esda : existingSceData.getArtifacts()) {
					String dedupKey = esda.artifactUuid().toString() + esda.componentUuid();
					if (!dedupList.contains(dedupKey)) {
						List<SCEArtifact> updArts = new ArrayList<>(sced.getArtifacts());
						updArts.add(esda);
						sced.setArtifacts(updArts);
					}
				}
			} else {
				sced.setArtifacts(existingSceData.getArtifacts());
			}
		}
		Map<String,Object> recordData = Utils.dataToRecord(sced);
		SourceCodeEntry mergedSce = saveSourceCodeEntry(sce, recordData, wu);
		// Mirror createSourceCodeEntry's post-save reverse-index update so
		// the session's commit list picks up a merge-resolved attribution
		// just like a fresh-create one would. Best-effort; failure does
		// not roll back the merge.
		if (mergeResolvedSession != null) {
			try {
				agentSessionService.recordCommit(mergeResolvedSession, mergedSce.getUuid(), wu);
			} catch (Exception e) {
				log.warn("Failed to record SCE {} on session {} (merge path): {}",
						mergedSce.getUuid(), mergeResolvedSession, e.getMessage());
			}
		}
		return Optional.of(mergedSce);
	}

	// REQUIRES_NEW so a unique-violation on (vcs, commit) rolls back only this
	// attempt's tx, letting the routine's catch-and-recover proceed.
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public SourceCodeEntry createSourceCodeEntry (SceDto sceDto, WhoUpdated wu) {
		SourceCodeEntry sce = new SourceCodeEntry();
		VcsRepositoryData vrd = vcsRepositoryService.getVcsRepositoryData(sceDto.getVcs()).get(); //must exist - TODO error handling
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

		SourceCodeEntryData sced = SourceCodeEntryData.scEntryDataFactory(sceDto);

		// Parse the PR 2 agent commit trailers off the message and resolve
		// them to a (leaf agent, root session) pair before saving the SCE.
		// Resolution failures are non-fatal: we want the SCE to land
		// regardless of whether its agent attribution survives —
		// untracked commits are still releaseable. See
		// {@code ai-plans/agentic/README.md} §6-7 for the contract.
		UUID resolvedSession = resolveAndAttributeTrailers(sced);

		Map<String,Object> recordData = Utils.dataToRecord(sced);
		SourceCodeEntry saved = saveSourceCodeEntry(sce, recordData, wu);

		// Record the SCE on the session's reverse-index in a best-effort
		// post-write step. Failure here is logged but does not roll back
		// the SCE creation — the forward pointer on the SCE row is the
		// source of truth for the read path, the reverse index is an
		// optimisation.
		if (resolvedSession != null) {
			try {
				agentSessionService.recordCommit(resolvedSession, saved.getUuid(), wu);
			} catch (Exception e) {
				log.warn("Failed to record SCE {} on session {}: {}",
						saved.getUuid(), resolvedSession, e.getMessage());
			}
		}
		return saved;
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
		List<SCEArtifact> artifacts = sced.getArtifacts();
		artifacts.add(art);
		sced.setArtifacts(artifacts);
		Map<String,Object> recordData = Utils.dataToRecord(sced);
		saveSourceCodeEntry(sce, recordData, wu);
		return true;
	}
	@Transactional
	public boolean replaceArtifact(UUID sceUuid, SCEArtifact replaceArt, SCEArtifact art, WhoUpdated wu) throws RelizaException{
		SourceCodeEntry sce = getSourceCodeEntryService.getSourceCodeEntry(sceUuid).get();
		SourceCodeEntryData sced = SourceCodeEntryData.dataFromRecord(sce);
		List<SCEArtifact> artifacts = sced.getArtifacts();
		artifacts.remove(replaceArt);
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
		Set<UUID> affectedReleases = new HashSet<>();
		var sceReleases = sharedReleaseService.findReleasesBySce(sce.getUuid(), sced.getOrg());
		sceReleases.forEach(r -> affectedReleases.add(r.getUuid()));
		sced.getArtifacts().forEach(a -> {
			var releases = sharedReleaseService.findReleasesByReleaseArtifact(a.artifactUuid(), sced.getOrg());
			releases.forEach(r -> affectedReleases.add(r.getUuid()));
		});
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
	 * @return {@code ActionEnum} the largest action parsed from commit message contents, or null if no valid commit message is present in SCE.
	 */
	public ActionEnum getBumpActionFromSourceCodeEntryInput(SceDto sceMap, List<SceDto> commits, Set<String> rejectedCommits) throws RelizaException{
		// make sure all commit messages use System line seperator for newlines
		// this can be removed once versioning library updated to at least commit db5c3387a1a1b31d0f248cac82251ba3f4783638
		if (sceMap != null && StringUtils.isNotEmpty(sceMap.getCommitMessage())) {
			sceMap.cleanMessage();
		}
		
		// If SCE specifies a commit but no commitMessage, try and find matching commit in repo
		if (sceMap != null && StringUtils.isNotEmpty(sceMap.getCommit()) 
				&& null != sceMap.getVcs() && StringUtils.isEmpty(sceMap.getCommitMessage())) {
			// Convert sceMap to sceDto and transfer to sourceCodeEntry service to check if commit exists in repo already
			Optional<SourceCodeEntryData> osced = Optional.empty();
			osced = populateSourceCodeEntryByVcsAndCommit(sceMap, false, WhoUpdated.getAutoWhoUpdated());
			if (osced.isPresent() && StringUtils.isNotEmpty(osced.get().getCommitMessage())) {
				String commitMessage = osced.get().getCommitMessage();
				sceMap.setCommitMessage(commitMessage);
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
			// Add commit from SCE to commits list to easily iterate through all commits
			if (sceMap != null && StringUtils.isNotEmpty(sceMap.getCommitMessage()) 
					&& !sceMap.getCommit().equalsIgnoreCase(commits.get(0).getCommit())) {
				commits.add(sceMap);
			}
			
			// Find largest action from list
			ActionEnum largestAction = null;
			for (SceDto commit : commits) {
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
