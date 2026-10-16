/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.common.CommonVariables.CallType;
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
 * the same permission.
 */
@Service
public class ArtifactDownloadAccessService {

	@Autowired
	private AuthorizationService authorizationService;

	// Optional: the implementation is Pro-side, and CE has no devices.
	@Autowired(required = false)
	private DeviceLifecycleHook deviceLifecycleHook;

	/**
	 * ARTIFACT_DOWNLOAD for a user. An artifact on a release is authorized per component, as
	 * before. One on no release has no component to ask about: an AI agent session's artifact
	 * (sessions hold theirs directly), a signature sub-artifact, or the previous artifact after a
	 * new-serial replacement. The per-component check refused those for everyone short of a global
	 * admin, org admins included, so the session page could list its reports but not open them.
	 * They are authorized at organization scope on the artifact's own org instead -- the boundary
	 * {@code ArtifactDataFetcher#getArtifact} already falls back to for the same artifacts' metadata.
	 */
	public void authorizeUserDownload(UserData ud, ArtifactData ad, Set<UUID> components) throws RelizaException {
		if (components.isEmpty()) {
			authorizationService.isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.ARTIFACT_DOWNLOAD,
				PermissionScope.ORGANIZATION, ad.getOrg(), List.of(ad), CallType.READ);
		} else {
			authorizationService.isUserAuthorizedForAnyObjectGraphQL(ud, PermissionFunction.ARTIFACT_DOWNLOAD,
				PermissionScope.COMPONENT, components, List.of(ad), CallType.READ);
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
