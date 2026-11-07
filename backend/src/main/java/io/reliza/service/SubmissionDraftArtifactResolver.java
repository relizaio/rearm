/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.Optional;
import java.util.UUID;

/**
 * Whose a submission draft's generated part is while it is unbound (FDAW-1 A3): implemented by the
 * ReARM Pro draft store and injected {@code @Autowired(required = false)} into
 * {@link ArtifactDownloadAccessService}. CE has no implementation, and a draft part has no holder there.
 */
public interface SubmissionDraftArtifactResolver {

	/**
	 * The component of the draft's release.
	 *
	 * @param draft the value of the artifact's {@code submissionDraft} system tag
	 * @param release the value of the artifact's {@code syntheticOfRelease} system tag
	 * @param org the artifact's organization
	 * @return empty unless the draft exists in {@code org}, is of {@code release}, and that release
	 *     exists in {@code org}
	 */
	Optional<UUID> componentOfDraftPart(UUID draft, UUID release, UUID org);
}
