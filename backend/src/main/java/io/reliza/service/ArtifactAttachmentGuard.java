/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData;
import io.reliza.model.BranchData;
import io.reliza.model.DeliverableData;
import io.reliza.model.ReleaseData;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.DeliverableRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SourceCodeEntryRepository;
import lombok.extern.slf4j.Slf4j;

/**
 * Which components hold an artifact, and which existing artifacts a release may list by reference
 * (SCORE-52 ADR-6, ADR-8).
 *
 * <p>Holders are what rule B asks about ({@code SbomUpdateGuard}): an update of a serialNumber needs
 * write on every component holding one of its artifacts. Holder status grants nothing by itself; it
 * only widens whose rights an update needs. Listing an artifact uuid in a release makes the
 * release's component a holder without an upload, so two rules govern it. The user rule
 * ({@link #refuseUnheldReference}) runs in the two user resolvers that take a caller-supplied uuid
 * list ({@code updateRelease}, {@code addReleaseManual}): an artifact held by other components needs
 * the caller's write on each of them, and a holderless one needs organization-wide write. The floor
 * ({@link #refuseForeignAttachment}) runs for every caller of {@code createRelease} and
 * {@code doUpdateRelease}: an added uuid must be of the organization and not stored by an agent
 * session.
 *
 * <p>A separate bean from {@code SbomUpdateGuard}, which depends on {@code OssReleaseService} for
 * its refusal record: a check inside it would be a constructor cycle.
 */
@Slf4j
@Service
public class ArtifactAttachmentGuard {

	public enum AttachmentRule {
		/** Every caller of createRelease and doUpdateRelease. */
		FLOOR,
		/** The two user mutations that carry a caller-supplied artifact uuid list. */
		USER
	}

	/** At most this many levels of containing artifacts above the artifact asked about. */
	static final int MAX_CONTAINER_DEPTH = 8;

	/** At most this many artifacts visited, the artifact asked about included, per resolution. */
	static final int MAX_ARTIFACTS_VISITED = 64;

	static final String NOT_FOUND_MESSAGE = "Artifact not found in this organization: ";

	/**
	 * The components holding an artifact, containers included. {@code unresolved} is true when the
	 * resolution reached a bound or a container of another organization: then only
	 * organization-wide write may update a serialNumber with this artifact or list it by reference.
	 */
	public record ArtifactHolders(Set<UUID> components, boolean unresolved) {}

	private final ArtifactRepository artifactRepository;
	private final ReleaseRepository releaseRepository;
	private final DeliverableRepository deliverableRepository;
	private final SourceCodeEntryRepository sourceCodeEntryRepository;
	private final BranchService branchService;

	public ArtifactAttachmentGuard(ArtifactRepository artifactRepository, ReleaseRepository releaseRepository,
			DeliverableRepository deliverableRepository, SourceCodeEntryRepository sourceCodeEntryRepository,
			BranchService branchService) {
		this.artifactRepository = artifactRepository;
		this.releaseRepository = releaseRepository;
		this.deliverableRepository = deliverableRepository;
		this.sourceCodeEntryRepository = sourceCodeEntryRepository;
		this.branchService = branchService;
	}

	/** The one text for every refused reference that is not "not found": it names no holder. */
	static String refusalMessage(UUID artifact) {
		return "Artifact cannot be attached by reference: " + artifact
				+ ". Listing an existing artifact needs write permission on every other component that holds it,"
				+ " or write permission on the whole organization.";
	}

	/**
	 * The components holding {@code artifact} today: its direct holders and the holders of every
	 * artifact that contains it, transitively (breadth-first, each artifact visited once). The
	 * direct holders of an artifact are the components of the releases listing it, in any
	 * organization, so that a listing of another organization fails closed (a product release gives
	 * the product), or in their synthetic list (TEA-4), the branch component of each deliverable
	 * listing it (for a deliverable without a branch, the component of every release reaching it
	 * through a variant),
	 * and for each source code entry element naming it its componentUuid tag, or, when untagged, the
	 * components of the organization's releases on that commit. An agent session gives no holder.
	 *
	 * <p>The walk stops, and answers {@code unresolved}, at more than {@value #MAX_CONTAINER_DEPTH}
	 * levels of containers, at more than {@value #MAX_ARTIFACTS_VISITED} artifacts, or at a
	 * container or a source code entry of another organization.
	 */
	public ArtifactHolders holdersOfArtifact(UUID org, UUID artifact) {
		Set<UUID> holders = new LinkedHashSet<>();
		Set<UUID> visited = new LinkedHashSet<>();
		Deque<UUID> level = new ArrayDeque<>();
		visited.add(artifact);
		level.add(artifact);
		int depth = 0;
		while (!level.isEmpty()) {
			Deque<UUID> next = new ArrayDeque<>();
			for (UUID current : level) {
				if (!addDirectHolders(org, current, holders)) {
					log.warn("SBOM holder resolution unresolved: org={} artifact={} reason=foreign source code entry",
							org, artifact);
					return new ArtifactHolders(holders, true);
				}
				for (Artifact container : artifactRepository.findContainingArtifacts(current.toString())) {
					if (visited.contains(container.getUuid())) {
						continue;
					}
					if (!org.equals(ArtifactData.dataFromRecord(container).getOrg())) {
						log.warn("SBOM holder resolution unresolved: org={} artifact={} reason=foreign container", org,
								artifact);
						return new ArtifactHolders(holders, true);
					}
					if (depth + 1 > MAX_CONTAINER_DEPTH || visited.size() >= MAX_ARTIFACTS_VISITED) {
						log.warn("SBOM holder resolution unresolved: org={} artifact={} reason=bound reached", org,
								artifact);
						return new ArtifactHolders(holders, true);
					}
					visited.add(container.getUuid());
					next.add(container.getUuid());
				}
			}
			level = next;
			depth++;
		}
		return new ArtifactHolders(holders, false);
	}

	/**
	 * Adds the direct holders of {@code artifact}; false when a source code entry of another
	 * organization names it, whose holders this organization cannot resolve (fails closed, as a
	 * foreign container does).
	 */
	private boolean addDirectHolders(UUID org, UUID artifact, Set<UUID> holders) {
		String artifactText = artifact.toString();
		releaseRepository.findReleasesHoldingArtifact(artifactText)
				.forEach(r -> addIfPresent(holders, ReleaseData.dataFromRecord(r).getComponent()));
		// A generated row (TEA-4) is listed in a release's synthetic list only: its holder is the
		// component of the release it was generated for, which is also the one component the engine's
		// own authority writes (SyntheticArtifactService.SyntheticWriteAuthority).
		releaseRepository.findReleasesBySyntheticArtifact(artifactText, org.toString())
				.forEach(r -> addIfPresent(holders, ReleaseData.dataFromRecord(r).getComponent()));
		boolean external = false;
		for (var d : deliverableRepository.findDeliverablesByArtifact(artifactText)) {
			UUID branch = DeliverableData.dataFromRecord(d).getBranch();
			if (null == branch) {
				external = true;
			} else {
				branchService.getBranchData(branch).map(BranchData::getComponent).ifPresent(holders::add);
			}
		}
		if (external) {
			releaseRepository.findReleasesSharingDeliverableArtifact(artifactText)
					.forEach(r -> addIfPresent(holders, ReleaseData.dataFromRecord(r).getComponent()));
		}
		for (Object[] element : sourceCodeEntryRepository.findSceArtifactHolders(artifactText)) {
			String sce = (String) element[0];
			String tag = (String) element[1];
			if (!org.toString().equals(element[2])) {
				return false;
			}
			if (null != tag) {
				holders.add(UUID.fromString(tag));
			} else {
				releaseRepository.findReleaseBySce(sce, org.toString())
						.forEach(r -> addIfPresent(holders, ReleaseData.dataFromRecord(r).getComponent()));
			}
		}
		return true;
	}

	private static void addIfPresent(Set<UUID> holders, UUID component) {
		if (null != component) {
			holders.add(component);
		}
	}

	/**
	 * The floor: refuses an added uuid that is missing or of another organization (one text, every
	 * such uuid in the order sent), then the first one stored by an agent session. The holder test is
	 * the user rule's: the floor's other callers add only artifacts uploaded in the same request.
	 *
	 * @param release the release being written, or null for a new one (the log line only)
	 */
	public void refuseForeignAttachment(UUID org, UUID component, UUID release, Collection<UUID> added)
			throws RelizaException {
		for (Map.Entry<UUID, ArtifactData> e : readInOrganization(org, added).entrySet()) {
			ArtifactData ad = e.getValue();
			if (isSessionArtifact(ad)) {
				refuse(AttachmentRule.FLOOR, org, release, component, e.getKey(), 0, ad);
			}
		}
	}

	/**
	 * The user rule: refuses an added uuid that is missing or of another organization (as the
	 * floor), then the first one that is archived or stored by an agent session, then the first one
	 * the caller may not list: with {@code h} its holders and {@code others} those other than
	 * {@code component}, it is allowed when {@code h} resolves to exactly {@code {component}}; when
	 * it resolves, {@code others} is not empty, and the caller has organization-wide write or write
	 * on every one of {@code others}; or when it has no holder or does not resolve, and the caller
	 * has organization-wide write.
	 *
	 * @param release the release being written, or null for a new one (the log line only)
	 */
	public void refuseUnheldReference(UUID org, UUID component, UUID release, Collection<UUID> added,
			SbomWriteAuthority authority) throws RelizaException {
		for (Map.Entry<UUID, ArtifactData> e : readInOrganization(org, added).entrySet()) {
			ArtifactData ad = e.getValue();
			if (StatusEnum.ARCHIVED == ad.getStatus() || isSessionArtifact(ad)) {
				refuse(AttachmentRule.USER, org, release, component, e.getKey(), 0, ad);
			}
			ArtifactHolders h = holdersOfArtifact(org, e.getKey());
			if (!mayList(h, component, authority)) {
				refuse(AttachmentRule.USER, org, release, component, e.getKey(), h.components().size(), ad);
			}
		}
	}

	private static boolean mayList(ArtifactHolders h, UUID component, SbomWriteAuthority authority) {
		if (h.unresolved() || h.components().isEmpty()) {
			return authority.orgWideWrite();
		}
		Set<UUID> others = new LinkedHashSet<>(h.components());
		others.remove(component);
		if (others.isEmpty()) {
			return true;
		}
		return authority.orgWideWrite() || others.stream().allMatch(authority::canWrite);
	}

	/**
	 * Every added uuid read by uuid, in the order sent; refused as a whole, one text for a missing
	 * and a foreign one (the validateReleaseReferences shape), when any is not of {@code org}.
	 */
	private Map<UUID, ArtifactData> readInOrganization(UUID org, Collection<UUID> added) throws RelizaException {
		Map<UUID, ArtifactData> found = new LinkedHashMap<>();
		if (null == added || added.isEmpty()) {
			return found;
		}
		List<UUID> refused = new ArrayList<>();
		for (UUID uuid : new LinkedHashSet<>(added)) {
			Optional<ArtifactData> oad = null == uuid ? Optional.empty()
					: artifactRepository.findById(uuid).map(ArtifactData::dataFromRecord);
			if (oad.isPresent() && null != org && org.equals(oad.get().getOrg())) {
				found.put(uuid, oad.get());
			} else {
				refused.add(uuid);
			}
		}
		if (!refused.isEmpty()) {
			throw new RelizaException(NOT_FOUND_MESSAGE + refused);
		}
		return found;
	}

	private static boolean isSessionArtifact(ArtifactData ad) {
		return null != ad.getInternalBom() && ArtifactBelongsTo.AGENT_SESSION == ad.getInternalBom().belongsTo();
	}

	private static void refuse(AttachmentRule rule, UUID org, UUID release, UUID component, UUID artifact,
			int holders, ArtifactData ad) throws RelizaException {
		log.warn("SBOM artifact attachment refused: rule={} org={} release={} component={} artifact={} holders={} status={}",
				rule, org, null == release ? "new" : release, component, artifact, holders, ad.getStatus());
		throw new RelizaException(refusalMessage(artifact));
	}
}
