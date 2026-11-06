/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.SupportBulkAct;
import io.reliza.model.SupportBulkActKind;
import io.reliza.repositories.SupportBulkActRepository;

/**
 * The receipt of every bulk support call (FDAW-1 F-6, WRITEUP 6.6, FDAW-14 3.4): one insert-only
 * {@code support_bulk_acts} row per call, written after the loop with its final counts. Shared by the
 * support sweeps of {@link SbomComponentService} and the hash sweeps of
 * {@link ReleaseSbomJustificationService}; B6's supplier-by-namespace reuses it with its own kind.
 */
@Service
public class SupportBulkActService {

	@Autowired
	private SupportBulkActRepository supportBulkActRepository;

	/** What one receipt records; {@code identifiers} are hashed, never stored. */
	public record Receipt(UUID act, UUID org, SupportBulkActKind kind, UUID release, UUID releaseSbom,
			Map<String, Object> selector, Collection<String> identifiers, int appliedCount, UUID assertedBy,
			Map<String, Object> recordData) {}

	/**
	 * Inserts the receipt in its own transaction, so it is the record of a completed sweep whatever the
	 * caller's transaction does next: rows naming no receipt are the trace of a sweep that did not finish.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public SupportBulkAct record(Receipt r) {
		SupportBulkAct act = new SupportBulkAct();
		act.setUuid(r.act());
		act.setOrg(r.org());
		act.setKind(r.kind());
		act.setRelease(r.release());
		act.setReleaseSbom(r.releaseSbom());
		act.setSelector(r.selector());
		act.setMatchedCount(r.identifiers().size());
		act.setAppliedCount(r.appliedCount());
		act.setListHash(listHash(r.identifiers()));
		act.setAssertedBy(r.assertedBy());
		act.setRecordData(r.recordData());
		return supportBulkActRepository.save(act);
	}

	/**
	 * The newest receipts of an org, {@code limit} clamped silently to
	 * 1..{@link SbomComponentService#SUPPORT_BULK_ACTS_MAX_LIMIT} as the release page clamps its own.
	 */
	public List<SupportBulkAct> listNewest(UUID org, int limit) {
		int lim = Math.min(Math.max(limit, 1), SbomComponentService.SUPPORT_BULK_ACTS_MAX_LIMIT);
		return supportBulkActRepository.listNewestByOrg(org.toString(), lim);
	}

	/**
	 * Lower-case hex sha256 of the identifiers sorted by {@code String.compareTo} and joined by LF: the
	 * component uuids of a support sweep, the component keys of a hash sweep (3.4).
	 */
	public static String listHash(Collection<String> identifiers) {
		String joined = String.join("\n", identifiers.stream().sorted().toList());
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(md.digest(joined.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}
}
