/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.kev;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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

import io.reliza.common.Utils;
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
 * <p><b>Upstream fetch is deduplicated per pass.</b> The catalog a source
 * returns depends only on its {@code (source, credential)}, not on the org,
 * so a pass fetches each distinct pair exactly once and reuses the parsed
 * result for every org that shares it (see {@link #fetchCached}). This
 * matters because {@code CISA_KEV} is default-enabled for every org and its
 * public feed ignores the credential: without dedup, N orgs would each GET
 * the identical feed from one egress IP and trip CISA's rate limit / WAF
 * (403). Token-gated sources collapse per shared token; distinct tokens
 * still fetch separately.
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
		// TPF-131: re-launch the re-gates whose statement failed on an earlier
		// tick, before this pass adds new ones (cross-bean, so @Async holds).
		vulnAnalysisUpdateService.drainKevReGateRetries()
				.forEach((org, ids) -> vulnAnalysisUpdateService.recomputeReleasesForNewlyKev(ids, org));

		Map<KevSource, KevSourceConnector> bySource = new HashMap<>();
		for (KevSourceConnector c : connectors) bySource.put(c.source(), c);

		// One upstream fetch per (source, credential) for the whole pass --
		// collapses the per-org CISA fan-out to a single GET. See fetchCached.
		Map<FetchKey, KevCatalogFetch> catalogCache = new HashMap<>();

		int totalNew = 0;
		for (IntegrationType type : List.of(IntegrationType.CISA_KEV, IntegrationType.VULNCHECK_KEV)) {
			KevSourceConnector connector = bySource.get(toSource(type));
			if (connector == null) continue;
			List<Integration> rows = integrationRepository.listEnabledIntegrationsByType(type.name());
			for (Integration row : rows) {
				try {
					IntegrationData id = IntegrationData.dataFromRecord(row);
					if (id.getOrg() == null) continue;
					KevCatalogFetch fetch = fetchCached(catalogCache, connector, decryptCredential(id));
					totalNew += syncOneOrgSource(id.getOrg(), row.getUuid(), connector, fetch);
				} catch (Exception e) {
					log.error("KEV sync failed for integration {} ({})", row.getUuid(), type, e);
				}
			}
		}
		return totalNew;
	}

	/** Fetch de-dup key: the catalog depends only on the source and its credential. */
	private record FetchKey(KevSource source, String credential) {}

	/**
	 * Fetch a source's catalog at most once per pass, memoized by
	 * {@code (source, credential)}. Empty (failed) results are cached too, so
	 * a bad upstream fails closed for every sharing org without re-hitting it
	 * this pass -- the fan-out is removed on the failure path as well.
	 */
	private KevCatalogFetch fetchCached(Map<FetchKey, KevCatalogFetch> cache,
			KevSourceConnector connector, String credential) {
		return cache.computeIfAbsent(new FetchKey(connector.source(), credential),
				k -> connector.fetchCatalog(credential));
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
			// Single org, single fetch -- no cross-org dedup needed here.
			syncOneOrgSource(orgUuid, row.getUuid(), connector, connector.fetchCatalog(decryptCredential(id)));
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

	/**
	 * Reconcile one source's already-fetched catalog into one org. The fetch
	 * is done by the caller (deduplicated per pass in {@link #syncCatalog()},
	 * or a single fetch in the async configure-flow path) so the public feed
	 * is not re-requested per org.
	 *
	 * <p>FDAW-2: once the apply has committed, the catalog header and the sync instant are stamped on
	 * the integration row in place (no audit row, no revision), so a VDR can name the catalog date it
	 * was checked against. A failed apply throws out of the template and never reaches the stamp.
	 */
	private int syncOneOrgSource(UUID orgUuid, UUID integrationRow, KevSourceConnector connector, KevCatalogFetch fetch) {
		List<KevAssertionData> entries = fetch.entries();
		if (entries.isEmpty()) {
			// Connector already logged the cause; skip reconcile (fail-closed).
			return 0;
		}
		boolean bootstrap = kevAssertionService.countRecordsForOrg(orgUuid) == 0;
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
		// The apply has committed: a failed stamp must not skip the re-gate below, so it is caught here.
		// The documents then name the previous sync until the next one stamps again.
		try {
			integrationRepository.stampKevCatalogState(integrationRow, fetch.catalogVersion(),
					null == fetch.dateReleased() ? null : Utils.toRfc3339Utc(fetch.dateReleased().atZone(ZoneOffset.UTC)),
					Utils.toRfc3339Utc(ZonedDateTime.now(ZoneOffset.UTC)));
		} catch (RuntimeException e) {
			log.error("KEV catalog applied for org {} source {}, but stamping its header on integration {} failed",
					orgUuid, connector.source(), integrationRow, e);
		}
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
	 * Collects the genuinely new KEV CVEs of this org that the org has a
	 * vulnerability record of, and hands them in ONE call to the shared
	 * {@code @Async} fan-out, which recomputes the metrics of every release
	 * within the org that ships any of them, so the KEV gate re-fires (one
	 * task per org per sync, not one per CVE: TPF-131).
	 * Called only after the reconcile transaction has committed.
	 */
	private void triggerKevReGate(UUID orgUuid, List<String> newlyKevCveIds) {
		if (newlyKevCveIds.isEmpty()) return;
		List<String> inOrg = new ArrayList<>();
		for (String cveId : newlyKevCveIds) {
			List<VulnerabilityRecord> affected =
					vulnerabilityRecordRepository.findAllOrgsByVulnIdOrAlias(cveId);
			boolean affectsOrg = affected.stream()
					.anyMatch(vr -> orgUuid.equals(vr.getOrg()));
			if (affectsOrg) {
				inOrg.add(cveId);
			}
		}
		if (!inOrg.isEmpty()) {
			vulnAnalysisUpdateService.recomputeReleasesForNewlyKev(inOrg, orgUuid);
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
