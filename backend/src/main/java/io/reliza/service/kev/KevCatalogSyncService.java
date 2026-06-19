/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.kev;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.KevAssertionData;
import io.reliza.model.KevSource;
import io.reliza.model.VulnerabilityRecord;
import io.reliza.model.VulnerabilityRecordData;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.repositories.VulnerabilityRecordRepository;
import io.reliza.service.EncryptionService;
import io.reliza.service.KevAssertionService;
import io.reliza.service.KevAssertionService.KevCatalogApplyOutcome;
import io.reliza.service.VulnAnalysisUpdateService;
import io.reliza.service.VulnerabilityRecordChangeHook;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-org KEV catalog sync orchestrator (V54 refactor): for every enabled
 * {@code Integration} of type {@code CISA_KEV} or {@code VULNCHECK_KEV},
 * dispatch the matching connector against that org's credential (where
 * applicable), reconcile the result into {@code kev_assertions} under that
 * org via {@link KevAssertionService#applyCatalog}, then emit a KEV_ADDED
 * notification for every org vulnerability record matching a genuinely new
 * KEV (by primary id or alias). Scheduled by the edition's scheduling
 * service under the {@code SYNC_KEV_CATALOG} advisory lock.
 *
 * <p><b>Fail-closed.</b> A connector returns an empty list on a failed or
 * empty fetch; the orchestrator then skips that (org, source) reconcile,
 * so a truncated response never revokes live assertions for the org.
 * (Soft-deleted assertions still read as KEV in the same org anyway — two
 * layers of safety.)
 *
 * <p><b>Bootstrap is silent per-org.</b> The first sync for an org (zero
 * existing rows in {@code kev_assertions} for that {@code org_id}) would
 * mark every entry "new" and storm the org with KEV_ADDED events for
 * years-old listings. The event pass is skipped on the per-org first
 * sync; only incremental additions on later runs notify.
 *
 * <p><b>CE-safe.</b> The notification hook is autowired
 * {@code required = false} — the CE backend has no implementation bean, so
 * the read surface works and the KEV_ADDED pass simply no-ops until the
 * notifications subsystem is present.
 *
 * <p>Transaction shape: each connector's HTTP fetch happens outside any
 * transaction; that (org, source) reconcile + event emission share one
 * {@link TransactionTemplate} transaction so the outbox rows commit iff
 * the assertion rows do.
 */
@Slf4j
@Service
public class KevCatalogSyncService {

	@Autowired
	private List<KevSourceConnector> connectors;

	@Autowired
	private KevAssertionService kevAssertionService;

	@Autowired
	private IntegrationRepository integrationRepository;

	@Autowired
	private EncryptionService encryptionService;

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

	/** Map IntegrationType → matching source enum. CISA_KEV / VULNCHECK_KEV only. */
	private static KevSource toSource(IntegrationType type) {
		if (type == IntegrationType.CISA_KEV) return KevSource.CISA;
		if (type == IntegrationType.VULNCHECK_KEV) return KevSource.VULNCHECK;
		return null;
	}

	/**
	 * One full sync pass across every org × every enabled KEV source.
	 * Returns the total number of genuinely new (org, CVE) pairs across
	 * the pass. Never throws — the scheduler tick treats every outcome as
	 * "try again next tick".
	 *
	 * <p><b>Caller contract: hold the {@code SYNC_KEV_CATALOG} advisory lock.</b>
	 * Per-org reconciles are separate transactions, so two concurrent
	 * passes could double-emit KEV_ADDED or collide on the
	 * {@code (org_id, source, cve_id)} unique constraint. The Pro
	 * scheduler serializes this behind the advisory lock; any other
	 * caller (e.g. a future CE scheduler) must do the same.
	 */
	public int syncCatalog() {
		Map<KevSource, KevSourceConnector> bySource = new HashMap<>();
		for (KevSourceConnector c : connectors) bySource.put(c.source(), c);

		int totalNew = 0;
		for (IntegrationType type : List.of(IntegrationType.CISA_KEV, IntegrationType.VULNCHECK_KEV)) {
			KevSourceConnector connector = bySource.get(toSource(type));
			if (connector == null) continue;
			List<Integration> rows = integrationRepository.listEnabledIntegrationsByType(type.name());
			for (Integration row : rows) {
				try {
					IntegrationData id = IntegrationData.dataFromRecord(row);
					if (id.getOrg() == null) continue;
					totalNew += syncOneOrgSource(id.getOrg(), connector, decryptCredential(id));
				} catch (Exception e) {
					log.error("KEV sync failed for integration {} ({})", row.getUuid(), type, e);
				}
			}
		}
		return totalNew;
	}

	/**
	 * Sync one (org, source). Public so the configure-flow can fire an
	 * immediate async sync after an org admin sets a VulnCheck token,
	 * without waiting for the next scheduler tick.
	 */
	@Async
	public void syncOrgSourceAsync(UUID orgUuid, IntegrationType type) {
		KevSource source = toSource(type);
		if (source == null) return;
		KevSourceConnector connector = connectors.stream()
				.filter(c -> c.source() == source)
				.findFirst().orElse(null);
		if (connector == null) {
			log.warn("KEV connector missing for source {}", source);
			return;
		}
		Integration row = integrationRepository
				.findIntegrationByOrgTypeIdentifier(orgUuid.toString(), type.name(), "base")
				.orElse(null);
		if (row == null) {
			log.warn("KEV async sync requested for {}/{} but no integration row found", orgUuid, type);
			return;
		}
		try {
			IntegrationData id = IntegrationData.dataFromRecord(row);
			syncOneOrgSource(orgUuid, connector, decryptCredential(id));
		} catch (Exception e) {
			log.error("KEV async sync failed for {}/{}", orgUuid, type, e);
		}
	}

	private String decryptCredential(IntegrationData id) {
		String enc = id.getSecret();
		if (enc == null || enc.isEmpty()) return null;
		try {
			return encryptionService.decrypt(enc);
		} catch (Exception e) {
			log.warn("Failed to decrypt KEV integration credential for org {}", id.getOrg(), e);
			return null;
		}
	}

	private int syncOneOrgSource(UUID orgUuid, KevSourceConnector connector, String credential) {
		boolean bootstrap = kevAssertionService.countRecordsForOrg(orgUuid) == 0;
		List<KevAssertionData> entries = connector.fetchCatalog(credential);
		if (entries.isEmpty()) {
			// Connector already logged the cause; skip reconcile (fail-closed).
			return 0;
		}
		TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
		KevCatalogApplyOutcome outcome = txTemplate.execute(status -> {
			KevCatalogApplyOutcome applied = kevAssertionService.applyCatalog(
					orgUuid, connector.source(), entries);
			if (!bootstrap) {
				emitKevAddedEvents(orgUuid, applied.newlyKevCveIds());
			}
			return applied;
		});
		if (outcome == null) return 0;
		if (bootstrap) {
			log.info("KEV catalog bootstrapped for org {} source {}: {} entries; KEV_ADDED event pass skipped",
					orgUuid, connector.source(), outcome.total());
		} else {
			// AFTER commit: kev_assertions is now persisted, so the async
			// recompute reads the new KEV status. Bootstrap is excluded
			// (would storm a recompute of every release on first load),
			// matching the KEV_ADDED event-pass guard above.
			triggerKevReGate(orgUuid, outcome.newlyKevCveIds());
		}
		return outcome.newlyKevCveIds().size();
	}

	/**
	 * For each genuinely new KEV CVE in this org, hand off to the shared
	 * {@code @Async} fan-out to recompute the metrics of every release
	 * within the org that ships the CVE, so the KEV gate re-fires.
	 * Called only after the reconcile transaction has committed.
	 */
	private void triggerKevReGate(UUID orgUuid, List<String> newlyKevCveIds) {
		if (newlyKevCveIds.isEmpty()) return;
		for (String cveId : newlyKevCveIds) {
			List<VulnerabilityRecord> affected =
					vulnerabilityRecordRepository.findAllOrgsByVulnIdOrAlias(cveId);
			boolean affectsOrg = affected.stream()
					.anyMatch(vr -> orgUuid.equals(vr.getOrg()));
			if (affectsOrg) {
				vulnAnalysisUpdateService.recomputeReleasesForNewlyKev(cveId, Set.of(orgUuid));
			}
		}
	}

	/**
	 * For each genuinely new KEV CVE in this org, find every org-scoped
	 * vulnerability record (within the same org) that knows the CVE
	 * (canonical id or alias) and hand it to the notification hook.
	 * No-op when the hook is absent (CE).
	 */
	private void emitKevAddedEvents(UUID orgUuid, List<String> newlyKevCveIds) {
		if (vulnerabilityRecordChangeHook == null || newlyKevCveIds.isEmpty()) return;
		for (String cveId : newlyKevCveIds) {
			List<VulnerabilityRecord> affected =
					vulnerabilityRecordRepository.findAllOrgsByVulnIdOrAlias(cveId);
			List<VulnerabilityRecord> inOrg = affected.stream()
					.filter(vr -> orgUuid.equals(vr.getOrg()))
					.collect(Collectors.toCollection(ArrayList::new));
			for (VulnerabilityRecord vr : inOrg) {
				VulnerabilityRecordData vrd = VulnerabilityRecordData.dataFromRecord(vr);
				vulnerabilityRecordChangeHook.onKevAdded(vr.getOrg(), vr.getPrimaryVulnId(), vrd);
			}
			if (!inOrg.isEmpty()) {
				log.info("KEV_ADDED: {} now KEV-listed in org {}, notified {} record(s)",
						cveId, orgUuid, inOrg.size());
			}
		}
	}
}
