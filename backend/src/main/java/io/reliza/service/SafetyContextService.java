/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.AdvisoryLockKey;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AnalysisScope;
import io.reliza.model.ProposalStatus;
import io.reliza.model.ReleaseData;
import io.reliza.model.SafetyContextData;
import io.reliza.model.SafetyContextData.GateRule;
import io.reliza.model.SafetyContextData.LevelChange;
import io.reliza.model.SafetyContextData.MatchStatus;
import io.reliza.model.SafetyContextProposal;
import io.reliza.model.SafetyContextProposalData;
import io.reliza.model.SafetyContextRecord;
import io.reliza.model.SafetyContextRecordData;
import io.reliza.model.SafetyContextRecordData.AcceptedVia;
import io.reliza.model.SafetyContextRecordData.RecordStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.SafetyContextProposalRepository;
import io.reliza.repositories.SafetyContextRecordRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

/**
 * Persistence for safety-context proposals and records: every save goes through the audit path
 * (audit row, revision, WhoUpdated), locks are advisory on the coarse key, and an accepted level
 * becomes the one ACTIVE record of its identity, superseding the previous one.
 */
@Service
public class SafetyContextService {

	/** Same text for a missing row and a forbidden one, so a uuid's existence does not leak. */
	public static final String NOT_FOUND = "Safety context entry not found";

	public static final int MAX_PAGE = 500;

	static final int MAX_TEXT = 2000;

	/** Records read at a release's scope targets before the inventory filter. */
	static final int RELEASE_TARGET_CAP = 5000;

	@PersistenceContext private EntityManager entityManager;

	@Autowired private SafetyContextProposalRepository proposalRepository;
	@Autowired private SafetyContextRecordRepository recordRepository;
	@Autowired private AuditService auditService;
	@Autowired private SbomComponentService sbomComponentService;
	@Autowired private SharedReleaseService sharedReleaseService;

	/** The coarse key: org, scope target, component purl, scheme. Locks and component-wide reads use it. */
	public static String coarseKey(SafetyContextData d) {
		return String.join("|", d.getOrg().toString(), d.getScope().name(), blank(d.getScopeUuid()),
			d.getComponentPurl(), d.getSchemeKey());
	}

	/** Takes the advisory locks for {@code keys} in sorted order (consistent order avoids deadlock). */
	public void lockCoarse(Collection<String> keys) {
		for (String key : new TreeSet<>(keys)) {
			proposalRepository.lockCoarseKey(AdvisoryLockKey.SAFETY_CONTEXT.getQueryVal(), key);
		}
	}

	public Optional<SafetyContextRecordData> findActiveAtIdentity(SafetyContextData d) {
		return recordRepository.findActiveAtIdentity(d.getOrg().toString(), d.getScope().name(),
			blank(d.getScopeUuid()), d.getComponentPurl(), d.getSchemeKey(), blank(d.getBlueprintRefId()),
			blank(d.getAssetRefId())).map(SafetyContextRecordData::dataFromRecord);
	}

	public Optional<SafetyContextProposalData> findPendingAtIdentity(SafetyContextProposalData d) {
		return proposalRepository.findPendingAtIdentity(d.getOrg().toString(), d.getScope().name(),
			blank(d.getScopeUuid()), d.getComponentPurl(), d.getSchemeKey(), blank(d.getBlueprintRefId()),
			blank(d.getAssetRefId()), d.getLineageKey()).map(SafetyContextProposalData::dataFromRecord);
	}

	public List<SafetyContextProposalData> findAllPendingAtIdentity(SafetyContextData d) {
		return proposalRepository.findAllPendingAtIdentity(d.getOrg().toString(), d.getScope().name(),
			blank(d.getScopeUuid()), d.getComponentPurl(), d.getSchemeKey(), blank(d.getBlueprintRefId()),
			blank(d.getAssetRefId())).stream().map(SafetyContextProposalData::dataFromRecord).toList();
	}

	public boolean existsUndecidedOrRejectedWithHash(SafetyContextProposalData d) {
		return proposalRepository.existsUndecidedOrRejectedWithHash(d.getOrg().toString(), d.getScope().name(),
			blank(d.getScopeUuid()), d.getComponentPurl(), d.getSchemeKey(), blank(d.getBlueprintRefId()),
			blank(d.getAssetRefId()), d.getLineageKey(), d.getSourceAssertionHash());
	}

	public List<SafetyContextRecordData> findActiveAtCoarseKey(SafetyContextData d) {
		return recordRepository.findActiveAtCoarseKey(d.getOrg().toString(), d.getScope().name(),
			blank(d.getScopeUuid()), d.getComponentPurl(), d.getSchemeKey())
			.stream().map(SafetyContextRecordData::dataFromRecord).toList();
	}

	public long countPending(UUID org, UUID release) {
		return proposalRepository.countPendingByOrgAndRelease(org.toString(), release.toString());
	}

	public SafetyContextProposalData saveProposal(SafetyContextProposalData d, WhoUpdated wu) {
		SafetyContextProposal e = proposalRepository.findById(d.getUuid()).orElse(null);
		if (e == null) {
			e = new SafetyContextProposal();
			e.setUuid(d.getUuid());
		} else {
			auditService.createAndSaveAuditRecord(TableName.SAFETY_CONTEXT_PROPOSAL, e);
			e.setRevision(e.getRevision() + 1);
			e.setLastUpdatedDate(ZonedDateTime.now());
		}
		e.setRecordData(d.toRecordData());
		e = (SafetyContextProposal) WhoUpdated.injectWhoUpdatedData(e, wu);
		return SafetyContextProposalData.dataFromRecord(proposalRepository.save(e));
	}

	public SafetyContextRecordData saveRecord(SafetyContextRecordData d, WhoUpdated wu) {
		SafetyContextRecord e = recordRepository.findById(d.getUuid()).orElse(null);
		if (e == null) {
			e = new SafetyContextRecord();
			e.setUuid(d.getUuid());
		} else {
			auditService.createAndSaveAuditRecord(TableName.SAFETY_CONTEXT_RECORD, e);
			e.setRevision(e.getRevision() + 1);
			e.setLastUpdatedDate(ZonedDateTime.now());
		}
		e.setRecordData(d.toRecordData());
		e = (SafetyContextRecord) WhoUpdated.injectWhoUpdatedData(e, wu);
		return SafetyContextRecordData.dataFromRecord(recordRepository.save(e));
	}

	/**
	 * Makes {@code proposal} the ACTIVE record of its identity: the previous ACTIVE record, if any,
	 * becomes SUPERSEDED and points at the new one. Caller holds the coarse lock and saves the
	 * proposal afterwards.
	 */
	public SafetyContextRecordData upsertActive(SafetyContextProposalData proposal, AcceptedVia via, UUID acceptedBy,
			String comment, WhoUpdated wu) {
		SafetyContextRecordData record = new SafetyContextRecordData();
		record.copySharedFrom(proposal);
		record.setUuid(UUID.randomUUID());
		record.setProposalUuid(proposal.getUuid());
		record.setAcceptedVia(via);
		record.setAcceptedBy(acceptedBy);
		record.setAcceptedAt(Instant.now());
		record.setAcceptedByUploader(acceptedBy != null && acceptedBy.equals(proposal.getUploadedBy()));
		record.setAcceptComment(comment);
		Optional<SafetyContextRecordData> active = findActiveAtIdentity(proposal);
		if (active.isPresent()) {
			SafetyContextRecordData old = active.get();
			old.setStatus(RecordStatus.SUPERSEDED);
			old.setSupersededBy(record.getUuid());
			// Free the partial unique index before the new ACTIVE row is inserted.
			saveRecord(old, wu);
			recordRepository.flush();
		}
		return saveRecord(record, wu);
	}

	/** Marks the PENDING proposal of the same lineage at this identity SUPERSEDED, if there is one. */
	public void supersedePendingOfLineage(SafetyContextProposalData incoming, WhoUpdated wu) {
		Optional<SafetyContextProposalData> pending = findPendingAtIdentity(incoming);
		if (pending.isPresent() && !pending.get().getUuid().equals(incoming.getUuid())) {
			SafetyContextProposalData p = pending.get();
			p.setStatus(ProposalStatus.SUPERSEDED);
			p.setActedAt(Instant.now());
			saveProposal(p, wu);
			proposalRepository.flush();
		}
	}

	/**
	 * Accepts a PENDING proposal by hand. An unmatched or ambiguous proposal needs
	 * {@code componentUuid}, which must be one of the release's components. A downgrade against the
	 * current ACTIVE record (recomputed now, not at import) needs a non-blank comment.
	 */
	@Transactional
	public SafetyContextProposalData accept(UUID uuid, UUID componentUuid, String comment, WhoUpdated wu)
			throws RelizaException {
		checkText(comment, "comment");
		SafetyContextProposal e = lockedForDecision(uuid);
		SafetyContextProposalData d = SafetyContextProposalData.dataFromRecord(e);
		if (d.getGateRule() == GateRule.INTRA_ASSET_CONFLICT) {
			throw new RelizaException("The asset declares conflicting levels for this scheme; reject it and correct the document");
		}
		if (componentUuid != null || d.getMatchStatus() != MatchStatus.MATCHED) {
			if (componentUuid == null) {
				throw new RelizaException("Choose the release component this level applies to");
			}
			SbomComponentService.ReleaseComponentPurls rc = sbomComponentService.resolveReleaseComponentPurls(d.getReleaseUuid());
			if (!d.getOrg().equals(rc.org()) || !rc.canonicalByComponent().containsKey(componentUuid)) {
				throw new RelizaException("The chosen component is not part of this release");
			}
			d.setSbomComponentUuid(componentUuid);
		}
		Optional<SafetyContextRecordData> active = findActiveAtIdentity(d);
		LevelChange change = SafetyContextTrustPolicy.levelChange(active.isPresent(),
			active.map(SafetyContextData::getLevelRank).orElse(null),
			active.map(a -> SafetyContextTrustPolicy.label(a.getLevelNormalized(), a.getLevelRaw())).orElse(null),
			d.getLevelRank(), SafetyContextTrustPolicy.label(d.getLevelNormalized(), d.getLevelRaw()));
		d.setLevelChange(change);
		if ((change == LevelChange.DOWNGRADE || change == LevelChange.UNCOMPARABLE)
				&& (comment == null || comment.isBlank())) {
			throw new RelizaException("Accepting a level lower than, or not comparable with, the current one needs a comment");
		}
		UUID actor = actorOf(wu);
		SafetyContextRecordData record = upsertActive(d, AcceptedVia.MANUAL, actor, comment, wu);
		d.setStatus(ProposalStatus.ACCEPTED);
		d.setActedBy(actor);
		d.setActedAt(Instant.now());
		d.setAcceptComment(comment);
		d.setAcceptedRecordUuid(record.getUuid());
		return saveProposal(d, wu);
	}

	@Transactional
	public SafetyContextProposalData reject(UUID uuid, String reason, WhoUpdated wu) throws RelizaException {
		checkText(reason, "reason");
		SafetyContextProposalData d = SafetyContextProposalData.dataFromRecord(lockedForDecision(uuid));
		d.setStatus(ProposalStatus.REJECTED);
		d.setRejectReason(reason);
		d.setActedBy(actorOf(wu));
		d.setActedAt(Instant.now());
		return saveProposal(d, wu);
	}

	/**
	 * Takes the coarse advisory lock (the order an import takes it in), then the row lock, and
	 * re-reads the row so a decision never acts on a state an import changed while it waited.
	 */
	private SafetyContextProposal lockedForDecision(UUID uuid) throws RelizaException {
		SafetyContextProposal e = proposalRepository.findById(uuid).orElseThrow(() -> new RelizaException(NOT_FOUND));
		lockCoarse(List.of(coarseKey(SafetyContextProposalData.dataFromRecord(e))));
		entityManager.refresh(e, LockModeType.PESSIMISTIC_WRITE);
		ProposalStatus status = SafetyContextProposalData.dataFromRecord(e).getStatus();
		if (status != ProposalStatus.PENDING) {
			throw new RelizaException("Safety context proposal is not pending (was " + status + ")");
		}
		return e;
	}

	private static void checkText(String value, String what) throws RelizaException {
		if (value != null && value.length() > MAX_TEXT) {
			throw new RelizaException("The " + what + " is longer than " + MAX_TEXT + " characters");
		}
	}

	public Optional<SafetyContextProposalData> getProposal(UUID uuid) {
		return proposalRepository.findById(uuid).map(SafetyContextProposalData::dataFromRecord);
	}

	public Optional<SafetyContextRecordData> getRecord(UUID uuid) {
		return recordRepository.findById(uuid).map(SafetyContextRecordData::dataFromRecord);
	}

	public List<SafetyContextProposalData> listProposals(UUID org, ProposalStatus status, int limit, int offset) {
		return proposalRepository.findByOrgAndStatus(org.toString(), status == null ? null : status.name(),
			page(limit), Math.max(0, offset)).stream().map(SafetyContextProposalData::dataFromRecord).toList();
	}

	public List<SafetyContextProposalData> listProposalsForRelease(UUID org, UUID release, int limit, int offset) {
		return proposalRepository.findByOrgAndRelease(org.toString(), release.toString(), page(limit),
			Math.max(0, offset)).stream().map(SafetyContextProposalData::dataFromRecord).toList();
	}

	public List<SafetyContextProposalData> listProposalsForArtifact(UUID org, UUID artifact, int limit, int offset) {
		return proposalRepository.findByOrgAndSourceArtifact(org.toString(), artifact.toString(), page(limit),
				Math.max(0, offset))
			.stream().map(SafetyContextProposalData::dataFromRecord).toList();
	}

	/**
	 * The safety records that apply to a release, resolved the way a Finding Analysis is: a record
	 * at the release, its branch, its component or the org applies when its component purl is in
	 * the release's inventory, and per identity (purl, scheme, blueprint, asset) the most specific
	 * scope wins. So a level accepted at component scope on one release shows on every release of
	 * that component that contains the package. With {@code includeSuperseded}, the superseded
	 * history at those targets is listed too.
	 */
	public List<SafetyContextRecordData> listRecordsForRelease(UUID org, UUID release, boolean includeSuperseded,
			int limit, int offset) {
		Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(release, org);
		if (ord.isEmpty()) return List.of();
		Set<String> purls = inventoryPurls(sbomComponentService.resolveReleaseComponentPurls(release)
			.canonicalByComponent().values());
		return recordsApplyingTo(org, release, ord.get().getBranch(), ord.get().getComponent(), purls,
			includeSuperseded, limit, offset);
	}

	List<SafetyContextRecordData> recordsApplyingTo(UUID org, UUID release, UUID branch, UUID component,
			Set<String> inventoryPurls, boolean includeSuperseded, int limit, int offset) {
		if (inventoryPurls.isEmpty()) return List.of();
		List<SafetyContextRecordData> atTargets = recordRepository.findAtReleaseTargets(org.toString(),
				release.toString(), blank(branch), blank(component), String.join("\n", inventoryPurls),
				includeSuperseded, RELEASE_TARGET_CAP)
			.stream().map(SafetyContextRecordData::dataFromRecord)
			.toList();
		Map<String, Integer> mostSpecificActive = new HashMap<>();
		for (SafetyContextRecordData r : atTargets) {
			if (r.getStatus() != RecordStatus.ACTIVE) continue;
			mostSpecificActive.merge(identityWithinScope(r), specificity(r.getScope()), Math::max);
		}
		return atTargets.stream()
			.filter(r -> r.getStatus() != RecordStatus.ACTIVE
				|| specificity(r.getScope()) == mostSpecificActive.get(identityWithinScope(r)))
			.skip(Math.max(0, offset))
			.limit(page(limit))
			.toList();
	}

	/**
	 * The release inventory's purls in the form the import stores on a record
	 * ({@link Utils#canonicalizePurl}, which re-encodes some characters the SBOM's own canonical
	 * form keeps), plus the stored form itself.
	 */
	static Set<String> inventoryPurls(Collection<String> sbomPurls) {
		Set<String> out = new HashSet<>();
		for (String purl : sbomPurls) {
			if (purl == null || purl.isBlank()) continue;
			out.add(purl);
			try {
				out.add(Utils.canonicalizePurl(purl));
			} catch (RuntimeException e) {
				// Not parseable as a purl: the stored form above is all there is.
			}
		}
		return out;
	}

	/** Identity of a level without its scope target: the same asset's level for the same package and scheme. */
	private static String identityWithinScope(SafetyContextData d) {
		return String.join("|", d.getComponentPurl(), d.getSchemeKey(), blank(d.getBlueprintRefId()),
			blank(d.getAssetRefId()));
	}

	/** Release over branch over component over org, as Finding Analysis resolves. */
	private static int specificity(AnalysisScope scope) {
		if (scope == null) return 0;
		return switch (scope) {
			case RELEASE -> 4;
			case BRANCH -> 3;
			case COMPONENT -> 2;
			case ORG -> 1;
			default -> 0;
		};
	}

	public List<SafetyContextRecordData> listRecordsForScopeTarget(UUID org, String scope, UUID scopeUuid,
			boolean includeSuperseded, int limit, int offset) {
		return recordRepository.findByOrgAndScopeTarget(org.toString(), scope, scopeUuid.toString(),
			includeSuperseded, page(limit), Math.max(0, offset))
			.stream().map(SafetyContextRecordData::dataFromRecord).toList();
	}

	/** The human behind the call: the actor of an API-key call when known, else the caller. */
	public static UUID actorOf(WhoUpdated wu) {
		if (wu == null) return null;
		return wu.getActor() != null ? wu.getActor() : wu.getLastUpdatedBy();
	}

	static String blank(Object value) {
		return value == null ? "" : value.toString();
	}

	private static int page(int limit) {
		return limit <= 0 ? 100 : Math.min(limit, MAX_PAGE);
	}
}
