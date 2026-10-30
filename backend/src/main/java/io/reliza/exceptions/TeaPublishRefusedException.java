/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.exceptions;

import java.util.List;
import java.util.UUID;

import io.reliza.exceptions.MergedSbomUnavailableException.ChildBomOutcome;

/**
 * A TEA publish, re-publish or hide was refused, and why (task TEA-5). Callers, part 6's output
 * events among them, switch on {@link #reason()}, never on the message; the message is one ASCII
 * line for the person reading it that names the release and the fact. A RelizaException, so
 * GraphQLExceptionHandlers carries the message to the caller as BAD_REQUEST.
 */
public class TeaPublishRefusedException extends RelizaException {

	private static final long serialVersionUID = 2026100301L;

	public enum TeaPublishRefusal {
		RELEASE_NOT_FOUND,
		RELEASE_ARCHIVED,
		COMPONENT_ARCHIVED,
		LIFECYCLE_TOO_LOW,
		PROFILE_CONFLICT,
		/**
		 * An SBOM profile the TEA settings name does not resolve for the publishing component; a cascade
		 * child is refused over its own settings' names only (tasks TEA-27, TEA-28).
		 */
		PROFILE_NOT_FOUND,
		PUBLISHING_DISABLED,
		ALREADY_PUBLISHED,
		NOT_PUBLISHED,
		NO_SBOM_ARTIFACTS,
		CHILD_MERGE_FAILED,
		GENERATION_FAILED,
		/** Another act on the release held its lease past the wait; nothing was written; retryable. */
		PUBLISH_IN_PROGRESS
	}

	private final TeaPublishRefusal reason;
	private final UUID release;
	private final List<ChildBomOutcome> children;

	public TeaPublishRefusedException(TeaPublishRefusal reason, UUID release, String message) {
		this(reason, release, message, List.of());
	}

	public TeaPublishRefusedException(TeaPublishRefusal reason, UUID release, String message,
			List<ChildBomOutcome> children) {
		super(message);
		this.reason = reason;
		this.release = release;
		this.children = null == children ? List.of() : List.copyOf(children);
	}

	public TeaPublishRefusal reason() {
		return reason;
	}

	/** The release the act was asked for. */
	public UUID release() {
		return release;
	}

	/** For CHILD_MERGE_FAILED (and NO_SBOM_ARTIFACTS of a product): the merge's per-child outcomes. */
	public List<ChildBomOutcome> children() {
		return children;
	}
}
