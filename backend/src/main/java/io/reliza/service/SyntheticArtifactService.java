/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.SidPurlUtils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.StripBom;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.Release;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ArtifactDto;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.service.oss.OssReleaseService.AcollectionMode;
import lombok.extern.slf4j.Slf4j;

/**
 * The only writer of {@link ReleaseData#getSyntheticArtifacts()}: artifacts ReARM generated for
 * a release itself (a published aggregated SBOM, a VDR snapshot). Such an artifact is an
 * ordinary artifact row, bound to the release through the synthetic list only, so no merge,
 * collection gather, sbom_components reconcile or Dependency-Track path ever reads it.
 *
 * <p>Contract for generators: {@link #write} uploads and binds and returns the artifact uuid,
 * forcing {@code belongsTo} to {@link ArtifactBelongsTo#SYNTHETIC}; {@link #remove} unbinds and
 * archives an artifact no other release holds; {@link #findByTag} is the dedupe helper. The
 * release save fires no trigger, no collection resolve, no reconcile and no merge, and does not
 * assert the component lock: a generated document is derived from the release's content and
 * does not change it.
 */
@Slf4j
@Service
public class SyntheticArtifactService {

	@Autowired
	private SharedReleaseService sharedReleaseService;

	@Autowired
	private OssReleaseService ossReleaseService;

	@Autowired
	private ArtifactService artifactService;

	/** Proxy of this service, so {@link #write} reaches {@link #bind} through its transaction. */
	@Autowired
	@Lazy
	private SyntheticArtifactService self;

	/**
	 * Uploads a generated document and binds it to the release's synthetic list.
	 *
	 * @param dto the artifact; its org is replaced by the release's, storedIn is forced to
	 *     REARM, and the system tags {@code syntheticArtifact} and {@code syntheticOfRelease}
	 *     are appended to the caller's tags
	 * @param rebomOptions upload options; {@code belongsTo} is forced to SYNTHETIC. Null gives
	 *     the release-scoped defaults (release version, preferred purl, no strip)
	 * @return the uuid of the bound artifact
	 */
	public UUID write(UUID releaseUuid, ArtifactDto dto, Resource file,
			RebomOptions rebomOptions, WhoUpdated wu) throws RelizaException {
		ReleaseData rd = sharedReleaseService.getReleaseData(releaseUuid)
				.orElseThrow(() -> new RelizaException("release " + releaseUuid + " not found"));
		dto.setOrg(rd.getOrg());
		dto.setStoredIn(ArtifactData.StoredIn.REARM);
		dto.setTags(withSyntheticTags(dto.getTags(), releaseUuid));
		RebomOptions options;
		if (null != rebomOptions) {
			options = rebomOptions.withBelongsTo(ArtifactBelongsTo.SYNTHETIC);
		} else {
			String preferredPurl = SidPurlUtils.pickPreferredPurl(rd.getIdentifiers())
					.map(RearmIdentifier::getIdValue).orElse(null);
			options = new RebomOptions(null, null, rd.getVersion(), ArtifactBelongsTo.SYNTHETIC, null,
					StripBom.FALSE, preferredPurl);
		}
		// Outside any release transaction: this calls rebom and the registry. A failure binds nothing.
		UUID artUuid = artifactService.uploadArtifact(dto, file, options, wu);
		return (null != self ? self : this).bind(releaseUuid, artUuid, wu);
	}

	private static List<TagRecord> withSyntheticTags(List<TagRecord> callerTags, UUID releaseUuid) {
		List<TagRecord> tags = new ArrayList<>();
		if (null != callerTags) {
			callerTags.stream()
					.filter(t -> !CommonVariables.SYNTHETIC_ARTIFACT_TAG_KEY.equals(t.key())
							&& !CommonVariables.SYNTHETIC_OF_RELEASE_TAG_KEY.equals(t.key()))
					.forEach(tags::add);
		}
		tags.add(new TagRecord(CommonVariables.SYNTHETIC_ARTIFACT_TAG_KEY, "true", Removable.NO));
		tags.add(new TagRecord(CommonVariables.SYNTHETIC_OF_RELEASE_TAG_KEY, releaseUuid.toString(), Removable.NO));
		return tags;
	}

	/**
	 * Binds an existing artifact row to the release's synthetic list. Idempotent: an artifact
	 * already bound saves nothing and returns its uuid.
	 */
	@Transactional
	UUID bind(UUID releaseUuid, UUID artifactUuid, WhoUpdated wu) throws RelizaException {
		Release release = sharedReleaseService.getRelease(releaseUuid)
				.orElseThrow(() -> new RelizaException("release " + releaseUuid + " not found"));
		ReleaseData rd = ReleaseData.dataFromRecord(release);
		ArtifactData ad = artifactService.getArtifactData(artifactUuid)
				.orElseThrow(() -> new RelizaException("artifact " + artifactUuid + " not found"));
		if (!rd.getOrg().equals(ad.getOrg())) {
			throw new IllegalStateException("SECURITY: synthetic artifact " + artifactUuid + " of org "
					+ ad.getOrg() + " cannot be bound to release " + releaseUuid + " of org " + rd.getOrg());
		}
		if (rd.getArtifacts().contains(artifactUuid)) {
			throw new RelizaException("artifact " + artifactUuid + " is inventory of release " + releaseUuid
					+ "; it cannot also be synthetic");
		}
		if (!rd.addSyntheticArtifact(artifactUuid)) return artifactUuid;
		rd.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.SYNTHETIC_ARTIFACT, ReleaseUpdateAction.ADDED,
				null, null, artifactUuid, ZonedDateTime.now(), wu));
		// No triggers (nothing a trigger evaluates changed, and a writer running from a trigger must
		// not re-enter processRelease), no collection resolve (the gatherer never reads this list),
		// no reconcile and no merged-SBOM invalidation (the artifact is the merge's output).
		ossReleaseService.saveRelease(release, rd, wu, false, AcollectionMode.SKIP);
		log.info("synthetic artifact {} bound to release {} ({} {})", artifactUuid, releaseUuid,
				ad.getType(), ad.getDisplayIdentifier());
		return artifactUuid;
	}

	/**
	 * Unbinds a generated artifact and archives it when no other release holds it in any list.
	 * Only the generator that wrote it calls this; no user flow does.
	 *
	 * @return false when the artifact was not bound to the release
	 */
	@Transactional
	public boolean remove(UUID releaseUuid, UUID artifactUuid, WhoUpdated wu) throws RelizaException {
		Release release = sharedReleaseService.getRelease(releaseUuid)
				.orElseThrow(() -> new RelizaException("release " + releaseUuid + " not found"));
		ReleaseData rd = ReleaseData.dataFromRecord(release);
		if (!rd.removeSyntheticArtifact(artifactUuid)) return false;
		rd.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.SYNTHETIC_ARTIFACT, ReleaseUpdateAction.REMOVED,
				null, null, artifactUuid, ZonedDateTime.now(), wu));
		ossReleaseService.saveRelease(release, rd, wu, false, AcollectionMode.SKIP);
		boolean heldElsewhere = sharedReleaseService.gatherReleasesForArtifact(artifactUuid, rd.getOrg()).stream()
				.anyMatch(other -> !releaseUuid.equals(other.getUuid()));
		if (!heldElsewhere) {
			artifactService.archiveArtifact(artifactUuid, wu);
		}
		log.info("synthetic artifact {} removed from release {} (archived: {})", artifactUuid, releaseUuid,
				!heldElsewhere);
		return true;
	}

	/**
	 * The release's generated artifacts in list order; an id that no longer resolves is skipped
	 * with a WARN naming the release.
	 */
	public List<ArtifactData> listSyntheticArtifacts(ReleaseData rd) {
		List<UUID> ids = rd.getSyntheticArtifacts();
		List<ArtifactData> ordered = new LinkedList<>();
		if (ids.isEmpty()) return ordered;
		Map<UUID, ArtifactData> byId = artifactService.getArtifactDataListLight(ids).stream()
				.collect(Collectors.toMap(ArtifactData::getUuid, Function.identity(), (a, b) -> a));
		for (UUID id : ids) {
			ArtifactData ad = byId.get(id);
			if (null != ad) {
				ordered.add(ad);
			} else {
				log.warn("Synthetic artifact not found for UUID: {}, releaseId: {}", id, rd.getUuid());
			}
		}
		return ordered;
	}

	/** The first generated artifact of the release carrying {@code tagKey=tagValue}. */
	public Optional<ArtifactData> findByTag(ReleaseData rd, String tagKey, String tagValue) {
		return listSyntheticArtifacts(rd).stream()
				.filter(ad -> null != ad.getTags() && ad.getTags().stream()
						.anyMatch(t -> tagKey.equals(t.key()) && tagValue.equals(t.value())))
				.findFirst();
	}
}
