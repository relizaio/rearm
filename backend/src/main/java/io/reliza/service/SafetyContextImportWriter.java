/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.ImportMode;
import io.reliza.model.IssuerClass;
import io.reliza.model.ProposalStatus;
import io.reliza.model.SafetyContextData;
import io.reliza.model.SafetyContextData.LevelChange;
import io.reliza.model.SafetyContextData.MatchStatus;
import io.reliza.model.SafetyContextData.Note;
import io.reliza.model.SafetyContextProposalData;
import io.reliza.model.SafetyContextRecordData;
import io.reliza.model.SafetyContextRecordData.AcceptedVia;
import io.reliza.model.TrustAction;
import io.reliza.model.WhoUpdated;

/**
 * Phase B of a SAFETY_CONTEXT import: all writes for one upload in one new transaction. Any
 * exception rolls the whole import back; the handler turns it into an "aborted, nothing stored"
 * summary. Candidates arrive validated, matched and targeted (phase A has no writes).
 */
@Service
public class SafetyContextImportWriter {

	/** Counts for the summary; see {@code SafetyContextImportService} for the invariant. */
	public record Counts(int created, int autoAccepted, int autoRejected, int unchanged, int unmatched) {}

	@Autowired private SafetyContextService service;
	@Autowired private ImportVerdictResolver verdictResolver;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Counts importAll(List<SafetyContextProposalData> candidates, ImportMode importMode, WhoUpdated wu) {
		service.lockCoarse(candidates.stream().map(SafetyContextService::coarseKey).collect(Collectors.toSet()));
		int created = 0;
		int autoAccepted = 0;
		int autoRejected = 0;
		int unchanged = 0;
		int unmatched = 0;
		for (SafetyContextProposalData c : candidates) {
			Optional<SafetyContextRecordData> active = service.findActiveAtIdentity(c);
			// Re-imports never re-stage: identical to the ACTIVE record, or to a pending or rejected
			// proposal of the same lineage, is a no-op.
			if (active.isPresent() && c.getSourceAssertionHash().equals(active.get().getSourceAssertionHash())) {
				unchanged++;
				continue;
			}
			if (service.existsUndecidedOrRejectedWithHash(c)) {
				unchanged++;
				continue;
			}
			c.setLevelChange(SafetyContextTrustPolicy.levelChange(active.isPresent(),
				active.map(SafetyContextData::getLevelRank).orElse(null),
				active.map(SafetyContextData::getLevelNormalized).orElse(null),
				c.getLevelRank(), c.getLevelNormalized()));
			if (active.isPresent() && c.getLevelChange() != LevelChange.UNCHANGED
					&& !c.getSourceDocumentHash().equals(active.get().getSourceDocumentHash())) {
				c.getNotes().add(Note.CROSS_DOC_CONFLICT);
				c.setConflictFlag(true);
			}

			TrustAction gateAction;
			if (c.getGateRule() != null) {
				// Phase A already forced a stage (unmatched, ambiguous, SBOM pending, intra-asset conflict).
				gateAction = TrustAction.STAGE;
			} else {
				SafetyContextTrustPolicy.Gate gate = SafetyContextTrustPolicy.gate(c.getIssuerClassEffective(),
					c.getLevelNote(), c.getLevelRank(), c.getLevelChange());
				c.setGateRule(gate.rule());
				gateAction = gate.action();
			}
			TrustAction action = verdictResolver.resolve(gateAction, importMode).action();
			if (action == TrustAction.REJECT) {
				autoRejected++;
				continue;
			}
			if (action == TrustAction.STAGE && gateAction == TrustAction.AUTO_ACCEPT) {
				c.getNotes().add(Note.USER_MODE_STAGE);
			}

			service.supersedePendingOfLineage(c, wu);
			List<SafetyContextProposalData> otherLineages = service.findAllPendingAtIdentity(c);
			if (!otherLineages.isEmpty()) {
				c.getNotes().add(Note.CROSS_DOC_CONFLICT);
				c.setConflictFlag(true);
				for (SafetyContextProposalData other : otherLineages) {
					if (!other.isConflictFlag()) {
						other.setConflictFlag(true);
						service.saveProposal(other, wu);
					}
				}
			}

			if (action == TrustAction.AUTO_ACCEPT) {
				AcceptedVia via = c.getIssuerClassEffective() == IssuerClass.SELF ? AcceptedVia.AUTO_SELF : AcceptedVia.AUTO_VENDOR;
				SafetyContextRecordData record = service.upsertActive(c, via, c.getUploadedBy(), null, wu);
				c.setStatus(ProposalStatus.ACCEPTED);
				c.setActedBy(c.getUploadedBy());
				c.setActedAt(Instant.now());
				c.setAcceptedRecordUuid(record.getUuid());
				service.saveProposal(c, wu);
				autoAccepted++;
			} else {
				c.setStatus(ProposalStatus.PENDING);
				service.saveProposal(c, wu);
				created++;
				if (c.getMatchStatus() != MatchStatus.MATCHED) unmatched++;
			}
		}
		return new Counts(created, autoAccepted, autoRejected, unchanged, unmatched);
	}
}
