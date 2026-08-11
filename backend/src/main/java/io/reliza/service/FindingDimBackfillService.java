/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.Utils;
import io.reliza.model.FindingChangeEvent;
import io.reliza.model.FindingChangeEventV3;
import io.reliza.model.FindingDim;
import io.reliza.repositories.FindingChangeEventV3Repository;
import io.reliza.repositories.FindingDimRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the branch-grain "events-lite" {@code finding_change_events_v3} + shared {@code finding_dim}
 * store: dimension upsert/resolution, the v3 fact write, and hydration of v3 facts back into full
 * {@link FindingChangeEvent}s for the reconstruction engine (board task #38 normalization + the v3
 * follow-on). The v1 ({@code finding_change_events}) and v2 ({@code finding_change_events_v2}) fact
 * tables it once backfilled were dropped in V64; v3 is now the sole store.
 */
@Service
@Slf4j
public class FindingDimBackfillService {

	@Autowired private FindingDimRepository findingDimRepository;
	@Autowired private FindingChangeEventV3Repository v3Repository;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private GetOrganizationService getOrganizationService;

	/**
	 * Whether {@code org} still needs a BRANCH-GRAIN v3 backfill at the CURRENT dimension key version -- no
	 * v3 watermark, or a watermark below the current {@link FindingDimKey#KEY_VERSION}. Fail-safe: a lookup
	 * error reads as "needs backfill".
	 */
	public boolean needsV3Backfill(UUID org) {
		try {
			return getOrganizationService.getOrganizationData(org)
					.map(od -> od.getSettings() == null
							|| !od.getSettings().isFindingChangeV3BackfillComplete()
							|| od.getSettings().getFindingChangeV3BackfillKeyVersionOrDefault() < FindingDimKey.KEY_VERSION)
					.orElse(false); // org gone -- nothing to backfill
		} catch (Exception e) {
			log.error("finding_change_events v3 backfill: watermark lookup failed for org {}; treating as needs-backfill", org, e);
			return true;
		}
	}

	/**
	 * Writes a batch of BRANCH-GRAIN {@link FindingChangeEvent}s (produced by the branch-chained diff) to
	 * the "events-lite" {@code finding_change_events_v3} table + shared {@code finding_dim}: upsert each
	 * distinct dimension, resolve ids, then insert a slim branch-grain fact per event. Idempotent
	 * ({@code ON CONFLICT DO NOTHING}). The caller supplies the transactional context. Each event MUST
	 * carry its {@code branchUuid} (the grain anchor); its {@code releaseUuid} is persisted as the fact's
	 * {@code first_release_uuid} provenance.
	 *
	 * <p>Reached from the branch-chained backfill (via {@code FindingChangeEventBackfillService.backfillReleaseV3})
	 * and from the live emit ({@code FindingChangeEventEmitter.emitV3}).
	 *
	 * @return the number of v3 fact rows actually inserted
	 */
	public int writeEventsToV3(UUID org, List<FindingChangeEvent> events) {
		if (events == null || events.isEmpty()) {
			return 0;
		}
		Map<String, UUID> dimIdByHexHash = resolveDims(org, events);
		// Order fact inserts by the dedup tuple (first_release_uuid, to_metrics_revision, change_kind, dim)
		// so concurrent writers acquire the v3 dedup-index locks in one global order (deadlock-free).
		List<FindingChangeEvent> ordered = new ArrayList<>(events);
		ordered.sort(Comparator
				.comparing(FindingChangeEvent::getReleaseUuid)
				.thenComparingInt(FindingChangeEvent::getToMetricsRevision)
				.thenComparing(ev -> ev.getChangeKind().name())
				.thenComparing(ev -> hex(FindingDimKey.hash(ev))));
		// Pre-validate before the batch (a throw inside the batch setter would abort it mid-flight).
		final UUID[] dims = new UUID[ordered.size()];
		for (int i = 0; i < ordered.size(); i++) {
			FindingChangeEvent ev = ordered.get(i);
			if (!org.equals(ev.getOrg())) {
				throw new IllegalArgumentException("writeEventsToV3: mixed-org batch (dim org " + org
						+ " != event org " + ev.getOrg() + ") -- batches must be single-org");
			}
			if (ev.getBranchUuid() == null) {
				// v3 is branch-grain: a null branch anchor would violate the NOT NULL grain column and
				// silently corrupt the reconstruction grain. Fail loud + retryable (every caller isolates).
				throw new IllegalStateException("writeEventsToV3: null branchUuid for event (release "
						+ ev.getReleaseUuid() + ", rev " + ev.getToMetricsRevision() + ", kind "
						+ ev.getChangeKind() + ") -- the branch-chained producer must stamp the branch anchor");
			}
			UUID findingDim = dimIdByHexHash.get(hex(FindingDimKey.hash(ev)));
			if (findingDim == null) {
				throw new IllegalStateException("writeEventsToV3: finding_dim unresolved for event (release "
						+ ev.getReleaseUuid() + ", rev " + ev.getToMetricsRevision() + ", kind "
						+ ev.getChangeKind() + ") -- dim insert not yet visible; retry");
			}
			dims[i] = findingDim;
		}
		// JDBC batch: one pipelined round-trip instead of one INSERT per event (the dominant seed cost on
		// deep histories). Rows go in the deadlock-ordered sequence above. ON CONFLICT DO NOTHING keeps it
		// idempotent; per-statement counts (0 on conflict, 1 on insert) stay accurate without
		// reWriteBatchedInserts, so the repair sweep's hole detection still works.
		int[] counts = jdbcTemplate.batchUpdate(V3_FACT_INSERT_SQL, new BatchPreparedStatementSetter() {
			@Override public void setValues(PreparedStatement ps, int i) throws SQLException {
				FindingChangeEvent ev = ordered.get(i);
				int p = 1;
				ps.setObject(p++, UUID.randomUUID());
				ps.setObject(p++, ev.getOrg());
				ps.setString(p++, ev.getEntityType().name());
				ps.setObject(p++, ev.getComponentUuid());
				ps.setObject(p++, ev.getBranchUuid());
				ps.setObject(p++, dims[i]);
				ps.setString(p++, ev.getFindingKind().name());
				ps.setString(p++, ev.getChangeKind().name());
				setTs(ps, p++, ev.getChangeDate());
				ps.setObject(p++, ev.getReleaseUuid());
				ps.setString(p++, ev.getVersion());
				ps.setString(p++, ev.getComponentName());
				ps.setInt(p++, ev.getToMetricsRevision());
				ps.setString(p++, ev.getSeverity());
				ps.setString(p++, ev.getPreviousSeverity());
				setBool(ps, p++, ev.getKnownExploited());
				ps.setString(p++, ev.getAnalysisState());
				setTs(ps, p++, ev.getCreatedDate());
			}
			@Override public int getBatchSize() { return ordered.size(); }
		});
		int inserted = 0;
		for (int c : counts) {
			if (c > 0) {
				inserted += c;
			}
		}
		return inserted;
	}

	private static final String V3_FACT_INSERT_SQL =
			"INSERT INTO rearm.finding_change_events_v3 (uuid, org, entity_type, component_uuid, branch_uuid, "
			+ "finding_dim, finding_kind, change_kind, change_date, first_release_uuid, version, "
			+ "component_name, to_metrics_revision, severity, previous_severity, known_exploited, "
			+ "analysis_state, created_date) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
			+ "ON CONFLICT (first_release_uuid, to_metrics_revision, change_kind, finding_dim) DO NOTHING";

	private static final String DIM_INSERT_SQL =
			"INSERT INTO rearm.finding_dim (uuid, org, dim_hash, key_version, finding_kind, finding_key, purl, "
			+ "vuln_id, cwe_id, rule_id, location, violation_type, aliases) "
			+ "VALUES (?,?,?,?,?,?,?,?,?,?,?,?, CAST(? AS jsonb)) ON CONFLICT (org, dim_hash) DO NOTHING";

	private static void setTs(PreparedStatement ps, int idx, ZonedDateTime z) throws SQLException {
		if (z == null) {
			ps.setNull(idx, Types.TIMESTAMP_WITH_TIMEZONE);
		} else {
			ps.setObject(idx, z.toOffsetDateTime());
		}
	}

	private static void setBool(PreparedStatement ps, int idx, Boolean b) throws SQLException {
		if (b == null) {
			ps.setNull(idx, Types.BOOLEAN);
		} else {
			ps.setBoolean(idx, b);
		}
	}

	/**
	 * Upserts the distinct dimensions for a batch of finding-change events and returns hex(dim_hash) ->
	 * finding_dim uuid. Representative payload = the event with the LATEST change_date for each hash
	 * (deterministic). Inserts are ordered by dim_hash for deterministic lock acquisition (deadlock-safety
	 * for the concurrent write path).
	 */
	private Map<String, UUID> resolveDims(UUID org, List<FindingChangeEvent> events) {
		// hex(hash) -> the representative (latest-by-change_date) event
		Map<String, FindingChangeEvent> repByHash = new HashMap<>();
		for (FindingChangeEvent ev : events) {
			String hex = hex(FindingDimKey.hash(ev));
			repByHash.merge(hex, ev, (a, b) ->
					a.getChangeDate().isAfter(b.getChangeDate()) ? a : b);
		}
		// Insert ordered by hash (deterministic lock order), as ONE pipelined JDBC batch instead of one
		// INSERT per distinct dim.
		List<Map.Entry<String, FindingChangeEvent>> ordered = new ArrayList<>(repByHash.entrySet());
		ordered.sort(Comparator.comparing(Map.Entry::getKey));
		jdbcTemplate.batchUpdate(DIM_INSERT_SQL, new BatchPreparedStatementSetter() {
			@Override public void setValues(PreparedStatement ps, int i) throws SQLException {
				FindingChangeEvent ev = ordered.get(i).getValue();
				int p = 1;
				ps.setObject(p++, UUID.randomUUID());
				ps.setObject(p++, org);
				ps.setBytes(p++, FindingDimKey.hash(ev));
				ps.setInt(p++, FindingDimKey.KEY_VERSION);
				ps.setString(p++, ev.getFindingKind().name());
				ps.setString(p++, ev.getFindingKey());
				ps.setString(p++, ev.getPurl());
				ps.setString(p++, ev.getVulnId());
				ps.setString(p++, ev.getCweId());
				ps.setString(p++, ev.getRuleId());
				ps.setString(p++, ev.getLocation());
				ps.setString(p++, ev.getViolationType());
				ps.setString(p++, ev.getAliases() == null ? null : writeAliases(ev));
			}
			@Override public int getBatchSize() { return ordered.size(); }
		});
		// Resolve ids.
		List<byte[]> hashes = new ArrayList<>();
		for (String hex : repByHash.keySet()) {
			hashes.add(unhex(hex));
		}
		Map<String, UUID> byHex = new HashMap<>();
		for (FindingDim d : findingDimRepository.findByOrgAndHashes(org, hashes)) {
			byHex.put(hex(d.getDimHash()), d.getUuid());
		}
		return byHex;
	}

	/**
	 * Hydrates BRANCH-GRAIN v3 facts (events-lite) in a window back into full {@link FindingChangeEvent}s,
	 * keyed by {@code first_release_uuid} (the RECONSTRUCTION read -- see
	 * {@code FindingComparisonService.readFindingChangesInRange}). Each stitched event's {@code releaseUuid}
	 * is set to the fact's {@code first_release_uuid} so the reverse-replay engine (which groups by release)
	 * is unchanged; it simply sees a release's deduped transition set.
	 */
	@Transactional(readOnly = true)
	public List<FindingChangeEvent> hydrateInRangeV3(UUID org, Collection<UUID> firstReleaseUuids,
			ZonedDateTime from, ZonedDateTime to) {
		return hydrateFactsV3(v3Repository.findInRangeByFirstRelease(org, firstReleaseUuids, from, to));
	}

	/**
	 * ORG-GRAIN v3 hydration for the "posture over time" read (board task #39): every branch-transition
	 * whose {@code change_date} is in {@code [from,to]} for the given (already authorized) components,
	 * NOT bounded to a release set produced in the window -- so re-scan-driven changes on releases
	 * produced before the window are included. The {@code component_uuid IN} clause carries the
	 * perspective/authorization boundary. Stitched identically to {@link #hydrateInRangeV3} (each event's
	 * {@code releaseUuid} = the fact's {@code first_release_uuid}, its producing release/scan).
	 */
	@Transactional(readOnly = true)
	public List<FindingChangeEvent> hydrateInRangeByComponentsV3(UUID org, Collection<UUID> componentUuids,
			ZonedDateTime from, ZonedDateTime to, Pageable pageable) {
		if (componentUuids == null || componentUuids.isEmpty()) {
			return List.of();
		}
		return hydrateFactsV3(v3Repository.findInRangeByOrgAndComponents(org, componentUuids, from, to, pageable));
	}

	/** Reassembles v3 facts into full {@link FindingChangeEvent}s via a single batched dim lookup. */
	private List<FindingChangeEvent> hydrateFactsV3(List<FindingChangeEventV3> facts) {
		if (facts.isEmpty()) {
			return List.of();
		}
		Map<UUID, FindingDim> dims = new HashMap<>();
		List<UUID> dimIds = facts.stream().map(FindingChangeEventV3::getFindingDim).distinct().toList();
		for (FindingDim d : findingDimRepository.findAllById(dimIds)) {
			dims.put(d.getUuid(), d);
		}
		List<FindingChangeEvent> out = new ArrayList<>(facts.size());
		for (FindingChangeEventV3 f : facts) {
			FindingDim d = dims.get(f.getFindingDim());
			if (d == null) {
				throw new IllegalStateException("finding_change_events_v3 row " + f.getUuid()
						+ " references missing finding_dim " + f.getFindingDim());
			}
			out.add(stitchV3(f, d));
		}
		return out;
	}

	/**
	 * Reassembles a full v1-shaped event from a BRANCH-GRAIN v3 fact + its dimension. {@code releaseUuid}
	 * is set from the fact's {@code first_release_uuid} (the release that produced the transition) so the
	 * release-keyed reverse-replay engine is unchanged; {@code branchUuid} is carried too.
	 */
	private static FindingChangeEvent stitchV3(FindingChangeEventV3 f, FindingDim d) {
		FindingChangeEvent ev = new FindingChangeEvent();
		ev.setUuid(f.getUuid());
		ev.setOrg(f.getOrg());
		ev.setEntityType(f.getEntityType());
		ev.setReleaseUuid(f.getFirstReleaseUuid());
		ev.setBranchUuid(f.getBranchUuid());
		ev.setVersion(f.getVersion());
		ev.setComponentUuid(f.getComponentUuid());
		ev.setComponentName(f.getComponentName());
		ev.setChangeDate(f.getChangeDate());
		ev.setToMetricsRevision(f.getToMetricsRevision());
		ev.setChangeKind(f.getChangeKind());
		ev.setFindingKind(f.getFindingKind());
		ev.setSeverity(f.getSeverity());
		ev.setPreviousSeverity(f.getPreviousSeverity());
		ev.setKnownExploited(f.getKnownExploited());
		ev.setAnalysisState(f.getAnalysisState());
		ev.setCreatedDate(f.getCreatedDate());
		// Intrinsic identity from the dimension.
		ev.setFindingKey(d.getFindingKey());
		ev.setPurl(d.getPurl());
		ev.setVulnId(d.getVulnId());
		ev.setCweId(d.getCweId());
		ev.setRuleId(d.getRuleId());
		ev.setLocation(d.getLocation());
		ev.setViolationType(d.getViolationType());
		ev.setAliases(d.getAliases());
		return ev;
	}

	private static String writeAliases(FindingChangeEvent ev) {
		try {
			return Utils.OM.writeValueAsString(ev.getAliases());
		} catch (Exception e) {
			throw new IllegalStateException("Failed to serialize aliases for finding_dim backfill", e);
		}
	}

	private static String hex(byte[] b) {
		return HexFormat.of().formatHex(b);
	}

	private static byte[] unhex(String s) {
		return HexFormat.of().parseHex(s);
	}
}
