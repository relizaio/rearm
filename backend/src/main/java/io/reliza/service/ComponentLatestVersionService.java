/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.github.packageurl.PackageURL;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentRepository.CanonicalPurlRow;
import io.reliza.repositories.SbomComponentRepository.DueBucketRow;
import io.reliza.service.DTrackService.ComponentLatestVersion;
import io.reliza.service.DTrackService.LatestVersionLookup;
import io.reliza.service.DTrackService.RepositoryMetaListing;
import io.reliza.service.DTrackService.RepositoryMetaRejectedException;
import io.reliza.service.FindingPurlBridge.ComponentIndex;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps {@code sbom_components.latest_version} fresh from Dependency-Track's repository
 * metadata: the latest version of each component's package that DT's repository analyzer
 * found. A freshness signal only, never end of support or end of life.
 *
 * <p>Source, the same on DT 5 and DT 4: the component listing of each synthetic bucket's DT
 * project carries {@code repositoryMeta} per component, so a bucket of 500 components costs
 * 5 paged requests. Components in no bucket (never submitted to DT) go one by one to
 * {@code /api/v1/repository/latest}; a purl type DT has no repository for (204: deb, apk,
 * rpm, generic) is learned once per run and its other components are stamped without a
 * request. Every component asked about is stamped as checked, with or without a version,
 * so it is not asked again before {@link #FRESHNESS} has passed; an answer without a
 * version keeps the one already known. A refused request (a project gone, a purl DT's
 * parser rejects) moves on; an unavailable instance ends the run and the org waits
 * {@link #ERROR_BACKOFF}.
 */
@Slf4j
@Service
public class ComponentLatestVersionService {

	/** How long a component's latest version counts as fresh. */
	static final Duration FRESHNESS = Duration.ofHours(24);

	/** The most due buckets looked at per org per run; the request budget usually stops the run first. */
	static final int MAX_BUCKETS_PER_RUN = 50;

	/**
	 * The most due unbucketed components read per org per run. Larger than the request
	 * budget on purpose: once a purl type is known to have no repository its components are
	 * stamped without a request, and an org's generic or distro components should not take
	 * one run per budget's worth of rows.
	 */
	static final int MAX_UNBUCKETED_ROWS_PER_RUN = 1000;

	/**
	 * How long after its ingest a bucket is left alone: Dependency-Track's repository
	 * analyzer looks new packages up after the BOM upload, and a listing taken before it has
	 * would stamp them as having no latest version for a day.
	 */
	static final Duration INGEST_SETTLE = Duration.ofHours(1);

	/**
	 * How long an org is left alone after a Dependency-Track error: the refresh runs every
	 * scheduler tick, and an unreachable instance would otherwise log an error a minute.
	 */
	static final Duration ERROR_BACKOFF = Duration.ofMinutes(30);

	/** Orgs in {@link #ERROR_BACKOFF}, until when. In memory: a restart retries at once. */
	private final Map<UUID, Instant> backoffUntil = new ConcurrentHashMap<>();

	/**
	 * Dependency-Track requests one org may spend per run: a bucket listing costs one per
	 * 100 components, an unbucketed component one. A started bucket always finishes, so a
	 * run can go over by one bucket's pages. Steady state spends nothing.
	 */
	@Value("${relizaprops.latestVersionFetchLimit:20}")
	private int fetchLimit;

	@Autowired
	private SbomComponentRepository sbomComponentRepository;

	@Autowired
	private DTrackService dTrackService;

	/** What one run did: buckets listed, components stamped, requests spent. */
	public record RefreshResult(int buckets, int components, int requests) {}

	/**
	 * Refresh the org's components whose latest version is due. Stops at the first
	 * Dependency-Track error, and leaves the org alone for {@link #ERROR_BACKOFF}: the rest
	 * would most likely fail the same way, and what was not stamped stays due.
	 */
	public RefreshResult refreshOrg(UUID orgUuid) {
		Instant until = backoffUntil.get(orgUuid);
		if (until != null && Instant.now().isBefore(until)) return new RefreshResult(0, 0, 0);
		ZonedDateTime now = ZonedDateTime.now();
		ZonedDateTime cutoff = now.minus(FRESHNESS);
		String org = orgUuid.toString();
		int requests = 0;
		int buckets = 0;
		int stamped = 0;
		for (DueBucketRow bucket : sbomComponentRepository.findLatestVersionDueBuckets(org, cutoff,
				now.minus(INGEST_SETTLE), MAX_BUCKETS_PER_RUN)) {
			if (requests >= fetchLimit) break;
			List<CanonicalPurlRow> members = sbomComponentRepository.findCanonicalPurlsByOrgAndBucket(org, bucket.getBucketIndex());
			List<ComponentLatestVersion> listed;
			try {
				RepositoryMetaListing listing = dTrackService.syntheticFetchRepositoryMeta(orgUuid, bucket.getDtrackProjectUuid());
				requests += listing.requests();
				listed = listing.components();
			} catch (RepositoryMetaRejectedException e) {
				// asking again gets the same answer: checked, nothing learned, next bucket
				log.error("Dependency-Track refused the latest versions of synthetic bucket {} of org {}: {}",
						bucket.getBucketIndex(), orgUuid, e.getMessage());
				requests++;
				listed = List.of();
			} catch (RelizaException e) {
				log.error("Could not read latest versions of synthetic bucket {} of org {} from Dependency-Track,"
						+ " next try in {}: {}", bucket.getBucketIndex(), orgUuid, ERROR_BACKOFF, e.getMessage());
				backoffUntil.put(orgUuid, Instant.now().plus(ERROR_BACKOFF));
				return new RefreshResult(buckets, stamped, requests + 1);
			}
			buckets++;
			stamped += stamp(org, latestByMember(members, listed));
		}
		if (requests < fetchLimit) {
			Map<UUID, String> unbucketed = new LinkedHashMap<>();
			Set<String> typesWithoutRepository = new HashSet<>();
			for (CanonicalPurlRow row : sbomComponentRepository.findLatestVersionDueUnbucketed(org, cutoff, MAX_UNBUCKETED_ROWS_PER_RUN)) {
				String type = purlTypeOf(row.getCanonicalPurl());
				if (type != null && typesWithoutRepository.contains(type)) {
					unbucketed.put(row.getUuid(), null);
					continue;
				}
				if (requests >= fetchLimit) break;
				requests++;
				LatestVersionLookup lookup;
				try {
					lookup = dTrackService.fetchLatestVersion(orgUuid, row.getCanonicalPurl());
				} catch (RepositoryMetaRejectedException e) {
					// this purl only: checked, nothing learned, next component
					unbucketed.put(row.getUuid(), null);
					continue;
				} catch (RelizaException e) {
					log.error("Could not read the latest version of a component of org {} from Dependency-Track,"
							+ " next try in {}: {}", orgUuid, ERROR_BACKOFF, e.getMessage());
					backoffUntil.put(orgUuid, Instant.now().plus(ERROR_BACKOFF));
					break;
				}
				switch (lookup.answer()) {
				case FOUND -> unbucketed.put(row.getUuid(), lookup.latestVersion());
				case NO_REPOSITORY_FOR_TYPE -> {
					if (type != null) typesWithoutRepository.add(type);
					unbucketed.put(row.getUuid(), null);
				}
				case UNKNOWN_PACKAGE -> unbucketed.put(row.getUuid(), null);
				}
			}
			stamped += stamp(org, unbucketed);
		}
		return new RefreshResult(buckets, stamped, requests);
	}

	/**
	 * Each bucket member's latest version from Dependency-Track's listing of its project,
	 * null for a member the listing has none for. A listed purl is matched to a member as
	 * findings are ({@link ComponentIndex}): DT re-encodes the canonical purls it is sent.
	 */
	static Map<UUID, String> latestByMember(List<CanonicalPurlRow> members, List<ComponentLatestVersion> listed) {
		Map<UUID, String> canonicalByMember = new HashMap<>();
		for (CanonicalPurlRow m : members) canonicalByMember.put(m.getUuid(), m.getCanonicalPurl());
		ComponentIndex index = ComponentIndex.of(canonicalByMember);
		Map<UUID, String> out = new LinkedHashMap<>();
		for (CanonicalPurlRow m : members) out.put(m.getUuid(), null);
		for (ComponentLatestVersion c : listed) {
			if (c.latestVersion() == null) continue;
			UUID member = index.componentOf(c.purl());
			if (member != null) out.put(member, c.latestVersion());
		}
		return out;
	}

	/** One row of the stamp payload; the names are the columns of the UPDATE's jsonb_to_recordset. */
	record StampRow(UUID uuid, String latest) {}

	private int stamp(String org, Map<UUID, String> latestByComponent) {
		if (latestByComponent.isEmpty()) return 0;
		List<StampRow> rows = new ArrayList<>(latestByComponent.size());
		latestByComponent.forEach((uuid, latest) -> rows.add(new StampRow(uuid, latest)));
		return sbomComponentRepository.stampLatestVersions(org, Utils.OM.writeValueAsString(rows));
	}

	private static String purlTypeOf(String purl) {
		PackageURL p = Utils.parsePurlOrNull(purl);
		return p == null ? null : p.getType();
	}
}
