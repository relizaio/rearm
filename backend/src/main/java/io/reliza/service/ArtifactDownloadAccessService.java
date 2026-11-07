/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.DeviceLifecycle;
import io.reliza.model.ReleaseData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;

/**
 * Who may obtain an artifact's document, and which device window its augmented download carries.
 *
 * <p>One implementation for every way a user obtains an artifact's document: the manual download
 * endpoints and the SBOM score query, which reveals part of the same document and so sits behind
 * the same permission. {@link #holdersOf} is the one answer to who holds an artifact, for those and
 * for the artifact reads of {@code ArtifactDataFetcher} and the key downloads of {@code ArtifactWs}.
 */
@Service
public class ArtifactDownloadAccessService {

	/**
	 * Who holds an artifact: the components to ask about, or, when {@code orgScope}, its organization.
	 * Empty components without {@code orgScope} is nobody: the callers ask the component check over the
	 * empty set, which passes a global admin and refuses everyone else.
	 */
	public record Holders(Set<UUID> components, boolean orgScope) {}

	private static final Holders NO_HOLDER = new Holders(Set.of(), false);

	@Autowired
	private AuthorizationService authorizationService;

	@Autowired
	private SharedReleaseService sharedReleaseService;

	// Optional: the implementation is Pro-side, and CE has no devices.
	@Autowired(required = false)
	private DeviceLifecycleHook deviceLifecycleHook;

	// Optional: the implementation is Pro-side, and CE has no drafts, so a draft part has no holder there.
	@Autowired(required = false)
	private SubmissionDraftArtifactResolver submissionDraftArtifactResolver;

	/** {@link #holdersOf(ArtifactData, List)} over the releases that hold {@code ad}. */
	public Holders holdersOf(ArtifactData ad) {
		return resolveHolders(ad, sharedReleaseService.gatherReleasesForArtifact(ad.getUuid(), ad.getOrg()));
	}

	/**
	 * Who holds {@code ad}, for a caller that already gathered the {@code releases} holding it. In order:
	 * <ol>
	 * <li>the components of those releases;</li>
	 * <li>on no release, with a {@code submissionDraft} system tag: the component of the draft's release, from
	 * {@link SubmissionDraftArtifactResolver}, or nobody once the draft is gone and on CE;</li>
	 * <li>with a {@code syntheticOfRelease} system tag and no draft tag: that release's component, or nobody when
	 * the release is missing or of another organization. A generated row whose bind did not or has not committed,
	 * the release SBOM pin of a first draft among them;</li>
	 * <li>with neither: the organization (an AI agent session's artifact, a signature sub-artifact, the previous
	 * artifact after a new-serial replacement).</li>
	 * </ol>
	 * Only {@code Removable.NO} tags are read, and a value that is not a uuid answers nobody, never the organization.
	 */
	public Holders holdersOf(ArtifactData ad, List<ReleaseData> releases) {
		return resolveHolders(ad, releases);
	}

	// Private, so that a spy on the bean records exactly one public holdersOf call per request.
	private Holders resolveHolders(ArtifactData ad, List<ReleaseData> releases) {
		if (!releases.isEmpty()) {
			return new Holders(releases.stream().map(ReleaseData::getComponent).collect(Collectors.toSet()), false);
		}
		Optional<TagRecord> draftTag = systemTag(ad, CommonVariables.SUBMISSION_DRAFT_TAG_KEY);
		Optional<TagRecord> releaseTag = systemTag(ad, CommonVariables.SYNTHETIC_OF_RELEASE_TAG_KEY);
		if (draftTag.isEmpty() && releaseTag.isEmpty()) return new Holders(Set.of(), true);
		Optional<UUID> release = releaseTag.flatMap(t -> Utils.parseUuid(t.value()));
		if (release.isEmpty()) return NO_HOLDER;
		if (draftTag.isEmpty()) {
			return holderOf(sharedReleaseService.getReleaseData(release.get())
					.filter(rd -> ad.getOrg().equals(rd.getOrg())).map(ReleaseData::getComponent));
		}
		Optional<UUID> draft = Utils.parseUuid(draftTag.get().value());
		if (draft.isEmpty() || null == submissionDraftArtifactResolver) return NO_HOLDER;
		return holderOf(submissionDraftArtifactResolver.componentOfDraftPart(draft.get(), release.get(), ad.getOrg()));
	}

	private static Holders holderOf(Optional<UUID> component) {
		return component.map(c -> new Holders(Set.of(c), false)).orElse(NO_HOLDER);
	}

	/** The {@code Removable.NO} tag of that key: the test {@code SyntheticArtifactService.carriesSystemTags} applies. */
	private static Optional<TagRecord> systemTag(ArtifactData ad, String key) {
		if (null == ad.getTags()) return Optional.empty();
		return ad.getTags().stream()
				.filter(t -> null != t && Removable.NO == t.removable() && key.equals(t.key())).findFirst();
	}

	/**
	 * ARTIFACT_DOWNLOAD for a user. An artifact on a release is authorized per component, as
	 * before. One on no release has no component to ask about: an AI agent session's artifact
	 * (sessions hold theirs directly), a signature sub-artifact, or the previous artifact after a
	 * new-serial replacement. The per-component check refused those for everyone short of a global
	 * admin, org admins included, so the session page could list its reports but not open them.
	 * They are authorized at organization scope on the artifact's own org instead -- the boundary
	 * {@code ArtifactDataFetcher#getArtifact} already falls back to for the same artifacts' metadata.
	 * {@link #holdersOf} decides which, and {@code Holders(empty, false)} goes to the per-component check.
	 */
	public void authorizeUserDownload(UserData ud, ArtifactData ad, Holders holders) throws RelizaException {
		if (holders.orgScope()) {
			authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.ARTIFACT_DOWNLOAD,
				PermissionScope.ORGANIZATION, ad.getOrg(), List.of(ad), CallType.READ);
		} else {
			authorizationService.isUserAuthorizedForAnyObjectGraphQL(ud, PermissionFunction.ARTIFACT_DOWNLOAD,
				PermissionScope.COMPONENT, holders.components(), List.of(ad), CallType.READ);
		}
	}

	/**
	 * The device window for an artifact, from {@link DeviceLifecycleHook} (D7).
	 *
	 * <p><b>The "exactly one PRODUCT release" rule is gone.</b> This used to take the
	 * release's own {@code eos}/{@code eol} only when precisely one PRODUCT release was in
	 * play, or when {@code ?releaseUuid} named one -- which meant the augmented download of
	 * an artifact attached to a product carried the device properties while the SAME artifact
	 * fetched from a component page carried none. That was a rule about how many releases
	 * happened to reference a file, standing in for a question about which device the file is
	 * in, and it needed a paragraph of walkthrough text to stop reading as a bug.
	 *
	 * <p>Now the artifact resolves through its release's product component, and the only
	 * remaining null case is genuine ambiguity -- several products whose declared windows
	 * DISAGREE, where stamping either would attribute one device's commitment to another's
	 * software. See {@code DeviceLifecycleHook.forArtifactReleases}.
	 */
	public DeviceLifecycle resolveDeviceLifecycle(List<ReleaseData> releases, UUID releaseUuid) {
		// Absent in CE, where there are no devices to make a commitment about, so the artifact is
		// served without device properties rather than with empty ones.
		return null == deviceLifecycleHook ? null : deviceLifecycleHook.forArtifactReleases(releases, releaseUuid);
	}
}
