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
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.service.oss.OssReleaseService.AcollectionMode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;

/**
 * The only writer of {@link ReleaseData#getSyntheticArtifacts()}: artifacts ReARM generated for
 * a release itself (a published aggregated SBOM, a VDR snapshot). Such an artifact is an
 * ordinary artifact row, bound to the release through the synthetic list only, so no merge,
 * collection gather, sbom_components reconcile or Dependency-Track path ever reads it.
 *
 * <p>Contract for generators: {@link #write} uploads and binds and returns the artifact uuid,
 * forcing {@code belongsTo} to {@link ArtifactBelongsTo#SYNTHETIC}; {@link #remove} unbinds and
 * archives an artifact no other release holds; {@link #findByTag} is the dedupe helper. A
 * generator that needs one release write per act inside its own serialized commit (the TEA
 * publication engine) uses the two halves instead: {@link #upload}, which touches no release
 * row, and {@link #stageBind}, which binds on the caller's {@link ReleaseData} without saving.
 * Every save here is made on the release row refreshed in its transaction. The
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

	/** Re-reads the release row inside the bind and remove transactions. */
	@PersistenceContext
	private EntityManager entityManager;

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
		UUID artUuid = upload(releaseUuid, dto, file, rebomOptions, wu);
		return (null != self ? self : this).bind(releaseUuid, artUuid, wu);
	}

	/**
	 * The first half of {@link #write}: uploads a generated document for the release and binds
	 * nothing, so no release row is written. The caller binds it with {@link #stageBind} in its
	 * own commit; an upload it never binds is the caller's to archive.
	 *
	 * @return the uuid of the uploaded, unbound artifact
	 */
	public UUID upload(UUID releaseUuid, ArtifactDto dto, Resource file,
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
		return artifactService.uploadArtifact(dto, file, options, new SyntheticWriteAuthority(rd.getComponent()), wu);
	}

	/**
	 * The engine's rights for one generated row under rule B (SCORE-52 ADR-2): write on the component
	 * of the release the row is generated for, nothing organization-wide. Every update therefore runs
	 * the holder walk, and main's T-21 holds. For logs: SYSTEM:SYNTHETIC:<component>.
	 */
	record SyntheticWriteAuthority(UUID component) implements SbomWriteAuthority {
		@Override public boolean orgWideWrite() { return false; }
		@Override public boolean canWrite(UUID c) { return null != component && component.equals(c); }
		@Override public String describe() { return "SYSTEM:SYNTHETIC:" + component; }
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
	 * Binds an existing artifact row to the release's synthetic list, on the release row
	 * refreshed in this transaction, so a change another writer committed after the caller read
	 * the release is kept. Idempotent: an artifact already bound saves nothing and returns its
	 * uuid.
	 */
	@Transactional
	UUID bind(UUID releaseUuid, UUID artifactUuid, WhoUpdated wu) throws RelizaException {
		Release release = sharedReleaseService.getRelease(releaseUuid)
				.orElseThrow(() -> new RelizaException("release " + releaseUuid + " not found"));
		entityManager.refresh(release);
		ReleaseData rd = ReleaseData.dataFromRecord(release);
		if (!stageBind(rd, artifactUuid, wu)) return artifactUuid;
		// No triggers (nothing a trigger evaluates changed, and a writer running from a trigger must
		// not re-enter processRelease), no collection resolve (the gatherer never reads this list),
		// no reconcile and no merged-SBOM invalidation (the artifact is the merge's output).
		ossReleaseService.saveRelease(release, rd, wu, false, AcollectionMode.SKIP);
		return artifactUuid;
	}

	/**
	 * Binds {@code artifactUuid} on {@code rd} and appends the SYNTHETIC_ARTIFACT ADDED event to
	 * it, without saving: the caller saves {@code rd} once, in its own transaction, on the release
	 * row it refreshed there.
	 *
	 * @return false, with {@code rd} unchanged, when the artifact is already bound
	 * @throws IllegalStateException when the artifact is of another organization
	 * @throws RelizaException when the artifact is missing or is inventory of the release
	 */
	public boolean stageBind(ReleaseData rd, UUID artifactUuid, WhoUpdated wu) throws RelizaException {
		UUID releaseUuid = rd.getUuid();
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
		if (!rd.addSyntheticArtifact(artifactUuid)) return false;
		rd.addUpdateEvent(new ReleaseUpdateEvent(ReleaseUpdateScope.SYNTHETIC_ARTIFACT, ReleaseUpdateAction.ADDED,
				null, null, artifactUuid, ZonedDateTime.now(), wu));
		log.info("synthetic artifact {} bound to release {} ({} {})", artifactUuid, releaseUuid,
				ad.getType(), ad.getDisplayIdentifier());
		return true;
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
		entityManager.refresh(release);
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

	/**
	 * The first generated artifact of the release carrying {@code tagKey=tagValue} as a system tag
	 * ({@code Removable.NO}). A generator identifies its rows by the tags it wrote, which are system tags;
	 * a user's tag is always YES (task TEA-28, F-5) and never identifies a row.
	 */
	public Optional<ArtifactData> findByTag(ReleaseData rd, String tagKey, String tagValue) {
		return findByTags(rd, Map.of(tagKey, tagValue));
	}

	/**
	 * The first generated artifact of the release carrying EVERY pair of {@code pairs} as a system tag
	 * ({@code Removable.NO}; task TEA-28, D15). A YES tag never matches, whatever its key and value: every
	 * writer of a NO tag is server code, and a generator must write the tags it identifies its rows by as
	 * NO and never pass a caller-supplied tag into {@link #upload} as NO.
	 */
	public Optional<ArtifactData> findByTags(ReleaseData rd, Map<String, String> pairs) {
		if (null == pairs || pairs.isEmpty()) return Optional.empty();
		return listSyntheticArtifacts(rd).stream().filter(ad -> carriesSystemTags(ad, pairs)).findFirst();
	}

	/** Whether {@code ad} carries every pair as a {@code Removable.NO} tag. */
	static boolean carriesSystemTags(ArtifactData ad, Map<String, String> pairs) {
		if (null == ad.getTags()) return false;
		return pairs.entrySet().stream().allMatch(p -> ad.getTags().stream()
				.anyMatch(t -> null != t && Removable.NO == t.removable() && p.getKey().equals(t.key())
						&& p.getValue().equals(t.value())));
	}
}
