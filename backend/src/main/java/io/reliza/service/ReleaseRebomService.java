/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseRebom;
import io.reliza.model.ReleaseRebomData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.repositories.ReleaseRebomRepository;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ReleaseRebomService {

	/**
	 * A release's stored rows as one scalar read saw them, with the record's uuid, revision and
	 * creation date (SCORE-71 ADR-11). A store is conditioned on {@code revision}; {@code record} null
	 * means the release had no record.
	 */
	public record RowsRead(UUID record, int revision, ZonedDateTime created, List<ReleaseBom> rows) {
		public static RowsRead none() {
			return new RowsRead(null, 0, null, List.of());
		}

		public boolean seenRecord() {
			return null != record;
		}
	}
	
	@Autowired
    private AuditService auditService;
	
	private final ReleaseRebomRepository repository;
	
	ReleaseRebomService(ReleaseRebomRepository repository) {
	    this.repository = repository;
	}
	
	public Optional<ReleaseRebom> getReleaseRebom (UUID id) {
		Optional<ReleaseRebom> orr = Optional.empty();
		if (null != id) {
			orr = repository.findById(id);
		}
		return orr;
	}
	public Optional<ReleaseRebom> getReleaseRebom (UUID org, UUID release) {
		Optional<ReleaseRebom> orr = Optional.empty();
		if (null != org && null != release) {
			orr = repository.findReleaseRebomByOrgAndRelease(org.toString(), release.toString());
		}
		return orr;
	}
	public Optional<ReleaseRebomData> getReleaseRebomData (ReleaseData rd) {
		Optional<ReleaseRebomData> releaseRebomData = Optional.empty();
		Optional<ReleaseRebom> orr = getReleaseRebom(rd.getOrg(), rd.getUuid());
		if (orr.isPresent()) {
			releaseRebomData = Optional
							.of(
									ReleaseRebomData
									.dataFromRecord(orr
										.get()
								));
		}
		return releaseRebomData;
	}

	private ReleaseRebom setReboms (UUID org, UUID release, 
													List<ReleaseBom> reboms, WhoUpdated wu) {
		ReleaseRebom rr = new ReleaseRebom();
		Optional<ReleaseRebom> orr = getReleaseRebom(org, release);
		if(orr.isPresent()){
			rr = orr.get();
		}
		ReleaseRebomData rrd = ReleaseRebomData.releaseRebomFactory(org, release, reboms);
		Map<String,Object> recordData = Utils.dataToRecord(rrd);
		return saveReleaseRebom(rr, recordData, wu);
	}
	
	private ReleaseRebom saveReleaseRebom (ReleaseRebom rr, Map<String,Object> recordData, WhoUpdated wu) {
		Optional<ReleaseRebom> orr = getReleaseRebom(rr.getUuid());
		if (orr.isPresent()) {
			auditService.createAndSaveAuditRecord(TableName.VCS_REPOSITORIES, rr);
			rr.setRevision(rr.getRevision() + 1);
			rr.setLastUpdatedDate(ZonedDateTime.now());
		}
		rr.setRecordData(recordData);
		rr = (ReleaseRebom) WhoUpdated.injectWhoUpdatedData(rr, wu);
		return repository.save(rr);
	}
	
    public List<ReleaseBom> getReleaseBoms(ReleaseData rd){
        List<ReleaseBom> reboms = new ArrayList<>();
		Optional<ReleaseRebomData> rrd = getReleaseRebomData(rd);
		if(rrd.isPresent()){
			reboms = rrd.get().getReboms();
		}
        return reboms;
    }

    public void setReboms(ReleaseData rd, List<ReleaseBom> reboms, WhoUpdated wu){
		setReboms(rd.getOrg(),rd.getUuid(), reboms, wu);
    }

	/**
	 * Invalidates the stored merged rows of {@code releases} and of every release of {@code org}
	 * above them (SCORE-71 ADR-1): each existing record's row list is emptied and its revision moved,
	 * already empty or not, so a store fenced on an earlier read is refused (ADR-11); the next export
	 * or merge rebuilds the rows. Runs in the caller's transaction; nothing for an empty collection.
	 *
	 * @return the number of records touched
	 */
	public int invalidateRowsOfAndAbove(UUID org, Collection<UUID> releases) {
		if (null == releases || releases.isEmpty()) return 0;
		String[] seeds = releases.stream().map(UUID::toString).toArray(String[]::new);
		return repository.invalidateRowsOfReleasesAndProductsAbove(org.toString(), seeds);
	}

	/**
	 * The component-seeded form of {@link #invalidateRowsOfAndAbove}: every release of
	 * {@code component} in {@code org} and every release above them (SCORE-71 ADR-9).
	 *
	 * @return the number of records touched
	 */
	public int invalidateRowsOfComponentReleasesAndAbove(UUID org, UUID component) {
		return repository.invalidateRowsOfComponentReleasesAndProductsAbove(org.toString(), component.toString());
	}

	/**
	 * Reads the release's stored rows freshly, with the record's revision, as scalars that bypass the
	 * persistence context (SCORE-71 ADR-11). A missing record reads as {@link RowsRead#none()}.
	 */
	public RowsRead readRows(ReleaseData rd) {
		List<Object[]> found = repository.readStamp(rd.getOrg().toString(), rd.getUuid().toString());
		if (found.isEmpty()) return RowsRead.none();
		if (found.size() > 1) {
			throw new IllegalStateException("More than one release_reboms record for release " + rd.getUuid());
		}
		Object[] row = found.get(0);
		UUID record = UUID.fromString(row[0].toString());
		int revision = ((Number) row[1]).intValue();
		ZonedDateTime created = null == row[2] ? null
				: Instant.ofEpochMilli(((Number) row[2]).longValue()).atZone(ZoneOffset.UTC);
		List<ReleaseBom> rows = List.of();
		if (null != row[3]) {
			try {
				List<ReleaseBom> parsed = Utils.OM.readValue(row[3].toString(), ReleaseRebomData.class).getReboms();
				if (null != parsed) rows = parsed;
			} catch (Exception e) {
				throw new IllegalStateException("Could not parse release_reboms record " + record, e);
			}
		}
		return new RowsRead(record, revision, created, rows);
	}

	/**
	 * Stores {@code rows} as the release's row list only when its record is still at the revision
	 * {@code seen} read, or inserts the record only when the release still has none (SCORE-71 ADR-11).
	 * A refused write stores nothing and logs one line; the next request rebuilds.
	 *
	 * @return the stamp the write left, or empty when it was refused
	 */
	@Transactional
	public Optional<RowsRead> storeRowsIfUnchanged(ReleaseData rd, List<ReleaseBom> rows, RowsRead seen, WhoUpdated wu) {
		Map<String, Object> recordData = Utils.dataToRecord(
				ReleaseRebomData.releaseRebomFactory(rd.getOrg(), rd.getUuid(), rows));
		WhoUpdated.injectWhoUpdatedIntoMap(recordData, wu);
		String json;
		try {
			json = Utils.OM.writeValueAsString(recordData);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to serialize release_reboms record data", e);
		}
		if (seen.seenRecord()) {
			int n = repository.updateRecordIfRevision(seen.record(), json, seen.revision());
			if (n == 1) {
				ReleaseRebom snapshot = new ReleaseRebom();
				snapshot.setUuid(seen.record());
				snapshot.setRevision(seen.revision());
				if (null != seen.created()) snapshot.setCreatedDate(seen.created());
				snapshot.setSchemaVersion(0);
				snapshot.setRecordData(Utils.dataToRecord(
						ReleaseRebomData.releaseRebomFactory(rd.getOrg(), rd.getUuid(), seen.rows())));
				auditService.createAndSaveAuditRecord(TableName.RELEASE_REBOMS, snapshot);
				return Optional.of(new RowsRead(seen.record(), seen.revision() + 1, seen.created(), rows));
			}
			log.info("merged SBOM rows of release {} not stored: its record moved (revision {} seen) while the merge ran; the next request rebuilds them (SCORE-71)",
					rd.getUuid(), seen.revision());
			return Optional.empty();
		}
		UUID uuid = UUID.randomUUID();
		int n = repository.insertRecordIfAbsent(uuid, json, rd.getOrg().toString(), rd.getUuid().toString());
		if (n == 1) {
			return Optional.of(new RowsRead(uuid, 0, ZonedDateTime.now(), rows));
		}
		log.info("merged SBOM rows of release {} not stored: a record appeared while the merge ran; the next request rebuilds them (SCORE-71)",
				rd.getUuid());
		return Optional.empty();
	}
}
