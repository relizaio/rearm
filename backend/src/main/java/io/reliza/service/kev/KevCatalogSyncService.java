/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.kev;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.model.KevAssertionData;
import io.reliza.model.VulnerabilityRecord;
import io.reliza.model.VulnerabilityRecordData;
import io.reliza.repositories.VulnerabilityRecordRepository;
import io.reliza.service.KevAssertionService;
import io.reliza.service.KevAssertionService.KevCatalogApplyOutcome;
import io.reliza.service.VulnAnalysisUpdateService;
import io.reliza.service.VulnerabilityRecordChangeHook;
import lombok.extern.slf4j.Slf4j;

/**
 * KEV catalog sync orchestrator: for each {@link KevSourceConnector},
 * fetch the source's full catalog, reconcile it into {@code kev_assertions}
 * via {@link KevAssertionService#applyCatalog}, then emit a KEV_ADDED
 * notification for every org vulnerability record matching a genuinely new
 * KEV (by primary id or alias). Scheduled by the edition's scheduling
 * service under the {@code SYNC_KEV_CATALOG} advisory lock.
 *
 * <p><b>Fail-closed.</b> A connector returns an empty list on a failed or
 * empty fetch; the orchestrator then skips that source's reconcile, so a
 * truncated response never revokes live assertions. (And revocation is a
 * soft mark that still reads as KEV anyway — two layers of safety.)
 *
 * <p><b>Bootstrap is silent.</b> On the first deploy ({@code kev_assertions}
 * empty), the initial load would mark every entry "new" and storm every org
 * with KEV_ADDED events for years-old listings. The event pass is skipped
 * when the store starts empty; only incremental additions on later runs
 * notify.
 *
 * <p><b>CE-safe.</b> The notification hook is autowired
 * {@code required = false} — the CE backend has no implementation bean, so
 * the read surface works and the KEV_ADDED pass simply no-ops until the
 * notifications subsystem is present.
 *
 * <p>Transaction shape: each connector's HTTP fetch happens outside any
 * transaction; that source's reconcile + event emission share one
 * {@link TransactionTemplate} transaction so the outbox rows commit iff the
 * assertion rows do.
 */
@Slf4j
@Service
public class KevCatalogSyncService {

	@Autowired
	private List<KevSourceConnector> connectors;

	@Autowired
	private KevAssertionService kevAssertionService;

	@Autowired
	private VulnerabilityRecordRepository vulnerabilityRecordRepository;

	// CE has no implementation; the read surface still works and the
	// KEV_ADDED pass no-ops.
	@Autowired(required = false)
	private VulnerabilityRecordChangeHook vulnerabilityRecordChangeHook;

	@Autowired
	private PlatformTransactionManager transactionManager;

	// Shared @Async fan-out: recomputes metrics of releases that ship a
	// newly-KEV CVE so kevCount/the release gate re-fires without a scan.
	// Cross-bean call keeps @Async honored (no same-class self-invocation).
	@Autowired
	private VulnAnalysisUpdateService vulnAnalysisUpdateService;

	/**
	 * One full sync pass across every source. Returns the total number of
	 * genuinely new KEV CVEs across all sources. Never throws — the
	 * scheduler tick treats every outcome as "try again next tick".
	 *
	 * <p><b>Caller contract: hold the {@code SYNC_KEV_CATALOG} advisory lock.</b>
	 * The bootstrap decision is read once up front and per-source reconciles
	 * are separate transactions, so two concurrent passes could double-emit
	 * KEV_ADDED or collide on the {@code (source, cve_id)} unique constraint.
	 * The Pro scheduler serializes this behind the advisory lock; any other
	 * caller (e.g. a future CE scheduler) must do the same.
	 */
	public int syncCatalog() {
		boolean bootstrap = kevAssertionService.countRecords() == 0;
		int totalNew = 0;
		for (KevSourceConnector connector : connectors) {
			try {
				totalNew += syncSource(connector, bootstrap);
			} catch (Exception e) {
				log.error("KEV sync failed for source {}", connector.source(), e);
			}
		}
		return totalNew;
	}

	private int syncSource(KevSourceConnector connector, boolean bootstrap) {
		List<KevAssertionData> entries = connector.fetchCatalog();
		if (entries.isEmpty()) {
			// Connector already logged the cause; skip reconcile (fail-closed).
			return 0;
		}
		TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
		KevCatalogApplyOutcome outcome = txTemplate.execute(status -> {
			KevCatalogApplyOutcome applied = kevAssertionService.applyCatalog(connector.source(), entries);
			if (!bootstrap) {
				emitKevAddedEvents(applied.newlyKevCveIds());
			}
			return applied;
		});
		if (outcome == null) return 0;
		if (bootstrap) {
			log.info("KEV catalog bootstrapped for {}: {} entries; KEV_ADDED event pass skipped",
					connector.source(), outcome.total());
		} else {
			// AFTER commit: kev_assertions is now persisted, so the async
			// recompute reads the new KEV status. Bootstrap is excluded
			// (would storm a recompute of every release on first load),
			// matching the KEV_ADDED event-pass guard above.
			triggerKevReGate(outcome.newlyKevCveIds());
		}
		return outcome.newlyKevCveIds().size();
	}

	/**
	 * For each genuinely new KEV CVE, gather the distinct orgs whose
	 * vulnerability records know the CVE (canonical id or alias) and hand
	 * off to the shared {@code @Async} fan-out, which recomputes the
	 * metrics of every release that ships the CVE so the KEV gate re-fires.
	 * Called only after the reconcile transaction has committed.
	 */
	private void triggerKevReGate(List<String> newlyKevCveIds) {
		for (String cveId : newlyKevCveIds) {
			List<VulnerabilityRecord> affected =
					vulnerabilityRecordRepository.findAllOrgsByVulnIdOrAlias(cveId);
			Set<UUID> affectedOrgs = affected.stream()
					.map(VulnerabilityRecord::getOrg)
					.filter(o -> o != null)
					.collect(Collectors.toSet());
			if (!affectedOrgs.isEmpty()) {
				vulnAnalysisUpdateService.recomputeReleasesForNewlyKev(cveId, affectedOrgs);
			}
		}
	}

	/**
	 * For each genuinely new KEV CVE, find every org vulnerability record that
	 * knows the CVE (canonical id or alias) and hand it to the notification
	 * hook. No-op when the hook is absent (CE).
	 */
	private void emitKevAddedEvents(List<String> newlyKevCveIds) {
		if (vulnerabilityRecordChangeHook == null) return;
		for (String cveId : newlyKevCveIds) {
			List<VulnerabilityRecord> affected =
					vulnerabilityRecordRepository.findAllOrgsByVulnIdOrAlias(cveId);
			for (VulnerabilityRecord vr : affected) {
				VulnerabilityRecordData vrd = VulnerabilityRecordData.dataFromRecord(vr);
				vulnerabilityRecordChangeHook.onKevAdded(vr.getOrg(), vr.getPrimaryVulnId(), vrd);
			}
			if (!affected.isEmpty()) {
				log.info("KEV_ADDED: {} now KEV-listed, notified {} org record(s)", cveId, affected.size());
			}
		}
	}
}
