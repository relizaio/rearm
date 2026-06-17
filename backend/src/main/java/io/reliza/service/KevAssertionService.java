/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.dto.KevRecordDetails;
import io.reliza.dto.KevSourceAssertion;
import io.reliza.model.KevAssertion;
import io.reliza.model.KevAssertionData;
import io.reliza.model.KevRansomwareStatus;
import io.reliza.model.KevSource;
import io.reliza.repositories.KevAssertionRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Read + write surface for the global {@link KevAssertion} store. Reads
 * serve the {@code knownExploited} GraphQL surface and the per-source
 * detail view; the single write path is {@link #applyCatalog}, called by
 * {@code KevCatalogSyncService} once per source with a freshly fetched
 * full catalog.
 *
 * <p>Soft delete: a source dropping a CVE marks the row revoked, never
 * deletes it. Read surfaces still treat any asserted CVE (even
 * all-revoked) as known-exploited — the membership probe deliberately
 * ignores {@code revoked_date} — so a truncated or tampered feed cannot
 * blank the KEV list.
 */
@Slf4j
@Service
public class KevAssertionService {

	@Autowired
	private KevAssertionRepository repository;

	/** The full per-source detail for one CVE; empty when no source ever asserted it. */
	public Optional<KevRecordDetails> getKevDetails(String cveId) {
		String normalized = normalizeCveId(cveId);
		if (normalized == null) return Optional.empty();
		List<KevAssertion> rows = repository.findByCveIdOrderBySourceAsc(normalized);
		if (rows.isEmpty()) return Optional.empty();

		List<KevSourceAssertion> assertions = new ArrayList<>(rows.size());
		KevRansomwareStatus aggregate = KevRansomwareStatus.UNSPECIFIED;
		for (KevAssertion row : rows) {
			KevAssertionData d = KevAssertionData.dataFromRecord(row);
			assertions.add(new KevSourceAssertion(
					row.getSource().name(),
					// RFC-3339 UTC instant (…Z), not ZonedDateTime.toString() which
					// would leak a host-zone suffix like +01:00[Europe/London]
					row.getRevokedDate() != null ? row.getRevokedDate().toInstant().toString() : null,
					d.getVendorProject(), d.getProduct(), d.getVulnerabilityName(),
					d.getDateAdded(), d.getShortDescription(), d.getRequiredAction(),
					d.getDueDate(), d.getRansomwareStatus(), d.getRansomwareCampaigns(),
					d.getNotes(), d.getCwes()));
			aggregate = mergeRansomware(aggregate, d.getRansomwareStatus());
		}
		return Optional.of(new KevRecordDetails(normalized, aggregate, assertions));
	}

	/** KNOWN dominates UNKNOWN dominates UNSPECIFIED when several sources disagree. */
	private static KevRansomwareStatus mergeRansomware(KevRansomwareStatus a, KevRansomwareStatus b) {
		if (a == KevRansomwareStatus.KNOWN || b == KevRansomwareStatus.KNOWN) return KevRansomwareStatus.KNOWN;
		if (a == KevRansomwareStatus.UNKNOWN || b == KevRansomwareStatus.UNKNOWN) return KevRansomwareStatus.UNKNOWN;
		return KevRansomwareStatus.UNSPECIFIED;
	}

	/**
	 * Bulk membership probe: of the candidate ids passed in, which are
	 * KEV-listed by any source? Non-CVE ids (GHSA etc.) are filtered out
	 * before the query — assertions are keyed by CVE id only. One round trip.
	 */
	public Set<String> filterKnownExploited(Collection<String> candidateIds) {
		if (candidateIds == null || candidateIds.isEmpty()) return Set.of();
		Set<String> cveIds = new HashSet<>();
		for (String id : candidateIds) {
			String normalized = normalizeCveId(id);
			if (normalized != null) cveIds.add(normalized);
		}
		if (cveIds.isEmpty()) return Set.of();
		return new HashSet<>(repository.findExistingCveIds(cveIds));
	}

	/** True when any of the candidate ids is KEV-listed. */
	public boolean isAnyKnownExploited(Collection<String> candidateIds) {
		return !filterKnownExploited(candidateIds).isEmpty();
	}

	public long countRecords() {
		return repository.count();
	}

	/**
	 * Uppercases and validates a candidate id as a CVE id; returns null for
	 * anything that isn't one (aliases routinely carry GHSA / OSV ids that
	 * can never match the catalog).
	 */
	public static String normalizeCveId(String id) {
		if (id == null) return null;
		String upper = id.trim().toUpperCase(Locale.ROOT);
		return upper.startsWith("CVE-") ? upper : null;
	}

	/**
	 * Outcome of one source's catalog reconciliation. {@code newlyKevCveIds}
	 * are CVEs that had no prior assertion from any source — the only ones
	 * that drive KEV_ADDED events (once KEV, always KEV: re-adds and extra
	 * sources don't re-notify).
	 */
	public record KevCatalogApplyOutcome(List<String> newlyKevCveIds, int updated,
			int revived, int revoked, int total) {}

	/**
	 * Reconcile one source's full catalog: insert new assertions, rewrite
	 * changed ones (JSONB map comparison), un-revoke any that reappeared, and
	 * soft-delete (stamp {@code revoked_date}) this source's rows that are no
	 * longer published. The caller guarantees {@code entries} is a complete,
	 * sanity-checked catalog — an empty or truncated fetch must be rejected
	 * upstream.
	 */
	@Transactional
	public KevCatalogApplyOutcome applyCatalog(KevSource source, List<KevAssertionData> entries) {
		Map<String, KevAssertion> existingForSource = new HashMap<>();
		for (KevAssertion ka : repository.findBySource(source)) {
			existingForSource.put(ka.getCveId(), ka);
		}
		Set<String> assertedAnySource = new HashSet<>(repository.findAllDistinctCveIds());

		List<String> newlyKev = new ArrayList<>();
		int updated = 0;
		int revived = 0;
		Set<String> seen = new HashSet<>();
		ZonedDateTime now = ZonedDateTime.now();
		List<KevAssertion> toSave = new ArrayList<>();

		for (KevAssertionData entry : entries) {
			String cveId = normalizeCveId(entry.getCveId());
			if (cveId == null || !seen.add(cveId)) continue;
			entry.setCveId(cveId);
			Map<String, Object> nextData = entry.toRecordData();
			KevAssertion existing = existingForSource.get(cveId);
			if (existing == null) {
				KevAssertion ka = new KevAssertion();
				ka.setSource(source);
				ka.setCveId(cveId);
				ka.setCreatedDate(now);
				ka.setLastUpdatedDate(now);
				ka.setRecordData(nextData);
				toSave.add(ka);
				if (!assertedAnySource.contains(cveId)) newlyKev.add(cveId);
			} else {
				boolean wasRevoked = existing.getRevokedDate() != null;
				// Byte-stable map comparison: holds for CISA's flat scalars +
				// empty ransomwareCampaigns. When a source populates nested
				// campaign objects, verify the dataToRecord <-> JSONB round-trip
				// is stable with a live-DB test, else every entry reads "changed".
				boolean dataChanged = !nextData.equals(existing.getRecordData());
				if (wasRevoked || dataChanged) {
					existing.setRecordData(nextData);
					existing.setLastUpdatedDate(now);
					if (wasRevoked) {
						existing.setRevokedDate(null);
						revived++;
					} else {
						updated++;
					}
					toSave.add(existing);
				}
			}
		}
		if (!toSave.isEmpty()) repository.saveAll(toSave);

		List<KevAssertion> toRevoke = new ArrayList<>();
		for (KevAssertion existing : existingForSource.values()) {
			if (!seen.contains(existing.getCveId()) && existing.getRevokedDate() == null) {
				existing.setRevokedDate(now);
				existing.setLastUpdatedDate(now);
				toRevoke.add(existing);
			}
		}
		if (!toRevoke.isEmpty()) repository.saveAll(toRevoke);

		if (!newlyKev.isEmpty() || updated > 0 || revived > 0 || !toRevoke.isEmpty()) {
			log.info("KEV catalog reconciled for {}: {} new, {} updated, {} revived, {} revoked, {} total",
					source, newlyKev.size(), updated, revived, toRevoke.size(), seen.size());
		}
		return new KevCatalogApplyOutcome(newlyKev, updated, revived, toRevoke.size(), seen.size());
	}
}
