/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.reliza.exceptions.SubmissionRefusedException;
import io.reliza.model.PackageKind;
import io.reliza.model.SubmissionActResult;
import io.reliza.model.SubmissionData;
import io.reliza.model.SubmissionPreview;
import io.reliza.model.SubmissionState;
import io.reliza.model.SubmissionVersionData;
import io.reliza.model.WhoUpdated;

/**
 * Regulatory submissions (task TEA-35, decision E1): implemented by the ReARM Pro submission
 * service and injected {@code @Autowired(required = false)} into the shared fetcher, which
 * authorizes every call before it reaches the hook. CE has no implementation: the fetcher's
 * null-hook branch is the CE answer (empty lists, null singles, EDITION_UNSUPPORTED).
 */
public interface SubmissionHook {

	SubmissionActResult create(UUID release, PackageKind kind, String reason, WhoUpdated wu)
			throws SubmissionRefusedException;

	SubmissionActResult resubmit(UUID org, UUID submission, String reason, Integer expectedLatestVersion,
			WhoUpdated wu) throws SubmissionRefusedException;

	SubmissionActResult withdraw(UUID org, UUID submission, String reason, WhoUpdated wu)
			throws SubmissionRefusedException;

	SubmissionPreview preview(UUID release, PackageKind kind);

	/** By uuid alone: the fetcher checks the answer's organization against the caller before anything else. */
	Optional<SubmissionData> getSubmission(UUID submission);

	List<SubmissionData> listOfRelease(UUID org, UUID release);

	List<SubmissionData> listOfComponent(UUID org, UUID component, PackageKind kindOrNull, SubmissionState stateOrNull);

	List<SubmissionVersionData> listVersions(UUID org, UUID submission);

	Optional<SubmissionVersionData> getVersion(UUID org, UUID submission, int version);
}
